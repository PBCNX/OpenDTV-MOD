package com.pbcnx.opendtvmod.ui.watch;

import com.pbcnx.opendtvmod.ui.Dims;

import com.pbcnx.opendtvmod.tuner.DisplayModes;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import com.pbcnx.opendtvmod.BuildStamp;
import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.dev.DeveloperActions;

/**
 * The Settings-driven dialogs: the channel-file sheet, the tuner-setup sheet, the About box and the
 * one-choice pickers (captions, aspect, audio language, emergency-alert area).
 *
 * <p>Each dialog takes the activity's state through the narrow {@link MainScreen} the activity supplies and
 * hands every change back through it, so none of them keeps state of its own except the About door's
 * toast. Moved out of MainActivity without changing a string or a behaviour.</p>
 */
public final class MainDialogs {

    /** The EWBS picker's automatic-location result: a city/region code, or 0 when nothing matched. */
    public interface LocationCallback {
        void onLocation(int code);
    }

    /** The state and actions the dialogs borrow from the activity. */
    private final Activity activity;
    private final MainScreen screen;
    private final FirmwareCards firmwareCards;
    /** The last countdown toast of the About door; cancelled before the next one so fast taps do not queue. */
    private Toast doorToast;

    public MainDialogs(Activity activity, MainScreen screen) {
        this.activity = activity;
        this.screen = screen;
        this.firmwareCards = new FirmwareCards(activity, screen);
    }

    /** Tuner setup, rendered by FirmwareCards. */
    public void showFirmwareSheet() {
        firmwareCards.showSheet();
    }

    public void showChannelFileSheet() {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_channel_list, null);
        LinearLayout body = content.findViewById(R.id.channel_content);

