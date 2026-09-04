package com.master.bedtime.child;

import com.master.bedtime.parent.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final long FINAL_STATUS_REFRESH_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText backend;
    private EditText child;
    private TextView title;
    private TextView setupStep;
    private TextView setupHelp;
    private TextView deviceOwnerStatus;
    private TextView deviceOwnerHelp;
    private TextView status;
    private TextView pairingCode;
    private TextView pairingStatus;
    private TextView recoveryCode;
    private TextView bedtimeStatus;
    private LinearLayout finalChildPanel;

    private Button accounts;
    private Button continueSetup;
    private Button activeDeviceOwner;
    private Button permission;
    private Button start;
    private Button battery;
    private Button autoRun;
    private Button saveRestart;
    private Button restoreAccounts;
    private Button test;
    private Button releaseDeviceOwner;

    private DevicePolicyManager dpm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.child_activity_main);

        title = findViewById(R.id.title);
        setupStep = findViewById(R.id.setupStep);
        setupHelp = findViewById(R.id.setupHelp);
        deviceOwnerStatus = findViewById(R.id.deviceOwnerStatus);
        deviceOwnerHelp = findViewById(R.id.deviceOwnerHelp);
        status = findViewById(R.id.status);
        backend = findViewById(R.id.backendUrl);
        child = findViewById(R.id.childId);
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
        finalChildPanel = findViewById(R.id.finalChildPanel);
        pairingCode = findViewById(R.id.pairingCode);
        pairingStatus = findViewById(R.id.pairingStatus);
        recoveryCode = findViewById(R.id.recoveryCode);
        bedtimeStatus = findViewById(R.id.bedtimeStatus);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);

        String savedBackend = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("backend", "https://bedtime-parental-api.itjundobal.workers.dev");
        String normalizedBackend = normalizeBackend(savedBackend);
        backend.setText(normalizedBackend);
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("backend", normalizedBackend).apply();
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
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

        refreshSetupState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetupState();
        startFinalStatusRefresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(finalStatusRefresh);
        super.onPause();
    }

    private final Runnable finalStatusRefresh = new Runnable() {
        @Override
        public void run() {
            if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("setup_complete", false)) {
                refreshFinalStatus();
                handler.postDelayed(this, FINAL_STATUS_REFRESH_MS);
            }
        }
    };

    private void startFinalStatusRefresh() {
        handler.removeCallbacks(finalStatusRefresh);
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("setup_complete", false)) {
            handler.post(finalStatusRefresh);
        }
    }

    private void checkDeviceOwnerStep() {
        if (isDeviceOwner()) {
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Device Owner is active. Continue with START BEDTIME MONITOR.");
            Toast.makeText(this, "ACTIVE DEVICE OWNER ✓", Toast.LENGTH_SHORT).show();
        } else {
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta sa PC at patakbuhin:\n\nadb shell dpm set-device-owner "
                    + getPackageName() + "/com.master.bedtime.child.BedtimeDeviceAdminReceiver");
            Toast.makeText(this, "Device Owner is not active yet.", Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
    }

    private void refreshSetupState() {
        boolean accountsConfirmed = getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("accounts_confirmed", false);
        boolean setupComplete = getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();
        boolean monitorStarted = getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("monitor_started", false);

        if (setupComplete) {
            showFinalChildUi();
            return;
        }

        showSetupUi();
        title.setText("Child Bedtime Setup");
        setupHelp.setVisibility(View.VISIBLE);
        setupStep.setVisibility(View.VISIBLE);
        deviceOwnerStatus.setVisibility(View.VISIBLE);
        deviceOwnerHelp.setVisibility(View.VISIBLE);

        if (!accountsConfirmed) {
            setupStep.setText("STEP 1 — ACCOUNT / SECURITY");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Alisin muna ang saved accounts, pagkatapos pindutin ang TAPOS NA — CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 — ACTIVE DEVICE OWNER");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta sa PC at patakbuhin:\n\nadb shell dpm set-device-owner "
                    + getPackageName() + "/com.master.bedtime.child.BedtimeDeviceAdminReceiver");
        } else if (!monitorStarted) {
            setupStep.setText("STEP 3 — START BEDTIME MONITOR");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Device Owner active. Tap START BEDTIME MONITOR, then finish Battery and Auto-run settings.");
        } else {
            setupStep.setText("STEP 4–6 — BATTERY → AUTO-RUN → SAVE & RESTART");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Monitor started. Finish Battery: No restrictions and Auto-run/Background: Allow, then SAVE & RESTART.");
        }

        activeDeviceOwner.setText(owner ? "2. ACTIVE DEVICE OWNER ✓" : "2. ACTIVE DEVICE OWNER");
        start.setText(monitorStarted ? "3. START BEDTIME MONITOR ✓" : "3. START BEDTIME MONITOR");
        status.setText("Setup not finished");
    }

    private void showSetupUi() {
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
        releaseDeviceOwner.setVisibility(isDeviceOwner() ? View.VISIBLE : View.GONE);
        finalChildPanel.setVisibility(View.GONE);

        accounts.setEnabled(true);
        continueSetup.setEnabled(true);
        activeDeviceOwner.setEnabled(true);
        permission.setEnabled(true);
        start.setEnabled(true);
        battery.setEnabled(true);
        autoRun.setEnabled(true);
        saveRestart.setEnabled(true);
        test.setEnabled(true);
    }

    private void showFinalChildUi() {
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
        restoreAccounts.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);

        title.setText("BEDTIME CHILD");
        setupStep.setVisibility(View.GONE);
        setupHelp.setVisibility(View.GONE);
        deviceOwnerStatus.setVisibility(View.GONE);
        deviceOwnerHelp.setVisibility(View.GONE);
        finalChildPanel.setVisibility(View.VISIBLE);
        status.setText(isDeviceOwner() ? "Protected by Device Owner ✓" : "ATTENTION — Device Owner is not active");

        ensureRecoveryCode();
        ensureChildPairingCode();
        refreshFinalStatus();
        startFinalStatusRefresh();
    }

    private void refreshFinalStatus() {
        boolean active = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("last_active", false);
        bedtimeStatus.setText(active ? "🌙 BEDTIME ON" : "☀ BEDTIME OFF");

        String recovery = getSharedPreferences("cfg", MODE_PRIVATE).getString("recovery_code", "");
        if (!recovery.isEmpty()) recoveryCode.setText(recovery);

        boolean paired = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false);
        if (paired) {
            pairingStatus.setText("✓ CHILD PAIRED WITH PARENT");
            String lastCode = getSharedPreferences("cfg", MODE_PRIVATE).getString("last_pairing_code", "");
            pairingCode.setText(lastCode.isEmpty() ? "PAIRED ✓" : lastCode);
        }
    }

    private String ensureRecoveryCode() {
        String existing = getSharedPreferences("cfg", MODE_PRIVATE).getString("recovery_code", "");
        if (!existing.isEmpty()) {
            recoveryCode.setText(existing);
            return existing;
        }
        int n = new SecureRandom().nextInt(100000000);
        String code = String.format(Locale.US, "%08d", n);
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("recovery_code", code).apply();
        recoveryCode.setText(code);
        return code;
    }

    private void ensureChildPairingCode() {
        boolean paired = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false);
        if (paired) {
            refreshFinalStatus();
            return;
        }

        String existing = getSharedPreferences("cfg", MODE_PRIVATE).getString("pairing_code", "");
        if (existing.matches("\\d{6}")) {
            pairingCode.setText(existing);
            pairingStatus.setText("Give this 6-digit code to the Parent device. Waiting for Parent…");
            pollChildPairingStatus(existing);
            return;
        }
        generateChildPairingCode();
    }

    private void generateChildPairingCode() {
        if (!isDeviceOwner()) {
            pairingCode.setText("------");
            pairingStatus.setText("ACTIVE DEVICE OWNER is required before pairing.");
            return;
        }

        String base = normalizeBackend(getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("backend", backend.getText().toString()));
        String requestedId = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("child", child.getText().toString().trim());

        pairingCode.setText("------");
        pairingStatus.setText("Generating 6-digit pairing code…");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/api/pairing/child/create").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                String payload = "{\"childId\":\"" + requestedId.replace("\"", "") + "\"}";
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int http = connection.getResponseCode();
                String body = readBody(connection, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                String childId = data.getString("childId");
                String code = data.getString("code");

                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                        .putString("backend", base)
                        .putString("child", childId)
                        .putString("pairing_code", code)
                        .putString("last_pairing_code", code)
                        .putBoolean("paired", false)
                        .apply();

                runOnUiThread(() -> {
                    child.setText(childId);
                    pairingCode.setText(code);
                    pairingStatus.setText("Give this 6-digit code to the Parent device. Waiting for Parent…");
                });
                pollChildPairingStatus(code);
            } catch (Exception e) {
                runOnUiThread(() -> pairingStatus.setText("Pairing code generation failed: " + safeMessage(e)));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void pollChildPairingStatus(String code) {
        if (code == null || !code.matches("\\d{6}")) return;
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false)) return;

        String base = normalizeBackend(getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("backend", backend.getText().toString()));

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        base + "/api/pairing/child/status?code=" + code).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Cache-Control", "no-cache");

                int http = connection.getResponseCode();
                String body = readBody(connection, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                boolean valid = data.optBoolean("valid", true);
                boolean paired = data.optBoolean("paired", false);
                String childId = data.optString("childId", "");
                String childToken = data.optString("childToken", "");

                if (paired && !childToken.isEmpty()) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putString("child", childId)
                            .putString("child_token", childToken)
                            .remove("pairing_code")
                            .putBoolean("paired", true)
                            .apply();
                    runOnUiThread(() -> {
                        pairingCode.setText(code);
                        pairingStatus.setText("✓ CHILD PAIRED WITH PARENT");
                        Toast.makeText(this, "Child paired successfully ✓", Toast.LENGTH_LONG).show();
                    });
                } else if (!valid) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().remove("pairing_code").apply();
                    runOnUiThread(() -> {
                        pairingStatus.setText("Pairing code expired. Generating a new code…");
                        generateChildPairingCode();
                    });
                    return;
                }
            } catch (Exception e) {
                runOnUiThread(() -> pairingStatus.setText("Pairing status failed: " + safeMessage(e)));
            } finally {
                if (connection != null) connection.disconnect();
                handler.postDelayed(() -> {
                    if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("paired", false)) return;
                    String current = getSharedPreferences("cfg", MODE_PRIVATE).getString("pairing_code", "");
                    if (code.equals(current)) pollChildPairingStatus(code);
                }, 2000L);
            }
        }).start();
    }

    private void startMonitor() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "ACTIVE DEVICE OWNER FIRST", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }

        String base = normalizeBackend(backend.getText().toString());
        String childId = child.getText().toString().trim();
        if (base.isEmpty() || childId.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }

        backend.setText(base);
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("backend", base)
                .putString("child", childId)
                .putBoolean("monitor_started", true)
                .apply();

        try {
            startForegroundService(new Intent(this, BedtimeMonitorService.class));
        } catch (Exception e) {
            Toast.makeText(this, "Monitor start failed: " + safeMessage(e), Toast.LENGTH_LONG).show();
            return;
        }

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
        String childId = child.getText().toString().trim();
        if (base.isEmpty() || childId.isEmpty()) {
            Toast.makeText(this, "Backend URL and Child ID are required.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("backend", base)
                .putString("child", childId)
                .putBoolean("setup_complete", true)
                .apply();

        ensureRecoveryCode();

        try {
            startForegroundService(new Intent(this, BedtimeMonitorService.class));
        } catch (Exception ignored) {}

        Toast.makeText(this, "Saved. Restarting Child…", Toast.LENGTH_LONG).show();
        handler.postDelayed(() -> {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(launch);
                    finish();
                } else {
                    showFinalChildUi();
                }
            } catch (Exception e) {
                showFinalChildUi();
            }
        }, 1000L);
    }

    private void testBedtime() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("last_active", true).apply();
        if (isDeviceOwner()) {
            startActivity(new Intent(this, BedtimeLockActivity.class));
        } else if (Settings.canDrawOverlays(this)) {
            BedtimeOverlay.show(this);
        } else {
            Toast.makeText(this, "Allow overlay permission first.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private String normalizeBackend(String value) {
        if (value == null) return "";
        String raw = value.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) return "";
        while (raw.toLowerCase(Locale.US).startsWith("http://")
                || raw.toLowerCase(Locale.US).startsWith("https://")) {
            raw = raw.toLowerCase(Locale.US).startsWith("https://")
                    ? raw.substring(8) : raw.substring(7);
        }
        return "https://" + raw;
    }

    private String readBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream input = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "HTTP " + statusCode;
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line);
        reader.close();
        return out.toString();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private void showAccountPreparationReminder() {
        new AlertDialog.Builder(this)
                .setTitle("Bago tayo magsimula")
                .setMessage("Para tuloy-tuloy ang Device Owner setup, alisin muna ang mga naka-save na account sa device. Siguraduhing alam ninyo ang login details bago alisin ang mga ito. Maaari silang ibalik pagkatapos ng setup.")
                .setNegativeButton("Hindi muna", null)
                .setPositiveButton("OKAY, PUNTA SA ACCOUNTS", (dialog, which) -> openAccountsSettings())
                .show();
    }

    private void showRestoreAccountsReminder() {
        new AlertDialog.Builder(this)
                .setTitle("Setup complete")
                .setMessage("Tapos na ang Bedtime setup. Maaari nang ibalik o idagdag muli ang mga account.")
                .setNegativeButton("Mamaya", null)
                .setPositiveButton("PUNTA SA ACCOUNTS", (dialog, which) -> openAccountsSettings())
                .show();
    }

    private void openAccountsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void confirmReleaseDeviceOwner() {
        if (!isDeviceOwner()) {
            refreshSetupState();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("TEST ONLY — Release Device Owner?")
                .setMessage("Tatanggalin nito ang Device Owner role para ma-uninstall o ma-reprovision ang test device.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RELEASE DEVICE OWNER", (dialog, which) -> releaseDeviceOwnerForTesting())
                .show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForTesting() {
        try {
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putBoolean("last_active", false)
                    .putBoolean("setup_complete", false)
                    .putBoolean("monitor_started", false)
                    .apply();
            stopService(new Intent(this, BedtimeMonitorService.class));
            dpm.clearDeviceOwnerApp(getPackageName());
            Toast.makeText(this, "Device Owner released for testing.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Release failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshSetupState();
    }
}
