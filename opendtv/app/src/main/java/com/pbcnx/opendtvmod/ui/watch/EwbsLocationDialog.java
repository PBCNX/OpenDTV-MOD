package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Arrays;
import java.util.function.IntConsumer;

import com.pbcnx.opendtvmod.R;
import com.pbcnx.opendtvmod.ts.EwbsAreaTable;
import com.pbcnx.opendtvmod.ui.Dims;

/**
 * Settings > EWBS Location: the zone/region/city picker. Extracted from MainDialogs, which keeps
 * the asking; this class owns the two-page dialog and the zone ordering.
 */
final class EwbsLocationDialog {
    private final Activity activity;
    private final MainScreen screen;

    private final AlertDialog dialog;
    private final int[] original = new int[3];
    private final int[] pick = new int[3];
    private final View fieldsPage;
    private final View listPage;
    private final LinearLayout listItems;
    private final ScrollView listScroll;
    private final TextView listTitle;
    private final TextView zoneValue;
    private final TextView regionValue;
    private final TextView cityValue;
    private final TextView codeText;
    private final View zoneRow;
    private final View regionRow;
    private final View cityRow;
    private final Button auto;
    private View lastRow;

    EwbsLocationDialog(Activity activity, MainScreen screen) {
        this.activity = activity;
        this.screen = screen;
        int current = screen.ewbsLocation();
        if (current == EwbsAreaTable.NATIONWIDE) current = 0;   // Nationwide is just Show all
        original[0] = pick[0] = current == 0 ? 0 : EwbsAreaTable.zoneOf(current);
        original[1] = pick[1] = current == 0 ? 0 : EwbsAreaTable.regionOf(current);
        original[2] = pick[2] = current;

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_ewbs_location, null, false);
        fieldsPage = content.findViewById(R.id.ewbs_page_fields);
        listPage = content.findViewById(R.id.ewbs_page_list);
        listItems = content.findViewById(R.id.ewbs_list_items);
        listScroll = content.findViewById(R.id.ewbs_list_scroll);
        listTitle = content.findViewById(R.id.ewbs_list_title);
        codeText = content.findViewById(R.id.ewbs_code);
        zoneRow = content.findViewById(R.id.ewbs_row_zone);
        regionRow = content.findViewById(R.id.ewbs_row_region);
        cityRow = content.findViewById(R.id.ewbs_row_city);
        zoneValue = zoneRow.findViewById(R.id.picker_value);
        regionValue = regionRow.findViewById(R.id.picker_value);
        cityValue = cityRow.findViewById(R.id.picker_value);
        auto = content.findViewById(R.id.ewbs_auto);
        ((TextView) zoneRow.findViewById(R.id.picker_label)).setText(R.string.ewbs_zone);
        ((TextView) regionRow.findViewById(R.id.picker_label)).setText(R.string.ewbs_region);
        ((TextView) cityRow.findViewById(R.id.picker_label)).setText(R.string.ewbs_city);

        content.findViewById(R.id.ewbs_list_back).setOnClickListener(v -> showFields(zoneRow));

        zoneRow.setOnClickListener(v -> openList(v, activity.getString(R.string.ewbs_zone), ewbsZones(), pick[0], which -> {
            int[] zones = ewbsZones();
            pick[0] = zones[which];
            int[] regions = pick[0] == 0 ? new int[0] : EwbsAreaTable.regionsInZone(pick[0]);
            pick[1] = regions.length == 0 ? 0 : regions[0];
            int[] cities = pick[1] == 0 ? new int[0] : EwbsAreaTable.citiesInRegion(pick[1]);
            pick[2] = cities.length == 0 ? 0 : cities[0];
        }));

        regionRow.setOnClickListener(v -> {
            if (pick[0] == 0) return;
            final int[] regions = EwbsAreaTable.regionsInZone(pick[0]);
            openList(v, activity.getString(R.string.ewbs_region), regions, pick[1], which -> {
                pick[1] = regions[which];
                int[] cities = EwbsAreaTable.citiesInRegion(pick[1]);
                pick[2] = cities.length == 0 ? 0 : cities[0];
            });
        });

