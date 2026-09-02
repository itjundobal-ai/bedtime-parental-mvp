package com.master.bedtime.child;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class RoleSelectionActivity extends Activity {
    private static final String PREFS = "app_role";
    private static final String KEY_ROLE = "role";

    private DevicePolicyManager dpm;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        String savedRole = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROLE, "");
        if ("parent".equals(savedRole)) {
            if (isDeviceOwner()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ROLE).apply();
                showStaleOwnerRecoveryNotice();
            } else {
                openParent();
            }
            return;
        }
        if ("child".equals(savedRole)) {
            openChild();
            return;
        }

        showRoleChooser();
    }

    private void showRoleChooser() {
        setContentView(R.layout.activity_role_selection);

        Button parent = findViewById(R.id.btnParentMode);
        Button child = findViewById(R.id.btnChildMode);

        parent.setOnClickListener(v -> {
            if (isDeviceOwner()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ROLE).apply();
                showStaleOwnerRecoveryNotice();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROLE, "parent").apply();
            openParent();
        });

        child.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROLE, "child").apply();
            openChild();
        });
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void showStaleOwnerRecoveryNotice() {
        new AlertDialog.Builder(this)
            .setTitle("Old CHILD protection detected")
            .setMessage("This phone is still Android Device Owner from an earlier CHILD setup. Release the old CHILD protection first. The PARENT role will not be saved until Device Owner is removed.")
            .setNegativeButton("CANCEL", (dialog, which) -> showRoleChooser())
            .setPositiveButton("OPEN RECOVERY", (dialog, which) -> {
                startActivity(new Intent(this, ParentActivity.class));
            })
            .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (dpm == null) return;
        String savedRole = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROLE, "");
        if (savedRole.isEmpty() && !isDeviceOwner()) {
            showRoleChooser();
        }
    }

    private void openParent() {
        startActivity(new Intent(this, ParentActivity.class));
        finish();
    }

    private void openChild() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
