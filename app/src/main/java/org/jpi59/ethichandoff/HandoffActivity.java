/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethichandoff;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class HandoffActivity extends Activity {
    private final int teal = Color.rgb(0, 105, 92);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        showHome();
    }

    private TextView t(int id, float size) {
        TextView v = new TextView(this);
        v.setText(id);
        v.setTextSize(size);
        v.setTextColor(Color.rgb(24, 35, 34));
        v.setPadding(0, 8, 0, 8);
        return v;
    }

    private Button b(int id) {
        Button x = new Button(this);
        x.setText(id);
        x.setTextSize(16);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        x.setBackgroundColor(teal);
        return x;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void showHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(244, 247, 246));
        setContentView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(8));

        ImageButton btnMenu = new ImageButton(this);
        btnMenu.setImageResource(R.drawable.ic_settings_menu);
        btnMenu.setBackground(null);
        btnMenu.setColorFilter(teal);
        btnMenu.setContentDescription(getString(R.string.menu_title));
        btnMenu.setOnClickListener(v -> EthicEcosystemMenu.show(this, false));
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView headerTitle = t(R.string.title, 26);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(8));
        topBar.addView(headerTitle, titleParams);
        root.addView(topBar);

        root.addView(t(R.string.subtitle, 17));
        TextView p = t(R.string.privacy, 15);
        p.setTextColor(teal);
        root.addView(p);

        root.addView(t(R.string.choose_app, 20));
        TextView scope = t(R.string.app_name, 18);
        scope.setTextColor(teal);
        root.addView(scope);

        TextView info = t(R.string.instructions, 15);
        info.setTextColor(Color.DKGRAY);
        root.addView(info);

        Button start = b(R.string.start);
        start.setOnClickListener(v -> openPhone());
        root.addView(start);

        Button settings = new Button(this);
        settings.setText(R.string.settings);
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        root.addView(settings);
    }

    private void openPhone() {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL));
            Toast.makeText(this, R.string.started, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_enabled, Toast.LENGTH_LONG).show();
        }
    }
}
