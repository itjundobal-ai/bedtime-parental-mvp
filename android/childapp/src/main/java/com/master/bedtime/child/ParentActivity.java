package com.master.bedtime.child;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

public class ParentActivity extends Activity {
    private static final String DEFAULT_BACKEND = "https://bedtime-parental-api.itjundobal.workers.dev";
    private static final String PREFS = "parent_cfg";
    private static final String CHILD_CFG = "cfg";
    private static final String KEY_RECOVERY_PIN = "parent_recovery_pin";
    private static final int MAX_COMMAND_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700L;

    private EditText pairCode;
    private EditText childId;
    private TextView recoveryCode;
    private TextView status;
    private TextView legacyOwnerWarning;
    private TextView generatedCode;
    private Button pairButton;
    private Button onButton;
    private Button offButton;
    private Button refreshButton;
    private Button releaseOldChildProtection;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);

        pairCode = findViewById(R.id.parentPairCode);
        childId = findViewById(R.id.parentChildId);
        recoveryCode = findViewById(R.id.parentRecoveryCode);
        status = findViewById(R.id.parentStatus);
        legacyOwnerWarning = findViewById(R.id.legacyOwnerWarning);
        generatedCode = findViewById(R.id.parentGeneratedCode);
        pairButton = findViewById(R.id.btnPairChild);
        onButton = findViewById(R.id.btnBedtimeOn);
        offButton = findViewById(R.id.btnBedtimeOff);
        refreshButton = findViewById(R.id.btnRefreshState);
        releaseOldChildProtection = findViewById(R.id.btnReleaseOldChildProtection);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, BedtimeDeviceAdminReceiver.class);

        loadPairedChild();
        pairButton.setOnClickListener(v -> generatePairingCode());
        onButton.setOnClickListener(v -> sendBedtime(true));
        offButton.setOnClickListener(v -> sendBedtime(false));
        refreshButton.setOnClickListener(v -> refreshState());
        releaseOldChildProtection.setOnClickListener(v -> showOldChildRecoveryPrompt());

        refreshDeviceOwnerRecoveryState();
        if (hasPairedChild()) refreshState(); else setControlsForPairing(false);
    }

    @Override protected void onResume() { super.onResume(); refreshDeviceOwnerRecoveryState(); }

    private void loadPairedChild() {
        String child = getSharedPreferences(PREFS, MODE_PRIVATE).getString("child", "");
        String recovery = getSharedPreferences(PREFS, MODE_PRIVATE).getString("recovery_pin", "");
        childId.setText(child);
        recoveryCode.setText(recovery.isEmpty() ? "Recovery code: not paired yet" : "Recovery code: " + recovery);
    }

    private boolean hasPairedChild() {
        return !getSharedPreferences(PREFS, MODE_PRIVATE).getString("parent_token", "").isEmpty()
            && !getSharedPreferences(PREFS, MODE_PRIVATE).getString("child", "").isEmpty();
    }

    private boolean isDeviceOwner() { return dpm != null && dpm.isDeviceOwnerApp(getPackageName()); }

    private void refreshDeviceOwnerRecoveryState() {
        boolean staleOwner = isDeviceOwner();
        legacyOwnerWarning.setVisibility(staleOwner ? View.VISIBLE : View.GONE);
        releaseOldChildProtection.setVisibility(staleOwner ? View.VISIBLE : View.GONE);
    }

    private void showOldChildRecoveryPrompt() {
        if (!isDeviceOwner()) { refreshDeviceOwnerRecoveryState(); return; }
        final String expectedPin = getSharedPreferences(CHILD_CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "");
        if (expectedPin.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Old CHILD protection detected")
                .setMessage("The local recovery code for this old CHILD setup is missing. A guarded maintenance release is available only for this PARENT-role stale Device Owner case.")
                .setNegativeButton("CANCEL", null).setPositiveButton("MAINTENANCE RELEASE", (d,w) -> showMaintenanceReleasePrompt()).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("6-digit recovery code");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Release old CHILD protection")
            .setMessage("Enter the recovery code that belonged to this phone when it was configured as CHILD.")
            .setView(input).setNegativeButton("CANCEL", null).setPositiveButton("CONTINUE", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!expectedPin.equals(input.getText().toString().trim())) { input.setError("Wrong recovery code"); return; }
            dialog.dismiss(); confirmReleaseOldChildProtection();
        }));
        dialog.show();
    }

    private void showMaintenanceReleasePrompt() {
        String role = getSharedPreferences("app_role", MODE_PRIVATE).getString("role", "");
        String recoveryPin = getSharedPreferences(CHILD_CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "");
        if (!"parent".equals(role) || !isDeviceOwner() || !recoveryPin.isEmpty()) {
            Toast.makeText(this, "Maintenance release is not available for this state.", Toast.LENGTH_LONG).show(); return;
        }
        EditText input = new EditText(this);
        input.setHint("Type RELEASE"); input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Maintenance release")
            .setMessage("Use this only to clean up an old CHILD Device Owner state after this phone has already been switched to PARENT role. Type RELEASE to continue.")
            .setView(input).setNegativeButton("CANCEL", null).setPositiveButton("CONTINUE", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"RELEASE".equals(input.getText().toString().trim().toUpperCase())) { input.setError("Type RELEASE exactly"); return; }
            dialog.dismiss(); confirmReleaseOldChildProtection();
        }));
        dialog.show();
    }

    private void confirmReleaseOldChildProtection() {
        new AlertDialog.Builder(this).setTitle("Release Device Owner?")
            .setMessage("This will stop old CHILD protection, remove the uninstall block, and release Device Owner on this phone. PARENT data stays on this phone unless you uninstall the app afterward.")
            .setNegativeButton("CANCEL", null).setPositiveButton("RELEASE", (d,w) -> releaseOldChildProtection()).show();
    }

    @SuppressWarnings("deprecation")
    private void releaseOldChildProtection() {
        if (!isDeviceOwner()) return;
        try {
            getSharedPreferences(CHILD_CFG, MODE_PRIVATE).edit().putBoolean("last_active", false).putBoolean("setup_complete", false).apply();
            stopService(new Intent(this, BedtimeMonitorService.class));
            try { BedtimeOverlay.hide(this); } catch (Exception ignored) {}
            try { if (!BedtimeLockActivity.requestRemoteUnlock()) { Intent unlock = new Intent(this, BedtimeLockActivity.class); unlock.putExtra("bedtime_off", true); startActivity(unlock); } } catch (Exception ignored) {}
            try { dpm.setUninstallBlocked(admin, getPackageName(), false); } catch (Exception ignored) {}
            dpm.clearDeviceOwnerApp(getPackageName());
            getSharedPreferences(CHILD_CFG, MODE_PRIVATE).edit().remove(KEY_RECOVERY_PIN).remove("child_token").remove("pair_code").remove("legacy_pairing_migration").apply();
            Toast.makeText(this, "Old CHILD protection released. This app can now be uninstalled normally.", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
        refreshDeviceOwnerRecoveryState();
    }

    private void setControlsForPairing(boolean busy) {
        pairButton.setEnabled(!busy);
        childId.setEnabled(!busy);
        pairCode.setVisibility(View.GONE);
        boolean paired = hasPairedChild() && !busy;
        onButton.setEnabled(paired); offButton.setEnabled(paired); refreshButton.setEnabled(paired);
    }

    private String ensureParentRecoveryPin() {
        String existing = getSharedPreferences(PREFS, MODE_PRIVATE).getString("recovery_pin", "");
        if (!existing.isEmpty()) return existing;
        SecureRandom random = new SecureRandom();
        String pin = String.valueOf(100000 + random.nextInt(900000));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("recovery_pin", pin).apply();
        return pin;
    }

    private void generatePairingCode() {
        final String child = childId.getText().toString().trim();
        if (!child.matches("[A-Za-z0-9._-]{1,80}")) { childId.setError("Enter a valid Child ID"); return; }
        setControlsForPairing(true);
        status.setText("Generating secure pairing code...");
        generatedCode.setText("PAIRING CODE: generating...");
        final String recovery = ensureParentRecoveryPin();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(DEFAULT_BACKEND + "/api/pairing/start-parent");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000); conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.setRequestProperty("Accept", "application/json");
                JSONObject body = new JSONObject(); body.put("childId", child); body.put("recoveryPin", recovery);
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int http = conn.getResponseCode();
                String response = readAnyResponse(conn, http);
                JSONObject json = new JSONObject(response);
                if (http < 200 || http >= 300) throw new Exception(json.optString("error", "HTTP " + http));
                String pair = json.getString("pairCode");
                String token = json.getString("parentToken");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("child", child).putString("parent_token", token).putString("recovery_pin", recovery).apply();
                main.post(() -> {
                    generatedCode.setText("PAIRING CODE: " + pair + "\nGive this code to the CHILD phone.");
                    status.setText("CODE READY ✓ — Enter it on CHILD");
                    pairButton.setEnabled(true); childId.setEnabled(true);
                    onButton.setEnabled(false); offButton.setEnabled(false); refreshButton.setEnabled(false);
                });
            } catch (Exception e) {
                main.post(() -> { generatedCode.setText("Pairing failed: " + e.getMessage()); status.setText("Pairing code generation failed"); setControlsForPairing(false); });
            } finally { if (conn != null) conn.disconnect(); }
        }).start();
    }

    private String selectedChild() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString("child", ""); }
    private String parentToken() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString("parent_token", ""); }

    private void setBusy(boolean busy) {
        pairButton.setEnabled(!busy); childId.setEnabled(!busy); onButton.setEnabled(!busy); offButton.setEnabled(!busy); refreshButton.setEnabled(!busy);
    }

    private void sendBedtime(boolean active) {
        if (!hasPairedChild()) { Toast.makeText(this, "Generate a pairing code and pair the CHILD first.", Toast.LENGTH_LONG).show(); return; }
        final String child = selectedChild(); setBusy(true); status.setText(active ? "Sending BEDTIME ON..." : "Sending BEDTIME OFF...");
        new Thread(() -> {
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_COMMAND_ATTEMPTS; attempt++) {
                try {
                    postState(child, active); boolean verified = getState(child);
                    if (verified == active) { main.post(() -> { status.setText(active ? "BEDTIME ON ✓" : "BEDTIME OFF ✓"); setBusy(false); }); return; }
                    lastError = new Exception("State verification mismatch");
                } catch (Exception e) { lastError = e; }
                if (attempt < MAX_COMMAND_ATTEMPTS) try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            final String message = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "Unable to verify command";
            main.post(() -> { status.setText("Command failed: " + message); setBusy(false); });
        }).start();
    }

    private void postState(String child, boolean active) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime"); conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000); conn.setDoOutput(true); conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("x-parent-token", parentToken());
            JSONObject body = new JSONObject(); body.put("active", active);
            try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode(); if (code < 200 || code >= 300) throw new Exception("POST HTTP " + code + ": " + readAnyResponse(conn, code));
        } finally { if (conn != null) conn.disconnect(); }
    }

    private boolean getState(String child) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime?verify=" + System.currentTimeMillis()); conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000); conn.setUseCaches(false); conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("Cache-Control", "no-cache"); conn.setRequestProperty("x-parent-token", parentToken());
            int code = conn.getResponseCode(); String response = readAnyResponse(conn, code); if (code < 200 || code >= 300) throw new Exception("GET HTTP " + code); return new JSONObject(response).optBoolean("active", false);
        } finally { if (conn != null) conn.disconnect(); }
    }

    private void refreshState() {
        if (!hasPairedChild()) { status.setText("Generate a pairing code to begin"); setControlsForPairing(false); return; }
        final String child = selectedChild(); setBusy(true); status.setText("Checking state...");
        new Thread(() -> {
            try { boolean state = getState(child); main.post(() -> { status.setText(state ? "BEDTIME ON" : "BEDTIME OFF"); setBusy(false); }); }
            catch (Exception e) { main.post(() -> { status.setText("Unable to read state: " + e.getMessage()); setBusy(false); }); }
        }).start();
    }

    private String readAnyResponse(HttpURLConnection conn, int code) throws Exception {
        java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(); if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) sb.append(line); }
        return sb.toString();
    }
}
