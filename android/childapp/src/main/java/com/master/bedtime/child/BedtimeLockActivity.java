package com.master.bedtime.child;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BedtimeLockActivity extends Activity {
    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, BedtimeDeviceAdminReceiver.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView moon = new TextView(this);
        moon.setText("🌙");
        moon.setTextSize(64);
        moon.setGravity(Gravity.CENTER);
        root.addView(moon);

        TextView title = new TextView(this);
        title.setText("Bedtime Mode");
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Time to rest. Your parent will unlock the phone in the morning.");
        body.setTextSize(18);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 24, 0, 0);
        root.addView(body);

        setContentView(root);
        enterManagedLock();
        hideSystemUi();
    }

    @Override protected void onResume() {
        super.onResume();
        enterManagedLock();
        hideSystemUi();
    }

    private void enterManagedLock() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
            boolean allowPower = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("allow_power_controls", false);
            int features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
            if (allowPower) features |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
            dpm.setLockTaskFeatures(admin, features);
            if (dpm.isLockTaskPermitted(getPackageName())) startLockTask();
        } catch (Exception ignored) {}
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    public void exitManagedLock() {
        try { stopLockTask(); } catch (Exception ignored) {}
        finishAndRemoveTask();
    }

    @Override public void onBackPressed() {
        // Intentionally ignored while bedtime is active.
    }
}
