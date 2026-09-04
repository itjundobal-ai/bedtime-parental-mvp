package com.master.bedtime.parent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private TextView statusView;
    private TextView childrenView;
    private EditText pairingCodeInput;
    private Button addButton;
    private Button bedtimeOnButton;
    private Button bedtimeOffButton;

    private String parentToken = "";
    private String selectedChildId = "";
    private boolean hasPairedChild = false;
    private boolean addingChild = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String role = getPreferences(MODE_PRIVATE).getString(MODE, "");

        if (PARENT.equals(role)) {
            parentToken = getPreferences(MODE_PRIVATE).getString("parent_token", "");
            selectedChildId = getPreferences(MODE_PRIVATE).getString("selected_child", "");
            showParent();
            ensureParentToken();
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
            ensureParentToken();
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
        instruction.setText("Enter the 6-digit code shown on the Child device.");
        instruction.setTextSize(17);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 20, 0, 10);
        root.addView(instruction);

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setHint("6-digit Child code");
        pairingCodeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pairingCodeInput.setGravity(Gravity.CENTER);
        pairingCodeInput.setTextSize(28);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        root.addView(pairingCodeInput);

        addButton = new Button(this);
        addButton.setText("ADD CHILD");
        root.addView(addButton);

        statusView = new TextView(this);
        statusView.setText("Starting Parent account…");
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 14, 0, 16);
        root.addView(statusView);

        childrenView = new TextView(this);
        childrenView.setText("Children: none yet");
        childrenView.setGravity(Gravity.CENTER);
        childrenView.setPadding(0, 4, 0, 18);
        root.addView(childrenView);

        bedtimeOnButton = new Button(this);
        bedtimeOnButton.setText("BEDTIME ON");
        bedtimeOnButton.setVisibility(View.GONE);
        root.addView(bedtimeOnButton);

        bedtimeOffButton = new Button(this);
        bedtimeOffButton.setText("BEDTIME OFF");
        bedtimeOffButton.setVisibility(View.GONE);
        root.addView(bedtimeOffButton);

        addButton.setOnClickListener(v -> redeemChildCode());
        bedtimeOnButton.setOnClickListener(v -> setBedtime(true));
        bedtimeOffButton.setOnClickListener(v -> setBedtime(false));

        setContentView(root);
        updateParentControls();
    }

    private void updateParentControls() {
        if (addButton == null) return;
        addButton.setEnabled(!addingChild && !parentToken.isEmpty());
        bedtimeOnButton.setVisibility(hasPairedChild && !addingChild ? View.VISIBLE : View.GONE);
        bedtimeOffButton.setVisibility(hasPairedChild && !addingChild ? View.VISIBLE : View.GONE);
    }

    private void ensureParentToken() {
        if (!parentToken.isEmpty()) {
            statusView.setText("Parent ready. Enter the code from a Child.");
            pollPairingStatus();
            updateParentControls();
            return;
        }

        statusView.setText("Creating Parent account…");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/pairing/parent", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);
                JSONObject data = new JSONObject(body);
                parentToken = data.getString("parentToken");
                getPreferences(MODE_PRIVATE).edit()
                        .putString(MODE, PARENT)
                        .putString("parent_token", parentToken)
                        .apply();
                runOnUiThread(() -> {
                    statusView.setText("Parent ready ✓ Enter the code shown on the Child.");
                    updateParentControls();
                });
                pollPairingStatus();
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Parent setup failed: " + safeMessage(e)));
            }
        }).start();
    }

    private void redeemChildCode() {
        if (parentToken.isEmpty()) {
            statusView.setText("Parent account is still starting…");
            ensureParentToken();
            return;
        }
        String code = pairingCodeInput.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            statusView.setText("Enter the 6-digit code shown on the Child.");
            return;
        }

        addingChild = true;
        updateParentControls();
        statusView.setText("Pairing Child…");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BACKEND + "/api/pairing/redeem", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Parent-Token", parentToken);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                String childId = data.getString("childId");
                selectedChildId = childId;
                getPreferences(MODE_PRIVATE).edit()
                        .putString("selected_child", childId)
                        .apply();

                runOnUiThread(() -> {
                    pairingCodeInput.setText("");
                    addingChild = false;
                    hasPairedChild = true;
                    statusView.setText("CHILD PAIRED ✓ — " + childId);
                    updateParentControls();
                });
                pollPairingStatus();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addingChild = false;
                    statusView.setText("Pairing failed: " + safeMessage(e));
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
                c.setRequestProperty("Cache-Control", "no-cache");
                int http = c.getResponseCode();
                String body = readBody(c, http);
                if (http < 200 || http >= 300) throw new Exception(body);

                JSONObject data = new JSONObject(body);
                JSONArray children = data.optJSONArray("children");
                boolean anyPaired = false;
                StringBuilder text = new StringBuilder("Children linked:");
                if (children == null || children.length() == 0) {
                    text.append(" none yet");
                } else {
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null) continue;
                        String id = child.optString("childId", "");
                        boolean paired = child.optBoolean("paired", false);
                        text.append("\n").append(i + 1).append(". ")
                                .append(id).append(" — ")
                                .append(paired ? "PAIRED ✓" : "WAITING");
                        if (paired) anyPaired = true;
                    }
                }

                final boolean finalAnyPaired = anyPaired;
                final String finalText = text.toString();
                runOnUiThread(() -> {
                    childrenView.setText(finalText);
                    hasPairedChild = finalAnyPaired;
                    updateParentControls();
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
