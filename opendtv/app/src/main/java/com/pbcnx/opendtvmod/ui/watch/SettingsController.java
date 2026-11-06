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

        items.add(SettingAdapter.SettingItem.header("CHANNELS & ALERTS"));
        items.add(SettingAdapter.SettingItem.action(
                "Auto Channel Scan",
                "Scans the UHF band - merge it into the list or replace it",
                R.drawable.ic_scan,
                screen::confirmFullScan));
        items.add(SettingAdapter.SettingItem.action(
                "Manual Frequency Scan",
                "Tune one frequency and watch the signal meter",
                R.drawable.ic_tune,
                scan::showManualSheet));
        int location = screen.ewbsLocation();
        items.add(SettingAdapter.SettingItem.action(
                "Emergency Alerts",
                location == 0 ? "All alerts shown · tap to choose your area"
                        : com.pbcnx.opendtvmod.ts.EwbsAreaTable.name(location) + " · tap to change",
                R.drawable.ic_ewbs_alert,
                dialogs::showEwbsLocationDialog));
        items.add(SettingAdapter.SettingItem.action(
                "Channel list",
                screen.channelCount() + " channels · back up or restore the list",
                R.drawable.ic_list,
                dialogs::showChannelFileSheet));

        items.add(SettingAdapter.SettingItem.header("PICTURE & SOUND"));
        int aspect = screen.aspectMode();
        items.add(SettingAdapter.SettingItem.action(
                "Video Aspect Ratio",
                aspectModeName(aspect) + " · tap to change",
                R.drawable.ic_aspect,
                dialogs::showAspectRatioDialog));
        items.add(SettingAdapter.SettingItem.action(
                "Audio Language",
                screen.audioLanguageSummary() + " · tap to change",
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
        items.add(SettingAdapter.SettingItem.header("DECODER"));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                "Manual Settings",
                () -> screen.decoderManual()
                        ? "On · the switches below decide"
                        : "Off · the app chooses the decoder",
                R.drawable.ic_toggle,
                screen::decoderManual,
                screen::setDecoderManual));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                "Video Decoder",
                () -> screen.decoderVideoSummary(screen.decoderManual(), screen.currentKhz()),
                R.drawable.ic_video,
                screen::decoderVideoSoftware,
                screen::setDecoderVideoSoftware,
                screen::decoderManual));
        items.add(SettingAdapter.SettingItem.liveSwitchItem(
                "Audio Decoder",
                () -> screen.decoderManual()
                        ? (screen.decoderAudioSoftware()
                                ? "Software (FFmpeg)"
                                : "The device's decoder")
                        : "Auto · the device's decoder, FFmpeg for MPEG-1",
                R.drawable.ic_audio,
                screen::decoderAudioSoftware,
                screen::setDecoderAudioSoftware,
                screen::decoderManual));

        items.add(SettingAdapter.SettingItem.header("CAPTIONS"));
        items.add(SettingAdapter.SettingItem.switchItem(
                "Closed Captions (CC)",
                "Show captions when a channel has them",
                R.drawable.ic_captions,
                screen::captionsEnabled,
                screen::setCaptionsEnabled));
        items.add(SettingAdapter.SettingItem.action(
                "Caption Display",
                captionStyleName(screen.captionStyle()) + " · tap to change",
                R.drawable.ic_captions,
                dialogs::showCaptionDisplayDialog));
        items.add(SettingAdapter.SettingItem.action(
                "Caption Language",
                captionLanguageName(screen.captionLanguage()) + " · tap to change",
                R.drawable.ic_captions,
                dialogs::showCaptionLanguageDialog));

        items.add(SettingAdapter.SettingItem.header("ADVANCED"));
        // A stick is a bridge with no firmware of its own: the driver uploads an image every time
        // it is plugged in. Those images are vendor material, so a public build carries none and
        // the viewer supplies them here (hashed against what the driver was written for).
        items.add(SettingAdapter.SettingItem.action(
                "Tuner setup",
                "Vendor images for the supported USB tuners",
                R.drawable.ic_memory,
                dialogs::showFirmwareSheet));
        boolean isRecording = screen.recording();
        items.add(SettingAdapter.SettingItem.action(
                isRecording ? "Stop Channel Recording" : "Record Channel",
                isRecording ? "Tap to stop recording" : "Save this channel to OpenDTV/Record",
                R.drawable.ic_record,
                screen::toggleRecording));
        items.add(SettingAdapter.SettingItem.action(
                "Signal & TS Analyzer",
                "Bitrates, PIDs, tables and raw packets",
                R.drawable.ic_analytics,
                screen::openAnalyzer));

        items.add(SettingAdapter.SettingItem.header("ABOUT"));
        items.add(SettingAdapter.SettingItem.action(
                activity.getString(R.string.about_title, activity.getString(R.string.app_name)),
                screen.aboutSummary(),
                R.drawable.ic_info,
                dialogs::showAboutDialog));
        items.add(SettingAdapter.SettingItem.action(
                "Open source licenses",
                "What this app is built on",
                R.drawable.ic_license,
                screen::openLicenses));
        // No Developers row here, by the owner's decision: the About art is the only door,
        // always. See MainDialogs.showAboutDialog.
        return items;
    }

    private static String aspectModeName(int mode) {
        switch (DisplayModes.aspectMode(mode)) {
            case DisplayModes.ASPECT_FIT: return "Fit (original ratio)";
            case DisplayModes.ASPECT_ZOOM: return "Zoom (crop to 16:9)";
            default: return "Stretch (full 16:9)";
        }
    }

    private static String captionStyleName(int style) {
        return style == DisplayModes.CAPTION_STYLED ? "Styled"
                : style == DisplayModes.CAPTION_AUTO ? "Auto" : "Simple text";
    }

    private static String captionLanguageName(int language) {
        return language == DisplayModes.LANGUAGE_SECOND ? "Language 2" : "Language 1";
    }
}
