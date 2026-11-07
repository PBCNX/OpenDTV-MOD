package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.player.DecoderPrefs;
import com.pbcnx.opendtvmod.tuner.MhzLabel;
import com.pbcnx.opendtvmod.tuner.MuxSweep;
import com.pbcnx.opendtvmod.tuner.ServiceWait;
import com.pbcnx.opendtvmod.ui.ChannelAdapter;
import androidx.annotation.Nullable;
import com.pbcnx.opendtvmod.ts.pipe.TsPipe;
import com.pbcnx.opendtvmod.ts.TsDemux;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.bml.BmlController;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import androidx.media3.ui.PlayerView;
import androidx.media3.exoplayer.ExoPlayer;
import android.widget.Toast;
import androidx.media3.exoplayer.source.MediaSource;
import com.pbcnx.opendtvmod.source.TunerSignal;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.DefaultLoadControl;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

import com.pbcnx.opendtvmod.player.PlayerFactory;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;
import com.pbcnx.opendtvmod.ts.StreamInfo;

/**
 * The watching screen's playback state machine: tuning, the player and its decoder fallbacks, the
 * per-multiplex decoder memory, the tuning/no-signal cards and the retry/watchdog rules.
 *
 * <p>It lived inside MainActivity, which is why the activity was also the app's playback owner as
 * well as its composition root. The screen keeps the views and the lifecycle; this class owns the
 * attempts, generations and decoder decisions, and reaches the screen through the Host interface below, never through activity fields.</p>
 */
public final class WatchPlayback {
    /** Everything the machine needs from the screen; no activity fields are reached directly. */
    public interface Host extends ScreenSeam {
        ExecutorService worker();
        TsPipe pipe();
        ExoPlayer player();
        void setPlayer(ExoPlayer player);
        @Nullable PlayerView playerView();
        ChannelRecorder recorder();
        @Nullable TuningCard tuning();
        BmlController bml();
        AtomicInteger playGen();
        int currentTab();
        boolean isScanning();
        boolean isFinishing();
        boolean firstStart();
        void setFirstStart(boolean first);
        boolean sourceCannotTune();
        @Nullable ServiceInfo liveService(TsDemux demux);
        void resetCaptions();
        void bindCaptionSink();
        void applyAudioLanguagePref();
        /** Aspect geometry for Automatic depends on the video size, which is known only now. */
        void refreshAspectMode();
        void updateContentAdapter();
        void showInfoBanner();
        void hideTuningCard();
        void showCurrentChannel();
        void recreate();
        Runnable showTuningCardRunnable();
        Runnable showNoSignalCardRunnable();
        String audioLanguageKey();
    }


    static final String TAG = "OpenDTV-Main";
    static final int MAX_RETRIES = 3;

