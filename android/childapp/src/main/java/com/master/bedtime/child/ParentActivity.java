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
    private static final String PREFS = "parent_cfg";
    private static final int MAX_COMMAND_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700L;

    private EditText pairCode;
    private EditText childId;
    private TextView recoveryCode;
    private TextView status;
    private Button pairButton;
    private Button onButton;
    private Button offButton;
    private Button refreshButton;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);

        pairCode = findViewById(R.id.parentPairCode);
        childId = findViewById(R.id.parentChildId);
        recoveryCode = findViewById(R.id.parentRecoveryCode);
        status = findViewById(R.id.parentStatus);
        pairButton = findViewById(R.id.btnPairChild);
        onButton = findViewById(R.id.btnBedtimeOn);
        offButton = findViewById(R.id.btnBedtimeOff);
        refreshButton = findViewById(R.id.btnRefreshState);

        loadPairedChild();
        pairButton.setOnClickListener(v -> claimPairing());
        onButton.setOnClickListener(v -> sendBedtime(true));
        offButton.setOnClickListener(v -> sendBedtime(false));
        refreshButton.setOnClickListener(v -> refreshState());

        if (hasPairedChild()) refreshState();
        else setControlsForPairing(false);
    }

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

    private void setControlsForPairing(boolean busy) {
        pairButton.setEnabled(!busy);
        pairCode.setEnabled(!busy);
        boolean paired = hasPairedChild() && !busy;
        onButton.setEnabled(paired);
        offButton.setEnabled(paired);
        refreshButton.setEnabled(paired);
    }

    private void claimPairing() {
        final String code = pairCode.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            pairCode.setError("Enter the 6-digit code from CHILD");
            return;
        }
        setControlsForPairing(true);
        status.setText("Pairing child...");

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(DEFAULT_BACKEND + "/api/pairing/claim");
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
                String response = readAnyResponse(conn, http);
                JSONObject json = new JSONObject(response);
                if (http < 200 || http >= 300) throw new Exception(json.optString("error", "HTTP " + http));

                String child = json.getString("childId");
                String token = json.getString("parentToken");
                String recovery = json.getString("recoveryPin");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("child", child)
                    .putString("parent_token", token)
                    .putString("recovery_pin", recovery)
                    .apply();

                main.post(() -> {
                    pairCode.setText("");
                    loadPairedChild();
                    status.setText("PAIRED ✓ — Child ready");
                    setControlsForPairing(false);
                    refreshState();
                });
            } catch (Exception e) {
                main.post(() -> {
                    status.setText("Pairing failed: " + e.getMessage());
                    setControlsForPairing(false);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String selectedChild() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("child", "");
    }

    private String parentToken() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("parent_token", "");
    }

    private void setBusy(boolean busy) {
        pairButton.setEnabled(!busy);
        pairCode.setEnabled(!busy);
        onButton.setEnabled(!busy);
        offButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
    }

    private void sendBedtime(boolean active) {
        if (!hasPairedChild()) {
            Toast.makeText(this, "Pair a CHILD first.", Toast.LENGTH_LONG).show();
            return;
        }
        final String child = selectedChild();
        setBusy(true);
        status.setText(active ? "Sending BEDTIME ON..." : "Sending BEDTIME OFF...");

        new Thread(() -> {
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_COMMAND_ATTEMPTS; attempt++) {
                try {
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
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            }
            final String message = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "Unable to verify command";
            main.post(() -> {
                status.setText("Command failed: " + message);
                setBusy(false);
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
            conn.setRequestProperty("x-parent-token", parentToken());

            JSONObject body = new JSONObject();
            body.put("active", active);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("POST HTTP " + code + ": " + readAnyResponse(conn, code));
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
            conn.setRequestProperty("x-parent-token", parentToken());
            int code = conn.getResponseCode();
            String response = readAnyResponse(conn, code);
            if (code < 200 || code >= 300) throw new Exception("GET HTTP " + code);
            return new JSONObject(response).optBoolean("active", false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void refreshState() {
        if (!hasPairedChild()) {
            status.setText("Pair a child to begin");
            setControlsForPairing(false);
            return;
        }
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

    private String readAnyResponse(HttpURLConnection conn, int code) throws Exception {
        java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
