package com.pbcnx.opendtvmod.ui.watch;


import com.pbcnx.opendtvmod.tuner.DisplayModes;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.R;

/**
 * The Settings tab's rows: consumer sections first, technical tools last.
 *
 * <p>Current values come from the screen through {@link MainScreen}; dialog rows call the dialog
 * owner and the scan controller directly, so the activity is not a switchboard between them.
 * The captions and aspect labels moved with the rows they belong to.</p>
 */
public final class SettingsController {

    /** The state and actions the Settings rows borrow from the activity. */
    private SettingsController() {
    }

    public static List<SettingAdapter.SettingItem> build(Activity activity, MainScreen screen,
            MainDialogs dialogs, ScanController scan) {
        List<SettingAdapter.SettingItem> items = new ArrayList<>();

        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_channels)));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_auto_scan),
                activity.getString(R.string.settings_auto_scan_summary),
                R.drawable.ic_scan,
                screen::confirmFullScan));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_manual_scan),
                activity.getString(R.string.settings_manual_scan_summary),
                R.drawable.ic_tune,
                scan::showManualSheet));
        int location = screen.ewbsLocation();
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_ewbs_alerts),
                location == 0 ? activity.getString(R.string.settings_ewbs_summary_all)
                        : activity.getString(R.string.settings_tap_to_change,
                                com.pbcnx.opendtvmod.ts.EwbsAreaTable.name(location)),
                R.drawable.ic_ewbs_alert,
                dialogs::showEwbsLocationDialog));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_channel_list),
                activity.getResources().getQuantityString(R.plurals.settings_channel_list_summary,
                        screen.channelCount(), screen.channelCount()),
                R.drawable.ic_list,
                dialogs::showChannelFileSheet));

        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_picture)));
        int aspect = screen.aspectMode();
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_aspect),
                activity.getString(R.string.settings_tap_to_change, aspectModeName(activity, aspect)),
                R.drawable.ic_aspect,
                dialogs::showAspectRatioDialog));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_audio),
                activity.getString(R.string.settings_tap_to_change, screen.audioLanguageSummary()),
                R.drawable.ic_language,
                dialogs::showAudioLanguageDialog));
        // DECODER: one gate, two overrides. The owner's shape, and the reason for it is that the
        // two halves fail on different streams: TV5's video needs the device's decoder, ALLTV on
        // the phone needs FFmpeg video while its AAC sound stays on the platform decoder.
        // Gate OFF is Auto: the device's decoder, and the other one when a channel shows no
        // picture (the first-frame watchdog), with the winning choice remembered per multiplex.
        // Gate ON is Manual: the two switches decide, and nothing corrects them. ON = SOFTWARE on
        // both rows, so the label names the decoder and the switch names the exception.
        // The descriptions are providers, not strings: the list is built once, so a fixed sentence
        // would still say "Off · ..." after the viewer turned the gate on. liveSwitchItem re-reads
        // them on every bind, the same way the gate's EnabledState is re-read (the UI session found
        // this on glass; the fix belongs here because the text lives here).
        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_decoder)));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                activity.getString(R.string.settings_decoder_manual),
                () -> screen.decoderManual()
                        ? activity.getString(R.string.settings_decoder_manual_on)
                        : activity.getString(R.string.settings_decoder_manual_off),
                R.drawable.ic_toggle,
                screen::decoderManual,
                screen::setDecoderManual));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                activity.getString(R.string.settings_video_decoder),
                () -> screen.decoderVideoSummary(screen.decoderManual(), screen.currentKhz()),
                R.drawable.ic_video,
                screen::decoderVideoSoftware,
                screen::setDecoderVideoSoftware,
                screen::decoderManual));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                activity.getString(R.string.settings_audio_decoder),
                () -> screen.decoderManual()
                        ? (screen.decoderAudioSoftware()
                                ? activity.getString(R.string.decoder_software)
                                : activity.getString(R.string.decoder_device))
                        : activity.getString(R.string.decoder_auto_audio),
                R.drawable.ic_audio,
                screen::decoderAudioSoftware,
                screen::setDecoderAudioSoftware,
                screen::decoderManual));

        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_captions)));
        items.add(SettingAdapter.SettingItem.switchItem(
                activity.getString(R.string.settings_cc),
                activity.getString(R.string.settings_cc_summary),
                R.drawable.ic_captions,
                screen::captionsEnabled,
                screen::setCaptionsEnabled));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_caption_display),
                activity.getString(R.string.settings_tap_to_change, captionStyleName(activity, screen.captionStyle())),
                R.drawable.ic_captions,
                dialogs::showCaptionDisplayDialog));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_caption_language),
                activity.getString(R.string.settings_tap_to_change,
                        captionLanguageName(activity, screen.captionLanguage())),
                R.drawable.ic_captions,
                dialogs::showCaptionLanguageDialog));

        // App language: the same per-app language Android 13 keeps in its own Settings, and the only
        // way to choose one on Android 5 to 12. The row's title says "language" in both languages,
        // so it can be found from either.
        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_language)));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_language),
                activity.getString(R.string.settings_tap_to_change, AppLanguage.currentLabel(activity)),
                R.drawable.ic_translate,
                dialogs::showAppLanguageDialog));

        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_advanced)));
        // A stick is a bridge with no firmware of its own: the driver uploads an image every time
        // it is plugged in. Those images are vendor material, so a public build carries none and
        // the viewer supplies them here (hashed against what the driver was written for).
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_tuner_setup),
                activity.getString(R.string.settings_tuner_setup_summary),
                R.drawable.ic_memory,
                dialogs::showFirmwareSheet));
        boolean isRecording = screen.recording();
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(isRecording ? R.string.settings_record_stop : R.string.settings_record),
                activity.getString(isRecording ? R.string.settings_record_stop_summary : R.string.settings_record_summary),
                R.drawable.ic_record,
                screen::toggleRecording));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_analyzer),
                activity.getString(R.string.settings_analyzer_summary),
                R.drawable.ic_analytics,
                screen::openAnalyzer));

        items.add(SettingAdapter.SettingItem.header(activity.getString(R.string.settings_header_about)));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.about_title, activity.getString(R.string.app_name)),
                screen.aboutSummary(),
                R.drawable.ic_info,
                dialogs::showAboutDialog));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.settings_licenses),
                activity.getString(R.string.settings_licenses_summary),
                R.drawable.ic_license,
                screen::openLicenses));
        // No Developers row here, by the owner's decision: the About art is the only door,
        // always. See MainDialogs.showAboutDialog.
        return items;
    }

    /**
     * The aspect mode's name in the app language, for the Picture row, the aspect dialog and the
     * mode toast. DisplayModes.aspectName keeps the English names that its JVM tests check.
     */
    public static String aspectModeName(Activity activity, int mode) {
        switch (DisplayModes.aspectMode(mode)) {
            case DisplayModes.ASPECT_STRETCH: return activity.getString(R.string.aspect_stretch);
            case DisplayModes.ASPECT_FORCE_4_3: return activity.getString(R.string.aspect_force_4_3);
            case DisplayModes.ASPECT_FORCE_16_9: return activity.getString(R.string.aspect_force_16_9);
            case DisplayModes.ASPECT_ZOOM: return activity.getString(R.string.aspect_zoom);
            default: return activity.getString(R.string.aspect_auto);
        }
    }

    private static String captionStyleName(Activity activity, int style) {
        return activity.getString(style == DisplayModes.CAPTION_STYLED ? R.string.caption_style_name_styled
                : style == DisplayModes.CAPTION_AUTO ? R.string.caption_style_name_auto : R.string.caption_style_name_simple);
    }

    private static String captionLanguageName(Activity activity, int language) {
        return activity.getString(language == DisplayModes.LANGUAGE_SECOND
                ? R.string.caption_language_name_2 : R.string.caption_language_name_1);
    }
}