    final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            videoRendered = true;
            retries = 0;
            host.hideTuningCard();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            // Audio-only services never render a frame: the tuning card ends when the player is ready.
            if (state == Player.STATE_READY && !hasVideoTrack()) host.hideTuningCard();
        }

        @Override
        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                if (group.length == 0) continue;
                if (group.getType() == C.TRACK_TYPE_VIDEO || group.getType() == C.TRACK_TYPE_AUDIO) {
                    Log.i(TAG, "track type=" + group.getType() + " mime="
                            + group.getTrackFormat(0).sampleMimeType + " selected=" + group.isTrackSelected(0));
                }
            }
            pickBestAudioTrack(tracks);
            // Automatic follows the playing video's aspect, and the tracks callback is the first
            // point where that size exists. Other modes are unaffected (they resolve without it).
            host.refreshAspectMode();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "player error: " + error.getErrorCodeName() + " " + error.getMessage());
            final ChannelEntry e = host.currentChannel();
            final DecoderFailurePolicy.Action action = e == null || host.isFinishing()
                    ? DecoderFailurePolicy.Action.RETRY
                    : DecoderFailurePolicy.decide(error.errorCode, hasVideoTrack(),
                            DecoderPrefs.manual(host.activity()),
                            // A player already built for software video counts as "tried": after
                            // the recreate below this flag is fresh again, so without it a failing
                            // software decoder would fall back to itself in a loop.
                            softwareFallbackTried || playerForcedSoftwareVideo);
            if (action == DecoderFailurePolicy.Action.FALLBACK_SOFTWARE) {
                // The decoder refused a stream its format check said it supported; retrying it
                // cannot help, and that retry is what used to end in "No signal" on a clean mux.
                Log.w(TAG, "decoder error " + error.getErrorCodeName() + " (" + error.errorCode
                        + "): switching video to software instead of retrying the same decoder");
                fallbackToSoftwareDecoder("decoder error " + error.getErrorCodeName()
                        + " (" + error.errorCode + ")");
                return;
            }
            if (action == DecoderFailurePolicy.Action.GIVE_UP) {
                // Software video was already the active decoder, so there is no fallback left.
                Log.w(TAG, "decoder error " + error.getErrorCodeName()
                        + " with software video: not retrying the same decoder");
                showPlayerErrorCard();
                return;
            }
            if (e != null && !host.isFinishing() && retries++ < MAX_RETRIES) {
                // Same multiplex: no retune, just a fresh filter + prepare.
                host.ui().postDelayed(() -> {
                    if (!host.isFinishing() && !host.isScanning() && host.currentChannel() == e) tuneAndPlay(e);
                }, 1000);
            } else {
                showPlayerErrorCard();
            }
        }
    };

    /**
     * The failure card and the hub's reason, shared by "retries exhausted" and "no fallback left".
     * The card says this and stays up; a toast on top of it said the same thing twice. The hub's
     * reason is different: it says why the tune/source failed, so it goes to the log and to the
     * user instead of staying invisible.
     */
    private void showPlayerErrorCard() {
        String why = host.hub().failure();
        if (why != null) {
            Log.w(TAG, "playback failed after: " + why);
            android.widget.Toast.makeText(host.activity(), why, android.widget.Toast.LENGTH_LONG).show();
        }
        showNoSignalCard();
    }

    /** The auto-selected audio group (MainScreen reads it). */
    public androidx.media3.common.TrackGroup autoAudioGroup() { return autoAudioGroup; }

    /** The load control built once at start-up. */
    public DefaultLoadControl loadControl() { return loadControl; }

    public void setLoadControl(DefaultLoadControl control) { loadControl = control; }

    /** The player listener this machine installs on the screen's player. */
    public Player.Listener playerListener() { return playerListener; }

    public void markDecoderRebuildPending() { decoderRebuildPending = true; }

    public void forgetLastPlay() { lastPlayEntry = null; }

    public void resetRetries() { retries = 0; }

    public void resetLastPlayAt() { lastPlayAtMs = 0; }

    private final Host host;

    /** The filter that feeds the player; owned here, read by the caption binding. */
    @Nullable private ServiceFilter liveFilter;

    public WatchPlayback(Host host) {
        this.host = host;
    }

    int retries;
    volatile boolean videoRendered;
    boolean softwareFallbackTried;
    boolean playerForcedSoftwareVideo;
    boolean decoderRebuildPending;
    DefaultLoadControl loadControl;
    ChannelEntry lastPlayEntry;
    long lastPlayAtMs;
    androidx.media3.common.TrackGroup autoAudioGroup;

    int beginPlayAttempt() {
        final int generation = host.playGen().incrementAndGet();
        videoRendered = false;
        host.ui().removeCallbacks(host.showTuningCardRunnable());
        host.ui().removeCallbacks(host.showNoSignalCardRunnable());
        host.ui().postDelayed(host.showTuningCardRunnable(), 500);
        host.ui().postDelayed(host.showNoSignalCardRunnable(), 7500);
        return generation;
    }

    void stopPlayback(final int generation) {
        host.ui().post(() -> {
            if (generation != host.playGen().get()) return;
            host.hub().detachSink(host.owner());
            host.player().stop();
            host.player().clearMediaItems();
            host.pipe().clear();
        });
    }

    public void tuneAndPlay(final ChannelEntry entry) {
        // Belt and braces with firstStart: the permission path and a lifecycle callback can still
        // land together, and two players for one channel is never what anybody asked for. A real
        // retune (the 12 s watchdog, a re-pick) is seconds later, so it is not affected.
        long nowMs = SystemClock.elapsedRealtime();
        if (entry == lastPlayEntry && nowMs - lastPlayAtMs < 1500) {
            Log.i(TAG, "play sid=" + entry.service.serviceId + " ignored: same attempt "
                    + (nowMs - lastPlayAtMs) + " ms ago");
            return;
        }
        lastPlayEntry = entry;
        lastPlayAtMs = nowMs;
        final int generation = beginPlayAttempt();
        Log.i(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                + " khz=" + entry.khz + " source=" + host.hub().sourceName());
        Runnable task = () -> {
            if (generation != host.playGen().get()) return;   // superseded while queued
            // A frequency means something only to a tuner. Tuning a capture would call openLocked(),
            // find no device, and tear the source down - the exact teardown this change removes.
            final boolean tune = !host.sourceCannotTune();
            if (tune && !host.hub().isTunerPresent()) {
                host.setCanTune(false);
                return;
            }
            host.hub().acquire(host.owner());
            stopPlayback(generation);
            if (tune && !host.hub().ensureTuned(entry.khz, () -> generation != host.playGen().get())) {
                if (generation != host.playGen().get()) return;
                Log.w(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                        + " -> NO LOCK at " + entry.khz);
                host.ui().post(() -> Toast.makeText(host.activity(),
                        "Could not lock " + MhzLabel.mhz(entry.khz), Toast.LENGTH_SHORT).show());
                return;
            }
            if (generation != host.playGen().get()) return;
            // Saved channels are stubs: take the live service (PMT PID, streams) from the demux.
            final long startedAt = SystemClock.elapsedRealtime();
            final ServiceInfo live = waitForService(entry.service.serviceId, generation);
            benchChoice("tune", live, startedAt);
            if (live == null) {
                Log.w(TAG, "play sid=" + entry.service.serviceId + " name=" + entry.service.name
                        + " -> NOT FOUND after " + (tune ? "lock" : "source start"));
                if (generation == host.playGen().get()) {
                    if (tune) {
                        host.ui().post(() -> Toast.makeText(host.activity(),
                                "Channel not found on this frequency", Toast.LENGTH_SHORT).show());
                    } else {
                        host.ui().post(this::showSourceNoFrame);
                    }
                }
                return;
            }
            beginPlayback(live, generation, entry, tune ? "LOCKED" : "PLAYING");
        };
        try {
            host.worker().execute(task);
        } catch (RejectedExecutionException ignored) {
            // activity is being destroyed
        }
    }

    @Nullable public ServiceFilter liveFilter() {
        return liveFilter;
    }

    void beginPlayback(final ServiceInfo live, final int generation, final ChannelEntry entry,
                               final String how) {
        StringBuilder streams = new StringBuilder();
        for (StreamInfo st : live.streams) {
            if (streams.length() > 0) streams.append(", ");
            streams.append(String.format(Locale.US, "pid=0x%04X type=0x%02X comp=0x%04X tag=0x%02X",
                    st.pid, st.streamType, st.dataComponentId, st.componentTag));
        }
        Log.i(TAG, "play sid=" + live.serviceId + " name=" + live.name + " -> " + how
                + " streams=" + streams);
        final TsDemux d = host.hub().demux();
        host.ui().post(() -> {
            if (generation != host.playGen().get() || host.isFinishing()) return;
            host.pipe().clear();
            ServiceFilter previous = liveFilter;
            if (previous != null && previous.isRecording()) {
                previous.stopRecord();
                Toast.makeText(host.activity(), "Recording stopped - channel changed", Toast.LENGTH_SHORT).show();
            }
            liveFilter = new ServiceFilter(host.pipe(), d.getTsId(), live);
            host.recorder().updateTally();
            host.resetCaptions();
            host.bindCaptionSink();
            host.applyAudioLanguagePref();
            d.setPacketSink(liveFilter);

            applyVideoMemoryFor(entry.khz);

            MediaSource ms = PlayerFactory.liveSource(host.pipe(), () -> host.hub().demux());
            host.player().setMediaSource(ms);
            host.player().prepare();
            host.player().setPlayWhenReady(true);
            armBufferingWatchdog(entry, generation);
            if (host.currentTab() == 1) host.updateContentAdapter();
            host.showInfoBanner();
        });
    }

    public void playFromRunningSource() {
        final int generation = beginPlayAttempt();
        Log.i(TAG, "play from source " + host.hub().sourceName());
        Runnable task = () -> {
            if (generation != host.playGen().get()) return;
            host.hub().acquire(host.owner());
            stopPlayback(generation);
            final long startedAt = SystemClock.elapsedRealtime();
            final ServiceInfo live = waitForSourceService(generation);
            benchChoice("source", live, startedAt);
            if (live == null) {
                Log.w(TAG, "source " + host.hub().sourceName() + " produced no playable service");
                if (generation == host.playGen().get()) host.ui().post(this::showSourceNoFrame);
                return;
            }
            final ChannelEntry entry = new ChannelEntry(host.hub().currentKhz(), live);
            host.ui().post(() -> {
                if (generation != host.playGen().get() || host.isFinishing()) return;
                // Set, not saved: a capture has no frequency and the start channel must stay real.
                host.setCurrentChannel(entry);
                host.showCurrentChannel();
            });
            beginPlayback(live, generation, entry, "PLAYING");
        };
        try {
            host.worker().execute(task);
        } catch (RejectedExecutionException ignored) {
            // activity is being destroyed
        }
    }

    ServiceInfo waitForSourceService(int generation) {
        // A tuner answers within a second of a good tune; a network source can take ~15 s to open
        // its TS tap (measured on the Baron box), so it gets a longer window rather than a false
        // "no playable service" seconds before the first packet.
        long limitMs = host.hub().isHardwareTuner() ? 12_000 : 25_000;
        return ServiceWait.anyPlayable(() -> host.hub().demux(), limitMs,
                () -> generation != host.playGen().get());
    }

    /**
     * Bench witness for the ABS-CBN pick (0.2.0): which service the picker adopted, and what the
     * demux believed about every service at that moment. The phone's logcat shows none of the app's
     * tags, so the outcome (oneseg 3224 adopted instead of the main 3200 that carries the datacast)
     * has to be explained from this line rather than guessed at. Debug builds only.
     */
    private void benchChoice(String where, @Nullable ServiceInfo chosen, long startedAtMs) {
        TsDemux d = host.hub().demux();
        StringBuilder sb = new StringBuilder("pick@").append(where)
                .append(" chosen=").append(chosen == null ? "none" : chosen.serviceId)
                .append(" khz=").append(host.hub().currentKhz())
                .append(" source=").append(host.hub().sourceName())
                .append(" tsId=").append(d == null ? -1 : d.getTsId())
                .append(" waited=").append(SystemClock.elapsedRealtime() - startedAtMs).append("ms");
        if (d != null) {
            for (ServiceInfo s : d.getServices()) {
                int data = 0;
                for (StreamInfo st : s.streams) if (st.isDatacast()) data++;
                sb.append(" | ").append(s.serviceId)
                        .append(" pmt=").append(s.pmtSeen ? 1 : 0)
                        .append(" vid=").append(s.hasVideo() ? 1 : 0)
                        .append(" data=").append(data)
                        .append(" str=").append(s.streams.size());
            }
        }
        com.pbcnx.opendtvmod.dev.BenchHooks.marker(host.activity(), sb.toString());
    }

    void armBufferingWatchdog(final ChannelEntry entry, final int generation) {
        // Stage 1, at 4.5 s: the decoder is running and presenting nothing. ALLTV on the Qualcomm
        // decoder looks exactly like this - READY, frames in, Render 0 - and the old single 12 s
        // gate meant twelve black seconds before recovery. Long enough for a healthy decoder to
        // present; short enough that the viewer has not looked away.
        host.ui().postDelayed(() -> {
            if (generation != host.playGen().get() || host.isFinishing() || host.isScanning() || host.currentChannel() != entry) return;
            if (softwareFallbackTried) return;
            if (host.player().getPlaybackState() == Player.STATE_READY && !videoRendered && hasVideoTrack()
                    && !DecoderPrefs.manual(host.activity())) {
                fallbackToSoftwareDecoder("ready but no frame after 4.5 s");
            }
        }, 4_500);
        // Stage 2, at 12 s: never left BUFFERING on a locked mux - the decoder's fault, not the
        // channel's, so it falls back too; an unlocked mux is left alone to retry.
        host.ui().postDelayed(() -> {
            if (generation != host.playGen().get() || host.isFinishing() || host.isScanning() || host.currentChannel() != entry) return;
            // Auto only: a Manual choice is the viewer's and nothing here may override it.
            boolean autoVideo = !DecoderPrefs.manual(host.activity());
            if (host.player().getPlaybackState() == Player.STATE_READY) {
                // ALLTV HD (and some other muxes) feeds frames to the Qualcomm AVC
                // decoder but it never renders one. FFmpeg handles the same stream. The early
                // 4.5 s check above normally catches this; this is the safety net.
                if (!videoRendered && hasVideoTrack() && autoVideo) {
                    fallbackToSoftwareDecoder("ready but no frame after 12 s");
                }
                return;
            }
            TunerSignal sig = host.hub().signal();
            boolean locked = !host.hub().isHardwareTuner() || (sig != null && sig.locked);
            if (autoVideo && locked && hasVideoTrack() && !softwareFallbackTried) {
                fallbackToSoftwareDecoder("still " + host.player().getPlaybackState() + " after 12 s on a locked mux");
                return;
            }
            Log.w(TAG, "still buffering after 12 s (state " + host.player().getPlaybackState() + "), retry " + retries);
            // Giving up says nothing here: the card over the video has already escalated to its
            // own sentence, and "check the signal" is the wrong advice when the mux is locked.
            if (retries++ < MAX_RETRIES) tuneAndPlay(entry);
        }, 12_000);
    }

    boolean hasVideoTrack() {
        for (androidx.media3.common.Tracks.Group group : host.player().getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO && group.length > 0) return true;
        }
        return false;
    }

    void applyVideoMemoryFor(int khz) {
        boolean wantSoftware = DecoderPrefs.videoMode(host.activity(), khz)
                == PlayerFactory.MODE_PREFER;
        if (!decoderRebuildPending && wantSoftware == playerForcedSoftwareVideo) return;
        decoderRebuildPending = false;
        playerForcedSoftwareVideo = wantSoftware;
        host.player().release();
        host.setPlayer(PlayerFactory.build(host.activity(), loadControl, true, wantSoftware));
        host.player().addListener(playerListener);
        if (host.playerView() != null) host.playerView().setPlayer(host.player());
        Log.i(TAG, "decoder at " + khz + " kHz: video="
                + (wantSoftware ? "FFmpeg software" : "the device's MediaCodec") + " audio="
                + (DecoderPrefs.audioMode(host.activity()) == PlayerFactory.MODE_PREFER
                        ? "FFmpeg software" : "the device's decoder"));
    }

    void fallbackToSoftwareDecoder(String reason) {
        if (softwareFallbackTried || host.isFinishing()) return;
        softwareFallbackTried = true;
        int khz = host.currentChannel() != null ? host.currentChannel().khz
                : host.hub().currentKhz();
        Log.w(TAG, "switching video to FFmpeg: " + reason
                + (khz > 0 ? " - remembered for " + khz + " kHz" : " - a capture, so the video preference moves"));
        if (khz > 0) {
            DecoderPrefs.rememberAuto(host.activity(), khz, true);
        } else {
            // A capture has no multiplex to remember, so the switch itself moves: Manual + software
            // video, which is the same statement the settings screen makes.
            DecoderPrefs.setManual(host.activity(), true);
            DecoderPrefs.setVideoSoftware(host.activity(), true);
        }
        Toast.makeText(host.activity(), khz > 0
                ? "Video stalled · software video from now on for this channel"
                : "Video stalled · switched video to software decoding", Toast.LENGTH_LONG).show();
        host.recreate();
    }

    ServiceInfo waitForService(int sid, int generation) {
        // A tuner answers within a second of a good tune; a network source can take ~15 s to open
        // its TS tap (measured on the Baron box), so it gets a longer window rather than a false
        // "no playable service" seconds before the first packet.
        long limitMs = host.hub().isHardwareTuner() ? 12_000 : 25_000;
        return ServiceWait.byId(() -> host.hub().demux(), sid, limitMs,
                ServiceWait.FAST_MS, () -> generation != host.playGen().get());
    }

    public void showTuningCard() {
        if (host.tuning() == null || videoRendered) return;
        if (host.bml().isVisible()) return;
        if (host.currentChannel() == null) {
            host.tuning().hide();
            return;
        }
        ServiceInfo service = host.currentChannel().service;
        String number = service.displayNumber();
        if (host.sourceCannotTune()) {
            // Nothing is being tuned here: the card would claim a sweep for a file that is opening.
            host.tuning().showLoading(number, ChannelAdapter.displayName(service),
                    frequencyText());
        } else {
            host.tuning().showTuning(number, ChannelAdapter.displayName(service),
                    frequencyText());
        }
    }

    String frequencyText() {
        int khz = host.currentChannel() != null ? host.currentChannel().khz : 0;
        if (khz <= 0) return "";
        return MuxSweep.planChannel(khz) >= 0
                ? MuxSweep.label(khz) : MhzLabel.mhz(khz);
    }

    public void showNoSignalCard() {
        if (host.tuning() == null || videoRendered) return;
        if (host.bml().isVisible()) return;
        if (host.hub().isOpen() && !host.hub().isHardwareTuner()) {
            // Nothing is being received, so there is no aerial to blame and no lock to miss.
            showSourceNoFrame();
            return;
        }
        ServiceInfo saved = host.currentChannel() != null ? host.currentChannel().service : null;
        ServiceInfo live = host.liveService(host.hub().demux());
        ServiceInfo service = live != null ? live : saved;
        String name = service != null ? ChannelAdapter.displayName(service) : null;
        // A scrambled service never produces a frame, but the tuner is locked and the mux is
        // healthy - telling the viewer to check the antenna would send them after a fault
        // that is not there. The CA flag comes from the PMT, so it only exists once locked.
        boolean scrambled = (live != null && live.encrypted) || (saved != null && saved.encrypted);
        // A service on a healthy mux can also carry no video at all (audio or data only). The
        // PMT tells us that much, so the copy must not blame the aerial either.
        boolean noVideo = live != null && live.pmtSeen && !live.hasVideo();
        // The third case: locked, a video stream declared, and still no frame. The tuner did its
        // job and the decoder has nothing to show yet, which is not a fault either.
        TunerSignal sig = host.hub().signal();
        boolean locked = sig != null && sig.locked;
        boolean tooWeak = live != null && live.pmtSeen && live.hasVideo() && locked;
        if (scrambled) {
            host.tuning().showEncrypted(name, frequencyText());
        } else if (noVideo) {
            host.tuning().showNoVideo(name, frequencyText());
        } else if (tooWeak) {
            host.tuning().showLockedNoFrame(name, frequencyText());
        } else {
            host.tuning().showNoSignal(name, frequencyText());
        }
    }

    void showSourceNoFrame() {
        if (host.tuning() == null || videoRendered) return;
        if (host.bml().isVisible()) return;
        ServiceInfo live = host.liveService(host.hub().demux());
        String name = live != null ? ChannelAdapter.displayName(live) : null;
        host.tuning().showNoPictureFromSource(name, host.hub().sourceName());
    }

    void pickBestAudioTrack(androidx.media3.common.Tracks tracks) {
        if (!host.prefs().getString(host.audioLanguageKey(), "").isEmpty()) return;
        androidx.media3.common.TrackSelectionParameters tsp = host.player().getTrackSelectionParameters();
        androidx.media3.common.Tracks.Group best = null;
        androidx.media3.common.Tracks.Group selected = null;
        int audioGroups = 0;
        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO || group.length == 0 || !group.isTrackSupported(0)) continue;
            audioGroups++;
            if (group.isTrackSelected(0)) selected = group;
            if (tsp.overrides.containsKey(group.getMediaTrackGroup()) && group.getMediaTrackGroup() != autoAudioGroup) return;   // a manual pick
            if (best == null || audioQuality(group.getTrackFormat(0)) > audioQuality(best.getTrackFormat(0))) best = group;
        }
        if (audioGroups < 2 || best == null || best == selected) return;
        autoAudioGroup = best.getMediaTrackGroup();
        host.player().setTrackSelectionParameters(tsp.buildUpon()
                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(best.getMediaTrackGroup(), 0))
                .build());
    }

    static long audioQuality(androidx.media3.common.Format f) {
        long bitrate = f.bitrate > 0 ? Math.min(f.bitrate / 1000, 4095) : 0;       // kbit/s
        long rate = f.sampleRate > 0 ? Math.min(f.sampleRate / 1000, 255) : 0;      // kHz
        long ch = f.channelCount > 0 ? Math.min(f.channelCount, 15) : 0;
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        long codec = m.contains("mp4a") ? 3 : m.contains("ac3") ? 2 : m.contains("mpeg") ? 1 : 0;
        return (ch << 40) | (rate << 28) | (bitrate << 8) | codec;
    }
}
