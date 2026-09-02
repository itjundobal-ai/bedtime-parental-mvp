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
    private Button restoreAccounts;
    private Button releaseDeviceOwner;
    private DevicePolicyManager dpm;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BedtimeStorage.mirror(this);

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
        restoreAccounts = findViewById(R.id.btnRestoreAccounts);
        test = findViewById(R.id.btnTestOverlay);
        releaseDeviceOwner = findViewById(R.id.btnReleaseDeviceOwner);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        String savedBackend = getSharedPreferences("cfg", MODE_PRIVATE).getString("backend", "https://bedtime-parental-api.itjundobal.workers.dev");
        String normalizedBackend = normalizeBackend(savedBackend);
        backend.setText(normalizedBackend);
        if (!normalizedBackend.equals(savedBackend)) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("backend", normalizedBackend).apply();
            BedtimeStorage.mirror(this);
        }
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            BedtimeStorage.mirror(this);
            refreshSetupState();
        });

        permission.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        start.setOnClickListener(v -> startMonitor());
        restoreAccounts.setOnClickListener(v -> showRestoreAccountsReminder());
        test.setOnClickListener(v -> testBedtime());
        releaseDeviceOwner.setOnClickListener(v -> confirmReleaseDeviceOwner());

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

    private String normalizeBackend(String value) {
        if (value == null) return "";
        String raw = value.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) return "";

        boolean hadHttps = raw.toLowerCase().contains("https://");
        boolean hadHttp = raw.toLowerCase().contains("http://");
        while (raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")) {
            if (raw.toLowerCase().startsWith("https://")) raw = raw.substring(8);
            else raw = raw.substring(7);
        }

        if (raw.toLowerCase().endsWith(".workers.dev") || raw.toLowerCase().contains(".workers.dev/")) {
            return "https://" + raw;
        }
        if (hadHttps) return "https://" + raw;
        if (hadHttp) return "http://" + raw;
        return "https://" + raw;
    }

    private void refreshSetupState() {
        boolean accountsConfirmed = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean setupComplete = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();

        if (!accountsConfirmed) {
            setupStep.setText("STEP 1 OF 5 — Remove saved accounts");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Pagkatapos alisin ang accounts, bumalik dito at pindutin ang TAPOS NA — CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 OF 5 — Activate Device Owner");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta ang phone sa PC at patakbuhin:\n\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\nPag success, bumalik sa app. Automatic nitong makikita ang Device Owner status.");
        } else if (!setupComplete) {
            setupStep.setText("STEP 3 OF 5 — Pair and start monitor");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Managed mode ready. Ilagay ang Parent/Worker backend at Child ID, pagkatapos pindutin ang START BEDTIME MONITOR.");
        } else {
            setupStep.setText("STEP 5 OF 5 — Setup complete");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Setup complete. Maaari mong palitan ang Backend URL o Child ID at pindutin ang SAVE / RESTART BEDTIME MONITOR.");
        }

        permission.setVisibility(owner ? View.GONE : View.VISIBLE);
        start.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
        start.setText(setupComplete ? "SAVE / RESTART BEDTIME MONITOR" : "3. START BEDTIME MONITOR");
        restoreAccounts.setVisibility(setupComplete ? View.VISIBLE : View.GONE);
        test.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);

        if (setupComplete) {
            status.setText(owner ? "READY — Managed Bedtime Monitor running" : "READY — Fallback Bedtime Monitor running");
        } else if (owner) {
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

        String backendValue = normalizeBackend(backend.getText().toString());
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) {
            Toast.makeText(this, "Ilagay muna ang Backend URL at Child ID.", Toast.LENGTH_LONG).show();
            return;
        }
        backend.setText(backendValue);

        boolean wasSetupComplete = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("setup_complete", false);
        BedtimeStorage.setSetup(this, backendValue, childValue, true);

        Intent service = new Intent(this, BedtimeMonitorService.class);
        startForegroundService(service);
        refreshSetupState();
        Toast.makeText(this, wasSetupComplete ? "Bedtime monitor settings saved." : "Bedtime monitor started.", Toast.LENGTH_LONG).show();
        if (!wasSetupComplete) showRestoreAccountsReminder();
    }

    private void testBedtime() {
        if (isDeviceOwner()) {
            BedtimeStorage.setLastActive(this, true);
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

    private void confirmReleaseDeviceOwner() {
        if (!isDeviceOwner()) {
            refreshSetupState();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("TEST ONLY — Release Device Owner?")
            .setMessage("Gamitin lang ito sa test device. Tatanggalin nito ang Device Owner role para ma-uninstall o ma-reprovision ang app. Hindi nito ginagawa ang factory reset.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RELEASE DEVICE OWNER", (dialog, which) -> releaseDeviceOwnerForTesting())
            .show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForTesting() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        try {
            BedtimeStorage.setLastActive(this, false);
            BedtimeStorage.setSetup(this, getSharedPreferences("cfg", MODE_PRIVATE).getString("backend", ""), getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"), false);
            stopService(new Intent(this, BedtimeMonitorService.class));
            try {
                Intent unlock = new Intent(this, BedtimeLockActivity.class);
                unlock.putExtra("bedtime_off", true);
                startActivity(unlock);
            } catch (Exception ignored) {}

            dpm.clearDeviceOwnerApp(getPackageName());
            Toast.makeText(this, "Device Owner released for testing.", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Android blocked Device Owner release on this device.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
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

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this)
            .setTitle("Setup complete")
            .setMessage("Tapos na ang Bedtime setup. Maaari na ninyong ibalik o idagdag muli ang mga account na inalis kanina. Siguraduhing tama ang account credentials bago magpatuloy.")
            .setNegativeButton("Mamaya", null)
            .setPositiveButton("PUNTA SA ACCOUNTS", (dialog, which) -> openAccountsSettings())
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
