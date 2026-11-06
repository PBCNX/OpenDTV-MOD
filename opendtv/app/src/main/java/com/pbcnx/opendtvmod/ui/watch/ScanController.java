package com.pbcnx.opendtvmod.ui.watch;

import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.tuner.ChannelStore;
import com.pbcnx.opendtvmod.tuner.MhzLabel;
import com.pbcnx.opendtvmod.tuner.MuxSweep;
import com.pbcnx.opendtvmod.tuner.TunerHub;
import com.pbcnx.opendtvmod.ts.ServiceInfo;

/**
 * The two scan engines and the sheet they drive: a full UHF scan that rebuilds the list, and a manual
 * single-frequency scan with a live meter.
 *
 * <p>They lived in MainActivity, which is why the activity owned two scan loops as well as the
 * channel model and the playback state. The controller owns the scan state ({@code scanning},
 * {@code cancel}, the sheet, the meter poller) and talks to the screen through {@link Host}: it
 * stops playback before touching the tuner, saves the list it built, and hands the finished channel
 * back for the watch screen to show.</p>
 */
public final class ScanController {

    /** What the scan needs from the screen it runs on. */
    public interface Host extends ScreenSeam {



        /** The screen as the tuner's owner: the hub hands ownership to it during a scan. */

        ChannelStore scanStore();

        ChannelStore.Storage scanPrefs();

        /** The channel the watch screen is on, or null: the manual scan starts from it. */


        /** Stops playback (and its callbacks) before the scan touches the tuner. */
        void beginScanExclusive();

        /** The list changed and has been saved; repaint the surfaces that show it. */
        void channelsChanged();

        /** The scan is done: show {@code entry} if it is not null, then start it. */
        void scanFinished(ChannelEntry entry);

        /** Puts playback back on the channel the screen was on (the manual scan's meter moved the tuner). */
        void resumePlayingChannel();
    }

    private final Host host;
    private final ScanDialog.Listener listener = new ScanDialog.Listener() {
        @Override
        public void onScan(int khz) {
            scanStartedFromSheet = true;
            stopMeter();
            host.ui().post(() -> runManualScan(khz));
        }

        @Override
        public void onStop() {
            scanState.cancel();
        }

        @Override
        public void onDismissed() {
            stopMeter();
            // Closed without scanning after the meter had tuned somewhere else: the playing channel
            // lost its multiplex and the player is left waiting on a demux nobody feeds. Put it back
            // (jpdcs#14). A dismiss caused by a scan that ran says so in scanStartedFromSheet.
            boolean restore = shouldResume(scanStartedFromSheet, movedOff, scanState.isScanning());
            movedOff = false;
            if (restore) host.ui().post(host::resumePlayingChannel);
        }

        @Override
        public void onFrequency(int khz) {
            if (playingKhz > 0 && khz != playingKhz) movedOff = true;
            // Follow the picker so the meter shows the chosen frequency.
            new Thread(() -> {
                TunerHub hub = host.hub();
                if (hub.isTunerPresent()) hub.ensureTuned(khz, () -> scanState.cancelRequested());
            }, "ManualMeterTune").start();
        }
    };

    /**
     * Whether a dismissed manual sheet should put the playing channel back: no scan was started from
     * it, its meter actually tuned away from the playing frequency, and no scan is running. Pure, so
     * the decision is tested without a tuner (the retune itself needs one).
     */
    static boolean shouldResume(boolean scanStartedFromSheet, boolean movedOff, boolean scanning) {
        return !scanStartedFromSheet && movedOff && !scanning;
    }

    private ScanDialog sheet;
    private ScheduledExecutorService meter;
    private final ScanState scanState = new ScanState();
    /** The channel the manual sheet opened on, and whether its meter tuned away from it. */
    private int playingKhz = -1;
    private boolean movedOff;
    private boolean scanStartedFromSheet;
    /**
     * Entries THIS full scan has found so far. The sheet used to be fed the size of the saved list,
     * so an auto scan announced "41 services found" before reading a single multiplex (owner, 2026-11-06).
     */
    private final List<ChannelEntry> scanFound = new ArrayList<>();

    public ScanController(Host host) {
        this.host = host;
    }

    public boolean isScanning() {
        return scanState.isScanning();
    }

    /** Stops the running scan (the tuner is about to change owner) without dismissing the sheet. */
    public void cancel() {
        scanState.cancel();
    }

    /** Shuts the sheet and the meter down (the activity is going away). */
    public void dismiss() {
        scanState.cancel();
        stopMeter();
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
    }

    // ------------------------------------------------------------------ full scan

