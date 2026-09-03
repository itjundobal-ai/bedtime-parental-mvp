package com.master.bedtime.child;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String CFG = "cfg";
    private static final String KEY_RECOVERY_PIN = "parent_recovery_pin";
    private static final String KEY_BATTERY_CONFIRMED = "battery_settings_confirmed";
    private static final String KEY_AUTORUN_CONFIRMED = "autorun_background_confirmed";
    private static final String KEY_MONITOR_STARTED = "monitor_started";

    private EditText backend, child, pairingInput;
    private TextView status, setupStep, deviceOwnerStatus, deviceOwnerHelp, pairingCode, recoveryCode;
    private Button accounts, removeAccountsDone, deviceOwnerButton, permission, generatePairing, startMonitor,
            saveRestart, batterySettings, autoRunSettings, test, restoreAccounts, releaseDeviceOwner;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backend = findViewById(R.id.backendUrl);
        child = findViewById(R.id.childId);
        pairingInput = findViewById(R.id.childPairingInput);
        status = findViewById(R.id.status);
        setupStep = findViewById(R.id.setupStep);
        deviceOwnerStatus = findViewById(R.id.deviceOwnerStatus);
        deviceOwnerHelp = findViewById(R.id.deviceOwnerHelp);
        pairingCode = findViewById(R.id.pairingCode);
        recoveryCode = findViewById(R.id.recoveryCode);
        accounts = findViewById(R.id.btnAccountsSecurity);
        removeAccountsDone = findViewById(R.id.btnContinueSetup);
        deviceOwnerButton = findViewById(R.id.btnDeviceOwner);
        permission = findViewById(R.id.btnOverlayPermission);
        generatePairing = findViewById(R.id.btnGeneratePairingCode);
        startMonitor = findViewById(R.id.btnStartMonitor);
        saveRestart = findViewById(R.id.btnSaveRestart);
        batterySettings = findViewById(R.id.btnBatterySettings);
        autoRunSettings = findViewById(R.id.btnAutoRun);
        restoreAccounts = findViewById(R.id.btnRestoreAccounts);
        test = findViewById(R.id.btnTestOverlay);
        releaseDeviceOwner = findViewById(R.id.btnReleaseDeviceOwner);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, BedtimeDeviceAdminReceiver.class);

        String savedBackend = getSharedPreferences(CFG, MODE_PRIVATE)
                .getString("backend", "https://bedtime-parental-api.itjundobal.workers.dev");
        String normalizedBackend = normalizeBackend(savedBackend);
        backend.setText(normalizedBackend);
        if (!normalizedBackend.equals(savedBackend)) {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("backend", normalizedBackend).apply();
        }

        String savedChild = getSharedPreferences(CFG, MODE_PRIVATE).getString("child", "");
        if (savedChild.isEmpty()) {
            savedChild = "child-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("child", savedChild).apply();
        }
        child.setText(savedChild);

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        removeAccountsDone.setOnClickListener(v -> markAccountsDone());
        deviceOwnerButton.setOnClickListener(v -> showDeviceOwnerGuide());
        permission.setOnClickListener(v -> openOverlaySettings());
        batterySettings.setOnClickListener(v -> showBatterySettingsGuide());
        autoRunSettings.setOnClickListener(v -> showAutoRunGuide());
        startMonitor.setOnClickListener(v -> startBedtimeMonitor());
        saveRestart.setOnClickListener(v -> saveAndRestart());
        generatePairing.setOnClickListener(v -> claimPairingCode());
        restoreAccounts.setOnClickListener(v -> showRestoreAccountsReminder());
        test.setOnClickListener(v -> testBedtime());
        releaseDeviceOwner.setOnClickListener(v -> confirmReleaseDeviceOwner());

        status.setOnLongClickListener(v -> {
            if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false)) return false;
            showRecoveryPinPrompt();
            return true;
        });

        refreshSetupState();
        autoStartConfiguredMonitor();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSetupState();
        autoStartConfiguredMonitor();
    }

    private void autoStartConfiguredMonitor() {
        if (!"child".equals(getSharedPreferences("app_role", MODE_PRIVATE).getString("role", ""))) return;
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_MONITOR_STARTED, false)) return;
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private String normalizeBackend(String value) {
        if (value == null) return "";
        String raw = value.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) return "";
        boolean https = raw.toLowerCase().startsWith("https://");
        boolean http = raw.toLowerCase().startsWith("http://");
        while (raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")) {
            raw = raw.substring(raw.toLowerCase().startsWith("https://") ? 8 : 7);
        }
        if (raw.toLowerCase().endsWith(".workers.dev") || raw.toLowerCase().contains(".workers.dev/")) return "https://" + raw;
        if (https) return "https://" + raw;
        if (http) return "http://" + raw;
        return "https://" + raw;
    }

    private void refreshSetupState() {
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean accountsConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean batteryConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_BATTERY_CONFIRMED, false);
        boolean autoRunConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_AUTORUN_CONFIRMED, false);
        boolean monitorStarted = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_MONITOR_STARTED, false);
        boolean owner = isDeviceOwner();
        boolean paired = !getSharedPreferences(CFG, MODE_PRIVATE).getString("child_token", "").isEmpty();

        if (!setupComplete) {
            setupStep.setText("SETUP CHECKLIST — 1 to 7");
            deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText(owner
                    ? "Active Device Owner detected. You may continue the checklist."
                    : "Tap ACTIVE DEVICE OWNER for the exact provisioning command, then return here and refresh/check again.");

            accounts.setText(accountsConfirmed ? "1. ACCOUNT / SECURITY ✓" : "1. ACCOUNT / SECURITY");
            removeAccountsDone.setText(accountsConfirmed ? "2. REMOVE SAVED ACCOUNTS ✓" : "2. REMOVE SAVED ACCOUNTS — DONE");
            deviceOwnerButton.setText(owner ? "3. ACTIVE DEVICE OWNER ✓" : "3. ACTIVE DEVICE OWNER");
            batterySettings.setText(batteryConfirmed ? "4. BATTERY — NO RESTRICTIONS ✓" : "4. BATTERY — NO RESTRICTIONS");
            autoRunSettings.setText(autoRunConfirmed ? "5. AUTO-RUN / BACKGROUND ✓" : "5. AUTO-RUN / BACKGROUND");
            startMonitor.setText(monitorStarted ? "6. START BEDTIME MONITOR ✓" : "6. START BEDTIME MONITOR");
            saveRestart.setText("7. SAVE & RESTART");
            saveRestart.setEnabled(true);

            showSetupUi();
            return;
        }

        setupStep.setText("CHILD ACTIVE ✓");
        deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Managed protection needs attention");
        deviceOwnerHelp.setText(owner
                ? "Setup complete. Pairing is now available. Recovery Code appears after successful pairing."
                : "Device Owner is no longer active.");
        applyCompletedChildProtection(owner);
        showCompletedChildUi(owner, paired);
    }

    private void showSetupUi() {
        // Locked rule: ALL seven setup options remain visible during setup.
        accounts.setVisibility(View.VISIBLE);
        removeAccountsDone.setVisibility(View.VISIBLE);
        deviceOwnerButton.setVisibility(View.VISIBLE);
        batterySettings.setVisibility(View.VISIBLE);
        autoRunSettings.setVisibility(View.VISIBLE);
        startMonitor.setVisibility(View.VISIBLE);
        saveRestart.setVisibility(View.VISIBLE);

        backend.setVisibility(View.VISIBLE);
        child.setVisibility(View.VISIBLE);
        permission.setVisibility(View.VISIBLE);
        test.setVisibility(View.VISIBLE);

        // Pairing / Recovery / Release are not setup items and stay hidden until CHILD ACTIVE.
        generatePairing.setVisibility(View.GONE);
        pairingInput.setVisibility(View.GONE);
        pairingCode.setVisibility(View.GONE);
        recoveryCode.setVisibility(View.GONE);
        restoreAccounts.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);
    }

    private void showCompletedChildUi(boolean owner, boolean paired) {
        // Locked rule: CHILD ACTIVE only after successful SAVE & RESTART.
        accounts.setVisibility(View.GONE);
        removeAccountsDone.setVisibility(View.GONE);
        deviceOwnerButton.setVisibility(View.GONE);
        backend.setVisibility(View.GONE);
        child.setVisibility(View.GONE);
        permission.setVisibility(View.GONE);
        batterySettings.setVisibility(View.GONE);
        autoRunSettings.setVisibility(View.GONE);
        startMonitor.setVisibility(View.GONE);
        saveRestart.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        restoreAccounts.setVisibility(View.VISIBLE);

        generatePairing.setVisibility(View.VISIBLE);
        pairingInput.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairingCode.setVisibility(View.VISIBLE);
        recoveryCode.setVisibility(paired ? View.VISIBLE : View.GONE);

        generatePairing.setText(paired ? "PAIRING COMPLETE ✓" : "8. PAIRING");
        generatePairing.setEnabled(owner && !paired);
        pairingInput.setEnabled(owner && !paired);

        String recovery = getSharedPreferences(CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "");
        if (paired && !recovery.isEmpty()) {
            recoveryCode.setText("9. RECOVERY CODE\n" + recovery + "\nKEEP THIS CODE SAFE");
        } else if (paired) {
            recoveryCode.setText("9. RECOVERY CODE — available");
        }

        pairingCode.setText(paired
                ? "PAIRING COMPLETE ✓ — Child linked to Parent"
                : "8. PAIRING — Enter the 6-digit code from PARENT");

        // Release Device Owner is strictly LAST and only becomes available after pairing + recovery code.
        releaseDeviceOwner.setVisibility(paired ? View.VISIBLE : View.GONE);
        releaseDeviceOwner.setText("10. RELEASE DEVICE OWNER — LAST");
        releaseDeviceOwner.setEnabled(owner && paired);

        status.setText(owner
                ? "CHILD ACTIVE ✓ — Bedtime Monitor active"
                : "ATTENTION — Device Owner protection is not active");
    }

    private void applyCompletedChildProtection(boolean owner) {
        if (!owner || dpm == null || admin == null) return;
        try { dpm.setUninstallBlocked(admin, getPackageName(), true); } catch (Exception ignored) {}
    }

    private void markAccountsDone() {
        getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
        Toast.makeText(this, "REMOVE SAVED ACCOUNTS marked complete.", Toast.LENGTH_SHORT).show();
        refreshSetupState();
    }

    private void showDeviceOwnerGuide() {
        if (isDeviceOwner()) {
            Toast.makeText(this, "Device Owner is ACTIVE ✓", Toast.LENGTH_SHORT).show();
            refreshSetupState();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("3. ACTIVE DEVICE OWNER")
                .setMessage("Connect the Child phone to the PC with ADB, then run:\n\n" +
                        "adb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\n" +
                        "After SUCCESS, return to this app. Do not release Device Owner during setup.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CHECK AGAIN", (d, w) -> refreshSetupState())
                .show();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {}
        }
    }

    private void showBatterySettingsGuide() {
        new AlertDialog.Builder(this)
                .setTitle("4. BATTERY — NO RESTRICTIONS")
                .setMessage("Set this app to Battery → Unrestricted / No restrictions.\n\nReturn here and tap BATTERY SETUP DONE after completing it.")
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("BATTERY SETUP DONE", (d, w) -> {
                    getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean(KEY_BATTERY_CONFIRMED, true).apply();
                    refreshSetupState();
                })
                .setPositiveButton("OPEN APP SETTINGS", (d, w) -> openAppBatterySettings())
                .show();
    }

    private void openAppBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {}
        }
    }

    private void showAutoRunGuide() {
        new AlertDialog.Builder(this)
                .setTitle("5. AUTO-RUN / BACKGROUND")
                .setMessage("On the Child phone, allow Auto-run / Background activity for this app if the device provides that setting.\n\nReturn here and tap AUTO-RUN SETUP DONE.")
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("AUTO-RUN SETUP DONE", (d, w) -> {
                    getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean(KEY_AUTORUN_CONFIRMED, true).apply();
                    refreshSetupState();
                })
                .setPositiveButton("OPEN APP SETTINGS", (d, w) -> openAppBatterySettings())
                .show();
    }

    private void startBedtimeMonitor() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "Complete ACTIVE DEVICE OWNER first.", Toast.LENGTH_LONG).show();
            showDeviceOwnerGuide();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_BATTERY_CONFIRMED, false)) {
            Toast.makeText(this, "Complete BATTERY — NO RESTRICTIONS first.", Toast.LENGTH_LONG).show();
            showBatterySettingsGuide();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_AUTORUN_CONFIRMED, false)) {
            Toast.makeText(this, "Complete AUTO-RUN / BACKGROUND first.", Toast.LENGTH_LONG).show();
            showAutoRunGuide();
            return;
        }

        String backendValue = normalizeBackend(backend.getText().toString());
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(CFG, MODE_PRIVATE).edit()
                .putString("backend", backendValue)
                .putString("child", childValue)
                .putBoolean(KEY_MONITOR_STARTED, true)
                .commit();
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); }
        catch (Exception ignored) {}
        refreshSetupState();
        Toast.makeText(this, "BEDTIME MONITOR STARTED ✓", Toast.LENGTH_SHORT).show();
    }

    private void saveAndRestart() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "ACTIVE DEVICE OWNER is required before SAVE & RESTART.", Toast.LENGTH_LONG).show();
            showDeviceOwnerGuide();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("accounts_confirmed", false)) {
            Toast.makeText(this, "Complete REMOVE SAVED ACCOUNTS first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_BATTERY_CONFIRMED, false)) {
            Toast.makeText(this, "Complete BATTERY — NO RESTRICTIONS first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_AUTORUN_CONFIRMED, false)) {
            Toast.makeText(this, "Complete AUTO-RUN / BACKGROUND first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_MONITOR_STARTED, false)) {
            Toast.makeText(this, "Tap START BEDTIME MONITOR first.", Toast.LENGTH_LONG).show();
            return;
        }

        String backendValue = normalizeBackend(backend.getText().toString());
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }

        boolean saved = getSharedPreferences(CFG, MODE_PRIVATE).edit()
                .putString("backend", backendValue)
                .putString("child", childValue)
                .putBoolean("setup_complete", true)
                .commit();
        if (!saved) {
            Toast.makeText(this, "Could not save Child setup. Try again.", Toast.LENGTH_LONG).show();
            return;
        }

        applyCompletedChildProtection(true);
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
        Toast.makeText(this, "CHILD ACTIVE ✓ — setup saved.", Toast.LENGTH_LONG).show();
        refreshSetupState();
        new android.os.Handler().postDelayed(this::recreate, 700);
    }

    private String ensureRecoveryPin() {
        String existing = getSharedPreferences(CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "");
        if (!existing.isEmpty()) return existing;
        SecureRandom random = new SecureRandom();
        String pin = String.valueOf(100000 + random.nextInt(900000));
        getSharedPreferences(CFG, MODE_PRIVATE).edit().putString(KEY_RECOVERY_PIN, pin).apply();
        return pin;
    }

    private void claimPairingCode() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "Device Owner is required.", Toast.LENGTH_LONG).show();
            return;
        }
        final String code = pairingInput.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            pairingInput.setError("Enter the 6-digit code from PARENT");
            return;
        }

        generatePairing.setEnabled(false);
        pairingInput.setEnabled(false);
        pairingCode.setText("PAIRING — connecting to Parent...");
        final String base = normalizeBackend(backend.getText().toString());

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + "/api/pairing/claim");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("pairCode", code);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int http = conn.getResponseCode();
                String response = readResponse(conn, http);
                JSONObject json = new JSONObject(response);
                if (http < 200 || http >= 300) throw new Exception(json.optString("error", "HTTP " + http));

                String childValue = json.getString("childId");
                String childToken = json.getString("childToken");
                String recovery = json.optString("recoveryPin", ensureRecoveryPin());

                getSharedPreferences(CFG, MODE_PRIVATE).edit()
                        .putString("backend", base)
                        .putString("child", childValue)
                        .putString("child_token", childToken)
                        .putString("pair_code", code)
                        .putString(KEY_RECOVERY_PIN, recovery)
                        .apply();

                runOnUiThread(() -> {
                    pairingCode.setText("PAIRING COMPLETE ✓ — Child linked to Parent");
                    pairingInput.setVisibility(View.GONE);
                    generatePairing.setText("PAIRING COMPLETE ✓");
                    generatePairing.setEnabled(false);
                    refreshSetupState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pairingCode.setText("Pairing failed: " + e.getMessage());
                    generatePairing.setEnabled(true);
                    pairingInput.setEnabled(true);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void testBedtime() {
        if (isDeviceOwner()) {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", true).apply();
            startActivity(new Intent(this, BedtimeLockActivity.class));
        } else if (Settings.canDrawOverlays(this)) {
            BedtimeOverlay.show(this);
        } else {
            Toast.makeText(this, "Device Owner is not active yet. Complete setup first.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReleaseDeviceOwner() {
        if (!isDeviceOwner()) return;
        boolean paired = !getSharedPreferences(CFG, MODE_PRIVATE).getString("child_token", "").isEmpty();
        if (!paired || getSharedPreferences(CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "").isEmpty()) {
            Toast.makeText(this, "Pairing and Recovery Code must be completed first.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("10. RELEASE DEVICE OWNER — LAST")
                .setMessage("This is the final step. It stops the monitor, removes the uninstall block, and releases Device Owner so the device can be uninstalled or provisioned again.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE", (d, w) -> showRecoveryPinPrompt())
                .show();
    }

    private void showRecoveryPinPrompt() {
        if (!isDeviceOwner()) return;
        final String expectedPin = ensureRecoveryPin();
        EditText input = new EditText(this);
        input.setHint("Recovery Code");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Parent/Admin recovery")
                .setMessage("Enter the Recovery Code shown after pairing.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RELEASE DEVICE OWNER", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!expectedPin.equals(input.getText().toString().trim())) {
                input.setError("Wrong recovery code");
                return;
            }
            dialog.dismiss();
            releaseDeviceOwnerForRecovery();
        }));
        dialog.show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForRecovery() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .putBoolean("last_active", false)
                    .putBoolean("setup_complete", false)
                    .putBoolean(KEY_MONITOR_STARTED, false)
                    .remove("child_token")
                    .remove("pair_code")
                    .apply();
            stopService(new Intent(this, BedtimeMonitorService.class));
            try { BedtimeOverlay.hide(this); } catch (Exception ignored) {}
            try {
                if (!BedtimeLockActivity.requestRemoteUnlock()) {
                    Intent unlock = new Intent(this, BedtimeLockActivity.class);
                    unlock.putExtra("bedtime_off", true);
                    startActivity(unlock);
                }
            } catch (Exception ignored) {}
            try { dpm.setUninstallBlocked(admin, getPackageName(), false); } catch (Exception ignored) {}
            dpm.clearDeviceOwnerApp(getPackageName());
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .remove(KEY_RECOVERY_PIN)
                    .remove(KEY_BATTERY_CONFIRMED)
                    .remove(KEY_AUTORUN_CONFIRMED)
                    .apply();
            Toast.makeText(this, "Managed protection released. Device Owner is no longer active.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this)
                .setTitle("1. ACCOUNT / SECURITY")
                .setMessage("Punta sa Account / Security at alisin ang saved accounts bago mag-provision. Siguraduhing alam ninyo ang login details; maaari silang ibalik pagkatapos maging CHILD ACTIVE.")
                .setNegativeButton("Hindi muna", null)
                .setPositiveButton("PUNTA SA ACCOUNTS", (d, w) -> openAccountsSettings())
                .show();
    }

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this)
                .setTitle("CHILD ACTIVE ✓")
                .setMessage("Setup complete. Maaari nang ibalik ang mga account na inalis kanina.")
                .setNegativeButton("Mamaya", null)
                .setPositiveButton("PUNTA SA ACCOUNTS", (d, w) -> openAccountsSettings())
                .show();
    }

    private void openAccountsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS));
        } catch (Exception ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {}
        }
    }
}
