package ph.opendtv.player;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * One player setup for both the normal TV activity and the Analyzer.
 *
 * <p>The two screens used to build ExoPlayer independently, which is why an
 * extractor/decoder fix could land in one and be missed in the other.
 */
public final class PlayerFactory {
    public static final String PREFS = "opendtv_prefs";
    public static final String PREF_HW_DECODER = "hardware_decoder";

    private PlayerFactory() {
    }

    public static boolean hardwareFirst(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_HW_DECODER, false);
    }

    public static DefaultRenderersFactory renderers(Context context) {
        boolean hwFirst = hardwareFirst(context);
        return new NextRenderersFactory(context)
                .setExtensionRendererMode(hwFirst
                        ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true);
    }

    /**
     * @param audioFocus true for the normal TV player, false for the Analyzer
     */
    public static ExoPlayer build(Context context, DefaultLoadControl loadControl, boolean audioFocus) {
        ExoPlayer.Builder builder = new ExoPlayer.Builder(context, renderers(context))
                .setLoadControl(loadControl);
        if (audioFocus) {
            builder.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(), /* handleAudioFocus= */ true)
                    .setHandleAudioBecomingNoisy(true);
        }
        return builder.build();
    }
}
