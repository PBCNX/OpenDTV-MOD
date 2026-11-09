package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;
import android.util.Log;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * The landscape status row: UHF/MHz, receiver time, signal chips, NIT/provider line.
 *
 * <p>MainActivity builds one {@link Data} from its current state and hands it here; the view
 * writing, the clock diagnostic and the chip styling live in this class.
 */
public final class StatusHud {

    private static final String TAG = "OpenDTV-Main";

    private final Activity activity;
    private final TextView statusLeft;
    private final TextView tdt;
    private final TextView signal;
    private final TextView chipData;
    private final TextView chipCc;
    private final TextView chipLock;
    private final TextView chipEwbs;
    private final TextView tvNetworkName;
    private boolean clockDiagnosticLogged;

    /** The HUD's views, named as one object instead of eight positional TextViews. */
    public static final class Views {
        public final Activity activity;
        public final TextView statusLeft;
        public final TextView tdt;
        public final TextView signal;
        public final TextView chipData;
        public final TextView chipCc;
        public final TextView chipLock;
        public final TextView chipEwbs;
        public final TextView networkName;

        public Views(Activity activity, TextView statusLeft, TextView tdt, TextView signal,
                     TextView chipData, TextView chipCc, TextView chipLock, TextView chipEwbs,
                     TextView networkName) {
            this.activity = activity;
            this.statusLeft = statusLeft;
            this.tdt = tdt;
            this.signal = signal;
            this.chipData = chipData;
            this.chipCc = chipCc;
            this.chipLock = chipLock;
            this.chipEwbs = chipEwbs;
            this.networkName = networkName;
        }
    }

    public StatusHud(Views views) {
        this.activity = views.activity;
        this.statusLeft = views.statusLeft;
        this.tdt = views.tdt;
        this.signal = views.signal;
        this.chipData = views.chipData;
        this.chipCc = views.chipCc;
        this.chipLock = views.chipLock;
        this.chipEwbs = views.chipEwbs;
        this.tvNetworkName = views.networkName;
    }

    public void render(ChannelSnapshot d) {
        if (statusLeft == null) return;
        updateNetworkLine(d.service, d.demux);

        String left = "";
        int uhf = com.pbcnx.opendtvmod.tuner.MuxSweep.planChannel(d.khz);
        if (uhf >= 0) left = "UHF " + uhf;
        if (d.khz > 0) {
            if (!left.isEmpty()) left += " - ";
            left += com.pbcnx.opendtvmod.tuner.MhzLabel.mhz(d.khz);
        }
        statusLeft.setText(left.isEmpty() ? "-" : left);

        if (d.clockMs <= 0) {
            tdt.setText("TDT --");
        } else {
            logClockDiagnosticOnce(d.demux);
            SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US);
            // Follow the RECEIVER, not the table: the ABS-CBN TVplus ignores the TOT's descriptor and
            // adds eight hours to whatever the field holds (BMLPLAYER's eight-mux sweep, 31 Oct; the
            // hardware behaviour from PBCNX-DTV's TimeInjector). The device's own zone is irrelevant
            // here - the set does not consult it - so the offset is the fixed receiver one.
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            tdt.setText("TDT " + format.format(new Date(com.pbcnx.opendtvmod.ts.util.TsUtil.receiverClock(d.clockMs)))
                    + " " + com.pbcnx.opendtvmod.ts.util.TsUtil.broadcastZoneLabel());
        }

        switch (WatchSignalText.of(d.live, d.tunerPresent, d.signal != null,
                d.signal != null && d.signal.locked)) {
            case PLAYING:
                signal.setText(R.string.watch_playing);
                break;
            case NO_TUNER:
                signal.setText(WatchSignalText.NO_TUNER);
                break;
            case TUNING:
                signal.setText(R.string.hud_tuning);
                break;
            default:
                signal.setText("S " + signalPercent(d.signal.strengthPercent)
                        + "  Q " + signalPercent(d.signal.qualityPercent));
                break;
        }

        setChip(chipData, d.data, R.color.odtv_ok, activity.getString(R.string.cd_data_broadcast));
        setChip(chipCc, d.caption, R.color.odtv_ok, activity.getString(R.string.cd_closed_captions));
        setChip(chipLock, d.encrypted, R.color.odtv_warning, activity.getString(R.string.cd_encrypted));
        setAlertChip(d.emergency);
    }

    /**
     * EWBS in the landscape HUD: a filled pill when live, so the label has to stay white.
     * setChip() would tint it the same red as the fill and the word would vanish - the identical
     * trap the portrait strip had. Background and text both key off state_selected.
     */
    private void setAlertChip(boolean active) {
        if (chipEwbs == null) return;
        chipEwbs.setSelected(active);
        chipEwbs.setAlpha(active ? 1f : 0.28f);
        chipEwbs.setContentDescription(activity.getString(active ? R.string.cd_chip_active : R.string.cd_chip_inactive,
                activity.getString(R.string.cd_emergency_broadcast)));
    }

    /** NIT name and SDT provider share one marquee line, so they never fight for width. */
    public void updateNetworkLine(ServiceInfo service, TsDemux demux) {
        if (tvNetworkName == null) return;
        String nit = demux != null ? demux.getNetworkName() : "";
        String provider = service != null ? service.provider : "";
        String line = activity.getString(R.string.hud_network_line, nit.isEmpty() ? "-" : nit,
                provider.isEmpty() ? "-" : provider);
        if (!line.contentEquals(tvNetworkName.getText())) {
            tvNetworkName.setText(line);
        }
        if (!tvNetworkName.isSelected()) tvNetworkName.setSelected(true);
    }

    private void setChip(TextView chip, boolean active, int activeColorRes, String description) {
        if (chip == null) return;
        int color = ContextCompat.getColor(activity,
                active ? activeColorRes : R.color.odtv_on_surface_variant);
        chip.setTextColor(color);
        chip.setAlpha(active ? 1f : 0.28f);
        chip.setContentDescription(activity.getString(active ? R.string.cd_chip_active : R.string.cd_chip_inactive,
                description));
    }

    private static String signalPercent(int value) {
        return value < 0 ? "--" : value + "%";
    }

    /**
     * One log line per source, for the TOT diagnostic that must never become a correction: the field,
     * its local_time_offset descriptor, and what the display does with them. The descriptor disagrees
     * with the field on most PH muxes (GMA declares PHL +0:00 and sends UTC; TV5 declares +0:00 and
     * sends local time), and the receiver ignores it either way.
     */
    private void logClockDiagnosticOnce(TsDemux demux) {
        if (demux == null || clockDiagnosticLogged || demux.getClockMs() <= 0) return;
        clockDiagnosticLogged = true;
        int desc = demux.clockDescriptorMinutes();
        String descText = desc == TsDemux.NO_DESCRIPTOR ? "none"
                : String.format(Locale.US, "%s%d:%02d", desc < 0 ? "-" : "+",
                        Math.abs(desc) / 60, Math.abs(desc) % 60);
        Log.i(TAG, String.format(Locale.US,
                "clock %s field %s descriptor %s -> shown %s (%s) - the receiver adds +8 and ignores the descriptor",
                demux.clockIsTot() ? "TOT" : "TDT", com.pbcnx.opendtvmod.ts.util.TsUtil.formatTime(demux.getClockMs()), descText,
                com.pbcnx.opendtvmod.ts.util.TsUtil.formatReceiverTime(demux.getClockMs()), com.pbcnx.opendtvmod.ts.util.TsUtil.broadcastZoneLabel()));
    }
}
