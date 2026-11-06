package com.pbcnx.opendtvmod.player;


import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.Collections;
import java.util.List;

/**
 * One player setup for every player in the app: the TV activity, the Analyzer and the TV input
 * session.
 *
 * <p>They used to build ExoPlayer independently, which is why an extractor/decoder fix could land in
 * one and be missed in the others. The DECODER CHOICE is not decided here either: it comes from
 * {@link DecoderPrefs}, the one place the Auto/Manual gate and the per-mux memory live.</p>
 */
public final class PlayerFactory {
    /** Media3 extension-renderer modes, named for what the viewer chooses. */
    public static final int MODE_PLATFORM_FIRST = androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
    public static final int MODE_PREFER = androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;

    /**
     * Which platform decoders we are willing to use.
     *
     * <p>The LEAP-S1 (Amlogic) declares {@code c2.amlogic.audio.decoder.mp2} for
     * {@code audio/mpeg-L2}, and that decoder rejects every MPEG-1 Layer II frame TV5 sends it:
     * {@code AmFFmpegDecoder: Cannot decode this frame! got_picture/0}, hundreds of times. With the
     * hardware preference on, MediaCodec gets first refusal on that track, so the audio renderer
     * never becomes ready - and ExoPlayer only leaves STATE_BUFFERING when every renderer is ready,
     * which is why the picture (decoded all along) never appeared. TV5 carries MPEG-L2 beside AAC;
     * IBC is AAC-only, which is why only TV5 froze.
     *
     * <p>So MPEG-1 audio never goes to the platform: the FFmpeg extension handles it, which it does
     * correctly, and the video stays on the hardware decoder where it belongs.
     */
    static final MediaCodecSelector PLATFORM_SAFE = new MediaCodecSelector() {
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                     boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            if (MimeTypes.AUDIO_MPEG_L1.equals(mimeType) || MimeTypes.AUDIO_MPEG_L2.equals(mimeType)) {
                return Collections.emptyList();
            }
            return MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder,
                    requiresTunnelingDecoder);
        }
    };

    private PlayerFactory() {
    }

    /**
     * The one TS-to-MediaSource chain for every player: the app's extractor (AAC ADTS/LATM sniffing,
     * see {@link com.pbcnx.opendtvmod.ts.extractor.TsExtractorFactory}) with the demux the payload factory asks for the
     * transport hint. The Live Channels session built its own with DefaultExtractorsFactory, so it
     * silently missed the fix that made ALLTV audible in the two activities.
     */
    public static androidx.media3.exoplayer.source.MediaSource liveSource(
            androidx.media3.datasource.DataSource.Factory pipeFactory,
            java.util.function.Supplier<com.pbcnx.opendtvmod.ts.TsDemux> demux) {
        return new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                pipeFactory, new com.pbcnx.opendtvmod.ts.extractor.TsExtractorFactory(demux))
                .createMediaSource(androidx.media3.common.MediaItem.fromUri("tspipe://live"));
    }

    /** The same chain for a {@link com.pbcnx.opendtvmod.ts.pipe.TsPipe}, which is what all three players own. */
    public static androidx.media3.exoplayer.source.MediaSource liveSource(
            com.pbcnx.opendtvmod.ts.pipe.TsPipe pipe, java.util.function.Supplier<com.pbcnx.opendtvmod.ts.TsDemux> demux) {
        return liveSource(() -> new com.pbcnx.opendtvmod.player.TsPipeDataSource(pipe), demux);
    }

    /**
     * Video and audio get their own preference, because the two halves fail on different streams for
     * different reasons - see {@link SplitRenderersFactory} for the two measured cases. MPEG-1 audio
     * never reaches the platform decoder whatever the switches say: that decoder is broken and no
     * preference should be able to select it.
     *
     * @param videoMode one of the two {@code MODE_*} extension modes, for the caller that has already
     *                  resolved Auto against its own frequency ({@code TunerSession})
     */
    public static DefaultRenderersFactory renderersFor(Context context, int videoMode, int audioMode) {
        SplitRenderersFactory factory = new SplitRenderersFactory(context, videoMode, audioMode);
        factory.setEnableDecoderFallback(true);
        factory.setMediaCodecSelector(PLATFORM_SAFE);
        return factory;
    }

    /**
     * @param audioFocus true for the normal TV player, false for the Analyzer
     */
    public static ExoPlayer build(Context context, DefaultLoadControl loadControl, boolean audioFocus) {
        return build(context, loadControl, audioFocus, /* forceSoftwareVideo= */ false);
    }

    public static ExoPlayer build(Context context, DefaultLoadControl loadControl, boolean audioFocus,
                                  boolean forceSoftwareVideo) {
        return build(context, loadControl, audioFocus,
                DecoderPrefs.videoModeForced(context, forceSoftwareVideo),
                DecoderPrefs.audioMode(context));
    }

    /**
     * Builds the player for explicit renderer modes, which is what the TV session already resolved
     * per multiplex. Every player goes through here now - the session used to hand-build its own
     * ExoPlayer, which is how it could drift from the other two.
     *
     * @param audioFocus true for the normal TV player, false for the Analyzer and the TV input
     */
    public static ExoPlayer build(Context context, DefaultLoadControl loadControl, boolean audioFocus,
                                  int videoMode, int audioMode) {
        // DecoderPrefs.migrate runs in App.onCreate, before any caller reads a mode; a getter must
        // not write preferences, so this method only reads them.
        ExoPlayer.Builder builder =
                new ExoPlayer.Builder(context, renderersFor(context, videoMode, audioMode))
                        .setLoadControl(loadControl)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(), audioFocus);
        if (audioFocus) {
            builder.setHandleAudioBecomingNoisy(true);
        }
        return builder.build();
    }
}
