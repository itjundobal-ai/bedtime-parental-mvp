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
    private Button accounts, continueSetup, permission, start, battery, autoRun, saveRestart,
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
        boolean paired = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false);
        if (!ac) {
            setupStep.setText("STEP 1 OF 6 — Prepare device");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Alisin muna ang accounts, pagkatapos pindutin ang CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 OF 6 — Activate Device Owner");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta sa PC at patakbuhin:\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver");
        } else if (!done) {
            setupStep.setText("STEP 3–6 — Finish Child protection setup");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Start monitor → Battery: No restrictions → Auto-run/Background: Allow → Save & Restart.");
        } else {
            setupStep.setText(paired ? "CHILD ACTIVE — PAIRED ✓" : "CHILD ACTIVE ✓");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText(paired
                    ? "Parent account connected. Child is ready for remote bedtime control."
                    : "Setup complete. Now enter the 6-digit code from Parent below.");
        }

        start.setVisibility(ac && owner && !done ? View.VISIBLE : View.GONE);
        battery.setVisibility(owner && !done ? View.VISIBLE : View.GONE);
        autoRun.setVisibility(owner && !done ? View.VISIBLE : View.GONE);
        saveRestart.setVisibility(owner && !done ? View.VISIBLE : View.GONE);
        restoreAccounts.setVisibility(done ? View.VISIBLE : View.GONE);
        permission.setVisibility(owner ? View.GONE : View.VISIBLE);
        test.setVisibility(owner ? View.VISIBLE : View.GONE);
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);
        pairingPanel.setVisibility(done ? View.VISIBLE : View.GONE);

        if (done) {
            accounts.setVisibility(View.GONE);
            continueSetup.setVisibility(View.GONE);
            backend.setVisibility(View.GONE);
            child.setVisibility(View.GONE);
            permission.setVisibility(View.GONE);
            start.setVisibility(View.GONE);
            battery.setVisibility(View.GONE);
            autoRun.setVisibility(View.GONE);
            saveRestart.setVisibility(View.GONE);
            test.setVisibility(View.GONE);
            releaseDeviceOwner.setVisibility(View.GONE);
        }
    }

    private void startMonitor() {
        String base = normalizeBackend(backend.getText().toString());
        String id = child.getText().toString().trim();
        if (base.isEmpty() || id.isEmpty()) return;
        BedtimeStorage.setSetup(this, base, id, true);
        startForegroundService(new Intent(this, BedtimeMonitorService.class));
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("monitor_started", true).apply();
        refreshSetupState();
        Toast.makeText(this, "Monitor started. Next: Battery and Auto-run settings.", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Device Owner is not active.", Toast.LENGTH_LONG).show();
            return;
        }
        String base = normalizeBackend(backend.getText().toString());
        String id = child.getText().toString().trim();
        BedtimeStorage.setSetup(this, base, id, true);
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("setup_complete", true).apply();
        BedtimeStorage.mirror(this);
        startForegroundService(new Intent(this, BedtimeMonitorService.class));
        refreshSetupState();
        Toast.makeText(this, "Saved. Restarting Child monitor…", Toast.LENGTH_LONG).show();
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
