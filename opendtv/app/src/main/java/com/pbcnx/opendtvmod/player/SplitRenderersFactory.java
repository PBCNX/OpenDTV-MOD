package com.pbcnx.opendtvmod.player;

import android.content.Context;
import android.os.Handler;

import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * A renderer factory whose video and audio halves can prefer different decoders.
 *
 * <p>One switch for both was wrong for exactly the two streams measured on the bench (30 Oct):
 *
 * <pre>
 * TV5    hardware video is fine and cheap; the platform MPEG-L2 audio decoder rejects every frame,
 *        so audio must be FFmpeg                                  -> HW video + SW audio
 * ALLTV  the phone's Qualcomm video decoder takes the stream and renders nothing, while the phone's
 *        platform AAC decoder is the only one that plays its audio -> SW video + HW audio
 * </pre>
 *
 * <p>{@link NextRenderersFactory} already builds both halves from one extension mode; this subclass
 * simply hands each half its own, so {@link DefaultRenderersFactory#EXTENSION_RENDERER_MODE_ON}
 * ("platform first, FFmpeg as the fallback") and {@code PREFER} ("FFmpeg first") can differ per
 * media type. The renderer list order is the preference, which is all Media3 needs.
 */
final class SplitRenderersFactory extends NextRenderersFactory {

    private final int videoMode;
    private final int audioMode;

    SplitRenderersFactory(Context context, int videoMode, int audioMode) {
        super(context);
        this.videoMode = videoMode;
        this.audioMode = audioMode;
    }

    @Override
    protected void buildVideoRenderers(Context context, @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
                                       Handler eventHandler, VideoRendererEventListener eventListener,
                                       long allowedJoiningTimeMs, ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, videoMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedJoiningTimeMs, out);
    }

    @Override
    protected void buildAudioRenderers(Context context, @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
                                       AudioSink audioSink, Handler eventHandler,
                                       AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, audioMode, mediaCodecSelector, enableDecoderFallback,
                audioSink, eventHandler, eventListener, out);
    }
}
