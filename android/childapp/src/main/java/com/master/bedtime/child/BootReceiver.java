package com.master.bedtime.child;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            boolean active = context.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("last_active", false);
            if (active && Settings.canDrawOverlays(context)) {
                try { BedtimeOverlay.show(context); } catch (Exception ignored) {}
            }
            try { context.startForegroundService(new Intent(context, BedtimeMonitorService.class)); } catch (Exception ignored) {}
        }
    }
}
