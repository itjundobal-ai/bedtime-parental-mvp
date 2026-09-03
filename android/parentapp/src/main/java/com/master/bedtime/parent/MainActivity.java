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
    private static final String MODE = "selected_role";
    private static final String PARENT = "parent";
    private static final String CHILD = "child";
    private static final long POLL_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView codeView;
    private TextView statusView;
    private TextView childrenView;
    private Button generateButton;
    private Button addButton;
    private Button bedtimeOnButton;
    private Button bedtimeOffButton;

    private String parentToken = "";
    private String selectedChildId = "";
    private boolean waitingForPair = false;
    private boolean hasPairedChild = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String role = getPreferences(MODE_PRIVATE).getString(MODE, "");

        if (PARENT.equals(role)) {
            parentToken = getPreferences(MODE_PRIVATE).getString("parent_token", "");
            selectedChildId = getPreferences(MODE_PRIVATE).getString("selected_child", "");
            showParent();
            if (parentToken.isEmpty()) {
                generatePairingCode(false);
            } else {
                statusView.setText("Checking linked Child devices…");
                pollPairingStatus();
            }
        } else if (CHILD.equals(role)) {
            launchChild();
        } else {
            chooser();
        }
    }

    private void chooser() {
        LinearLayout root = box();
        TextView title = new TextView(this);
        title.setText("BEDTIME PARENTAL\n\nChoose your role");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        Button parent = new Button(this);
        parent.setText("PARENT");
        root.addView(parent);

        Button child = new Button(this);
        child.setText("CHILD");
        root.addView(child);

        parent.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().putString(MODE, PARENT).apply();
            parentToken = getPreferences(MODE_PRIVATE).getString("parent_token", "");
            showParent();
            if (parentToken.isEmpty()) generatePairingCode(false);
            else pollPairingStatus();
        });

        child.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().putString(MODE, CHILD).apply();
            launchChild();
        });

        setContentView(root);
    }

    private void launchChild() {
        try {
            startActivity(new android.content.Intent(
                    this, com.master.bedtime.child.MainActivity.class));
        } catch (Exception e) {
            getPreferences(MODE_PRIVATE).edit().remove(MODE).apply();
            chooser();
        }
    }

    private LinearLayout box() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 40, 30, 30);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        return root;
    }

    private void showParent() {
        LinearLayout root = box();

        TextView title = new TextView(this);
        title.setText("BEDTIME PARENT");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText("Give the 6-digit code to the Child device.");
        instruction.setTextSize(17);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 20, 0, 10);
        root.addView(instruction);

        codeView = new TextView(this);
        codeView.setText("------");
        codeView.setTextSize(40);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 10, 0, 12);
        root.addView(codeView);

        statusView = new TextView(this);
        statusView.setText("Starting…");
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 8, 0, 16);
        root.addView(statusView);

        childrenView = new TextView(this);
        childrenView.setText("Children: none yet");
        childrenView.setGravity(Gravity.CENTER);
        childrenView.setPadding(0, 4, 0, 18);
        root.addView(childrenView);

        generateButton = new Button(this);
        generateButton.setText("GENERATE CODE");
        root.addView(generateButton);

        addButton = new Button(this);
        addButton.setText("ADD");
        addButton.setVisibility(View.GONE);
        root.addView(addButton);

        bedtimeOnButton = new Button(this);
        bedtimeOnButton.setText("BEDTIME ON");
        bedtimeOnButton.setVisibility(View.GONE);
        root.addView(bedtimeOnButton);

        bedtimeOffButton = new Button(this);
        bedtimeOffButton.setText("BEDTIME OFF");
        bedtimeOffButton.setVisibility(View.GONE);
        root.addView(bedtimeOffButton);

        generateButton.setOnClickListener(v -> generatePairingCode(!parentToken.isEmpty()));
        addButton.setOnClickListener(v -> generatePairingCode(true));
        bedtimeOnButton.setOnClickListener(v -> setBedtime(true));
        bedtimeOffButton.setOnClickListener(v -> setBedtime(false));

        setContentView(root);
        updateParentControls();
    }

    private void updateParentControls() {
        if (generateButton == null) return;

        generateButton.setVisibility(waitingForPair ? View.VISIBLE : View.GONE);
        generateButton.setEnabled(!waitingForPair);
        addButton.setVisibility(hasPairedChild ? View.VISIBLE : View.GONE);
        bedtimeOnButton.setVisibility(hasPairedChild ? View.VISIBLE : View.GONE);
        bedtimeOffButton.setVisibility(hasPairedChild ? View.VISIBLE : View.GONE);
    }

    private void generatePairingCode(boolean addingAnotherChild) {
        if (addingAnotherChild && parentToken.isEmpty()) {
            addingAnotherChild = false;
        }

        waitingForPair = true;
        hasPairedChild = false;
        selectedChildId = "";
        updateParentControls();
        codeView.setText("------");
        statusView.setText(addingAnotherChild
                ? "Generating a new 6-digit code…"
                : "Generating your first 6-digit code…");

        final boolean addMode = addingAnotherChild;
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/pairing/create", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                if (!parentToken.isEmpty()) {
                    c.setRequestProperty("X-Parent-Token", parentToken);
                }
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }

                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                parentToken = data.getString("parentToken");
                String newChildId = data.getString("childId");
                String code = data.getString("code");

                getPreferences(MODE_PRIVATE).edit()
                        .putString(MODE, PARENT)
                        .putString("parent_token", parentToken)
                        .putString("pending_child", newChildId)
                        .apply();

                runOnUiThread(() -> {
                    codeView.setText(code);
                    statusView.setText(addMode
                            ? "Give this new 6-digit code to the next Child."
                            : "Give this 6-digit code to the Child.");
                    updateParentControls();
                });

                pollPairingStatus();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    waitingForPair = false;
                    codeView.setText("------");
                    statusView.setText("Pairing setup failed: " + safeMessage(e));
                    updateParentControls();
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
                String latestPairedId = "";
                StringBuilder text = new StringBuilder("Children linked:");

                if (children == null || children.length() == 0) {
                    text.append(" none yet");
                } else {
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null) continue;
                        String id = child.optString("childId", "");
                        boolean isPaired = child.optBoolean("paired", false);
                        text.append("\n").append(i + 1).append(". ")
                                .append(isPaired ? "PAIRED ✓" : "WAITING");
                        if (isPaired) {
                            paired = true;
                            latestPairedId = id;
                        }
                    }
                }

                final boolean finalPaired = paired;
                final String finalLatestId = latestPairedId;
                final String finalText = text.toString();

                runOnUiThread(() -> {
                    childrenView.setText(finalText);
                    hasPairedChild = finalPaired;

                    if (finalPaired) {
                        waitingForPair = false;
                        if (!finalLatestId.isEmpty()) {
                            selectedChildId = finalLatestId;
                            getPreferences(MODE_PRIVATE).edit()
                                    .putString("selected_child", selectedChildId)
                                    .remove("pending_child")
                                    .apply();
                        }
                        codeView.setText("PAIRED ✓");
                        statusView.setText("PAIRING SUCCESSFUL ✓");
                    } else {
                        waitingForPair = true;
                    }
                    updateParentControls();
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText(
                        "Pairing status failed: " + safeMessage(e)));
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
        final String childId = selectedChildId;

        new Thread(() -> {
            try {
                HttpURLConnection c = open(
                        BACKEND + "/api/children/" + childId + "/bedtime", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Parent-Token", parentToken);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(("{\"active\":" + enabled + "}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                int http = c.getResponseCode();
                String body = readBody(c, http);
                runOnUiThread(() -> statusView.setText(
                        http >= 200 && http < 300
                                ? (enabled ? "BEDTIME ON ✓" : "BEDTIME OFF ✓")
                                : "Server error: " + body));
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText(
                        "Connection failed: " + safeMessage(e)));
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
        return (m == null || m.trim().isEmpty())
                ? e.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
