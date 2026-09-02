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

    private EditText backend;
    private EditText child;
    private TextView status;
    private TextView setupStep;
    private TextView deviceOwnerStatus;
    private TextView deviceOwnerHelp;
    private TextView pairingCode;
    private Button accounts;
    private Button continueSetup;
    private Button permission;
    private Button generatePairing;
    private Button start;
    private Button batterySettings;
    private Button test;
    private Button restoreAccounts;
    private Button releaseDeviceOwner;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backend = findViewById(R.id.backendUrl);
        child = findViewById(R.id.childId);
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
        if (!savedPairCode.isEmpty()) pairingCode.setText("Pairing code: " + savedPairCode);

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            refreshSetupState();
        });
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        generatePairing.setOnClickListener(v -> generatePairingCode());
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

        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("account_reminder_seen", false)
            && !getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false)) showAccountPreparationReminder();

        refreshSetupState();
        autoStartConfiguredMonitor();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSetupState();
        autoStartConfiguredMonitor();
    }

    private void autoStartConfiguredMonitor() {
        String role = getSharedPreferences("app_role", MODE_PRIVATE).getString("role", "");
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        if (!"child".equals(role) || !setupComplete) return;
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
        boolean hadHttps = raw.toLowerCase().contains("https://");
        boolean hadHttp = raw.toLowerCase().contains("http://");
        while (raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")) {
            if (raw.toLowerCase().startsWith("https://")) raw = raw.substring(8);
            else raw = raw.substring(7);
        }
        if (raw.toLowerCase().endsWith(".workers.dev") || raw.toLowerCase().contains(".workers.dev/")) return "https://" + raw;
        if (hadHttps) return "https://" + raw;
        if (hadHttp) return "http://" + raw;
        return "https://" + raw;
    }

    private void refreshSetupState() {
        boolean accountsConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();
        boolean hasChildToken = !getSharedPreferences(CFG, MODE_PRIVATE).getString("child_token", "").isEmpty();

        if (!accountsConfirmed) {
            setupStep.setText("STEP 1 OF 6 — Remove saved accounts");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Pagkatapos alisin ang accounts, bumalik dito at pindutin ang TAPOS NA — CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 OF 6 — Activate Device Owner");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta ang phone sa PC at patakbuhin:\n\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\nPag success, bumalik sa app.");
        } else if (!hasChildToken) {
            setupStep.setText("STEP 3 OF 6 — Generate pairing code");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Generate a pairing code, then enter that 6-digit code in the PARENT app. The Parent app will automatically receive and store the recovery code.");
        } else if (!setupComplete) {
            setupStep.setText("STEP 4 OF 6 — Start monitor");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Pairing credential created. After the Parent enters the code, start the Bedtime monitor and finish Battery / Background settings.");
        } else {
            setupStep.setText("SETUP COMPLETE — Child device protected");
            deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Managed protection needs attention");
            deviceOwnerHelp.setText(owner
                ? "Bedtime Child is configured. Setup controls are hidden and normal uninstall is blocked. Remote Bedtime monitoring stays active."
                : "Device Owner is no longer active. Parent/admin maintenance is required.");
        }

        if (setupComplete) {
            applyCompletedChildProtection(owner);
            showCompletedChildUi(owner);
            return;
        }

        accounts.setVisibility(View.VISIBLE);
        continueSetup.setVisibility(View.VISIBLE);
        backend.setVisibility(View.VISIBLE);
        child.setVisibility(View.VISIBLE);
        permission.setVisibility(owner ? View.GONE : View.VISIBLE);
        generatePairing.setVisibility(owner ? View.VISIBLE : View.GONE);
        pairingCode.setVisibility(owner ? View.VISIBLE : View.GONE);
        start.setVisibility(View.VISIBLE);
        batterySettings.setVisibility(View.VISIBLE);
        restoreAccounts.setVisibility(View.GONE);
        test.setVisibility(View.VISIBLE);
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);

        generatePairing.setEnabled(accountsConfirmed && owner);
        start.setEnabled(accountsConfirmed && (owner ? hasChildToken : Settings.canDrawOverlays(this)));
        batterySettings.setEnabled(accountsConfirmed);
        test.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
    }

    private void showCompletedChildUi(boolean owner) {
        accounts.setVisibility(View.GONE);
        continueSetup.setVisibility(View.GONE);
        backend.setVisibility(View.GONE);
        child.setVisibility(View.GONE);
        permission.setVisibility(View.GONE);
        generatePairing.setVisibility(View.GONE);
        pairingCode.setVisibility(View.GONE);
        start.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);
        batterySettings.setVisibility(View.VISIBLE);
        batterySettings.setEnabled(true);
        batterySettings.setText("BATTERY / BACKGROUND SETTINGS");
        restoreAccounts.setVisibility(View.VISIBLE);
        restoreAccounts.setText("ACCOUNTS / DEVICE SETTINGS");
        status.setText(owner ? "PROTECTED — Remote Bedtime Monitor active" : "ATTENTION — Device Owner protection is not active");
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

    private void generatePairingCode() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "Activate Device Owner first.", Toast.LENGTH_LONG).show();
            return;
        }
        final String base = normalizeBackend(backend.getText().toString());
        final String childValue = child.getText().toString().trim();
        if (base.isEmpty() || childValue.isEmpty()) return;

        generatePairing.setEnabled(false);
        pairingCode.setText("Generating secure pairing code...");
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + "/api/pairing/start");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                JSONObject body = new JSONObject();
                body.put("childId", childValue);
                body.put("recoveryPin", ensureRecoveryPin());
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String response = readResponse(conn, code);
                JSONObject json = new JSONObject(response);
                if (code < 200 || code >= 300) throw new Exception(json.optString("error", "HTTP " + code));

                String pair = json.getString("pairCode");
                String childToken = json.getString("childToken");
                getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .putString("backend", base)
                    .putString("child", childValue)
                    .putString("child_token", childToken)
                    .putString("pair_code", pair)
                    .apply();

                runOnUiThread(() -> {
                    pairingCode.setText("PAIRING CODE: " + pair + "\nEnter this in the PARENT app.");
                    generatePairing.setEnabled(true);
                    refreshSetupState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pairingCode.setText("Pairing failed: " + e.getMessage());
                    generatePairing.setEnabled(true);
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

    private void showRecoveryPinPrompt() {
        if (!isDeviceOwner()) return;
        final String expectedPin = ensureRecoveryPin();
        EditText input = new EditText(this);
        input.setHint("6-digit parent recovery code");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Parent/Admin recovery")
            .setMessage("Enter the recovery code shown in the PARENT dashboard.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONTINUE", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!expectedPin.equals(input.getText().toString().trim())) {
                input.setError("Wrong recovery code");
                return;
            }
            dialog.dismiss();
            confirmParentRecoveryRelease();
        }));
        dialog.show();
    }

    private void confirmParentRecoveryRelease() {
        new AlertDialog.Builder(this)
            .setTitle("Release CHILD device?")
            .setMessage("This will stop the monitor, remove the uninstall block, and release Device Owner so the app can be uninstalled or provisioned again.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RELEASE DEVICE", (dialog, which) -> releaseDeviceOwnerForRecovery())
            .show();
    }

    private void startMonitor() {
        boolean owner = isDeviceOwner();
        if (owner && getSharedPreferences(CFG, MODE_PRIVATE).getString("child_token", "").isEmpty()) {
            Toast.makeText(this, "Generate the pairing code first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!owner && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps first for fallback mode.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        String backendValue = normalizeBackend(backend.getText().toString());
        String childValue = child.getText().toString().trim();
        if (backendValue.isEmpty() || childValue.isEmpty()) return;
        getSharedPreferences(CFG, MODE_PRIVATE).edit()
            .putString("backend", backendValue)
            .putString("child", childValue)
            .putBoolean("setup_complete", true)
            .apply();
        startForegroundService(new Intent(this, BedtimeMonitorService.class));
        applyCompletedChildProtection(owner);
        refreshSetupState();
        Toast.makeText(this, "Child setup complete. Bedtime protection is active.", Toast.LENGTH_LONG).show();
        showRestoreAccountsReminder();
    }

    private void showBatterySettingsGuide() {
        new AlertDialog.Builder(this)
            .setTitle("Battery / Background Settings")
            .setMessage("Choose Unrestricted / No restrictions / Allow background activity / Don't optimize if available.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("OPEN APP SETTINGS", (dialog, which) -> openAppBatterySettings())
            .show();
    }

    private void openAppBatterySettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception ignored) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {} }
    }

    private void testBedtime() {
        if (isDeviceOwner()) {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", true).apply();
            startActivity(new Intent(this, BedtimeLockActivity.class));
            return;
        }
        if (Settings.canDrawOverlays(this)) BedtimeOverlay.show(this);
    }

    private void confirmReleaseDeviceOwner() {
        if (!isDeviceOwner()) return;
        new AlertDialog.Builder(this)
            .setTitle("TEST ONLY — Release Device Owner?")
            .setMessage("Use only while setup/testing.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RELEASE DEVICE OWNER", (dialog, which) -> releaseDeviceOwnerForRecovery())
            .show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForRecovery() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", false).putBoolean("setup_complete", false).apply();
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
                .remove("child_token")
                .remove("pair_code")
                .apply();
            Toast.makeText(this, "Managed protection released. The app can now be uninstalled.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this)
            .setTitle("Bago tayo magsimula")
            .setMessage("Alisin muna ang saved accounts bago Device Owner setup. Siguraduhing alam ninyo ang login details; maaari silang ibalik pagkatapos.")
            .setNegativeButton("Hindi muna", null)
            .setPositiveButton("PUNTA SA ACCOUNTS", (dialog, which) -> {
                getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("account_reminder_seen", true).apply();
                openAccountsSettings();
            }).show();
    }

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this)
            .setTitle("Setup complete")
            .setMessage("Maaari na ninyong ibalik ang mga account na inalis kanina.")
            .setNegativeButton("Mamaya", null)
            .setPositiveButton("PUNTA SA ACCOUNTS", (dialog, which) -> openAccountsSettings())
            .show();
    }

    private void openAccountsSettings() {
        try { startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS)); }
        catch (Exception ignored) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored2) {} }
    }
}
