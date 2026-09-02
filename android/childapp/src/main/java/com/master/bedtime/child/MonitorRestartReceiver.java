package com.master.bedtime.child;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class MonitorRestartReceiver extends BroadcastReceiver {
    private static final String TAG = "BedtimeRestart";

    @Override public void onReceive(Context context, Intent intent) {
        boolean setupComplete = BedtimeStorage.prefs(context).getBoolean("setup_complete", false);
        if (!setupComplete) return;

        try {
            Intent service = new Intent(context, BedtimeMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.i(TAG, "Foreground monitor restart requested");
        } catch (Exception e) {
            Log.e(TAG, "Unable to restart monitor", e);
        }
    }
}
