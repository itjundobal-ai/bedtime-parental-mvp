package com.master.bedtime.child;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BedtimeLockActivity extends Activity {
    private static volatile BedtimeLockActivity activeInstance;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private boolean exiting;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        }

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, BedtimeDeviceAdminReceiver.class);

        if (getIntent().getBooleanExtra("bedtime_off", false)) {
            exitManagedLock();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(185, 28, 28));

        TextView moon = new TextView(this);
        moon.setText("🌙");
        moon.setTextSize(64);
        moon.setGravity(Gravity.CENTER);
        root.addView(moon);

        TextView title = new TextView(this);
        title.setText("BEDTIME MODE");
        title.setTextSize(34);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Phone locked for bedtime. Your parent will unlock it in the morning.");
        body.setTextSize(18);
        body.setTextColor(Color.WHITE);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 24, 0, 0);
        root.addView(body);

        setContentView(root);
        enterManagedLock();
        hideSystemUi();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("bedtime_off", false)) {
            exitManagedLock();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activeInstance = this;
        if (!exiting) {
            enterManagedLock();
            hideSystemUi();
        }
    }

    @Override protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    public static boolean requestRemoteUnlock() {
        BedtimeLockActivity current = activeInstance;
        if (current == null || current.isFinishing()) return false;
        current.runOnUiThread(current::exitManagedLock);
        return true;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !exiting) {
            enterManagedLock();
            hideSystemUi();
        }
    }

    private void enterManagedLock() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            dpm.setLockTaskPackages(admin, new String[]{getPackageName()});

            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }

            if (dpm.isLockTaskPermitted(getPackageName())) {
                startLockTask();
            }
        } catch (Exception ignored) {
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
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

    private void exitManagedLock() {
        if (exiting) return;
        exiting = true;
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(admin, new String[]{});
            }
        } catch (Exception ignored) {
        }
        finishAndRemoveTask();
    }

    @Override public void onBackPressed() {
    }
}
