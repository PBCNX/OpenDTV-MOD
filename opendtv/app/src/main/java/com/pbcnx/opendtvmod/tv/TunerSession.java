package com.pbcnx.opendtvmod.tv;

import com.pbcnx.opendtvmod.ts.pipe.TsPipe;
import com.pbcnx.opendtvmod.ts.pipe.ServiceFilter;

import com.pbcnx.opendtvmod.tuner.TunerHub;

import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pbcnx.opendtvmod.player.PlayerFactory;
import com.pbcnx.opendtvmod.ts.ServiceInfo;
import com.pbcnx.opendtvmod.ts.TsDemux;

/**
 * One Live Channels viewing session. Data flow (same as the phone app):
 * USB tuner thread -> TsDemux -> ServiceFilter -> TsPipe -> ExoPlayer -> the TV app's Surface.
 *
 * The TV framework calls the overridden methods on the main thread; tuning (USB, seconds) runs on {@link #worker}.
 * Every tune bumps {@link #gen}; background steps drop out as soon as their generation is stale.
 */
@OptIn(markerClass = UnstableApi.class)
final class TunerSession extends TvInputService.Session implements TunerHub.Owner {
    private static final String TAG = "OpenDTV-TV";
    private static final int MAX_RETRIES = 3;
    /** How long Auto waits for a picture before it tries the other decoder. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 2_500;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService epgExec = Executors.newSingleThreadScheduledExecutor();
    private final TunerHub hub;
    private final TsPipe pipe = new TsPipe(8 * 1024 * 1024);
    private final AtomicInteger gen = new AtomicInteger();
    private final DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 15000, 1500, 2500)   // live TV: start after ~1.5 s, keep 15 s
            .build();
    private ExoPlayer player;
    private Surface surface;

    // main thread only
    private boolean released;
    private boolean firstFrame;
    private Uri currentUri;
    private int retries;
    private ScheduledFuture<?> epgTask;
    private final List<TrackRef> audioRefs = new ArrayList<>();
    /** The renderer modes the current player was built with; -1 until the first build. */
    private int activeVideoMode = -1;
    private int activeAudioMode = -1;
    /** True once the watchdog has already tried the other decoder for this tune. */
    private boolean frameWatchdogFired;
    /** Auto found the decoder that works at this frequency: remember it when the picture arrives. */
    private int pendingRememberKhz;

    private volatile long lastEpgHash;

    TunerSession(Context context) {
        super(context);
        this.context = context;
        this.hub = com.pbcnx.opendtvmod.App.hub(context);
        // A session knows no frequency yet, so it starts on the device's decoder (Auto's default
        // everywhere). The decoder for the actual multiplex is chosen in startPlayback().
        buildPlayer(com.pbcnx.opendtvmod.player.DecoderPrefs.videoMode(context, 0),
                com.pbcnx.opendtvmod.player.DecoderPrefs.audioMode(context));
    }

