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
        counts.setText(screen.channelCount()
                + (screen.channelCount() == 1 ? " Channel · " : " Channels · ")
                + screen.favoriteCount()
                + (screen.favoriteCount() == 1 ? " Favorite" : " Favorites"));
        counts.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        counts.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface));
        body.addView(counts);

        TextView note = FirmwareCards.note(activity, "Export saves the names, numbers and favourites to a file. "
                + "Import adds a file's channels, or replaces the list.");
        note.setPadding(0, Dims.dp(activity, 16), 0, Dims.dp(activity, 4));
        body.addView(note);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Channel list")
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
        buildView.setText(BuildStamp.versionLine(version));
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
                    doorToast(left + (left == 1 ? " more tap" : " more taps")
                            + " to unlock Developer options");
                }
                return;
            }
            doorToast("Developer options unlocked");
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
        final String[] labels = {"Simple text", "Styled", "Auto"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Caption display")
                .setSingleChoiceItems(labels, screen.captionStyle(), (dlg, which) -> {
                    screen.setCaptionStyle(which);
                    dlg.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showCaptionLanguageDialog() {
        // Indexed by DisplayModes.LANGUAGE_MAIN - 1 and LANGUAGE_SECOND - 1.
        final String[] labels = {"Language 1", "Language 2"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Caption language")
                .setSingleChoiceItems(labels, screen.captionLanguage() - DisplayModes.LANGUAGE_MAIN,
                        (dlg, which) -> {
                    screen.setCaptionLanguage(which + DisplayModes.LANGUAGE_MAIN);
                    dlg.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Aspect ratio as a pick-one list, matching the audio language row: the owner's five modes in
     * the order DisplayModes stores them (Automatic, Stretch, Force 4:3, Force 16:9, Zoom).
     */
    public void showAspectRatioDialog() {
        final int current = screen.aspectMode();
        final String[] labels = {
                DisplayModes.aspectName(DisplayModes.ASPECT_AUTO),
                DisplayModes.aspectName(DisplayModes.ASPECT_STRETCH),
                DisplayModes.aspectName(DisplayModes.ASPECT_FORCE_4_3),
                DisplayModes.aspectName(DisplayModes.ASPECT_FORCE_16_9),
                DisplayModes.aspectName(DisplayModes.ASPECT_ZOOM)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Video aspect ratio")
                .setSingleChoiceItems(labels, current, (dlg, which) -> {
                    screen.setAspectMode(which);
                    dlg.dismiss();
                    screen.refreshSettings();
                })
                .setNegativeButton("Cancel", null)
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
            Toast.makeText(activity, "Tune a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[groups.size() + 1];
        labels[0] = "Automatic (best quality)";
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group g = groups.get(i);
            labels[i + 1] = screen.audioTrackLabel(i, g.getTrackFormat(0)) + (g.isTrackSupported(0) ? "" : "  [not supported]");
            if (g.isTrackSelected(0) && g.getMediaTrackGroup() != screen.autoAudioGroup()
                    && player.getTrackSelectionParameters().overrides.containsKey(g.getMediaTrackGroup())) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Audio language")
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
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Settings > EWBS Location: choose the Philippine area used to filter emergency warnings. */
    public void showEwbsLocationDialog() {
        new EwbsLocationDialog(activity, screen).show();
    }
}
