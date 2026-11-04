package com.vicky.clockeverywhere;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClockOverlayService extends Service {
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_24_HOUR = "24_hour";
    private static final String KEY_SHOW_TZ = "show_timezone";
    private static final String KEY_SHOW_APP_USAGE = "show_app_usage";
    private static final String KEY_TIMEZONE = "timezone";
    private static final String CHANNEL_ID = "clock_everywhere_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final long USAGE_REFRESH_INTERVAL_MILLIS = 8_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService usageExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout overlayView;
    private TextView usageView;
    private TextView clockView;
    private TextView tzView;
    private WindowManager windowManager;
    private SharedPreferences prefs;
    private UsageRepository usageRepository;
    private SimpleDateFormat clockFormat;
    private String clockPattern;
    private String clockTimeZone;
    private String usageTimeZoneId;
    private String displayedUsagePackage;
    private long displayedUsageMillis;
    private long displayedUsageElapsedRealtime;
    private long displayedTotalTvMillis;
    private long displayedTotalTvSampleElapsedRealtime;
    private boolean viewAdded;
    private boolean usageRefreshPending;
    private volatile boolean destroyed;
    // When you leave TV input, the clock resyncs. When you go back, it freezes again.
    private long referenceWallTime = 0;
    private long referenceElapsed = 0;
    private long lastNtpCheck = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (clockView == null || !viewAdded) return;
            if (!prefs.getBoolean(KEY_ENABLED, false)) {
                stopSelf();
                return;
            }

            String pattern = prefs.getBoolean(KEY_24_HOUR, true) ? "HH:mm:ss" : "hh:mm:ss a";
            String zoneId = prefs.getString(KEY_TIMEZONE, "Asia/Jakarta");
//          UNNUSED THiS ->  if (prefs.getBoolean(KEY_SHOW_TZ, false)) pattern += " z";
            if (clockFormat == null || !pattern.equals(clockPattern)) {
                clockFormat = new SimpleDateFormat(pattern, Locale.getDefault());
                clockPattern = pattern;
                clockTimeZone = null;
            }
            if (!zoneId.equals(clockTimeZone)) {
                clockFormat.setTimeZone(TimeZone.getTimeZone(zoneId));
                clockTimeZone = zoneId;
            }

            // Get accurate time using monotonic clock
            if (referenceWallTime == 0) {
                referenceWallTime = System.currentTimeMillis();
                referenceElapsed = SystemClock.elapsedRealtime();
            }
            /*
              The formula: correct_time = reference + (elapsed_now - elapsed_at_reference)
              Even if the wall clock is frozen, elapsedRealtime() keeps counting every millisecond since boot. So your clock stays accurate.
              If you manually change the system time (Settings → Date & Time), your referenceWallTime won't update — the clock will stay at the old reference until the next reboot. For a TV this is unlikely to matter, but if you ever do change the time, restart the service:
              "adb shell am start-foreground-service -n com.vicky.clockeverywhere.debug/com.vicky.clockeverywhere.ClockOverlayService"
              # 1. Stop the service
                adb shell am stopservice com.vicky.clockeverywhere.debug/com.vicky.clockeverywhere.ClockOverlayService
              # 2. Go to launcher (navigate away from TV input)
              # 3. Start the service (captures correct reference)
                adb shell am start-foreground-service -n com.vicky.clockeverywhere.debug/com.vicky.clockeverywhere.ClockOverlayService
             */
            long atNow = referenceWallTime + (SystemClock.elapsedRealtime() - referenceElapsed);

            /// Resync: if system clock is AHEAD of our calculated time,
            // it was wrong when we captured the reference — update it
            long systemNow = System.currentTimeMillis();
            if (systemNow > atNow + 5000) {
                // System clock was wrong at capture, now fixed	systemNow > atNow + 5s	Resync to correct system time
                referenceWallTime = systemNow;
                referenceElapsed = SystemClock.elapsedRealtime();
                atNow = systemNow;
            }

            /// NTP resync every 5 minutes (independent of system clock)
            if (SystemClock.elapsedRealtime() - lastNtpCheck > 300_000) {
                lastNtpCheck = SystemClock.elapsedRealtime();
                new Thread(queryNtpThread).start();
            }

            String time = clockFormat.format(new Date(atNow));
            if (!time.equals(clockView.getText().toString())) clockView.setText(time);

            // Timezone in smaller text
            if (prefs.getBoolean(KEY_SHOW_TZ, false) && tzView != null) {
                SimpleDateFormat tzFormat = new SimpleDateFormat("z", Locale.getDefault());
                tzFormat.setTimeZone(TimeZone.getTimeZone(zoneId));
                SimpleDateFormat tzFmt = new SimpleDateFormat("X", Locale.getDefault());
                tzFmt.setTimeZone(TimeZone.getTimeZone(zoneId));
                String tz = tzFmt.format(new Date(atNow));
                if (!tz.equals(tzView.getText().toString())) tzView.setText(tz);
                tzView.setVisibility(android.view.View.VISIBLE);
            } else if (tzView != null) {
                tzView.setVisibility(android.view.View.GONE);
            }
            handler.postDelayed(this, 1000);
        }
    };


    // Runnable queryNtpThread
    private final Runnable queryNtpThread = new Runnable() {
        @Override
        public void run() {
            try {
                long ntpTime = queryNtp();
                if (ntpTime > 0) {
                    handler.post(() -> {
                        long currentAtNow = referenceWallTime + (SystemClock.elapsedRealtime() - referenceElapsed);
                        if (Math.abs(ntpTime - currentAtNow) > 2000) {
                            referenceWallTime = ntpTime;
                            referenceElapsed = SystemClock.elapsedRealtime();
                            android.util.Log.i("ClockOverlayService", "NTP resync: " + new Date(ntpTime));
                        }
                    });
                }
            } catch (Exception e) {
                // No internet or timeout — ignore
            }
        }
    };

    // Runnable Usage Ticker
    private final Runnable usageTicker = new Runnable() {
        @Override
        public void run() {
            if (!viewAdded || usageView == null) return;
            if (prefs.getBoolean(KEY_SHOW_APP_USAGE, false)) refreshForegroundUsage();
            else hideUsage();
            handler.postDelayed(this, USAGE_REFRESH_INTERVAL_MILLIS);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (sharedPreferences, key) -> {
        if (usageView == null) return;
        if (KEY_SHOW_APP_USAGE.equals(key) || UsageLimits.KEY_TV_LIMIT_MINUTES.equals(key) || UsageLimits.KEY_APP_LIMIT_MINUTES.equals(key)) {
            if (prefs.getBoolean(KEY_SHOW_APP_USAGE, false)) refreshForegroundUsage();
            else hideUsage();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = ClockPreferences.get(this);
        if (!prefs.getBoolean(KEY_ENABLED, false) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        // Wait 15s more (on top of BootReceiver's 45s = 60s total)
        handler.postDelayed(() -> trySetupOverlay(0), 15_000);

    }

    private void trySetupOverlay(int attempt) {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new LinearLayout(this);
            overlayView.setOrientation(LinearLayout.HORIZONTAL);
            overlayView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            overlayView.setClickable(false);
            overlayView.setFocusable(false);
            overlayView.setFocusableInTouchMode(false);

            usageView = new TextView(this);
            usageView.setTextColor(Color.CYAN);
            usageView.setTextSize(18);
            usageView.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            usageView.setShadowLayer(4f, 2f, 2f, Color.BLACK);
            usageView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            usageView.setSingleLine(true);
            usageView.setMaxWidth(dp(700));
            usageView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            usageView.setVisibility(android.view.View.GONE);
            usageView.setClickable(false);
            usageView.setFocusable(false);
            usageView.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams usageParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            usageParams.setMargins(0, 0, 0, 0);
            overlayView.addView(usageView, usageParams);

            clockView = new TextView(this);
            clockView.setTextColor(Color.GREEN);
            clockView.setTextSize(20);
            clockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            clockView.setShadowLayer(4f, 2f, 2f, Color.BLACK);

            // Pill Background Started

            // Pill container (both time + tz inside one pill)
            LinearLayout clockContainer = new LinearLayout(this);
            clockContainer.setOrientation(LinearLayout.HORIZONTAL);
            clockContainer.setGravity(Gravity.CENTER_VERTICAL);
            clockContainer.setClickable(false);
            clockContainer.setFocusable(false);

            android.graphics.drawable.GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(80, 0, 0, 0));
            bg.setCornerRadius(dp(20));
            clockContainer.setBackground(bg);
            // change to dp
            clockContainer.setPadding(dp(12), dp(4), dp(12), dp(4));

            clockView.setClickable(false);
            clockView.setFocusable(false);
            clockView.setFocusableInTouchMode(false);
            clockContainer.addView(clockView);

//            Prepare for Timezone View
            tzView = new TextView(this);
            tzView.setTextColor(Color.WHITE);
            tzView.setTextSize(12);
            tzView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            tzView.setPadding(dp(4), 0, dp(0), dp(0));
            tzView.setClickable(false);
            tzView.setFocusable(false);

            clockContainer.addView(tzView);
            overlayView.addView(clockContainer);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = 6;
            params.y = 4;

            windowManager.addView(overlayView, params);
            viewAdded = true;
            handler.post(ticker);
            handler.post(usageTicker);

            // Immediate NTP sync to correct reference if system clock was stale at boot
            new Thread(queryNtpThread).start();

            android.util.Log.i("ClockOverlayService", "Overlay drawn (attempt " + (attempt + 1) + ")");
        } catch (Exception e) {
            android.util.Log.e("ClockOverlayService", "addView failed (attempt " + (attempt + 1) + ")", e);
            viewAdded = false;
            overlayView = null;
            usageView = null;
            clockView = null;
            if (attempt < 5) {
                handler.postDelayed(() -> trySetupOverlay(attempt + 1), 10_000);
            } else {
                android.util.Log.e("ClockOverlayService", "Max retries reached, giving up");
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private long queryNtp() {
        try {
            java.net.DatagramSocket socket = new java.net.DatagramSocket();
            socket.setSoTimeout(3000);
            byte[] buffer = new byte[48];
            buffer[0] = 0x1B; // NTP request
            java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length, java.net.InetAddress.getByName("time.google.com"), 123);
            socket.send(packet);
            java.net.DatagramPacket response = new java.net.DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            socket.close();

            // Ntp Logger
            android.util.Log.i("QueryNtp", "Response from: " + response.getAddress().getHostAddress()
                    + ", length: " + response.getLength());
            android.util.Log.w("QueryNtp", "Raw bytes: " + java.util.Arrays.toString(response.getData()));


            // Parse transmit timestamp (bytes 40-43 = seconds, 44-47 = fraction)
            long seconds = 0;
            for (int i = 40; i < 44; i++) seconds = (seconds << 8) | (buffer[i] & 0xFF);
            // NTP epoch (1900) → Unix epoch (1970): subtract 2208988800
            return (seconds - 2208988800L) * 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    private void createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Clock Everywhere", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Clock Everywhere overlay running");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
//        }
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        return builder.setContentTitle("Clock Everywhere").setContentText("Clock overlay is running").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentIntent(pendingIntent).setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private void refreshForegroundUsage() {
        if (usageRefreshPending || !viewAdded || usageView == null || !prefs.getBoolean(KEY_SHOW_APP_USAGE, false))
            return;
        usageRefreshPending = true;
        usageExecutor.execute(() -> {
            UsageRepository.ForegroundUsage usage;
            UsageRepository.DayUsage today;
            try {
                String currentTimeZoneId = TimeZone.getDefault().getID();
                if (usageRepository == null || !currentTimeZoneId.equals(usageTimeZoneId)) {
                    usageRepository = new UsageRepository(getApplicationContext());
                    usageTimeZoneId = currentTimeZoneId;
                }
                usage = usageRepository.loadForegroundUsageToday();
                today = usageRepository.loadToday();
            } catch (RuntimeException e) {
                android.util.Log.w("ClockOverlayService", "Could not refresh foreground app usage.", e);
                usage = null;
                today = null;
            }
            UsageRepository.ForegroundUsage result = usage;
            UsageRepository.DayUsage todayResult = today;
            if (!destroyed) {
                handler.post(() -> {
                    usageRefreshPending = false;
                    if (!viewAdded || usageView == null || !prefs.getBoolean(KEY_SHOW_APP_USAGE, false)) return;
                    if (result == null || todayResult == null) {
                        hideUsage();
                        return;
                    }

                    long elapsedRealtime = SystemClock.elapsedRealtime();
                    if (result.packageName.equals(displayedUsagePackage) && result.todayMillis >= displayedUsageMillis) {
                        long elapsedSinceSample = Math.max(0L, elapsedRealtime - displayedUsageElapsedRealtime);
                        displayedUsageMillis = Math.max(result.todayMillis, displayedUsageMillis + elapsedSinceSample);
                    } else {
                        displayedUsagePackage = result.packageName;
                        displayedUsageMillis = result.todayMillis;
                    }
                    displayedUsageElapsedRealtime = elapsedRealtime;

                    if (todayResult.totalForegroundMillis >= displayedTotalTvMillis) {
                        long elapsedSinceTotalSample = Math.max(0L, elapsedRealtime - displayedTotalTvSampleElapsedRealtime);
                        displayedTotalTvMillis = Math.max(todayResult.totalForegroundMillis, displayedTotalTvMillis + (displayedTotalTvSampleElapsedRealtime == 0L ? 0L : elapsedSinceTotalSample));
                    } else {
                        displayedTotalTvMillis = todayResult.totalForegroundMillis;
                    }
                    displayedTotalTvSampleElapsedRealtime = elapsedRealtime;

                    int appLimit = UsageLimits.getAppLimitMinutes(prefs);
                    int tvLimit = UsageLimits.getTvLimitMinutes(prefs);
                    String appStatus = UsageLimits.status(displayedUsageMillis, appLimit);
                    String tvStatus = UsageLimits.status(displayedTotalTvMillis, tvLimit);
                    String display = result.label + " Usage: " + UsageReportMath.formatUsageDuration(displayedUsageMillis) + " " + appStatus + " | " + tvStatus;
                    if (!display.equals(usageView.getText().toString())) usageView.setText(display);
                    usageView.setVisibility(android.view.View.VISIBLE);
                });
            }
        });
    }

    private void hideUsage() {
        displayedUsagePackage = null;
        displayedUsageMillis = 0L;
        displayedTotalTvMillis = 0L;
        displayedTotalTvSampleElapsedRealtime = 0L;
        if (usageView != null && usageView.getVisibility() != android.view.View.GONE) {
            usageView.setVisibility(android.view.View.GONE);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(usageTicker);
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        usageExecutor.shutdownNow();
        if (overlayView != null && windowManager != null && viewAdded) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        stopForeground(true);
        viewAdded = false;
        overlayView = null;
        usageView = null;
        clockView = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
