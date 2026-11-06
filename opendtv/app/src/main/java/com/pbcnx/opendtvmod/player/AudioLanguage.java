package com.pbcnx.opendtvmod.player;

import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;

/** The saved audio-language preference: summary text for Settings and the player override. */
public final class AudioLanguage {

    public static final String PREF = "audio_language";

    private AudioLanguage() {
    }

    /** "" = automatic, otherwise the ISO 639 code the viewer picked. */
    public static String code(SharedPreferences prefs) {
        return prefs.getString(PREF, "");
    }

    /** Applies the saved language to the player; it picks the matching track when a channel starts. */
    public static void apply(ExoPlayer player, SharedPreferences prefs) {
        String code = code(prefs);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(code.isEmpty() ? null : code)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build());
    }
}