    /**
     * Builds (or rebuilds) the player for the two renderer modes.
     *
     * <p>The renderers - and therefore the decoders - are fixed when ExoPlayer is built, so choosing
     * the other decoder means a new player. Media3's own decoder fallback only fires when a decoder
     * cannot be INITIALISED, which is not what a decoder that initialises and presents nothing does;
     * the first-frame watchdog below is what covers that case.</p>
     */
    private void buildPlayer(int videoMode, int audioMode) {
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
        }
        activeVideoMode = videoMode;
        activeAudioMode = audioMode;
        // One factory for both halves AND for the player itself: PlayerFactory applies the MPEG-1
        // audio blocklist, the split renderer modes and the audio-focus policy, so the TV input
        // cannot drift from the app's own player. A TV input must not grab audio focus.
        player = com.pbcnx.opendtvmod.player.PlayerFactory.build(context, loadControl, /* audioFocus= */ false,
                videoMode, audioMode);
        player.addListener(playerListener);
        if (surface != null && surface.isValid()) player.setVideoSurface(surface);
        addDiagnostics();
        Log.i(TAG, "player: video=" + (videoMode == PlayerFactory.MODE_PREFER ? "software" : "hardware")
                + " audio=" + (audioMode == PlayerFactory.MODE_PREFER ? "software" : "hardware"));
    }

    /**
     * Renderer-side diagnostics, debug builds only.
     *
     * <p>Added 2026-11-04 for TV5, which stutters while the USB path is perfect (heartbeat: 5.5 Mbps,
     * 0 gaps &gt;100 ms, 0 timeouts) with 0 pipe drops and 0 continuity errors - so the loss is
     * renderer-side, and these two callbacks are the only places it is reported. Kept because that
     * question returns with every mux; not shipped, because a release log should not narrate the
     * renderer at a viewer.</p>
     */
    private void addDiagnostics() {
        if (!com.pbcnx.opendtvmod.BuildConfig.DEBUG) return;
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onAudioUnderrun(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t,
                                        int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                Log.w(TAG, "AUDIO UNDERRUN: buffer=" + bufferSize + " B (" + bufferSizeMs
                        + " ms), sinceLastFeed=" + elapsedSinceLastFeedMs + " ms");
            }

            @Override
            public void onDroppedVideoFrames(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t,
                                             int droppedFrames, long elapsedMs) {
                Log.w(TAG, "VIDEO DROPPED: " + droppedFrames + " frames in " + elapsedMs + " ms");
            }
        });
    }

    // ------------------------------------------------------------------ TvInputService.Session

    @Override
    public boolean onSetSurface(Surface surface) {
        this.surface = surface;    // kept, because a rebuilt player has to be given it again
        if (surface == null) player.clearVideoSurface();
        else player.setVideoSurface(surface);
        return true;
    }

    @Override
    public void onSetStreamVolume(float volume) {
        player.setVolume(volume);
    }

    @Override
    public void onSetCaptionEnabled(boolean enabled) {
        // ARIB captions are not decoded.
    }

    @Override
    public boolean onTune(Uri channelUri) {
        currentUri = channelUri;
        retries = 0;
        beginTune(channelUri);
        return true;
    }

    @Override
    public boolean onSelectTrack(int type, String trackId) {
        if (type != TvTrackInfo.TYPE_AUDIO) return false;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        if (trackId == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
        } else {
            TrackRef ref = null;
            for (TrackRef r : audioRefs) if (r.id.equals(trackId)) ref = r;
            if (ref == null) return false;
            b.setOverrideForType(new TrackSelectionOverride(ref.group.getMediaTrackGroup(), ref.index));
        }
        player.setTrackSelectionParameters(b.build());
        notifyTrackSelected(type, trackId);
        return true;
    }

    @Override
    public void onRelease() {
        released = true;
        gen.incrementAndGet();
        cancelEpg();
        main.removeCallbacksAndMessages(null);
        hub.detachSink(this);
        hub.release(this);
        worker.shutdownNow();
        epgExec.shutdownNow();
        player.release();
        pipe.close();
    }

    // ------------------------------------------------------------------ tuner owner

    /** Another session or the scan screen took the tuner. */
    @Override
    public void onPreempted() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                gen.incrementAndGet();
                cancelEpg();
                player.stop();
                player.clearMediaItems();
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            }
        });
    }

    // ------------------------------------------------------------------ tuning

    private boolean stale(int g) {
        return released || gen.get() != g;
    }

    /** Main thread. Resets playback and starts the background tune. */
    private void beginTune(final Uri uri) {
        final int g = gen.incrementAndGet();
        firstFrame = false;
        lastEpgHash = 0;
        cancelEpg();
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    tuneTask(uri, g);
                }
            });
        } catch (RuntimeException ignored) {
            // executor already shut down by onRelease
        }
    }

    private void tuneTask(Uri uri, int g) {
        final TvChannels.ChannelRef ref = TvChannels.resolve(context, uri);
        if (ref == null) {
            fail(g, TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN, "unknown channel " + uri);
            return;
        }
        if (!hub.isTunerPresent()) {
            fail(g, TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN, "tuner not connected");
            return;
        }
        if (!hub.ensurePermission(60_000)) {
            fail(g, TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN, hub.lastMessage());
            return;
        }
        if (stale(g)) return;
        hub.acquire(this);
        boolean locked = hub.ensureTuned(ref.khz, () -> stale(g));
        if (stale(g)) return;
        if (!locked) {
            fail(g, TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL, "no lock on " + ref.khz + " kHz");
            return;
        }
        final ServiceInfo svc = waitForService(ref, g);
        if (svc == null) {
            if (!stale(g)) fail(g, TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN, "service " + ref.sid + " not found");
            return;
        }
        final TsDemux demux = hub.demux();
        main.post(new Runnable() {
            @Override
            public void run() {
                startPlayback(demux, svc, ref, g);
            }
        });
    }

    /** Waits (up to 12 s) until PAT/PMT of the wanted service have been parsed. */
    private ServiceInfo waitForService(TvChannels.ChannelRef ref, int g) {
        return com.pbcnx.opendtvmod.tuner.ServiceWait.preferredTsId(() -> hub.demux(), ref.sid, ref.tsId,
                12_000, () -> stale(g));
    }

    private void fail(final int g, final int reason, String why) {
        Log.w(TAG, "tune failed: " + why);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stale(g)) notifyVideoUnavailable(reason);
            }
        });
    }

    /** Main thread. */
    private void startPlayback(TsDemux demux, ServiceInfo svc, TvChannels.ChannelRef ref, int g) {
        if (stale(g) || !hub.isOwner(this)) return;
        pipe.clear();
        demux.setPacketSink(new ServiceFilter(pipe, demux.getTsId(), svc));

        // The decoder for THIS multiplex: what Auto learned about it, or Manual's switches.
        int videoMode = com.pbcnx.opendtvmod.player.DecoderPrefs.videoMode(context, ref.khz);
        int audioMode = com.pbcnx.opendtvmod.player.DecoderPrefs.audioMode(context);
        if (videoMode != activeVideoMode || audioMode != activeAudioMode) {
            buildPlayer(videoMode, audioMode);
        }
        pendingRememberKhz = 0;
        prepareLive();
        armFirstFrameWatchdog(ref.khz, g);
        startEpg(ref, g);
    }

    /** Points the current player at the live pipe and starts it. Shared by playback and the watchdog. */
    private void prepareLive() {
        // The app's own extractor, not DefaultExtractorsFactory: this session used to skip the AAC
        // ADTS/LATM sniffing fix that the two activities have, so a lying PMT meant no audio here.
        MediaSource ms = com.pbcnx.opendtvmod.player.PlayerFactory.liveSource(pipe, () -> hub.demux());
        player.setMediaSource(ms);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /**
     * Auto's first-frame watchdog, and the reason "Auto" is a real feature instead of a label.
     *
     * <p>Hardware-first is the right default, but a platform decoder that initialises and then
     * presents nothing (ALLTV HD on a Qualcomm decoder: frames in, Render 0) looks exactly like a
     * broken channel, and Media3 will never fall back for it - its fallback only fires on an
     * initialisation failure. So: no frame in {@value #FIRST_FRAME_TIMEOUT_MS} ms, rebuild with the
     * other decoder; if that one presents a picture, {@link #onRenderedFirstFrame} remembers the
     * multiplex. A Manual choice is never second-guessed: the viewer asked for it.</p>
     */
    private void armFirstFrameWatchdog(final int khz, final int g) {
        frameWatchdogFired = false;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (released || gen.get() != g || firstFrame) return;
                if (com.pbcnx.opendtvmod.player.DecoderPrefs.manual(context)) {
                    Log.i(TAG, "no picture after " + FIRST_FRAME_TIMEOUT_MS
                            + " ms, but Manual is on - leaving the decoder alone");
                    return;
                }
                if (frameWatchdogFired) {
                    // Both decoders have had their turn on this multiplex: say so plainly rather than
                    // leave a black screen with no explanation.
                    Log.w(TAG, "no picture with either decoder at " + khz + " kHz");
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                    return;
                }
                frameWatchdogFired = true;
                boolean softwareNow = activeVideoMode == PlayerFactory.MODE_PREFER;
                int next = softwareNow ? PlayerFactory.MODE_PLATFORM_FIRST : PlayerFactory.MODE_PREFER;
                Log.w(TAG, "no picture after " + FIRST_FRAME_TIMEOUT_MS + " ms at " + khz
                        + " kHz - trying the other decoder (" + (softwareNow ? "hardware" : "software") + ")");
                pendingRememberKhz = khz;      // if this works, the next tune on this mux starts here
                buildPlayer(next, activeAudioMode);
                pipe.clear();
                prepareLive();
                main.postDelayed(this, FIRST_FRAME_TIMEOUT_MS);
            }
        }, FIRST_FRAME_TIMEOUT_MS);
    }

    // ------------------------------------------------------------------ player events

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            firstFrame = true;
            retries = 0;
            if (pendingRememberKhz > 0) {
                boolean softwareWon = activeVideoMode == PlayerFactory.MODE_PREFER;
                com.pbcnx.opendtvmod.player.DecoderPrefs.rememberAuto(context, pendingRememberKhz, softwareWon);
                Log.i(TAG, "Auto: the " + (softwareWon ? "software" : "hardware") + " decoder won at "
                        + pendingRememberKhz + " kHz - remembered for this multiplex");
                pendingRememberKhz = 0;
            }
            notifyVideoAvailable();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (!firstFrame) return;
            if (state == Player.STATE_BUFFERING) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
            } else if (state == Player.STATE_READY) {
                notifyVideoAvailable();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "player error: " + error.getErrorCodeName() + " " + error.getMessage());
            final Uri uri = currentUri;
            if (uri != null && retries++ < MAX_RETRIES) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!released) beginTune(uri);   // same multiplex: no retune, just a new filter + prepare
                    }
                }, 1000);
            } else {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            }
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            publishTracks(tracks);
        }
    };

    private static final class TrackRef {
        final Tracks.Group group;
        final int index;
        final String id;

        TrackRef(Tracks.Group group, int index, String id) {
            this.group = group;
            this.index = index;
            this.id = id;
        }
    }

    /** Tells Live Channels which audio/video tracks exist, so its audio-language menu works. */
    private void publishTracks(Tracks tracks) {
        List<TvTrackInfo> infos = new ArrayList<>();
        audioRefs.clear();
        String selAudio = null;
        String selVideo = null;
        int a = 0;
        int v = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            int type = g.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                if (type == C.TRACK_TYPE_AUDIO) {
                    String id = "a" + a++;
                    TvTrackInfo.Builder b = new TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, id);
                    if (f.language != null && !"und".equals(f.language)) b.setLanguage(f.language);
                    if (f.channelCount > 0) b.setAudioChannelCount(f.channelCount);
                    if (f.sampleRate > 0) b.setAudioSampleRate(f.sampleRate);
                    infos.add(b.build());
                    audioRefs.add(new TrackRef(g, i, id));
                    if (g.isTrackSelected(i)) selAudio = id;
                } else {
                    String id = "v" + v++;
                    TvTrackInfo.Builder b = new TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, id);
                    if (f.width > 0 && f.height > 0) {
                        b.setVideoWidth(f.width);
                        b.setVideoHeight(f.height);
                    }
                    if (f.frameRate > 0) b.setVideoFrameRate(f.frameRate);
                    infos.add(b.build());
                    if (g.isTrackSelected(i)) selVideo = id;
                }
            }
        }
        if (infos.isEmpty()) return;
        notifyTracksChanged(infos);
        if (selAudio != null) notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, selAudio);
        if (selVideo != null) notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, selVideo);
    }

    // ------------------------------------------------------------------ EPG

    private void startEpg(final TvChannels.ChannelRef ref, final int g) {
        cancelEpg();
        try {
            epgTask = epgExec.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    if (stale(g) || !hub.isOwner(TunerSession.this)) return;
                    TsDemux d = hub.demux();
                    lastEpgHash = TvChannels.publishPrograms(context, ref.channelId,
                            d.getEvents(ref.sid), lastEpgHash);
                }
            }, 5, 30, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // executor shut down
        }
    }

    private void cancelEpg() {
        if (epgTask != null) {
            epgTask.cancel(false);
            epgTask = null;
        }
    }
}
