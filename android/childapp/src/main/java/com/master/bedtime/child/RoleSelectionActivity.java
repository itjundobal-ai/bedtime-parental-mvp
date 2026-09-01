package com.master.bedtime.child;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class RoleSelectionActivity extends Activity {
    private static final String PREFS = "app_role";
    private static final String KEY_ROLE = "role";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String savedRole = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROLE, "");
        if ("parent".equals(savedRole)) {
            openParent();
            return;
        }
        if ("child".equals(savedRole)) {
            openChild();
            return;
        }

        setContentView(R.layout.activity_role_selection);

        Button parent = findViewById(R.id.btnParentMode);
        Button child = findViewById(R.id.btnChildMode);

        parent.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROLE, "parent").apply();
            openParent();
        });

        child.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROLE, "child").apply();
            openChild();
        });
    }

    private void openParent() {
        startActivity(new Intent(this, ParentActivity.class));
        finish();
    }

    private void openChild() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
