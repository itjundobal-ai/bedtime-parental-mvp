package com.master.bedtime.child;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class BedtimeMonitorService extends Service {
    private volatile boolean running;
    private Thread worker;
    private static final String CHANNEL = "bedtime_monitor";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(this, CHANNEL)
            .setContentTitle("Bedtime monitor active")
            .setContentText("Listening for parent bedtime commands")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pi)
            .setOngoing(true).build();
        startForeground(7, n);
        running = true;
        worker = new Thread(this::pollLoop, "bedtime-poll");
        worker.start();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Bedtime Monitor", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void showManagedLock() {
        try {
            Intent i = new Intent(this, BedtimeLockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void hideManagedLock() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                Intent i = new Intent(this, BedtimeLockActivity.class)
                    .putExtra("bedtime_off", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    private void pollLoop() {
        while (running) {
            try {
                SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
                String base = p.getString("backend", "");
                String child = p.getString("child", "child-001");
                if (!base.isEmpty()) {
                    URL url = new URL(base + "/api/children/" + child + "/bedtime");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    int code = c.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                            String line; while ((line = br.readLine()) != null) sb.append(line);
                        }

                        JSONObject state = new JSONObject(sb.toString());
                        boolean active = state.optBoolean("active", false);
                        boolean allowPowerControls = state.optBoolean("allowPowerControls", false);
                        p.edit()
                            .putBoolean("last_active", active)
                            .putBoolean("allow_power_controls", allowPowerControls)
                            .apply();

                        if (isDeviceOwner()) {
                            BedtimeOverlay.hide(this);
                            if (active) showManagedLock();
                            else hideManagedLock();
                        } else {
                            if (active && Settings.canDrawOverlays(this)) BedtimeOverlay.show(this);
                            if (!active) BedtimeOverlay.hide(this);
                        }
                    }
                    c.disconnect();
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() { running = false; if (worker != null) worker.interrupt(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
