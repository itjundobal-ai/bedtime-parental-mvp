package com.master.bedtime.child;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BedtimeBoot";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) &&
            !Intent.ACTION_USER_UNLOCKED.equals(action)) return;

        // Copy any newer normal-storage settings into boot-safe storage after unlock.
        if (Intent.ACTION_USER_UNLOCKED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            BedtimeStorage.mirror(context);
        }

        android.content.SharedPreferences cfg = BedtimeStorage.prefs(context);
        boolean setupComplete = cfg.getBoolean("setup_complete", false);
        if (!setupComplete) return;

        boolean active = cfg.getBoolean("last_active", false);

        try {
            Intent service = new Intent(context, BedtimeMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
            Log.i(TAG, "Monitor restart requested after " + action);
        } catch (Exception e) {
            Log.e(TAG, "Unable to restart monitor after " + action, e);
        }

        if (!active || !Intent.ACTION_USER_UNLOCKED.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        DevicePolicyManager dpm = (DevicePolicyManager)
            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());

        if (deviceOwner && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            try {
                Intent lock = new Intent(context, BedtimeLockActivity.class);
                lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                              Intent.FLAG_ACTIVITY_CLEAR_TOP |
                              Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(lock);
                return;
            } catch (Exception ignored) {}
        }

        if (Settings.canDrawOverlays(context)) {
            try { BedtimeOverlay.show(context); } catch (Exception ignored) {}
        }
    }
}
