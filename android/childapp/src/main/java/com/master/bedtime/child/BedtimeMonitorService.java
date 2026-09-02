package com.master.bedtime.child;

import android.app.AlarmManager;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class BedtimeMonitorService extends Service {
    private volatile boolean running;
    private Thread worker;
    private static final String CHANNEL = "bedtime_monitor";
    private static final String TAG = "BedtimeMonitor";
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long RESTART_DELAY_MS = 5000L;
    private Boolean lastAppliedActive = null;

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
        Log.i(TAG, "Service started");
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

    private SharedPreferences cfg() {
        return BedtimeStorage.prefs(this);
    }

    private boolean setupComplete() {
        return cfg().getBoolean("setup_complete", false);
    }

    private void scheduleRestart() {
        if (!setupComplete()) return;
        try {
            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            Intent restart = new Intent(this, MonitorRestartReceiver.class).setAction("com.master.bedtime.child.RESTART_MONITOR");
            PendingIntent pi = PendingIntent.getBroadcast(
                this,
                73,
                restart,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            long when = SystemClock.elapsedRealtime() + RESTART_DELAY_MS;
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            Log.i(TAG, "Monitor restart scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Unable to schedule monitor restart", e);
        }
    }

    private String normalizeBackend(String value) {
        if (value == null) return "";
        String raw = value.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) return "";

        boolean hadHttps = raw.toLowerCase().contains("https://");
        boolean hadHttp = raw.toLowerCase().contains("http://");
        while (raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")) {
            if (raw.toLowerCase().startsWith("https://")) raw = raw.substring(8);
            else raw = raw.substring(7);
        }

        if (raw.toLowerCase().endsWith(".workers.dev") || raw.toLowerCase().contains(".workers.dev/")) {
            return "https://" + raw;
        }
        if (hadHttps) return "https://" + raw;
        if (hadHttp) return "http://" + raw;
        return "https://" + raw;
    }

    private void showManagedLock() {
        try {
            Log.i(TAG, "Applying managed lock");
            Intent i = new Intent(this, BedtimeLockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show managed lock", e);
        }
    }

    private void hideManagedLock() {
        try {
            Log.i(TAG, "Removing managed lock");
            Intent i = new Intent(this, BedtimeLockActivity.class)
                .putExtra("bedtime_off", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to hide managed lock", e);
        }
    }

    private void applyState(boolean active) {
        if (lastAppliedActive != null && lastAppliedActive == active) return;

        Log.i(TAG, "Applying state transition active=" + active);
        if (isDeviceOwner()) {
            BedtimeOverlay.hide(this);
            if (active) showManagedLock();
            else hideManagedLock();
        } else {
            if (active && Settings.canDrawOverlays(this)) BedtimeOverlay.show(this);
            if (!active) BedtimeOverlay.hide(this);
        }
        lastAppliedActive = active;
    }

    private void pollLoop() {
        while (running) {
            HttpURLConnection c = null;
            try {
                SharedPreferences p = cfg();
                String savedBase = p.getString("backend", "");
                String base = normalizeBackend(savedBase);
                String child = p.getString("child", "child-001");

                if (!base.equals(savedBase)) {
                    p.edit().putString("backend", base).apply();
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("backend", base).apply();
                    Log.i(TAG, "Normalized backend URL to " + base);
                }

                if (!base.isEmpty()) {
                    URL url = new URL(base + "/api/children/" + child + "/bedtime");
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setUseCaches(false);
                    c.setRequestProperty("Accept", "application/json");

                    int code = c.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                        }

                        JSONObject state = new JSONObject(sb.toString());
                        boolean active = state.optBoolean("active", false);
                        boolean allowPowerControls = state.optBoolean("allowPowerControls", false);
                        p.edit()
                            .putBoolean("last_active", active)
                            .putBoolean("allow_power_controls", allowPowerControls)
                            .putLong("last_poll_ok_at", System.currentTimeMillis())
                            .apply();
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putBoolean("last_active", active)
                            .putBoolean("allow_power_controls", allowPowerControls)
                            .putLong("last_poll_ok_at", System.currentTimeMillis())
                            .apply();
                        applyState(active);
                    } else {
                        Log.w(TAG, "Non-200 response: " + code);
                    }
                } else {
                    Log.w(TAG, "Backend URL is empty");
                }
            } catch (Exception e) {
                Log.e(TAG, "Poll failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } finally {
                if (c != null) c.disconnect();
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "Task removed; scheduling foreground monitor restart");
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        Log.w(TAG, "Service destroyed; scheduling restart when setup remains active");
        scheduleRestart();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
