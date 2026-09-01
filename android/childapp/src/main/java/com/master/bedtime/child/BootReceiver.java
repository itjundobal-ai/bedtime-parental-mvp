package com.master.bedtime.child;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTART_MONITOR = "com.master.bedtime.child.RESTART_MONITOR";
    private static final String TAG = "BedtimeBoot";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        boolean supported = Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || ACTION_RESTART_MONITOR.equals(action);
        if (!supported) return;

        String role = context.getSharedPreferences("app_role", Context.MODE_PRIVATE)
            .getString("role", "");
        boolean setupComplete = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("setup_complete", false);

        Log.i(TAG, "Received " + action + " role=" + role + " setupComplete=" + setupComplete);

        // Only a configured CHILD install should run the permanent monitor.
        if (!"child".equals(role) || !setupComplete) {
            Log.i(TAG, "Monitor auto-start skipped: child role/setup not ready");
            return;
        }

        startMonitor(context, action);

        // After a real reboot, restore the last known active lock immediately while
        // the monitor re-syncs with the backend.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        boolean active = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("last_active", false);
        if (!active) return;

        DevicePolicyManager dpm = (DevicePolicyManager)
            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());

        if (deviceOwner) {
            try {
                Intent lock = new Intent(context, BedtimeLockActivity.class);
                lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                              Intent.FLAG_ACTIVITY_CLEAR_TOP |
                              Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(lock);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Direct bedtime restore blocked; monitor will retry", e);
            }
        }

        if (Settings.canDrawOverlays(context)) {
            try { BedtimeOverlay.show(context); } catch (Exception e) {
                Log.w(TAG, "Fallback bedtime restore failed", e);
            }
        }
    }

    private void startMonitor(Context context, String reason) {
        try {
            context.startForegroundService(new Intent(context, BedtimeMonitorService.class));
            Log.i(TAG, "Monitor start requested after " + reason);
        } catch (Exception e) {
            Log.e(TAG, "Unable to auto-start monitor after " + reason, e);
        }
    }
}
