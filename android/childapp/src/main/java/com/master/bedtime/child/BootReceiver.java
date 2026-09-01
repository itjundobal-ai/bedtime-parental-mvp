package com.master.bedtime.child;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean active = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("last_active", false);

        // Always restart the monitor after boot so it can re-sync with the parent backend.
        try {
            context.startForegroundService(new Intent(context, BedtimeMonitorService.class));
        } catch (Exception ignored) {}

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
            } catch (Exception ignored) {
                // The foreground monitor will retry managed lock if direct launch is blocked by the OEM.
            }
        }

        // Fallback mode for non-Device-Owner installs.
        if (Settings.canDrawOverlays(context)) {
            try { BedtimeOverlay.show(context); } catch (Exception ignored) {}
        }
    }
}
