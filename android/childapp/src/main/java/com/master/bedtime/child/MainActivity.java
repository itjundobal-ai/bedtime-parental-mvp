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

import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final String CFG = "cfg";
    private static final String KEY_RECOVERY_PIN = "parent_recovery_pin";
    private static final String KEY_RECOVERY_PIN_SHOWN = "parent_recovery_pin_shown";

    private EditText backend;
    private EditText child;
    private TextView status;
    private TextView setupStep;
    private TextView deviceOwnerStatus;
    private TextView deviceOwnerHelp;
    private Button accounts;
    private Button continueSetup;
    private Button permission;
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
        accounts = findViewById(R.id.btnAccountsSecurity);
        continueSetup = findViewById(R.id.btnContinueSetup);
        permission = findViewById(R.id.btnOverlayPermission);
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
        if (!normalizedBackend.equals(savedBackend)) {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putString("backend", normalizedBackend).apply();
        }
        child.setText(getSharedPreferences(CFG, MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());
        continueSetup.setOnClickListener(v -> {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("accounts_confirmed", true).apply();
            refreshSetupState();
        });

        permission.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        start.setOnClickListener(v -> startMonitor());
        batterySettings.setOnClickListener(v -> showBatterySettingsGuide());
        restoreAccounts.setOnClickListener(v -> showRestoreAccountsReminder());
        test.setOnClickListener(v -> testBedtime());
        releaseDeviceOwner.setOnClickListener(v -> confirmReleaseDeviceOwner());

        // Hidden local parent/admin recovery entry while secure remote pairing is still under development.
        // Long-press the protected status text, then enter the recovery PIN shown once to the parent.
        status.setOnLongClickListener(v -> {
            if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false)) return false;
            showRecoveryPinPrompt();
            return true;
        });

        if (!getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("account_reminder_seen", false)
            && !getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false)) {
            showAccountPreparationReminder();
        }
        refreshSetupState();
        autoStartConfiguredMonitor();
        showRecoveryPinOnceIfNeeded();
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

        try {
            startForegroundService(new Intent(this, BedtimeMonitorService.class));
        } catch (Exception ignored) {
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
        boolean accountsConfirmed = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("accounts_confirmed", false);
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();

        if (!accountsConfirmed) {
            setupStep.setText("STEP 1 OF 6 — Remove saved accounts");
            deviceOwnerStatus.setText("Device Owner: waiting for account preparation");
            deviceOwnerHelp.setText("Pagkatapos alisin ang accounts, bumalik dito at pindutin ang TAPOS NA — CONTINUE SETUP.");
        } else if (!owner) {
            setupStep.setText("STEP 2 OF 6 — Activate Device Owner");
            deviceOwnerStatus.setText("Device Owner: NOT ACTIVE");
            deviceOwnerHelp.setText("Ikonekta ang phone sa PC at patakbuhin:\n\nadb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver\n\nPag success, bumalik sa app. Automatic nitong makikita ang Device Owner status.");
        } else if (!setupComplete) {
            setupStep.setText("STEP 3 OF 6 — Pair and start monitor");
            deviceOwnerStatus.setText("Device Owner: ACTIVE ✓");
            deviceOwnerHelp.setText("Managed mode ready. Ilagay ang Parent/Worker backend at Child ID, pagkatapos pindutin ang START BEDTIME MONITOR. Pagkatapos, buksan ang Battery / Background Settings at piliin ang Unrestricted / No restrictions / Allow background activity kung available.");
        } else {
            setupStep.setText("SETUP COMPLETE — Child device protected");
            deviceOwnerStatus.setText(owner ? "Device Owner: ACTIVE ✓" : "Managed protection needs attention");
            deviceOwnerHelp.setText(owner
                ? "Bedtime Child is configured. Setup controls are hidden and normal uninstall is blocked while this app remains Device Owner. Remote Bedtime monitoring stays active."
                : "Device Owner is no longer active. Parent/admin maintenance is required before this child device should be considered protected.");
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
        start.setVisibility(View.VISIBLE);
        batterySettings.setVisibility(View.VISIBLE);
        restoreAccounts.setVisibility(View.GONE);
        test.setVisibility(View.VISIBLE);
        releaseDeviceOwner.setVisibility(owner ? View.VISIBLE : View.GONE);

        start.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));
        start.setText("3. START BEDTIME MONITOR");
        batterySettings.setEnabled(accountsConfirmed);
        test.setEnabled(accountsConfirmed && (owner || Settings.canDrawOverlays(this)));

        if (owner) status.setText("Managed setup ready — waiting for monitor start");
    }

    private void showCompletedChildUi(boolean owner) {
        accounts.setVisibility(View.GONE);
        continueSetup.setVisibility(View.GONE);
        backend.setVisibility(View.GONE);
        child.setVisibility(View.GONE);
        permission.setVisibility(View.GONE);
        start.setVisibility(View.GONE);
        test.setVisibility(View.GONE);
        releaseDeviceOwner.setVisibility(View.GONE);

        batterySettings.setVisibility(View.VISIBLE);
        batterySettings.setEnabled(true);
        batterySettings.setText("BATTERY / BACKGROUND SETTINGS");
        restoreAccounts.setVisibility(View.VISIBLE);
        restoreAccounts.setText("ACCOUNTS / DEVICE SETTINGS");

        status.setText(owner
            ? "PROTECTED — Remote Bedtime Monitor active"
            : "ATTENTION — Device Owner protection is not active");
    }

    private void applyCompletedChildProtection(boolean owner) {
        if (!owner || dpm == null || admin == null) return;
        try {
            dpm.setUninstallBlocked(admin, getPackageName(), true);
        } catch (Exception ignored) {
        }
    }

    private String ensureRecoveryPin() {
        String existing = getSharedPreferences(CFG, MODE_PRIVATE).getString(KEY_RECOVERY_PIN, "");
        if (!existing.isEmpty()) return existing;

        SecureRandom random = new SecureRandom();
        int value = 100000 + random.nextInt(900000);
        String pin = String.valueOf(value);
        getSharedPreferences(CFG, MODE_PRIVATE).edit()
            .putString(KEY_RECOVERY_PIN, pin)
            .putBoolean(KEY_RECOVERY_PIN_SHOWN, false)
            .apply();
        return pin;
    }

    private void showRecoveryPinOnceIfNeeded() {
        boolean setupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        boolean owner = isDeviceOwner();
        if (!setupComplete || !owner) return;

        String pin = ensureRecoveryPin();
        boolean shown = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean(KEY_RECOVERY_PIN_SHOWN, false);
        if (shown) return;

        new AlertDialog.Builder(this)
            .setTitle("Parent recovery code — save this")
            .setMessage("Temporary parent/admin recovery code for this CHILD device:\n\n" + pin +
                "\n\nSave this somewhere the child cannot access. To use it later, long-press the PROTECTED status inside the CHILD app. This local recovery path will be replaced by secure parent pairing in the final product.")
            .setCancelable(false)
            .setPositiveButton("I SAVED IT", (dialog, which) ->
                getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean(KEY_RECOVERY_PIN_SHOWN, true).apply())
            .show();
    }

    private void showRecoveryPinPrompt() {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "Device Owner is not active.", Toast.LENGTH_LONG).show();
            return;
        }

        final String expectedPin = ensureRecoveryPin();
        EditText input = new EditText(this);
        input.setHint("6-digit parent recovery code");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Parent/Admin recovery")
            .setMessage("Enter the recovery code to release this CHILD device from managed protection.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONTINUE", null)
            .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String entered = input.getText().toString().trim();
            if (!expectedPin.equals(entered)) {
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
            .setMessage("This will turn Bedtime protection off locally, stop the monitor, remove the uninstall block, and attempt to release Device Owner so the app can be uninstalled or provisioned again. Use only when the parent/admin intentionally wants to remove management.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RELEASE DEVICE", (dialog, which) -> releaseDeviceOwnerForRecovery())
            .show();
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

        boolean wasSetupComplete = getSharedPreferences(CFG, MODE_PRIVATE).getBoolean("setup_complete", false);
        getSharedPreferences(CFG, MODE_PRIVATE).edit()
            .putString("backend", backendValue)
            .putString("child", childValue)
            .putBoolean("setup_complete", true)
            .apply();

        Intent service = new Intent(this, BedtimeMonitorService.class);
        startForegroundService(service);
        applyCompletedChildProtection(owner);
        ensureRecoveryPin();
        refreshSetupState();
        Toast.makeText(this, wasSetupComplete ? "Bedtime monitor settings saved." : "Child setup complete. Bedtime protection is active.", Toast.LENGTH_LONG).show();
        if (!wasSetupComplete) {
            showRestoreAccountsReminder();
            showRecoveryPinOnceIfNeeded();
        }
    }

    private void showBatterySettingsGuide() {
        new AlertDialog.Builder(this)
            .setTitle("Battery / Background Settings")
            .setMessage("Para mabilis ang remote BEDTIME ON/OFF kahit unplugged o screen off, hanapin ang Battery setting ng Bedtime app at piliin ang pinakamaluwag na option na available, gaya ng:\n\n• Unrestricted\n• No restrictions\n• Allow background activity\n• Don't optimize\n\nMagkakaiba ang pangalan depende sa phone brand.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("OPEN APP SETTINGS", (dialog, which) -> openAppBatterySettings())
            .show();
    }

    private void openAppBatterySettings() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(details);
        } catch (Exception first) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Buksan ang Settings > Apps > Bedtime > Battery.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void testBedtime() {
        if (isDeviceOwner()) {
            getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("last_active", true).apply();
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
            .setMessage("Gamitin lang ito habang setup/testing pa. Tatanggalin nito ang Device Owner role para ma-uninstall o ma-reprovision ang app.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RELEASE DEVICE OWNER", (dialog, which) -> releaseDeviceOwnerForRecovery())
            .show();
    }

    @SuppressWarnings("deprecation")
    private void releaseDeviceOwnerForRecovery() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        try {
            // Disable auto-restart before stopping the service so the watchdog does not bring it back.
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                .putBoolean("last_active", false)
                .putBoolean("setup_complete", false)
                .apply();

            stopService(new Intent(this, BedtimeMonitorService.class));

            try {
                BedtimeOverlay.hide(this);
            } catch (Exception ignored) {}

            try {
                if (!BedtimeLockActivity.requestRemoteUnlock()) {
                    Intent unlock = new Intent(this, BedtimeLockActivity.class);
                    unlock.putExtra("bedtime_off", true);
                    startActivity(unlock);
                }
            } catch (Exception ignored) {}

            try {
                dpm.setUninstallBlocked(admin, getPackageName(), false);
            } catch (Exception ignored) {}

            dpm.clearDeviceOwnerApp(getPackageName());

            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                .remove(KEY_RECOVERY_PIN)
                .remove(KEY_RECOVERY_PIN_SHOWN)
                .apply();

            Toast.makeText(this, "Managed protection released. The app can now be uninstalled or provisioned again.", Toast.LENGTH_LONG).show();
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
                getSharedPreferences(CFG, MODE_PRIVATE).edit().putBoolean("account_reminder_seen", true).apply();
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
