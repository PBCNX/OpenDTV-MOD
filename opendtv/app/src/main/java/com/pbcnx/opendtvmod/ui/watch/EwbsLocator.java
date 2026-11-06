package com.pbcnx.opendtvmod.ui.watch;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.ts.EwbsAreaTable;

/**
 * Resolves the phone's current place to a PH EWBS city/region code, out of MainActivity.
 *
 * <p>Permission prompt, the last-known-location pick, the single-update fallback with its 15 s
 * timeout and the reverse geocode all belong to one question; they used to be spread through the
 * activity's lifecycle and an onRequestPermissionsResult branch.</p>
 */
public final class EwbsLocator {

    private static final String TAG = "OpenDTV-Ewbs";
    private static final int REQUEST_CODE = 1002;
    private static final long FIX_TIMEOUT_MS = 15_000;

    private final Activity activity;
    private final Handler ui;
    private MainDialogs.LocationCallback pending;

    public EwbsLocator(Activity activity, Handler ui) {
        this.activity = activity;
        this.ui = ui;
    }

    /** Resolves the viewer's place and reports a code, 0 when unknown. May ask for permission first. */
    public void detect(MainDialogs.LocationCallback callback) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pending = callback;
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_CODE);
            return;
        }
        LocationManager manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
        if (manager == null) {
            callback.onLocation(0);
            return;
        }
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (Exception ignored) {
                // provider disabled or not permitted: try the next one
            }
        }
        if (best != null) {
            resolve(best, callback);
            return;
        }
        String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            callback.onLocation(0);
            return;
        }
        final LocationListener[] ref = new LocationListener[1];
        final boolean[] resolved = new boolean[1];
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (resolved[0]) return;
                resolved[0] = true;
                try { manager.removeUpdates(ref[0]); } catch (Exception ignored) {
                    // already removed by the timeout below
                }
                resolve(location, callback);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { /* intentionally empty */ }
            @Override public void onProviderEnabled(String provider) { /* intentionally empty */ }
            @Override public void onProviderDisabled(String provider) { /* intentionally empty */ }
        };
        ref[0] = listener;
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            ui.postDelayed(() -> {
                if (resolved[0]) return;
                resolved[0] = true;   // the dialog gets its answer even when no fix arrives
                try { manager.removeUpdates(listener); } catch (Exception ignored) {
                    // already removed in onLocationChanged
                }
                callback.onLocation(0);
            }, FIX_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "Location request failed", e);
            callback.onLocation(0);
        }
    }

    /**
     * The activity's onRequestPermissionsResult delegate.
     *
     * @return true when the request code was ours (the caller should stop there)
     */
    public boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return false;
        MainDialogs.LocationCallback callback = pending;
        pending = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && callback != null) {
            detect(callback);
        } else if (callback != null) {
            callback.onLocation(0);
        }
        return true;
    }

    private void resolve(Location location, MainDialogs.LocationCallback callback) {
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        new Thread(() -> {
            int code = 0;
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(activity, Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        String[] names = {address.getLocality(), address.getSubAdminArea(),
                                address.getAdminArea(), address.getSubLocality()};
                        for (String name : names) {
                            code = EwbsAreaTable.findByName(name);
                            if (code != 0) break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not reverse-geocode EWBS location", e);
            }
            final int result = code;
            ui.post(() -> callback.onLocation(result));
        }, "ewbs-geocode").start();
    }
}