        cityRow.setOnClickListener(v -> {
            if (pick[1] == 0) return;
            final int[] cities = EwbsAreaTable.citiesInRegion(pick[1]);
            openList(v, activity.getString(R.string.ewbs_city), cities, pick[2], which -> pick[2] = cities[which]);
        });

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_ewbs_alerts)
                .setView(content)
                .setPositiveButton(R.string.apply, (d, which) -> screen.saveEwbsLocation(selectedCode()))
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP
                    && listPage.getVisibility() == View.VISIBLE) {
                showFields(lastRow);
                return true;
            }
            return false;
        });

        auto.setOnClickListener(v -> screen.detectEwbsLocation(new MainDialogs.LocationCallback() {
            @Override public void onLocation(int code) {
                if (code == 0) {
                    Toast.makeText(activity,
                            R.string.toast_location_not_matched,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                applyDetected(code);
                Toast.makeText(activity,
                        activity.getString(R.string.toast_current_location, EwbsAreaTable.name(code)), Toast.LENGTH_SHORT).show();
            }
        }));
    }

    void show() {
        dialog.show();
        render();               // the buttons only exist once the dialog is up
        auto.requestFocus();
    }

    /** The most specific chosen level, which is what gets stored. */
    private int selectedCode() {
        if (pick[0] == 0) return 0;
        if (pick[2] != 0) return pick[2];
        if (pick[1] != 0) return pick[1];
        return pick[0];
    }

    private void render() {
        zoneValue.setText(ewbsLabel(pick[0]));
        regionValue.setText(pick[0] == 0 || pick[1] == 0 ? "—" : EwbsAreaTable.name(pick[1]));
        cityValue.setText(pick[1] == 0 || pick[2] == 0 ? "—" : EwbsAreaTable.name(pick[2]));
        setEwbsRowEnabled(regionRow, pick[0] != 0);
        setEwbsRowEnabled(cityRow, pick[1] != 0);
        codeText.setText(activity.getString(R.string.ewbs_code, selectedCode()));
        if (dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                    pick[0] != original[0] || pick[1] != original[1] || pick[2] != original[2]);
        }
    }

    /** Takes a detected code and rewrites the three fields. Nothing is stored: Apply decides. */
    private void applyDetected(int code) {
        if (code == 0 || code == EwbsAreaTable.NATIONWIDE) {
            pick[0] = 0;
            pick[1] = 0;
            pick[2] = 0;
        } else if (EwbsAreaTable.isZone(code)) {
            pick[0] = code;
            pick[1] = 0;
            pick[2] = 0;
        } else if (EwbsAreaTable.isRegion(code)) {
            pick[0] = EwbsAreaTable.zoneOf(code);
            pick[1] = code;
            pick[2] = 0;
        } else {
            pick[0] = EwbsAreaTable.zoneOf(code);
            pick[1] = EwbsAreaTable.regionOf(code);
            pick[2] = code;
        }
        render();
        zoneRow.requestFocus();
    }

    private void showFields(View row) {
        listPage.setVisibility(View.GONE);
        fieldsPage.setVisibility(View.VISIBLE);
        (row != null ? row : auto).requestFocus();
        render();
    }

    /** Fills the list page with the given codes, alphabetically, current value first in sight. */
    private void openList(View from, String title, int[] codes, int selected, IntConsumer onPick) {
        if (codes.length == 0) return;
        lastRow = from;
        listTitle.setText(title);
        listItems.removeAllViews();

        Integer[] order = new Integer[codes.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            if (codes[a] == 0 && codes[b] != 0) return -1;   // "Show all alerts" stays first
            if (codes[b] == 0 && codes[a] != 0) return 1;
            int rankA = zoneRank(codes[a]);
            int rankB = zoneRank(codes[b]);
            if (rankA != rankB) return Integer.compare(rankA, rankB);
            return ewbsLabel(codes[a]).compareToIgnoreCase(ewbsLabel(codes[b]));
        });

        final int gap = Dims.dp(activity, 6);
        View focusRow = null;
        for (int n = 0; n < order.length; n++) {
            final int index = order[n];
            View row = activity.getLayoutInflater().inflate(R.layout.view_choice_row, listItems, false);
            ((TextView) row.findViewById(R.id.choice_label)).setText(ewbsLabel(codes[index]));
            row.findViewById(R.id.choice_check)
                    .setVisibility(codes[index] == selected ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                onPick.accept(index);
                showFields(lastRow);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (n > 0) lp.topMargin = gap;
            listItems.addView(row, lp);
            if (codes[index] == selected) focusRow = row;

            // TEMP (public build): the "Show all alerts" shortcut and its rule
            // come out before release; the zones then start at Luzon.
            if (codes[index] == 0 && n < order.length - 1) {
                View rule = new View(activity);
                rule.setBackgroundColor(ContextCompat.getColor(activity, R.color.odtv_divider));
                LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.max(1, Dims.dp(activity, 1)));
                ruleParams.topMargin = gap + Dims.dp(activity, 4);
                ruleParams.bottomMargin = gap + Dims.dp(activity, 4);
                listItems.addView(rule, ruleParams);
            }
        }

        final View target = focusRow != null ? focusRow : listItems.getChildAt(0);
        fieldsPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        listScroll.post(() -> {
            target.requestFocus();
            listScroll.smoothScrollTo(0, Math.max(0, target.getTop() - listScroll.getHeight() / 2));
        });
    }

    /** Zone codes with the leading "show everything" entry (0). */
    private static int[] ewbsZones() {
        int[] table = EwbsAreaTable.zoneCodes();
        int[] zones = new int[table.length + 1];
        System.arraycopy(table, 0, zones, 1, table.length);
        return zones;
    }

    private String ewbsLabel(int code) {
        return code == 0 ? activity.getString(R.string.ewbs_show_all) : EwbsAreaTable.name(code);
    }

    /** The zones have a fixed order: Luzon, Visayas, Mindanao. It is the table's order, not names. */
    private static int zoneRank(int code) {
        int[] zones = EwbsAreaTable.zoneCodes();
        for (int i = 0; i < zones.length; i++) {
            if (zones[i] == code) return i + 1;
        }
        return 99;
    }

    private void setEwbsRowEnabled(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.38f);
    }

    /*
     * One card with two pages: the three fields, and the list a field opens.
     * The list replaces the page instead of stacking a second dialog on top, the
     * way the scan sheet does it, so everything keeps the app's own chrome.
     */
}
