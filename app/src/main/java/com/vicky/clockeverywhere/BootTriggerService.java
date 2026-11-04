package com.vicky.clockeverywhere;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

@SuppressLint("AccessibilityPolicy")
public class BootTriggerService extends AccessibilityService {
    private static final String TAG = "BootTrigger";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected - starting clock");

        SharedPreferences prefs = ClockPreferences.get(this);
        if (prefs.getBoolean("enabled", false) && Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(new Intent(this, ClockOverlayService.class));
                Log.i(TAG, "ClockOverlayService started from accessibility");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start clock service", e);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Do nothing - we only need onServiceConnected
    }

    @Override
    public void onInterrupt() {
    }
}