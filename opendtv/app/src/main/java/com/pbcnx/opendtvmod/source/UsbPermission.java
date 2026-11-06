package com.pbcnx.opendtvmod.source;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * The one USB-permission grant protocol: the broadcast action every receiver listens for, the
 * mutable {@link PendingIntent} the system fills with the result, and the registration flag.
 *
 * <p>It existed in four screens under three different action strings and three copies of the flag
 * logic, so a receiver could wait for a broadcast the request never sent.</p>
 */
public final class UsbPermission {

    /** The grant-result broadcast action, paired with {@link #request}. */
    public static final String ACTION = "com.pbcnx.opendtvmod.USB_PERMISSION";

    private UsbPermission() {
    }

    /** Asks for {@code device}; the answer arrives on {@link #ACTION}. */
    public static void request(Context context, UsbManager manager, UsbDevice device, int requestCode) {
        // Mutable (the system adds the result extras) but explicit through setPackage: required on
        // Android 14.
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION).setPackage(context.getPackageName());
        manager.requestPermission(device, PendingIntent.getBroadcast(context, requestCode, intent, flags));
    }

    /** Registers {@code receiver} for the grant result. Not exported: the answer is ours. */
    public static void register(Context context, BroadcastReceiver receiver) {
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }
}
