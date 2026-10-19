package ph.opendtv.tv;

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
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ph.opendtv.player.TsPipeDataSource;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.ts.ServiceFilter;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.TsDemux;
import ph.opendtv.ts.TsPipe;

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

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService epgExec = Executors.newSingleThreadScheduledExecutor();
    private final TunerHub hub;
    private final TsPipe pipe = new TsPipe(8 * 1024 * 1024);
    private final ExoPlayer player;
    private final AtomicInteger gen = new AtomicInteger();

    // main thread only
    private boolean released;
    private boolean firstFrame;
    private Uri currentUri;
    private int retries;
    private ScheduledFuture<?> epgTask;
    private final List<TrackRef> audioRefs = new ArrayList<>();

    private volatile long lastEpgHash;

    TunerSession(Context context, String inputId) {
        super(context);
        this.context = context;
        this.hub = TunerHub.get(context);

        // Live TV: start after ~1.5 s of data, keep at most 15 s, refill 2.5 s after an underrun.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 15000, 1500, 2500)
                .build();
        boolean hwFirst = context.getSharedPreferences("opendtv_prefs", Context.MODE_PRIVATE).getBoolean("hardware_decoder", false);
        player = new ExoPlayer.Builder(context, new io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context)
                .setExtensionRendererMode(hwFirst ? androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        : androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
                .setLoadControl(loadControl).build();
        // A TV input must not grab audio focus; the TV app owns it.
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), /* handleAudioFocus= */ false);
        player.addListener(playerListener);
    }

    // ------------------------------------------------------------------ TvInputService.Session

    @Override
    public boolean onSetSurface(Surface surface) {
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
        } catch (RuntimeException e) {
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
        long start = System.currentTimeMillis();
        while (!stale(g) && System.currentTimeMillis() - start < 12_000) {
            TsDemux d = hub.demux();
            for (ServiceInfo s : d.getServices()) {
                if (s.serviceId != ref.sid || !s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                // Prefer the TS id stored at scan time; accept any after 4 s (broadcaster may have changed it).
                if (ref.tsId <= 0 || d.getTsId() == ref.tsId || System.currentTimeMillis() - start > 4000) return s;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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

        DataSource.Factory factory = new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return new TsPipeDataSource(pipe);
            }
        };
        MediaSource ms = new ProgressiveMediaSource.Factory(factory, new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS))
                .createMediaSource(MediaItem.fromUri("tspipe://live"));
        player.setMediaSource(ms);
        player.prepare();
        player.setPlayWhenReady(true);
        startEpg(ref, g);
    }

    // ------------------------------------------------------------------ player events

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            firstFrame = true;
            retries = 0;
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
        String selAudio = null, selVideo = null;
        int a = 0, v = 0;
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
                    List<EpgEvent> events = d.getEvents(ref.sid);
                    if (events.isEmpty()) return;
                    long off = TvChannels.clockOffsetMs(d);
                    long h = TvChannels.hash(events, off);
                    if (h == lastEpgHash) return;
                    TvChannels.syncPrograms(context, ref.channelId, events, off);
                    lastEpgHash = h;
                }
            }, 5, 30, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
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
