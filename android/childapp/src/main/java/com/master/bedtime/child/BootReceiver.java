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

        boolean setupComplete = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("setup_complete", false);
        if (!setupComplete) return;

        boolean active = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("last_active", false);

        try {
            Intent service = new Intent(context, BedtimeMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
            Log.i(TAG, "Monitor restart requested after " + action);
        } catch (Exception e) {
            Log.e(TAG, "Unable to restart monitor after " + action, e);
        }

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
            } catch (Exception ignored) {}
        }

        if (Settings.canDrawOverlays(context)) {
            try { BedtimeOverlay.show(context); } catch (Exception ignored) {}
        }
    }
}
