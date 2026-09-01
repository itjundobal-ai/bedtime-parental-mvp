package com.master.bedtime.child;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

public class ParentActivity extends Activity {
    private static final String DEFAULT_BACKEND = "https://bedtime-parental-api.itjundobal.workers.dev";
    private EditText childId;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);

        childId = findViewById(R.id.parentChildId);
        status = findViewById(R.id.parentStatus);
        Button on = findViewById(R.id.btnBedtimeOn);
        Button off = findViewById(R.id.btnBedtimeOff);
        Button refresh = findViewById(R.id.btnRefreshState);

        String savedChild = getSharedPreferences("parent_cfg", MODE_PRIVATE).getString("child", "child-001");
        childId.setText(savedChild);

        on.setOnClickListener(v -> sendBedtime(true));
        off.setOnClickListener(v -> sendBedtime(false));
        refresh.setOnClickListener(v -> refreshState());

        refreshState();
    }

    private String selectedChild() {
        String value = childId.getText().toString().trim();
        if (value.isEmpty()) value = "child-001";
        childId.setText(value);
        getSharedPreferences("parent_cfg", MODE_PRIVATE).edit().putString("child", value).apply();
        return value;
    }

    private void sendBedtime(boolean active) {
        final String child = selectedChild();
        status.setText(active ? "Sending BEDTIME ON..." : "Sending BEDTIME OFF...");
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject body = new JSONObject();
                body.put("active", active);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                String response = readResponse(conn);
                JSONObject json = new JSONObject(response);
                boolean state = json.optBoolean("active", active);
                main.post(() -> status.setText(state ? "BEDTIME ON ✓" : "BEDTIME OFF ✓"));
            } catch (Exception e) {
                main.post(() -> {
                    status.setText("Command failed: " + e.getMessage());
                    Toast.makeText(this, "Parent command failed.", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void refreshState() {
        final String child = selectedChild();
        status.setText("Checking state...");
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                String response = readResponse(conn);
                JSONObject json = new JSONObject(response);
                boolean state = json.optBoolean("active", false);
                main.post(() -> status.setText(state ? "BEDTIME ON" : "BEDTIME OFF"));
            } catch (Exception e) {
                main.post(() -> status.setText("Unable to read state: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
