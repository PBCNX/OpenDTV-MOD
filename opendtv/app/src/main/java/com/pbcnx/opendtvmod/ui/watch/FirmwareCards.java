package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.firmware.FirmwareStore;
import com.pbcnx.opendtvmod.ui.Dims;

/**
 * Tuner setup: the cards for every vendor image, what is plugged in, and the image legend. Split
 * out of MainDialogs because it is a screen of its own (and the dialog class had grown past its
 * job of asking small questions).
 */
final class FirmwareCards {
    private final Activity activity;
    private final MainScreen screen;

    FirmwareCards(Activity activity, MainScreen screen) {
        this.activity = activity;
        this.screen = screen;
    }

    void showSheet() {
        final List<FirmwareStore.Image> images = FirmwareStore.all();

        final java.util.Map<String, UsbDevice> attached = new java.util.HashMap<>();
        final List<UsbDevice> others = new ArrayList<>();
        // The IN DEVELOPMENT card, and the stick it names, follow the driver core: a tree that does
        // not carry the provider (the generated public tree) shows neither, and an LME stick is then
        // listed as an unknown device rather than announced by a card whose driver is not in the
        // build. Same gate as the licence credit (owner, 2026-11-06).
        final boolean lmeCore = carriesLme2510();
        boolean lmePlugged = false;
        try {
            UsbManager um = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice device : um.getDeviceList().values()) {
                    if (device.getVendorId() == 0x1d6b) continue;   // root hubs, not tuners
                    String id = String.format(Locale.US, "%04x:%04x",
                            device.getVendorId(), device.getProductId());
                    boolean known = false;
                    for (FirmwareStore.Image image : images) {
                        if (usbId(image).equals(id)) known = true;
                    }
                    if (known) attached.put(id, device);
                    else if (lmeCore && isLme2510(device)) lmePlugged = true;   // the IN DEVELOPMENT card owns it
                    else others.add(device);
                }
            }
        } catch (RuntimeException ignored) {
            // no USB service: the image states still render
        }

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_tuner_setup, null);
        LinearLayout list = content.findViewById(R.id.tuner_content);
        list.addView(note(activity, activity.getString(R.string.tuner_setup_note)));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        List<FirmwareStore.Image> plugged = new ArrayList<>();
        List<FirmwareStore.Image> rest = new ArrayList<>();
        for (FirmwareStore.Image image : images) {
            if (attached.containsKey(usbId(image))) plugged.add(image);
            else rest.add(image);
        }
        if (!plugged.isEmpty()) {
            list.addView(header(activity, activity.getString(R.string.tuner_header_plugged)));
            for (FirmwareStore.Image image : plugged) {
                list.addView(tunerCard(image, true, dialogRef));
            }
        }
        list.addView(header(activity, activity.getString(R.string.tuner_header_supported)));
        for (FirmwareStore.Image image : rest) {
            list.addView(tunerCard(image, false, dialogRef));
        }
        // LME2510 is announced, not supported: the driver is being written and waits for a stick.
        // It gets its own section and the NO DRIVER pill so it cannot read like the four sticks
        // above (owner, 2026-11-06). No image exists, so there is nothing to import.
        if (lmeCore) {
            list.addView(header(activity, activity.getString(R.string.tuner_header_development)));
            list.addView(developmentCard("LME2510", "3344:1120", lmePlugged));
        }
        if (!others.isEmpty()) {
            list.addView(header(activity, activity.getString(R.string.tuner_header_other)));
            for (UsbDevice device : others) {
                list.addView(otherDeviceCard(device));
            }
        }
        list.addView(header(activity, activity.getString(R.string.tuner_header_legend)));
        addTunerLegend(list);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_tuner_setup)
                .setView(content)
                .create();
        dialogRef[0] = dialog;
        content.findViewById(R.id.tuner_import_folder).setOnClickListener(v -> {
            dialog.dismiss();
            screen.pickFirmwareFolder();
        });
        content.findViewById(R.id.tuner_import_file).setOnClickListener(v -> {
            dialog.dismiss();
            screen.pickFirmwareFile();
        });
        content.findViewById(R.id.tuner_close).setOnClickListener(v -> dialog.dismiss());
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Math.min(metrics.widthPixels - Dims.dp(activity, 40), Dims.dp(activity, 680)),
                    (int) (metrics.heightPixels * 0.82f));
        }
        dialog.show();
    }
    /**
     * True when this build carries the LME2510 driver core. The IN DEVELOPMENT card announces a
     * stick the app cannot drive yet, so the card follows the class: a tree without the provider
     * shows no card and no special case, and the same gate decides the licence credit.
     */
    private boolean carriesLme2510() {
        try {
            Class.forName("com.pbcnx.opendtvmod.source.provider.Lme2510Provider", false,
                    activity.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String usbId(FirmwareStore.Image image) {        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\\)")
                .matcher(image.device);
        return m.find() ? (m.group(1) + ":" + m.group(2)).toLowerCase(Locale.US) : "";
    }
    static TextView note(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        view.setLineSpacing(Dims.dp(activity, 2), 1f);
        view.setPadding(0, 0, 0, Dims.dp(activity, 2));
        return view;
    }
    static TextView header(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        view.setLetterSpacing(0.08f);
        view.setAllCaps(true);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        view.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        view.setPadding(0, Dims.dp(activity, 18), 0, Dims.dp(activity, 6));
        return view;
    }
    private View tunerCard(final FirmwareStore.Image image,
                           boolean plugged, final AlertDialog[] dialogRef) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_tuner_card);
        card.setPadding(Dims.dp(activity, 14), Dims.dp(activity, 12), Dims.dp(activity, 14), Dims.dp(activity, 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = Dims.dp(activity, 8);
        card.setLayoutParams(cardParams);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(activity);
        name.setText(image.label);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface));
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = new TextView(activity);
        status.setText(plugged ? (screen.canTune() ? R.string.tuner_ready : R.string.tuner_plugged)
                : R.string.tuner_not_plugged);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        status.setLetterSpacing(0.06f);
        status.setTextColor(ContextCompat.getColor(activity,
                plugged ? R.color.odtv_ok : R.color.odtv_on_surface_variant));
        top.addView(name);
        top.addView(status);
        card.addView(top);

        TextView id = new TextView(activity);
        id.setText(usbId(image));
        id.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        id.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        id.setPadding(0, Dims.dp(activity, 2), 0, Dims.dp(activity, 8));
        card.addView(id);

        final boolean imported = FirmwareStore.imported(activity, image);
        final boolean available = FirmwareStore.available(activity, image);
        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (imported) {
            meta.addView(tunerPill(activity.getString(R.string.tuner_pill_available), R.drawable.bg_pill_available, R.color.odtv_ok));
        } else if (available) {
            meta.addView(tunerPill(activity.getString(R.string.tuner_pill_vendor), R.drawable.bg_pill_vendor, R.color.odtv_key_blue));
        } else {
            meta.addView(tunerPill(activity.getString(R.string.tuner_pill_missing), R.drawable.bg_pill_missing, R.color.odtv_live));
        }
        TextView detail = new TextView(activity);
        detail.setText(imported || available
                ? String.format(Locale.US, "%,d B · %s", image.size, shortHash(image.sha256))
                : activity.getString(R.string.tuner_tap_to_import, String.format(Locale.US, "%,d", image.size)));
        detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        detail.setPadding(Dims.dp(activity, 10), 0, 0, 0);
        meta.addView(detail);
        card.addView(meta);

        card.setOnClickListener(v -> {
            if (imported) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                confirmForgetFirmware(image);
            } else if (available) {
                Toast.makeText(activity, activity.getString(R.string.toast_tuner_in_build, image.device),
                        Toast.LENGTH_SHORT).show();
            } else {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                screen.pickFirmwareFile();
            }
        });
        return card;
    }
    private TextView tunerPill(String text, int backgroundRes, int colorRes) {
        TextView pill = new TextView(activity);
        pill.setText(text);
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        pill.setLetterSpacing(0.06f);
        pill.setTextColor(ContextCompat.getColor(activity, colorRes));
        pill.setBackgroundResource(backgroundRes);
        pill.setPadding(Dims.dp(activity, 8), Dims.dp(activity, 3), Dims.dp(activity, 8), Dims.dp(activity, 3));
        return pill;
    }
    /** The LME2510 ids: recognised for the development card, not claimed by any provider yet. */
    private static boolean isLme2510(UsbDevice device) {
        if (device == null || device.getVendorId() != 0x3344) return false;
        int pid = device.getProductId();
        return pid == 0x1120 || pid == 0x2480 || pid == 0x1111;
    }

    /**
     * A named stick that is announced but not supported yet: no image exists, so there is no state
     * to read, nothing to import and no claim that it tunes. The card is inert on purpose; when the
     * stick is on the bus it says so, and the OTHER DEVICES list leaves the id to this card.
     */
    private View developmentCard(String name, String usbId, boolean plugged) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_tuner_card);
        card.setPadding(Dims.dp(activity, 14), Dims.dp(activity, 12),
                Dims.dp(activity, 14), Dims.dp(activity, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Dims.dp(activity, 8);
        card.setLayoutParams(params);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView nameView = new TextView(activity);
        nameView.setText(name);
        nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface));
        nameView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        nameView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = new TextView(activity);
        status.setText(plugged ? R.string.tuner_plugged : R.string.tuner_not_plugged);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        status.setLetterSpacing(0.06f);
        status.setTextColor(ContextCompat.getColor(activity,
                plugged ? R.color.odtv_ok : R.color.odtv_on_surface_variant));
        top.addView(nameView);
        top.addView(status);
        card.addView(top);

        TextView id = new TextView(activity);
        id.setText(usbId);
        id.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        id.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        id.setPadding(0, Dims.dp(activity, 2), 0, Dims.dp(activity, 8));
        card.addView(id);

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meta.addView(tunerPill(activity.getString(R.string.tuner_pill_no_driver), R.drawable.bg_pill_nodriver,
                R.color.odtv_on_surface_variant));
        TextView detailView = new TextView(activity);
        detailView.setText(plugged ? R.string.tuner_dev_detected : R.string.tuner_dev_waiting);
        detailView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        detailView.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        detailView.setPadding(Dims.dp(activity, 10), 0, 0, 0);
        meta.addView(detailView);
        card.addView(meta);
        return card;
    }

    private View otherDeviceCard(UsbDevice device) {        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_tuner_card);
        card.setPadding(Dims.dp(activity, 14), Dims.dp(activity, 12), Dims.dp(activity, 14), Dims.dp(activity, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Dims.dp(activity, 8);
        card.setLayoutParams(params);

        TextView name = new TextView(activity);
        name.setText(R.string.tuner_unknown_stick);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface));
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        card.addView(name);

        TextView id = new TextView(activity);
        id.setText(String.format(Locale.US, "%04x:%04x",
                device.getVendorId(), device.getProductId()));
        id.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        id.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        id.setPadding(0, Dims.dp(activity, 2), 0, Dims.dp(activity, 8));
        card.addView(id);

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meta.addView(tunerPill(activity.getString(R.string.tuner_pill_no_driver), R.drawable.bg_pill_nodriver,
                R.color.odtv_on_surface_variant));
        TextView detail = new TextView(activity);
        detail.setText(R.string.tuner_no_driver_detail);
        detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        detail.setPadding(Dims.dp(activity, 10), 0, 0, 0);
        meta.addView(detail);
        card.addView(meta);
        return card;
    }
    private void addTunerLegend(LinearLayout list) {
        legendRow(list, activity.getString(R.string.tuner_pill_vendor), R.drawable.bg_pill_vendor, R.color.odtv_key_blue,
                activity.getString(R.string.tuner_legend_vendor));
        legendRow(list, activity.getString(R.string.tuner_pill_available), R.drawable.bg_pill_available, R.color.odtv_ok,
                activity.getString(R.string.tuner_legend_available));
        legendRow(list, activity.getString(R.string.tuner_pill_missing), R.drawable.bg_pill_missing, R.color.odtv_live,
                activity.getString(R.string.tuner_legend_missing));
        legendRow(list, activity.getString(R.string.tuner_pill_no_driver), R.drawable.bg_pill_nodriver, R.color.odtv_on_surface_variant,
                activity.getString(R.string.tuner_legend_no_driver));
        presenceRow(list, activity.getString(R.string.tuner_ready), R.color.odtv_ok, activity.getString(R.string.tuner_legend_ready));
        presenceRow(list, activity.getString(R.string.tuner_plugged), R.color.odtv_ok, activity.getString(R.string.tuner_legend_plugged));
        presenceRow(list, activity.getString(R.string.tuner_not_plugged), R.color.odtv_on_surface_variant,
                activity.getString(R.string.tuner_legend_not_plugged));
    }
    private LinearLayout legendLine() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dims.dp(activity, 30));
        row.setPadding(0, Dims.dp(activity, 3), 0, Dims.dp(activity, 3));
        return row;
    }
    private void legendRow(LinearLayout list, String pillText, int backgroundRes, int colorRes,
                           String text) {
        LinearLayout row = legendLine();
        TextView pill = tunerPill(pillText, backgroundRes, colorRes);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setLayoutParams(new LinearLayout.LayoutParams(Dims.dp(activity, 112),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(pill);
        row.addView(legendText(text));
        list.addView(row);
    }
    private void presenceRow(LinearLayout list, String label, int colorRes, String text) {
        LinearLayout row = legendLine();
        TextView state = new TextView(activity);
        state.setText(label);
        state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        state.setLetterSpacing(0.04f);
        state.setTextColor(ContextCompat.getColor(activity, colorRes));
        state.setLayoutParams(new LinearLayout.LayoutParams(Dims.dp(activity, 112),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(state);
        row.addView(legendText(text));
        list.addView(row);
    }
    private TextView legendText(String text) {
        TextView detail = new TextView(activity);
        detail.setText(text);
        detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(ContextCompat.getColor(activity, R.color.odtv_on_surface_variant));
        detail.setPadding(Dims.dp(activity, 12), 0, 0, 0);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return detail;
    }
    private static String shortHash(String sha256) {
        return sha256 == null ? "" : (sha256.length() > 8 ? sha256.substring(0, 8) + "…" : sha256);
    }
    private void confirmForgetFirmware(final FirmwareStore.Image image) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tuner_remove_title)
                .setMessage(image.name + "\n\n" + activity.getString(FirmwareStore.available(activity, image)
                        ? R.string.tuner_remove_has_copy
                        : R.string.tuner_remove_no_copy))
                .setPositiveButton(R.string.tuner_remove, (d, w) -> {
                    boolean gone = FirmwareStore.forget(activity, image);
                    Toast.makeText(activity, gone
                                    ? activity.getString(R.string.toast_tuner_removed, image.name)
                                    : activity.getString(R.string.toast_tuner_nothing_removed),
                            Toast.LENGTH_SHORT).show();
                    screen.firmwareImagesChanged();
                })
                .setNegativeButton(R.string.tuner_keep, null)
                .show();
    }
}
