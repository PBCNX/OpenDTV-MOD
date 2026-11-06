package com.pbcnx.opendtvmod.ui.watch;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;

/**
 * The main screen as its dialogs and its Settings rows see it: reads of the state they display and
 * the actions they hand back.
 *
 * <p>This was two interfaces - {@code MainDialogs.Host} (23 methods) and {@code SettingsController.Host}
 * (31) - split by consumer rather than by role, with five methods declared twice. Two anonymous
 * implementations in {@code MainActivity} forwards and all. One seam now: the screen the dialogs talk
 * to, sectioned by what the caller is doing.</p>
 */
public interface MainScreen extends ScreenSeam {
    /** The main screen's tab positions, so no call site carries a bare 0..3. */
    int TAB_CHANNELS = 0;
    int TAB_EPG = 1;
    int TAB_FAVORITES = 2;
    int TAB_SETTINGS = 3;


    // ---------------------------------------------------------------- channels and favourites

    int channelCount();

    int favoriteCount();

    /** Builds the channel file and opens the export picker. */
    void exportChannelList();

    /** Opens the channel-file import picker. */
    void importChannelList();


    // ---------------------------------------------------------------- tuning, firmware, alerts

    /** A full scan rebuilds the list, so the screen asks first. */
    void confirmFullScan();


    /** The frequency being watched, 0 when nothing is tuned. */
    int currentKhz();

    void pickFirmwareFile();

    void pickFirmwareFolder();

    /** An imported image was removed: re-bind the Settings list if that is the visible tab. */
    void firmwareImagesChanged();


    int ewbsLocation();

    void saveEwbsLocation(int code);


    /** Resolves the phone's place and reports a PH EWBS city/region code, 0 when unknown. */
    void detectEwbsLocation(MainDialogs.LocationCallback callback);

    // ---------------------------------------------------------------- picture, sound, decoding

    /** One of the com.pbcnx.opendtvmod.tuner.DisplayModes.ASPECT_* values. */
    int aspectMode();

    /** Writes and applies the aspect mode; the caller dismisses the dialog afterwards. */
    void setAspectMode(int mode);


    @NonNull Player audioPlayer();

    /** The group the automatic audio pick chose; null when none was auto-picked. */
    @Nullable TrackGroup autoAudioGroup();

    /** "English (AAC 2ch)" for one audio track of the playing channel. */
    @NonNull String audioTrackLabel(int index, Format format);

    void saveAudioLanguage(String language);

    @NonNull String audioLanguageSummary();


    boolean decoderManual();

    void setDecoderManual(boolean manual);

    boolean decoderVideoSoftware();

    void setDecoderVideoSoftware(boolean software);

    boolean decoderAudioSoftware();

    void setDecoderAudioSoftware(boolean software);

    String decoderVideoSummary(boolean manual, int khz);

    // ---------------------------------------------------------------- captions and recording

    boolean captionsEnabled();

    void setCaptionsEnabled(boolean on);

    /** One of the com.pbcnx.opendtvmod.tuner.DisplayModes.CAPTION_* values. */
    int captionStyle();

    void setCaptionStyle(int style);


    /** One of the com.pbcnx.opendtvmod.tuner.DisplayModes.LANGUAGE_* values. */
    int captionLanguage();

    void setCaptionLanguage(int language);


    boolean recording();

    void toggleRecording();

    // ---------------------------------------------------------------- doors

    void openAnalyzer();

    void openDeveloperOptions();

    void openLicenses();

    String aboutSummary();


    /** Re-binds the Settings list (a decoder or caption toggle changed its own row). */
    void refreshSettings();
}
