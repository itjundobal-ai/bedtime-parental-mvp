package com.master.bedtime.parent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String BACKEND = "https://bedtime-parental-api.itjundobal.workers.dev";
    private static final long POLL_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView codeView;
    private TextView statusView;
    private TextView childrenView;
    private Button addChildButton;
    private Button bedtimeOnButton;
    private Button bedtimeOffButton;

    private String parentToken = "";
    private String selectedChildId = "";
    private boolean hasPairedChild = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parentToken = getPreferences(MODE_PRIVATE).getString("parent_token", "");
        selectedChildId = getPreferences(MODE_PRIVATE).getString("selected_child", "");
        buildUi();

        if (parentToken.isEmpty()) {
            statusView.setText("Creating your first 6-digit pairing code…");
            createPairingCode(false);
        } else {
            statusView.setText("Checking linked Child devices…");
            pollPairingStatus();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 36);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("BEDTIME PARENT");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText("Give this 6-digit code to the Child device.");
        instruction.setTextSize(18);
        instruction.setPadding(0, 28, 0, 12);
        instruction.setGravity(Gravity.CENTER);
        root.addView(instruction);

        codeView = new TextView(this);
        codeView.setText("------");
        codeView.setTextSize(38);
        codeView.setGravity(Gravity.CENTER);
        root.addView(codeView);

        statusView = new TextView(this);
        statusView.setPadding(0, 18, 0, 18);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView);

        childrenView = new TextView(this);
        childrenView.setText("Linked Children: none yet");
        childrenView.setPadding(0, 8, 0, 20);
        root.addView(childrenView);

        addChildButton = new Button(this);
        addChildButton.setText("ADD CHILD — GENERATE NEW 6-DIGIT CODE");
        addChildButton.setVisibility(View.GONE);
        root.addView(addChildButton);

        bedtimeOnButton = new Button(this);
        bedtimeOnButton.setText("BEDTIME ON");
        bedtimeOnButton.setVisibility(View.GONE);
        root.addView(bedtimeOnButton);

        bedtimeOffButton = new Button(this);
        bedtimeOffButton.setText("BEDTIME OFF");
        bedtimeOffButton.setVisibility(View.GONE);
        root.addView(bedtimeOffButton);

        addChildButton.setOnClickListener(v -> createPairingCode(true));
        bedtimeOnButton.setOnClickListener(v -> setBedtime(true));
        bedtimeOffButton.setOnClickListener(v -> setBedtime(false));

        setContentView(root);
    }

    private void createPairingCode(boolean addingAnotherChild) {
        addChildButton.setEnabled(false);
        statusView.setText(addingAnotherChild
                ? "Generating a new 6-digit code…"
                : "Creating your first 6-digit pairing code…");

        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/pairing/create", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                if (!parentToken.isEmpty()) c.setRequestProperty("X-Parent-Token", parentToken);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }

                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                parentToken = data.getString("parentToken");
                selectedChildId = data.getString("childId");
                String code = data.getString("code");

                getPreferences(MODE_PRIVATE).edit()
                        .putString("parent_token", parentToken)
                        .putString("selected_child", selectedChildId)
                        .apply();

                runOnUiThread(() -> {
                    codeView.setText(code);
                    statusView.setText("Waiting for Child to enter this code…");
                    addChildButton.setEnabled(true);
                });
                pollPairingStatus();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    codeView.setText("------");
                    statusView.setText("Pairing setup failed: " + safeMessage(e));
                    addChildButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void pollPairingStatus() {
        if (parentToken.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/pairing/status", "GET");
                c.setRequestProperty("X-Parent-Token", parentToken);
                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                JSONArray children = data.optJSONArray("children");
                boolean paired = false;
                StringBuilder text = new StringBuilder("Linked Children:");
                String firstPaired = "";

                if (children == null || children.length() == 0) {
                    text.append(" none yet");
                } else {
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null) continue;
                        String id = child.optString("childId", "");
                        boolean isPaired = child.optBoolean("paired", false);
                        text.append("\n").append(i + 1).append(". ").append(id)
                                .append(isPaired ? " — PAIRED ✓" : " — waiting");
                        if (isPaired) {
                            paired = true;
                            if (firstPaired.isEmpty()) firstPaired = id;
                        }
                    }
                }

                final boolean finalPaired = paired;
                final String finalFirstPaired = firstPaired;
                final String finalText = text.toString();
                runOnUiThread(() -> {
                    childrenView.setText(finalText);
                    hasPairedChild = finalPaired;
                    if (finalPaired) {
                        if (!finalFirstPaired.isEmpty()) {
                            selectedChildId = finalFirstPaired;
                            getPreferences(MODE_PRIVATE).edit()
                                    .putString("selected_child", selectedChildId).apply();
                        }
                        statusView.setText("PAIRING SUCCESSFUL ✓");
                        codeView.setText("PAIRED ✓");
                        addChildButton.setVisibility(View.VISIBLE);
                        bedtimeOnButton.setVisibility(View.VISIBLE);
                        bedtimeOffButton.setVisibility(View.VISIBLE);
                    } else {
                        addChildButton.setVisibility(View.GONE);
                        bedtimeOnButton.setVisibility(View.GONE);
                        bedtimeOffButton.setVisibility(View.GONE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Pairing status failed: " + safeMessage(e)));
            } finally {
                handler.postDelayed(this::pollPairingStatus, POLL_MS);
            }
        }).start();
    }

    private void setBedtime(boolean enabled) {
        if (!hasPairedChild || selectedChildId.isEmpty()) {
            statusView.setText("Pair a Child first.");
            return;
        }
        statusView.setText(enabled ? "Sending BEDTIME ON…" : "Sending BEDTIME OFF…");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/children/" + selectedChildId + "/bedtime", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Parent-Token", parentToken);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(("{\"active\":" + enabled + "}").getBytes(StandardCharsets.UTF_8));
                }
                int http = c.getResponseCode();
                String body = readBody(c, http);
                runOnUiThread(() -> statusView.setText(
                        http >= 200 && http < 300
                                ? (enabled ? "BEDTIME ON ✓" : "BEDTIME OFF ✓")
                                : "Server error: " + body));
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Connection failed: " + safeMessage(e)));
            }
        }).start();
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        return c;
    }

    private String readBody(HttpURLConnection c, int status) throws Exception {
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "HTTP " + status;
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) out.append(line);
        br.close();
        return out.toString();
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
