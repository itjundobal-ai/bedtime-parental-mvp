package com.master.bedtime.child;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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

    private EditText backend, child, pairingInput;
    private TextView status, setupStep, deviceOwnerStatus, deviceOwnerHelp, pairingCode;
    private Button accounts, continueSetup, permission, generatePairing, start, batterySettings, test, restoreAccounts, releaseDeviceOwner;
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
        accounts = findViewById(R.id.btnAccountsSecurity);
        continueSetup = findViewById(R.id.btnContinueSetup);
        permission = findViewById(R.id.btnOverlayPermission);
        generatePairing = findViewById(R.id.btnGeneratePairingCode);
        start = findViewById(R.id.btnStartMonitor);
        batterySettings = findViewById(R.id.btnBatterySettings);
        restoreAccounts = findViewById(R.id.btnRestoreAccounts);
        test = findViewById(R.id.btnTestOverlay);
        releaseDeviceOwner = findViewById(R.id.btnReleaseDeviceOwner);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, BedtimeDeviceAdminReceiver.class);

        String savedBackend = getSharedPreferences(CFG, MODE_PRIVATE).getString("backend", "https://bedtime-parental-api.itjundobal.workers.dev");
        String normalizedBackend = normalizeBackend(savedBackend);
        backend.setText(normalizedBackend);
        if (!normalizedBackend.equals(savedBackend)) getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("backend", normalizedBackend).apply();

        String savedChild = getSharedPreferences(CFG, MODE_PRIVATE).getString("child", "");
        if (savedChild.isEmpty()) {
            savedChild = "child-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("child", savedChild).apply();
        }
        child.setText(savedChild);
        String savedPairCode = getSharedPreferences(CFG, MODE_PRIVATE).getString("pair_code", "");
        if (!savedPairCode.isEmpty()) pairingCode.setText("PAIRED ✓ — code was used");

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            refreshSetupState();
        });
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        generatePairing.setOnClickListener(v -> claimPairingCode());
        start.setOnClickListener(v -> startMonitor());
        batterySettings.setOnClickListener(v -> showBatterySettingsGuide());
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
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false)) return;
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
    }

    private boolean isDeviceOwner() { return dpm != null && dpm.isDeviceOwnerApp(getPackageName()); }

    private String normalizeBackend(String value) {
        if (value == null) return "";
        String raw = value.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) return "";
        boolean https = raw.toLowerCase().startsWith("https://");
        boolean http = raw.toLowerCase().startsWith("http://");
        while (raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")) raw = raw.substring(raw.toLowerCase().startsWith("https://") ? 8 : 7);
        if (raw.toLowerCase().endsWith(".workers.dev") || raw.toLowerCase().contains(".workers.dev/")) return "https://" + raw;
        if (https) return "https://" + raw;
        if (http) return "http://" + raw;
        return "https://" + raw;
    }

    private void refreshSetupState() {
        boolean accountsConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean batteryConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_BATTERY_CONFIRMED, false);
        boolean owner = isDeviceOwner();
        boolean paired = !getSharedPreferences(CFG, MODE_PRIVATE).getString("child_token", "").isEmpty();

        if (!setupComplete) {
            if (!accountsConfirmed) {
                setupStep.setText("STEP 1 — ACCOUNT / SECURITY");
                deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
                deviceOwnerHelp.setText("Punta sa Account / Security, alisin ang saved accounts, then balik dito at TAPOS NA.");
            } else if (!owner) {
                setupStep.setText("STEP 2 — DEVICE OWNER");
                deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
                deviceOwnerHelp.setText("Run:\n\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\nPag success, balik sa app.");
            } else if (!batteryConfirmed) {
                setupStep.setText("STEP 3 — BATTERY / BACKGROUND");
                deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
                deviceOwnerHelp.setText("Set Battery to Unrestricted / No restrictions and allow Auto-run / Background activity. Then confirm this step.");
            } else {
                setupStep.setText("STEP 4 — START BEDTIME MONITOR");
                deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
                deviceOwnerHelp.setText("Battery/background confirmed. Tap SAVE & RESTART. Pairing will NOT appear until CHILD ACTIVE.");
            }
            showSetupUi(accountsConfirmed, owner, batteryConfirmed);
            return;
        }

        setupStep.setText("CHILD ACTIVE ✓");
        deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Managed protection needs attention");
        deviceOwnerHelp.setText(owner ? "Setup complete. Pairing is now available. Parent generates the 6-digit code; enter it below." : "Device Owner is no longer active.");
        applyCompletedChildProtection(owner);
        showCompletedChildUi(owner, paired);
    }

    private void showSetupUi(boolean accountsConfirmed, boolean owner, boolean batteryConfirmed) {
        accounts.setVisibility(View.VISIBLE);
        continueSetup.setVisibility(View.VISIBLE);
        backend.setVisibility(View.VISIBLE);
        child.setVisibility(View.VISIBLE);
        permission.setVisibility(owner ? View.GONE : View.VISIBLE);
        generatePairing.setVisibility(View.GONE);
        pairingInput.setVisibility(View.GONE);
        pairingCode.setVisibility(View.GONE);
        start.setVisibility(View.VISIBLE);
        batterySettings.setVisibility(View.VISIBLE);
        restoreAccounts.setVisibility(View.GONE);
        test.setVisibility(View.VISIBLE);
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);
        batterySettings.setEnabled(accountsConfirmed && owner);
        batterySettings.setText(batteryConfirmed ? "BATTERY / BACKGROUND SETTINGS ✓" : "BATTERY / BACKGROUND SETTINGS — OPEN SETTINGS");
        start.setEnabled(accountsConfirmed && owner && batteryConfirmed);
        test.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
        start.setText("SAVE & RESTART");
    }

    private void showCompletedChildUi(boolean owner, boolean paired) {
        accounts.setVisibility(View.GONE);
        continueSetup.setVisibility(View.GONE);
        backend.setVisibility(View.GONE);
        child.setVisibility(View.GONE);
        permission.setVisibility(View.GONE);
        start.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);
        batterySettings.setVisibility(View.GONE);
        restoreAccounts.setVisibility(View.VISIBLE);
        restoreAccounts.setText("ACCOUNTS / DEVICE SETTINGS");
        generatePairing.setVisibility(View.VISIBLE);
        pairingInput.setVisibility(View.VISIBLE);
        pairingCode.setVisibility(View.VISIBLE);
        generatePairing.setText(paired ? "PAIRING COMPLETE ✓" : "PAIR CHILD DEVICE");
        generatePairing.setEnabled(owner && !paired);
        pairingInput.setEnabled(owner && !paired);
        pairingInput.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairingCode.setText(paired ? "PAIRING COMPLETE ✓ — Child linked to Parent" : "PAIRING SECTION — Enter the 6-digit code from PARENT");
        status.setText(owner ? "CHILD ACTIVE ✓ — Bedtime Monitor active" : "ATTENTION — Device Owner protection is not active");
    }

    private void applyCompletedChildProtection(boolean owner) {
        if (!owner || dpm == null || admin == null) return;
        try { dpm.setUninstallBlocked(admin, getPackageName(), true); } catch (Exception ignored) {}
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
        if (!isDeviceOwner()) { Toast.makeText(this, "Device Owner is required.", Toast.LENGTH_LONG).show(); return; }
        final String code = pairingInput.getText().toString().trim();
        if (!code.matches("\\d{6}")) { pairingInput.setError("Enter the 6-digit code from PARENT"); return; }
        generatePairing.setEnabled(false);
        pairingInput.setEnabled(false);
        pairingCode.setText("PAIRING — connecting to Parent...");
        final String base = normalizeBackend(backend.getText().toString());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + "/api/pairing/claim");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000); conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.setRequestProperty("Accept", "application/json");
                JSONObject body = new JSONObject(); body.put("pairCode", code);
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int http = conn.getResponseCode();
                String response = readResponse(conn, http);
                JSONObject json = new JSONObject(response);
                if (http < 200 || http >= 300) throw new Exception(json.optString("error", "HTTP " + http));
                String childValue = json.getString("childId");
                String childToken = json.getString("childToken");
                String recovery = json.optString("recoveryPin", ensureRecoveryPin());
                getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("backend", base).putString("child", childValue).putString("child_token", childToken).putString("pair_code", code).putString(KEY_RECOVERY_PIN, recovery).apply();
                runOnUiThread(() -> { pairingCode.setText("PAIRING COMPLETE ✓ — Child linked to Parent"); pairingInput.setVisibility(View.GONE); generatePairing.setText("PAIRING COMPLETE ✓"); generatePairing.setEnabled(false); refreshSetupState(); });
            } catch (Exception e) {
                runOnUiThread(() -> { pairingCode.setText("Pairing failed: " + e.getMessage()); generatePairing.setEnabled(true); pairingInput.setEnabled(true); });
            } finally { if (conn != null) conn.disconnect(); }
        }).start();
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) sb.append(line); }
        return sb.toString();
    }

    private void startMonitor() {
        if (!isDeviceOwner()) { Toast.makeText(this, "Activate Device Owner first.", Toast.LENGTH_LONG).show(); return; }
        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_BATTERY_CONFIRMED, false)) { Toast.makeText(this, "Complete BATTERY / BACKGROUND SETTINGS first.", Toast.LENGTH_LONG).show(); showBatterySettingsGuide(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        String backendValue = normalizeBackend(backend.getText().toString());
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) return;
        getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("backend", backendValue).putString("child", childValue).putBoolean("setup_complete", true).apply();
        try { startForegroundService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
        applyCompletedChildProtection(true);
        refreshSetupState();
        Toast.makeText(this, "CHILD ACTIVE ✓ — setup saved.", Toast.LENGTH_LONG).show();
        new android.os.Handler().postDelayed(() -> { finish(); startActivity(new Intent(this, MainActivity.class)); }, 350);
    }

    private void showBatterySettingsGuide() {
        new AlertDialog.Builder(this).setTitle("Battery / Background Settings")
            .setMessage("1) Open App Settings.\n2) Battery → Unrestricted / No restrictions.\n3) Allow Auto-run / Background activity if the phone provides it.\n4) Return here and tap BATTERY SETUP DONE.\n\nPairing remains hidden until CHILD ACTIVE.")
            .setNegativeButton("CANCEL", null)
            .setNeutralButton("BATTERY SETUP DONE", (d,w) -> { getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean(KEY_BATTERY_CONFIRMED, true).apply(); refreshSetupState(); })
            .setPositiveButton("OPEN APP SETTINGS", (d,w) -> openAppBatterySettings()).show();
    }

    private void openAppBatterySettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception ignored) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {} }
    }

    private void testBedtime() {
        if (isDeviceOwner()) { getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", true).apply(); startActivity(new Intent(this, BedtimeLockActivity.class)); }
        else if (Settings.canDrawOverlays(this)) BedtimeOverlay.show(this);
    }

    private void confirmReleaseDeviceOwner() {
        if (!isDeviceOwner()) return;
        new AlertDialog.Builder(this).setTitle("TEST ONLY — Release Device Owner?").setMessage("Use only while setup/testing.").setNegativeButton("CANCEL", null).setPositiveButton("RELEASE DEVICE OWNER", (d,w) -> releaseDeviceOwnerForRecovery()).show();
    }

    private void showRecoveryPinPrompt() {
        if (!isDeviceOwner()) return;
        final String expectedPin = ensureRecoveryPin();
        EditText input = new EditText(this); input.setHint("6-digit parent recovery code"); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Parent/Admin recovery").setMessage("Enter the recovery code shown in the PARENT dashboard.").setView(input).setNegativeButton("CANCEL", null).setPositiveButton("CONTINUE", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { if (!expectedPin.equals(input.getText().toString().trim())) { input.setError("Wrong recovery code"); return; } dialog.dismiss(); confirmParentRecoveryRelease(); }));
        dialog.show();
    }

    private void confirmParentRecoveryRelease() {
        new AlertDialog.Builder(this).setTitle("Release CHILD device?").setMessage("This will stop the monitor, remove the uninstall block, and release Device Owner so the app can be uninstalled or provisioned again.").setNegativeButton("CANCEL", null).setPositiveButton("RELEASE DEVICE", (d,w) -> releaseDeviceOwnerForRecovery()).show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForRecovery() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", false).putBoolean("setup_complete", false).remove("child_token").remove("pair_code").apply();
            stopService(new Intent(this, BedtimeMonitorService.class));
            try { BedtimeOverlay.hide(this); } catch (Exception ignored) {}
            try { if (!BedtimeLockActivity.requestRemoteUnlock()) { Intent unlock = new Intent(this, BedtimeLockActivity.class); unlock.putExtra("bedtime_off", true); startActivity(unlock); } } catch (Exception ignored) {}
            try { dpm.setUninstallBlocked(admin, getPackageName(), false); } catch (Exception ignored) {}
            dpm.clearDeviceOwnerApp(getPackageName());
            getSharedPreferences(CFG, MODE_PRIVATE).edit().remove(KEY_RECOVERY_PIN).remove(KEY_BATTERY_CONFIRMED).apply();
            Toast.makeText(this, "Managed protection released. The app can now be uninstalled.", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
        refreshSetupState();
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this).setTitle("Bago tayo magsimula").setMessage("Punta muna sa Account / Security at alisin ang saved accounts. Siguraduhing alam ninyo ang login details; maaari silang ibalik pagkatapos.").setNegativeButton("Hindi muna", null).setPositiveButton("PUNTA SA ACCOUNTS", (d,w) -> { getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("account_reminder_seen", true).apply(); openAccountsSettings(); }).show();
    }

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this).setTitle("CHILD ACTIVE ✓").setMessage("Setup complete. Maaari nang ibalik ang mga account na inalis kanina.").setNegativeButton("Mamaya", null).setPositiveButton("PUNTA SA ACCOUNTS", (d,w) -> openAccountsSettings()).show();
    }

    private void openAccountsSettings() {
        try { startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS)); } catch (Exception ignored) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {} }
    }
}
