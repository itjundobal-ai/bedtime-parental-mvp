package com.master.bedtime.child;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText backend = findViewById(R.id.backendUrl);
        EditText child = findViewById(R.id.childId);
        TextView status = findViewById(R.id.status);
        Button accounts = findViewById(R.id.btnAccountsSecurity);
        Button permission = findViewById(R.id.btnOverlayPermission);
        Button start = findViewById(R.id.btnStartMonitor);
        Button test = findViewById(R.id.btnTestOverlay);

        backend.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("backend", "http://10.0.2.2:8080"));
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

        accounts.setOnClickListener(v -> showAccountPreparationReminder());

        permission.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Display over other apps first.", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("backend", backend.getText().toString().trim().replaceAll("/$", ""))
                .putString("child", child.getText().toString().trim()).apply();
            Intent service = new Intent(this, BedtimeMonitorService.class);
            startForegroundService(service);
            status.setText("Monitor running");
        });

        test.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow overlay permission first.", Toast.LENGTH_LONG).show();
                return;
            }
            BedtimeOverlay.show(this);
        });

        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("account_reminder_seen", false)) {
            showAccountPreparationReminder();
        }
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