    /**
     * A full scan can rebuild the channel list or add to it. The owner scans in more than one area,
     * and the old dialog only offered "replaces the current channel list", which quietly threw away
     * the channels saved from the other area. Merge keeps the saved list and adds/updates this scan's
     * results; Replace is the old behaviour.
     */
    public void confirmFullScan() {
        // Same order as the manual scan: no dongle, no question (ODTV-51).
        if (!host.hub().isTunerPresent()) {
            Toast.makeText(host.activity(), "No tuner detected · plug the dongle into the OTG port, then start the scan again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ChannelStore channels = host.scanStore();
        int count = channels.channelCount();
        String message = "Scans UHF 14 - 51. "
                + (count > 0 ? "Merge keeps the " + count + (count == 1 ? " saved channel" : " saved channels")
                        + " and adds or updates what this scan finds. Replace drops the saved list first."
                        : "It can take a few minutes.");
        new MaterialAlertDialogBuilder(host.activity())
                .setTitle("Auto Channel Scan")
                .setMessage(message)
                .setPositiveButton("Merge", (d, w) -> startFullScan(true))
                .setNegativeButton("Replace", (d, w) -> startFullScan(false))
                // Neutral draws left of negative in this theme, so the row reads
                // Cancel - Replace - Merge and the destructive choice is never the primary one.
                .setNeutralButton("Cancel", null)
                .show();
    }

    /** @param merge keep the saved channels and add this scan's results; false replaces the list */
    public void startFullScan(boolean merge) {
        if (scanState.isScanning()) {
            scanState.cancel();   // second press: stop
            return;
        }
        if (!host.hub().isTunerPresent()) {
            Toast.makeText(host.activity(), "No tuner detected · plug the dongle into the OTG port, then start the scan again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        scanState.begin();
        host.beginScanExclusive();
        List<Integer> freqs = new ArrayList<>();
        for (int f = MuxSweep.FIRST_KHZ; f <= MuxSweep.LAST_KHZ; f += MuxSweep.STEP_KHZ) freqs.add(f);
        scanFound.clear();
        showSheet(freqs.size());

        new Thread(() -> {
            if (!prepareScan()) return;
            for (int i = 0; i < freqs.size() && !scanState.cancelRequested(); i++) {
                List<ChannelEntry> at = collectAt(freqs.get(i), MuxSweep.label(freqs.get(i)), i,
                        scanFound, scanFound.size());
                if (at == null || scanState.cancelRequested()) continue;
                scanFound.addAll(at);
            }
            if (scanState.cancelRequested()) {
                scanState.finish();
                dismissSheet();
                host.ui().post(() -> {
                    toast("Scan stopped");
                    host.channelsChanged();
                });
                return;
            }
            List<ChannelEntry> result;
            if (merge) {
                result = new ArrayList<>(host.scanStore().channels());
                for (ChannelEntry e : scanFound) {
                    for (int j = result.size() - 1; j >= 0; j--) {
                        ChannelEntry old = result.get(j);
                        if (old.khz == e.khz && old.service.serviceId == e.service.serviceId) result.remove(j);
                    }
                    result.add(e);
                }
            } else {
                result = new ArrayList<>(scanFound);
            }
            host.scanStore().replaceChannelsAndSave(result, host.scanPrefs());
            scanState.finish();
            dismissSheet();
            host.ui().post(() -> {
                if (host.scanStore().channelCount() == 0 && host.hub().failure() != null) {
                    toast(host.hub().failure());
                } else {
                    toast("Scan complete - " + channelCount(host.scanStore().channelCount())
                            + (merge ? " in the list (merged)" : ""));
                }
                host.channelsChanged();
                ChannelEntry start = host.scanStore().pickStart(host.scanPrefs());
                host.setCurrentChannel(start);
                host.scanFinished(start);
            });
        }, "FullScanThread").start();
    }

    /** Starts a scan of one frequency, replacing the entries the list already has there. */
    public void runManualScan(final int khz) {
        if (!scanState.begin()) return;   // a Stop from an earlier scan must not poison this one
        host.beginScanExclusive();
        showSheet(1);
        progress(MuxSweep.label(khz), 0, 0, consoleOf(host.scanStore().channels(), khz));

        new Thread(() -> {
            if (!prepareScan()) return;
            List<ChannelEntry> found = collectAt(khz, MuxSweep.label(khz), 0, host.scanStore().channels(), 0);
            if (found == null) {
                scanState.finish();
                dismissSheet();
                String why = host.hub().failure();
                toast(scanState.cancelRequested() ? "Scan stopped" : "Could not lock " + MhzLabel.mhz(khz)
                        + (why == null ? "" : " - " + why));
                return;
            }
            host.scanStore().replaceFrequency(khz, found);
            host.scanStore().saveChannels(host.scanPrefs());
            scanState.finish();
            dismissSheet();
            host.ui().post(() -> {
                toast("Scan complete - " + channelCount(found.size()) + " at " + MhzLabel.mhz(khz));
                host.channelsChanged();
                ChannelEntry start = found.isEmpty() ? host.currentChannel() : found.get(0);
                if (!found.isEmpty()) host.setCurrentChannel(found.get(0));
                host.scanFinished(start);
            });
        }, "ManualScanThread").start();
    }

    /** Permission + ownership, the part every scan shares; false means the sheet is dealt with. */
    private boolean prepareScan() {
        TunerHub hub = host.hub();
        if (!hub.ensurePermission(60_000)) {
            scanState.finish();
            dismissSheet();
            toast("USB permission needed");
            return false;
        }
        hub.acquire(host.owner());
        return true;
    }

    /**
     * One mux through the shared probe; progress text for the scan sheet.
     *
     * @param shown  what the sheet lists while this mux is read: the running result of THIS scan
     *               (full scan) or the saved rows for this frequency (manual scan). Feeding it the
     *               saved list is what made an auto scan announce the old count before scanning.
     * @param count  services found by THIS scan so far
     */
    private List<ChannelEntry> collectAt(int khz, String label, int index,
                                         List<ChannelEntry> shown, int count) {
        com.pbcnx.opendtvmod.tuner.MuxProbe.Result r = com.pbcnx.opendtvmod.tuner.MuxProbe.probe(
                host.hub(), khz, () -> scanState.cancelRequested(),
                (d, n) -> progress(label, index, count, consoleOf(shown, 0)));
        if (r == null) return null;
        List<ChannelEntry> found = new ArrayList<>();
        for (ServiceInfo s : r.services) {
            found.add(new ChannelEntry(khz, s.copy()));   // one entry per (frequency, service)
        }
        return found;
    }


    // ------------------------------------------------------------------ the manual sheet

    public void showManualSheet() {
        TunerHub hub = host.hub();
        if (!hub.isTunerPresent()) {
            Toast.makeText(host.activity(), "No tuner detected · plug the dongle into the OTG port, then start the scan again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<Integer> freqs = new ArrayList<>();
        for (int f = MuxSweep.FIRST_KHZ; f <= MuxSweep.LAST_KHZ; f += MuxSweep.STEP_KHZ) freqs.add(f);
        ChannelEntry current = host.currentChannel();
        int khz = current != null && current.khz >= MuxSweep.FIRST_KHZ && current.khz <= MuxSweep.LAST_KHZ
                ? current.khz : freqs.get(0);
        // Remember where playback was, so a dismissed sheet can put the tuner back there.
        playingKhz = current != null ? current.khz : -1;
        movedOff = false;
        scanStartedFromSheet = false;
        if (sheet == null) sheet = new ScanDialog(host.activity(), listener);
        sheet.showManual(khz, freqs, "Pick a UHF channel, watch the lock, then scan it");
        startMeter();
    }

    private void showSheet(final int max) {
        host.ui().post(() -> {
            if (sheet == null) sheet = new ScanDialog(host.activity(), listener);
            sheet.showBand("UHF 14 - 51 · " + max + " frequencies", max);
        });
    }

    private void toast(final String text) {
        host.ui().post(() -> Toast.makeText(host.activity(), text, Toast.LENGTH_SHORT).show());
    }

    private void progress(String status, int done, int count, String foundNumbers) {
        host.ui().post(() -> {
            if (sheet != null) sheet.updateBand(status, done, count, foundNumbers);
        });
    }

    private void dismissSheet() {
        host.ui().post(() -> {
            if (sheet != null) sheet.dismiss();
        });
    }

    private void startMeter() {
        stopMeter();
        meter = Executors.newSingleThreadScheduledExecutor();
        meter.scheduleWithFixedDelay(() -> {
            com.pbcnx.opendtvmod.source.TunerSignal st = host.hub().signal();
            host.ui().post(() -> {
                if (sheet != null) sheet.updateManual(st);
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopMeter() {
        if (meter != null) {
            meter.shutdownNow();
            meter = null;
        }
    }

    // ------------------------------------------------------------------ console text

    /**
     * Console lines in discovery order: a "== UHF 16 - 485.143 MHz ==" header each time the multiplex
     * changes, then "7.1 | GMA" per service. No sorting here - the channel list is the sorted surface.
     *
     * @param entries  the entries to print: this scan's running results, or one frequency's saved rows
     * @param onlyKhz  0 for every entry given, or one frequency for a manual scan
     */
    private String consoleOf(List<ChannelEntry> entries, int onlyKhz) {
        StringBuilder sb = new StringBuilder();
        int lastKhz = -1;
        List<String> seen = new ArrayList<>();
        for (ChannelEntry ce : entries) {
            if (onlyKhz > 0 && ce.khz != onlyKhz) continue;
            if (ce.khz != lastKhz) {
                lastKhz = ce.khz;
                if (sb.length() > 0) sb.append('\n');
                sb.append("== ").append(MuxSweep.label(ce.khz)).append(" ==");
            }
            String number = ce.service.displayNumber();
            String line = number + " | "
                    + com.pbcnx.opendtvmod.ui.ChannelAdapter.displayName(ce.service);
            if (seen.contains(line)) continue;
            seen.add(line);
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    private static String channelCount(int n) {
        return n + (n == 1 ? " channel" : " channels");
    }
}
