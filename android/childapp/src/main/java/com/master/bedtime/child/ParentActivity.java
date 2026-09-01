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
    private static final int MAX_COMMAND_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700L;

    private EditText childId;
    private TextView status;
    private Button onButton;
    private Button offButton;
    private Button refreshButton;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);

        childId = findViewById(R.id.parentChildId);
        status = findViewById(R.id.parentStatus);
        onButton = findViewById(R.id.btnBedtimeOn);
        offButton = findViewById(R.id.btnBedtimeOff);
        refreshButton = findViewById(R.id.btnRefreshState);

        String savedChild = getSharedPreferences("parent_cfg", MODE_PRIVATE).getString("child", "child-001");
        childId.setText(savedChild);

        onButton.setOnClickListener(v -> sendBedtime(true));
        offButton.setOnClickListener(v -> sendBedtime(false));
        refreshButton.setOnClickListener(v -> refreshState());

        refreshState();
    }

    private String selectedChild() {
        String value = childId.getText().toString().trim();
        if (value.isEmpty()) value = "child-001";
        childId.setText(value);
        getSharedPreferences("parent_cfg", MODE_PRIVATE).edit().putString("child", value).apply();
        return value;
    }

    private void setBusy(boolean busy) {
        onButton.setEnabled(!busy);
        offButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        childId.setEnabled(!busy);
    }

    private void sendBedtime(boolean active) {
        final String child = selectedChild();
        setBusy(true);
        status.setText(active ? "Sending BEDTIME ON..." : "Sending BEDTIME OFF...");

        new Thread(() -> {
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_COMMAND_ATTEMPTS; attempt++) {
                try {
                    main.post(() -> status.setText((active ? "Sending BEDTIME ON" : "Sending BEDTIME OFF") + "..."));
                    postState(child, active);

                    boolean verified = getState(child);
                    if (verified == active) {
                        main.post(() -> {
                            status.setText(active ? "BEDTIME ON ✓" : "BEDTIME OFF ✓");
                            setBusy(false);
                        });
                        return;
                    }

                    lastError = new Exception("State verification mismatch");
                } catch (Exception e) {
                    lastError = e;
                }

                if (attempt < MAX_COMMAND_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            final String message = lastError != null && lastError.getMessage() != null
                ? lastError.getMessage()
                : "Unable to verify command";

            main.post(() -> {
                status.setText("Command failed: " + message);
                setBusy(false);
                Toast.makeText(this, "Parent command failed after automatic retries.", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void postState(String child, boolean active) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            JSONObject body = new JSONObject();
            body.put("active", active);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("POST HTTP " + code);

            String response = readResponse(conn);
            JSONObject json = new JSONObject(response);
            if (json.has("active") && json.optBoolean("active", !active) != active) {
                throw new Exception("POST response mismatch");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean getState(String child) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DEFAULT_BACKEND + "/api/children/" + child + "/bedtime?verify=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("GET HTTP " + code);

            String response = readResponse(conn);
            JSONObject json = new JSONObject(response);
            return json.optBoolean("active", false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void refreshState() {
        final String child = selectedChild();
        setBusy(true);
        status.setText("Checking state...");

        new Thread(() -> {
            try {
                boolean state = getState(child);
                main.post(() -> {
                    status.setText(state ? "BEDTIME ON" : "BEDTIME OFF");
                    setBusy(false);
                });
            } catch (Exception e) {
                main.post(() -> {
                    status.setText("Unable to read state: " + e.getMessage());
                    setBusy(false);
                });
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
