package com.master.bedtime.child;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private EditText backend, child, pairingCode;
    private TextView status, setupStep, deviceOwnerStatus, deviceOwnerHelp, pairingStatus;
    private Button accounts, continueSetup, activeDeviceOwner, permission, start, battery, autoRun, saveRestart,
            restoreAccounts, test, releaseDeviceOwner, pairWithParent;
    private LinearLayout pairingPanel;
    private DevicePolicyManager dpm;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        BedtimeStorage.mirror(this);
        backend = findViewById(R.id.backendUrl);
        child = findViewById(R.id.childId);
        status = findViewById(R.id.status);
        setupStep = findViewById(R.id.setupStep);
        deviceOwnerStatus = findViewById(R.id.deviceOwnerStatus);
        deviceOwnerHelp = findViewById(R.id.deviceOwnerHelp);
        accounts = findViewById(R.id.btnAccountsSecurity);
        continueSetup = findViewById(R.id.btnContinueSetup);
        activeDeviceOwner = findViewById(R.id.btnActiveDeviceOwner);
        permission = findViewById(R.id.btnOverlayPermission);
        start = findViewById(R.id.btnStartMonitor);
        battery = findViewById(R.id.btnBatterySettings);
        autoRun = findViewById(R.id.btnAutoRunSettings);
        saveRestart = findViewById(R.id.btnSaveRestart);
        restoreAccounts = findViewById(R.id.btnRestoreAccounts);
        test = findViewById(R.id.btnTestOverlay);
        releaseDeviceOwner = findViewById(R.id.btnReleaseDeviceOwner);
        pairingPanel = findViewById(R.id.pairingPanel);
        pairingCode = findViewById(R.id.pairingCode);
        pairWithParent = findViewById(R.id.btnPairWithParent);
        pairingStatus = findViewById(R.id.pairingStatus);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        String saved = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("backend", "https://bedtime-parental-api.itjundobal.workers.dev");
        backend.setText(normalizeBackend(saved));
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            BedtimeStorage.mirror(this);
            refreshSetupState();
        });
        activeDeviceOwner.setOnClickListener(v -> checkDeviceOwnerStep());
        permission.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        start.setOnClickListener(v -> startMonitor());
        battery.setOnClickListener(v -> openBatterySettings());
        autoRun.setOnClickListener(v -> openAutoRunSettings());
        saveRestart.setOnClickListener(v -> saveAndRestart());
        restoreAccounts.setOnClickListener(v -> showRestoreAccountsReminder());
        test.setOnClickListener(v -> testBedtime());
        releaseDeviceOwner.setOnClickListener(v -> confirmReleaseDeviceOwner());
        pairWithParent.setOnClickListener(v -> redeemPairing(pairingCode.getText().toString().trim()));
        refreshSetupState();
    }

    private void checkDeviceOwnerStep() {
        if (isDeviceOwner()) {
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Device Owner is active. Continue with START BEDTIME MONITOR.");
            Toast.makeText(this, "ACTIVE DEVICE OWNER ✓", Toast.LENGTH_SHORT).show();
        } else {
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta sa PC at patakbuhin:\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver");
            Toast.makeText(this, "Device Owner is not active yet.", Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
    }

    private void redeemPairing(String c) {
        if (!c.matches("\\d{6}")) {
            pairingStatus.setText("Enter the 6-digit code from Parent.");
            return;
        }
        String base = normalizeBackend(backend.getText().toString());
        pairingStatus.setText("Pairing with Parent…");
        new Thread(() -> {
            try {
                HttpURLConnection x = (HttpURLConnection) new URL(base + "/api/pairing/redeem").openConnection();
                x.setRequestMethod("POST");
                x.setConnectTimeout(7000);
                x.setReadTimeout(7000);
                x.setRequestProperty("Content-Type", "application/json");
                x.setDoOutput(true);
                try (OutputStream o = x.getOutputStream()) {
                    o.write(("{\"code\":\"" + c + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                int r = x.getResponseCode();
                String body = readBody(x, r);
                if (r < 200 || r >= 300) throw new Exception(body);
                JSONObject d = new JSONObject(body);
                String childId = d.getString("childId");
                String childToken = d.getString("childToken");

                String recovery = getSharedPreferences("cfg", MODE_PRIVATE)
                        .getString("recovery_code", "");
                if (recovery.isEmpty()) {
                    recovery = generateRecoveryCode();
                }

                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                        .putString("backend", base)
                        .putString("child", childId)
                        .putString("child_token", childToken)
                        .putString("recovery_code", recovery)
                        .putBoolean("paired", true)
                        .apply();
                BedtimeStorage.mirror(this);
                final String finalRecovery = recovery;
                runOnUiThread(() -> {
                    child.setText(childId);
                    pairingStatus.setText("✓ CHILD PAIRED WITH PARENT\n\nRECOVERY CODE: "
                            + finalRecovery + "\n\nSave this code. It is shown only on the Child device.");
                    setupStep.setText("CHILD ACTIVE — PAIRED ✓");
                    deviceOwnerHelp.setText("Parent account connected. Child is ready for remote bedtime control.");
                    Toast.makeText(this, "Child paired. Recovery Code generated.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> pairingStatus.setText("Pairing failed: " + e.getMessage()));
            }
        }).start();
    }

    private String generateRecoveryCode() {
        int n = new SecureRandom().nextInt(100000000);
        String code = String.format(java.util.Locale.US, "%08d", n);
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("recovery_code", code).apply();
        return code;
    }

    private void refreshSetupState() {
        boolean ac = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean done = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();
        boolean monitorStarted = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("monitor_started", false);
        boolean paired = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false);

        if (!done) {
            if (!ac) {
                setupStep.setText("STEP 1 — ACCOUNT / SECURITY");
                deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
                deviceOwnerHelp.setText("Alisin muna ang saved accounts, pagkatapos pindutin ang TAPOS NA — CONTINUE SETUP.");
            } else if (!owner) {
                setupStep.setText("STEP 2 — ACTIVE DEVICE OWNER");
                deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
                deviceOwnerHelp.setText("Ikonekta sa PC at patakbuhin:\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver");
            } else if (!monitorStarted) {
                setupStep.setText("STEP 3 — START BEDTIME MONITOR");
                deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
                deviceOwnerHelp.setText("Device Owner active. Tap START BEDTIME MONITOR. Then configure Battery and Auto-run/Background.");
            } else {
                setupStep.setText("STEP 4–6 — BATTERY → AUTO-RUN → SAVE & RESTART");
                deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
                deviceOwnerHelp.setText("Monitor started. Complete Battery: No restrictions and Auto-run/Background: Allow, then tap SAVE & RESTART.");
            }
            showSetupUi(owner, done);
            return;
        }

        setupStep.setText(paired ? "CHILD ACTIVE — PAIRED ✓" : "CHILD ACTIVE ✓");
        deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Managed protection needs attention");
        deviceOwnerHelp.setText(paired
                ? "Parent account connected. Child is ready for remote bedtime control."
                : "Setup complete. Now enter the 6-digit code from Parent below.");
        showCompletedChildUi(owner, paired);
    }

    private void showSetupUi(boolean owner, boolean done) {
        // Keep every setup option visible until SAVE & RESTART succeeds.
        accounts.setVisibility(View.VISIBLE);
        continueSetup.setVisibility(View.VISIBLE);
        activeDeviceOwner.setVisibility(View.VISIBLE);
        backend.setVisibility(View.VISIBLE);
        child.setVisibility(View.VISIBLE);
        permission.setVisibility(View.VISIBLE);
        start.setVisibility(View.VISIBLE);
        battery.setVisibility(View.VISIBLE);
        autoRun.setVisibility(View.VISIBLE);
        saveRestart.setVisibility(View.VISIBLE);
        restoreAccounts.setVisibility(View.GONE);
        test.setVisibility(View.VISIBLE);
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);
        pairingPanel.setVisibility(View.GONE);

        accounts.setEnabled(true);
        continueSetup.setEnabled(true);
        activeDeviceOwner.setEnabled(true);
        permission.setEnabled(true);
        start.setEnabled(true);
        battery.setEnabled(true);
        autoRun.setEnabled(true);
        saveRestart.setEnabled(true);
        test.setEnabled(true);

        start.setText(getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("monitor_started", false)
                ? "3. START BEDTIME MONITOR ✓" : "3. START BEDTIME MONITOR");
        saveRestart.setText("6. SAVE & RESTART");
        activeDeviceOwner.setText(isDeviceOwner() ? "2. ACTIVE DEVICE OWNER ✓" : "2. ACTIVE DEVICE OWNER");
    }

    private void showCompletedChildUi(boolean owner, boolean paired) {
        accounts.setVisibility(View.GONE);
        continueSetup.setVisibility(View.GONE);
        activeDeviceOwner.setVisibility(View.GONE);
        backend.setVisibility(View.GONE);
        child.setVisibility(View.GONE);
        permission.setVisibility(View.GONE);
        start.setVisibility(View.GONE);
        battery.setVisibility(View.GONE);
        autoRun.setVisibility(View.GONE);
        saveRestart.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);
        restoreAccounts.setVisibility(View.VISIBLE);
        pairingPanel.setVisibility(View.VISIBLE);
        pairingCode.setEnabled(owner && !paired);
        pairWithParent.setEnabled(owner && !paired);
        if (paired) pairingCode.setVisibility(View.GONE);
        else pairingCode.setVisibility(View.VISIBLE);
        status.setText(owner ? "CHILD ACTIVE ✓" : "ATTENTION — Device Owner protection is not active");
    }

    private void startMonitor() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "ACTIVE DEVICE OWNER FIRST", Toast.LENGTH_LONG).show();
            return;
        }
        String base = normalizeBackend(backend.getText().toString());
        String id = child.getText().toString().trim();
        if (base.isEmpty() || id.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }
        BedtimeStorage.setSetup(this, base, id, true);
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); }
        catch (Exception e) { Toast.makeText(this, "Monitor start failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("monitor_started", true).apply();
        BedtimeStorage.mirror(this);
        refreshSetupState();
        Toast.makeText(this, "START BEDTIME MONITOR ✓ — Next: Battery and Auto-run settings.", Toast.LENGTH_LONG).show();
    }

    private void openBatterySettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openAutoRunSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
        Toast.makeText(this,
                "Sa TECNO, hanapin ang Auto-start / App launch / Background activity at i-allow ang app.",
                Toast.LENGTH_LONG).show();
    }

    private void saveAndRestart() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "ACTIVE DEVICE OWNER FIRST", Toast.LENGTH_LONG).show();
            return;
        }
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("monitor_started", false)) {
            Toast.makeText(this, "START BEDTIME MONITOR first.", Toast.LENGTH_LONG).show();
            return;
        }
        String base = normalizeBackend(backend.getText().toString());
        String id = child.getText().toString().trim();
        if (base.isEmpty() || id.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }
        BedtimeStorage.setSetup(this, base, id, true);
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("setup_complete", true).apply();
        BedtimeStorage.mirror(this);
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
        refreshSetupState();
        Toast.makeText(this, "Saved. CHILD ACTIVE ✓ — restarting monitor…", Toast.LENGTH_LONG).show();
        new android.os.Handler().postDelayed(() -> {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }, 1200);
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private String normalizeBackend(String v) {
        if (v == null) return "";
        String s = v.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        while (s.startsWith("http://") || s.startsWith("https://")) {
            s = s.substring(s.indexOf("://") + 3);
        }
        return "https://" + s;
    }

    private String readBody(HttpURLConnection x, int c) throws Exception {
        InputStream in = c >= 400 ? x.getErrorStream() : x.getInputStream();
        if (in == null) return "HTTP " + c;
        BufferedReader b = new BufferedReader(new InputStreamReader(in));
        StringBuilder s = new StringBuilder();
        String l;
        while ((l = b.readLine()) != null) s.append(l);
        b.close();
        return s.toString();
    }

    private void testBedtime() {
        BedtimeStorage.setLastActive(this, true);
        startActivity(new Intent(this, BedtimeLockActivity.class));
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this)
                .setTitle("Bago tayo magsimula")
                .setMessage("Alisin muna ang saved accounts sa device. Maaari silang ibalik pagkatapos ng setup.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PUNTA SA ACCOUNTS", (d, w) -> {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("account_reminder_seen", true).apply();
                    startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS));
                }).show();
    }

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this)
                .setTitle("Setup complete")
                .setMessage("Tapos na ang Child setup. Maaari nang ibalik ang accounts.")
                .setNegativeButton("MAMAYA", null)
                .setPositiveButton("PUNTA SA ACCOUNTS", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS)))
                .show();
    }

    private void confirmReleaseDeviceOwner() {
        new AlertDialog.Builder(this)
                .setTitle("TEST ONLY — Release Device Owner?")
                .setMessage("Huwag gamitin sa production device.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RELEASE", (d, w) -> {
                    try { dpm.clearDeviceOwnerApp(getPackageName()); } catch (Exception ignored) {}
                    refreshSetupState();
                }).show();
    }
}
