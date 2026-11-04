package com.vicky.clockeverywhere;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
//import android.os.Handler;
//import android.os.Looper;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClockEverywhereBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_PRESENT.equals(action)) {
            return;
        }

        SharedPreferences prefs = ClockPreferences.get(context);
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean overlayAllowed = Settings.canDrawOverlays(context);

        Log.i(TAG, "Boot received: action=" + action
                + ", enabled=" + enabled
                + ", overlayAllowed=" + overlayAllowed);

        if (!enabled || !overlayAllowed) {
            Log.i(TAG, "Clock not started at boot: enabled=" + enabled
                    + ", overlayAllowed=" + overlayAllowed);
            return;
        }

        // Delay 45s to let TCL display/WindowManager initialize
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this.intentHandler(context), 45000);


    }

    private Runnable intentHandler(final Context context) {
        return () -> {
            Intent serviceIntent = new Intent(context, ClockOverlayService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                    Log.i(TAG, "Requested foreground clock service at boot");
                } else {
                    context.startService(serviceIntent);
                    Log.i(TAG, "Requested clock service at boot");
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to start clock service at boot", e);
            }
        };
    }
}
