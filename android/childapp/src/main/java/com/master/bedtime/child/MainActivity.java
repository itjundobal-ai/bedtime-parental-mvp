package com.master.bedtime.child;

import android.Manifest;
import android.app.Activity;
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
        Button permission = findViewById(R.id.btnOverlayPermission);
        Button start = findViewById(R.id.btnStartMonitor);
        Button test = findViewById(R.id.btnTestOverlay);

        backend.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("backend", "http://10.0.2.2:8080"));
        child.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("child", "child-001"));

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
    }
}
