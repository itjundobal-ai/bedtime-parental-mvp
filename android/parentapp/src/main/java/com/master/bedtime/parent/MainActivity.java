package com.master.bedtime.parent;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private EditText backendUrl, childId;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        backendUrl = findViewById(R.id.backendUrl);
        childId = findViewById(R.id.childId);
        status = findViewById(R.id.status);
        Button btnOn = findViewById(R.id.btnOn);
        Button btnOff = findViewById(R.id.btnOff);

        backendUrl.setText(getPreferences(MODE_PRIVATE).getString("backend", "http://10.0.2.2:8080"));
        childId.setText(getPreferences(MODE_PRIVATE).getString("child", "child-001"));

        btnOn.setOnClickListener(v -> setBedtime(true));
        btnOff.setOnClickListener(v -> setBedtime(false));
    }

    private void setBedtime(boolean enabled) {
        final String base = backendUrl.getText().toString().trim().replaceAll("/$", "");
        final String id = childId.getText().toString().trim();
        if (base.isEmpty() || id.isEmpty()) return;

        getPreferences(MODE_PRIVATE).edit().putString("backend", base).putString("child", id).apply();
        status.setText(enabled ? "Sending Bedtime ON…" : "Sending Bedtime OFF…");

        new Thread(() -> {
            try {
                URL url = new URL(base + "/api/children/" + id + "/bedtime");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/json");
                String parentKey = getPreferences(MODE_PRIVATE).getString("parent_key", "");
                if (!parentKey.isEmpty()) c.setRequestProperty("X-Parent-Key", parentKey);
                c.setDoOutput(true);
                byte[] body = ("{\"active\":" + enabled + "}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
                int code = c.getResponseCode();
                runOnUiThread(() -> {
                    if (code >= 200 && code < 300) {
                        status.setText("Bedtime: " + (enabled ? "ON" : "OFF"));
                    } else {
                        status.setText("Server error: " + code);
                    }
                });
                c.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Connection failed");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