        TextView counts = new TextView(activity);
        counts.setText(activity.getString(R.string.channel_list_counts,
                activity.getResources().getQuantityString(R.plurals.channel_list_channels,
                        screen.channelCount(), screen.channelCount()),
                activity.getResources().getQuantityString(R.plurals.channel_list_favorites,
                        screen.favoriteCount(), screen.favoriteCount())));
        counts.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        counts.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface));
        body.addView(counts);

        TextView note = FirmwareCards.note(activity, activity.getString(R.string.channel_list_note));
        note.setPadding(0, Dims.dp(activity, 16), 0, Dims.dp(activity, 4));
        body.addView(note);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_channel_list)
                .setView(content)
                .create();
        content.findViewById(R.id.channel_export).setOnClickListener(v -> {
            dialog.dismiss();
            screen.exportChannelList();
        });
        content.findViewById(R.id.channel_import).setOnClickListener(v -> {
            dialog.dismiss();
            screen.importChannelList();
        });
        content.findViewById(R.id.channel_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * Tuner setup: the supported sticks, what is plugged right now, and the state of each vendor
     * image. A stick starts blank; the driver uploads an image into it, and those images are vendor
     * material the build may or may not carry.
     */

    public void showAboutDialog() {
        String version = "?";
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // version stays "?" when the package info is unavailable
        }

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_about, null);
        TextView versionView = content.findViewById(R.id.about_version);
        TextView buildView = content.findViewById(R.id.about_build);
        TextView stampView = content.findViewById(R.id.about_stamp);
        versionView.setText(activity.getString(R.string.app_name));
        // One builder for this line: the Settings row under ABOUT prints the same string.
        buildView.setText(BuildStamp.versionLine(activity, version));
        String date = BuildStamp.date();
        String time = BuildStamp.time();
        if (date.isEmpty() || time.isEmpty()) {
            stampView.setVisibility(View.GONE);
        } else {
            stampView.setText(date + "  .  " + time);
        }

        // MaterialAlertDialog, not the platform one: the box is meant to be the same rounded M3
        // panel as the build-info and confirm dialogs, and the layout now paints nothing so this
        // window's shape shows.
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .create();
        // The door the owner defined: the art in the About box, thirteen taps - the same shape as
        // Android's build number - with a countdown so a press that lands by accident says so.
        // The count and the unlock both live in memory, so quitting the app hides the row again
        // and starts the countdown over.
        View door = content.findViewById(R.id.about_door);
        door.setOnClickListener(v -> {
            if (com.pbcnx.opendtvmod.App.developer(activity).isUnlocked()) {
                dialog.dismiss();
                screen.openDeveloperOptions();
                return;
            }
            int left = com.pbcnx.opendtvmod.App.developer(activity).tapDoor();
            if (left > 0) {
                // The first few taps are silent, like the build number: an accidental tap on a
                // picture must not advertise a developer door.
                if (com.pbcnx.opendtvmod.App.developer(activity).doorTaps() > DeveloperActions.DOOR_SILENT_TAPS) {
                    doorToast(activity.getResources().getQuantityString(R.plurals.dev_door_taps_left, left, left));
                }
                return;
            }
            doorToast(activity.getString(R.string.dev_door_unlocked));
            screen.refreshSettings();
            dialog.dismiss();
            screen.openDeveloperOptions();
        });
        View ok = content.findViewById(R.id.about_ok);
        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        // OK is the safe landing spot; the image is a deliberate press up from it.
        if (!activity.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
            ok.requestFocus();
        }
    }

    /** One toast at a time for the door. Thirteen taps in a row would otherwise queue thirteen toasts. */
    private void doorToast(String text) {
        if (doorToast != null) doorToast.cancel();
        doorToast = Toast.makeText(activity, text, Toast.LENGTH_SHORT);
        doorToast.show();
    }

    public void showCaptionDisplayDialog() {
        // Indexed by DisplayModes.CAPTION_* (simple, styled, auto).
        final String[] labels = {activity.getString(R.string.caption_style_name_simple),
                activity.getString(R.string.caption_style_name_styled), activity.getString(R.string.caption_style_name_auto)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.caption_display_title)
                .setSingleChoiceItems(labels, screen.captionStyle(), (dlg, which) -> {
                    screen.setCaptionStyle(which);
                    dlg.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void showCaptionLanguageDialog() {
        // Indexed by DisplayModes.LANGUAGE_MAIN - 1 and LANGUAGE_SECOND - 1.
        final String[] labels = {activity.getString(R.string.caption_language_name_1),
                activity.getString(R.string.caption_language_name_2)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.caption_language_title)
                .setSingleChoiceItems(labels, screen.captionLanguage() - DisplayModes.LANGUAGE_MAIN,
                        (dlg, which) -> {
                    screen.setCaptionLanguage(which + DisplayModes.LANGUAGE_MAIN);
                    dlg.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Aspect ratio as a pick-one list, matching the audio language row.
     *
     * <p>0 = Stretch (fill 16:9), 1 = Fit (keeps ratio, black bars), 2 = Zoom (keeps ratio, crops).
     */
    public void showAspectRatioDialog() {
        final int current = screen.aspectMode();
        // Indexed by DisplayModes.ASPECT_* (stretch, fit, zoom).
        final String[] labels = {
                activity.getString(R.string.aspect_stretch), activity.getString(R.string.aspect_fit),
                activity.getString(R.string.aspect_zoom)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.aspect_title)
                .setSingleChoiceItems(labels, current, (dlg, which) -> {
                    screen.setAspectMode(which);
                    dlg.dismiss();
                    screen.refreshSettings();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Settings > App language: follow the phone, English or Tagalog. The same setting as Android 13's
     * Settings > Apps > OpenDTV-MOD > Language; choosing recreates the screen (AppLanguage).
     */
    public void showAppLanguageDialog() {
        final int current = AppLanguage.current();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(AppLanguage.labels(activity), current, (dlg, which) -> {
                    dlg.dismiss();
                    if (which != current) AppLanguage.choose(which);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Settings > Audio Language: lists the audio streams of the playing channel and switches between them. */
    public void showAudioLanguageDialog() {
        final Player player = screen.audioPlayer();
        final List<Tracks.Group> groups = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.length > 0) groups.add(g);
        }
        if (groups.isEmpty()) {
            Toast.makeText(activity, R.string.toast_tune_first, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[groups.size() + 1];
        labels[0] = activity.getString(R.string.audio_automatic);
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group g = groups.get(i);
            String track = screen.audioTrackLabel(i, g.getTrackFormat(0));
            labels[i + 1] = g.isTrackSupported(0) ? track : activity.getString(R.string.audio_track_not_supported, track);
            if (g.isTrackSelected(0) && g.getMediaTrackGroup() != screen.autoAudioGroup()
                    && player.getTrackSelectionParameters().overrides.containsKey(g.getMediaTrackGroup())) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.audio_language_title)
                .setSingleChoiceItems(labels, checked, (dlg, which) -> {
                    TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                    if (which == 0) {
                        screen.saveAudioLanguage("");
                        b.setPreferredAudioLanguage(null).clearOverridesOfType(C.TRACK_TYPE_AUDIO);
                    } else {
                        Tracks.Group g = groups.get(which - 1);
                        String lang = g.getTrackFormat(0).language;
                        boolean known = lang != null && !lang.isEmpty() && !"und".equals(lang);
                        // remember the language (so it is picked again on other channels), the override switches now
                        screen.saveAudioLanguage(known ? lang : "");
                        b.setPreferredAudioLanguage(known ? lang : null)
                                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0));
                    }
                    player.setTrackSelectionParameters(b.build());
                    dlg.dismiss();
                    screen.refreshSettings();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Settings > EWBS Location: choose the Philippine area used to filter emergency warnings. */
    public void showEwbsLocationDialog() {
        new EwbsLocationDialog(activity, screen).show();
    }
}
