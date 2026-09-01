package com.master.bedtime.child;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText backend;
    private EditText child;
    private TextView status;
    private TextView setupStep;
    private TextView deviceOwnerStatus;
    private TextView deviceOwnerHelp;
    private Button permission;
    private Button start;
    private Button test;
    private DevicePolicyManager dpm;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backend = findViewById(R.id.backendUrl);
        child = findViewById(R.id.childId);
        status = findViewById(R.id.status);
        setupStep = findViewById(R.id.setupStep);
        deviceOwnerStatus = findViewById(R.id.deviceOwnerStatus);
        deviceOwnerHelp = findViewById(R.id.deviceOwnerHelp);
        Button accounts = findViewById(R.id.btnAccountsSecurity);
        Button continueSetup = findViewById(R.id.btnContinueSetup);
        permission = findViewById(R.id.btnOverlayPermission);
        start = findViewById(R.id.btnStartMonitor);
        test = findViewById(R.id.btnTestOverlay);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        backend.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("backend", "http://10.0.2.2:8080"));
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            refreshSetupState();
        });

        permission.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        start.setOnClickListener(v -> startMonitor());
        test.setOnClickListener(v -> testBedtime());

        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("account_reminder_seen", false)) {
            showAccountPreparationReminder();
        }
        refreshSetupState();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSetupState();
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void refreshSetupState() {
        boolean accountsConfirmed = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean owner = isDeviceOwner();

        if (!accountsConfirmed) {
            setupStep.setText("STEP 1 OF 4 — Remove saved accounts");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Pagkatapos alisin ang accounts, bumalik dito at pindutin ang TAPOS NA — CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 OF 4 — Activate Device Owner");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta ang phone sa PC at patakbuhin:\n\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\nPag success, bumalik sa app. Automatic nitong makikita ang Device Owner status.");
        } else {
            setupStep.setText("STEP 3 OF 4 — Pair and start monitor");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Managed mode ready. Ilagay ang Parent/Worker backend at Child ID, pagkatapos pindutin ang START BEDTIME MONITOR.");
        }

        permission.setVisibility(owner ? View.GONE : View.VISIBLE);
        start.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
        test.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));

        if (owner) {
            status.setText("Managed setup ready — waiting for monitor start");
        }
    }

    private void startMonitor() {
        boolean owner = isDeviceOwner();
        if (!owner && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps first for fallback mode.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }

        String backendValue = backend.getText().toString().trim().replaceAll("/$", "");
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) {
            Toast.makeText(this, "Ilagay muna ang Backend URL at Child ID.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("backend", backendValue)
            .putString("child", childValue)
            .putBoolean("setup_complete", true)
            .apply();

        Intent service = new Intent(this, BedtimeMonitorService.class);
        startForegroundService(service);
        setupStep.setText("STEP 4 OF 4 — READY");
        status.setText(owner ? "READY — Managed Bedtime Monitor running" : "READY — Fallback Bedtime Monitor running");
    }

    private void testBedtime() {
        if (isDeviceOwner()) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("last_active", true).apply();
            Intent lock = new Intent(this, BedtimeLockActivity.class);
            startActivity(lock);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow overlay permission first.", Toast.LENGTH_LONG).show();
            return;
        }
        BedtimeOverlay.show(this);
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this)
            .setTitle("Bago tayo magsimula")
            .setMessage("Para tuloy-tuloy ang Device Owner setup, alisin muna ang mga naka-save na account sa device. Siguraduhing alam ninyo ang email/username at password ng inyong mga account bago alisin ang mga ito. Pagkatapos ng installation at setup, maaari ninyo silang idagdag muli.\n\nKung okay po sa inyo, pindutin ang button sa ibaba at dadalhin kayo diretso sa Accounts / Account & Security settings.")
            .setNegativeButton("Hindi muna", null)
            .setPositiveButton("OKAY, PUNTA SA ACCOUNTS", (dialog, which) -> {
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("account_reminder_seen", true).apply();
                openAccountsSettings();
            })
            .show();
    }

    private void openAccountsSettings() {
        Intent i = new Intent(Settings.ACTION_SYNC_SETTINGS);
        try {
            startActivity(i);
        } catch (Exception first) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Buksan ang Settings > Accounts / Passwords & accounts.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
