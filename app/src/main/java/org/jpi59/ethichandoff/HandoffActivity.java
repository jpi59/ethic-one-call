/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethichandoff;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HandoffActivity extends Activity implements CallSandboxOverlay.Listener {

    private static final String TAG = "EthicOneCall";
    private static final int REQUEST_APP_PERMISSIONS = 1001;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE
    };

    // Quiet Luxury Color Palette
    private static final int TEAL_DEEP = Color.rgb(10, 77, 64);      // #0A4D40 Deep Forest Teal
    private static final int TEAL_ACCENT = Color.rgb(15, 118, 110);  // #0F766E Vibrant Teal
    private static final int BG_LIGHT = Color.rgb(248, 250, 252);    // #F8FAFC Warm Crisp Slate
    private static final int SURFACE_WHITE = Color.WHITE;
    private static final int BORDER_SUBTLE = Color.rgb(226, 232, 240); // #E2E8F0 Fine Slate Border
    private static final int TEXT_INK = Color.rgb(15, 23, 42);       // #0F172A Deep Ink Slate
    private static final int TEXT_MUTED = Color.rgb(71, 85, 105);    // #475569 Secondary Slate
    private static final int TEXT_TERTIARY = Color.rgb(148, 163, 184); // #94A3B8 Subtle Slate
    private static final int CALL_GREEN = Color.rgb(21, 128, 61);    // #15803D Emerald Green 700

    private final StringBuilder dialedNumber = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isLockedMode = false;
    private boolean isInCall = false;
    private boolean isAuthenticating = false;
    private long callPlacedTimestamp = 0L;

    private String activeCallNumber = "";
    private TextView numberDisplay;

    private CallSandboxOverlay callSandboxOverlay;
    private PhoneStateListener phoneStateListener;
    private PowerManager.WakeLock proximityWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppFont.init(this);
        initProximityWakeLock();
        callSandboxOverlay = new CallSandboxOverlay(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(Color.WHITE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (isInCall) return;
                        if (isLockedMode) {
                            if (dialedNumber.length() > 0) {
                                dialedNumber.deleteCharAt(dialedNumber.length() - 1);
                                updateDisplay();
                            } else {
                                Toast.makeText(HandoffActivity.this, R.string.locked_notice, Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        finish();
                    }
            );
        }

        isLockedMode = getSharedPreferences("ethic_state", Context.MODE_PRIVATE).getBoolean("is_locked", false);
        if (savedInstanceState != null) {
            isLockedMode = savedInstanceState.getBoolean("isLockedMode", isLockedMode);
            String savedDial = savedInstanceState.getString("dialedNumber", "");
            dialedNumber.setLength(0);
            dialedNumber.append(savedDial);
        }

        if (isLockedMode) {
            applyImmersiveMode();
            showDialpadScreen();
            enterLockTaskMode();
        } else {
            showHomeScreen();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isLockedMode", isLockedMode);
        outState.putString("dialedNumber", dialedNumber.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isLockedMode) {
            applyImmersiveMode();
            if (!isInCall && !isAuthenticating) {
                enterLockTaskMode();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isLockedMode) {
            applyImmersiveMode();
            if (!hasFocus && !isAuthenticating && !isInCall) {
                bringSelfToFront();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        isLockedMode = getSharedPreferences("ethic_state", Context.MODE_PRIVATE).getBoolean("is_locked", isLockedMode);
        if (isLockedMode) {
            applyImmersiveMode();
            showDialpadScreen();
            enterLockTaskMode();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isLockedMode && !isInCall && !isAuthenticating) {
            bringSelfToFront();
        }
    }

    private void bringSelfToFront() {
        if (!isLockedMode || isAuthenticating) return;
        try {
            Intent intent = new Intent(this, HandoffActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception ignored) { }
    }

    private void enterLockTaskMode() {
        // Lock task mode removed: Android shows an undismissable "App pinned"
        // system toast that degrades UX. The app relies on bringSelfToFront(),
        // back-button interception, immersive mode, and the call overlay instead.
    }

    private void exitLockTaskMode() {
        // No-op: lock task mode is no longer used.
    }

    @Override
    public void onBackPressed() {
        if (isInCall) return;
        if (isLockedMode) {
            if (dialedNumber.length() > 0) {
                dialedNumber.deleteCharAt(dialedNumber.length() - 1);
                updateDisplay();
            } else {
                Toast.makeText(this, R.string.locked_notice, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterCallListener();
        releaseProximityWakeLock();
        if (callSandboxOverlay != null) {
            callSandboxOverlay.dismiss();
            callSandboxOverlay = null;
        }
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void applySystemBarInsets(View container, View targetPaddingView, int horizontalDp, int topMinDp, int bottomMinDp) {
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            targetPaddingView.setPadding(
                    dp(horizontalDp),
                    Math.max(dp(topMinDp), top + dp(4)),
                    dp(horizontalDp),
                    Math.max(dp(bottomMinDp), bottom + dp(14))
            );
            return insets;
        });
    }

    // -------------------------------------------------------------
    // Home Screen (Owner Entry Point)
    // -------------------------------------------------------------
    private void showHomeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_LIGHT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(root);
        applySystemBarInsets(scroll, root, 24, 20, 24);
        setContentView(scroll);
        applyImmersiveMode();

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(14));

        ImageButton btnMenu = new ImageButton(this);
        btnMenu.setImageResource(R.drawable.ic_settings_menu);
        btnMenu.setBackground(null);
        btnMenu.setColorFilter(TEAL_DEEP);
        btnMenu.setContentDescription(getString(R.string.menu_title));
        btnMenu.setOnClickListener(v -> EthicEcosystemMenu.show(this, false));
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView headerTitle = new TextView(this);
        headerTitle.setText(R.string.title);
        headerTitle.setTextSize(26);
        headerTitle.setTypeface(AppFont.bold());
        headerTitle.setTextColor(TEXT_INK);
        headerTitle.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        topBar.addView(headerTitle, titleParams);
        root.addView(topBar);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subtitle);
        subtitle.setTextSize(15);
        subtitle.setTypeface(AppFont.regular());
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setLineSpacing(dp(2), 1f);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        // Privacy Card
        LinearLayout privacyCard = new LinearLayout(this);
        privacyCard.setOrientation(LinearLayout.VERTICAL);
        privacyCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable privBg = new GradientDrawable();
        privBg.setColor(Color.rgb(240, 253, 244));
        privBg.setCornerRadius(dp(14));
        privBg.setStroke(dp(1), Color.rgb(187, 247, 208));
        privacyCard.setBackground(privBg);
        LinearLayout.LayoutParams privParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privParams.setMargins(0, 0, 0, dp(18));
        privacyCard.setLayoutParams(privParams);

        TextView privText = new TextView(this);
        privText.setText(R.string.privacy);
        privText.setTextSize(13);
        privText.setTextColor(Color.rgb(22, 101, 52));
        privText.setTypeface(AppFont.medium());
        privText.setLineSpacing(dp(3), 1f);
        privacyCard.addView(privText);
        root.addView(privacyCard);

        // Instructions Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(SURFACE_WHITE);
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), BORDER_SUBTLE);
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(26));
        card.setLayoutParams(cardParams);

        TextView cardTitle = new TextView(this);
        cardTitle.setText(R.string.instructions_title);
        cardTitle.setTextSize(16);
        cardTitle.setTypeface(AppFont.bold());
        cardTitle.setTextColor(TEXT_INK);
        card.addView(cardTitle);

        TextView cardBody = new TextView(this);
        cardBody.setText(R.string.instructions_body);
        cardBody.setTextSize(14);
        cardBody.setTypeface(AppFont.regular());
        cardBody.setTextColor(TEXT_INK);
        cardBody.setLineSpacing(dp(5), 1f);
        cardBody.setPadding(0, dp(10), 0, 0);
        card.addView(cardBody);
        root.addView(card);

        // Primary Button: Lend Phone
        Button btnLend = new Button(this);
        btnLend.setText(R.string.action_lend_phone);
        btnLend.setTextSize(18);
        btnLend.setTextColor(Color.WHITE);
        btnLend.setTypeface(AppFont.bold());
        btnLend.setLetterSpacing(0.01f);
        btnLend.setAllCaps(false);
        GradientDrawable btnLendBg = new GradientDrawable();
        btnLendBg.setColor(TEAL_DEEP);
        btnLendBg.setCornerRadius(dp(18));
        btnLend.setBackground(btnLendBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btnLend.setElevation(dp(3));
        }
        LinearLayout.LayoutParams lendParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lendParams.setMargins(0, dp(6), 0, dp(16));
        btnLend.setLayoutParams(lendParams);
        btnLend.setOnClickListener(v -> initiateLendPhone());
        root.addView(btnLend);
    }

    private void initiateLendPhone() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && !km.isDeviceSecure()) {
            Toast.makeText(this, R.string.secure_device_required, Toast.LENGTH_LONG).show();
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_APP_PERMISSIONS);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog();
            return;
        }

        promptBiometric(
                getString(R.string.auth_start_title),
                getString(R.string.auth_start_subtitle),
                () -> {
                    isLockedMode = true;
                    getSharedPreferences("ethic_state", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_locked", true).apply();
                    isInCall = false;
                    dialedNumber.setLength(0);
                    applyImmersiveMode();
                    showDialpadScreen();
                    enterLockTaskMode();
                },
                null
        );
    }

    private void showOverlayPermissionDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.action_configure, (d, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())
                            );
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                                startActivity(intent);
                            } catch (Exception ignored) { }
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_APP_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initiateLendPhone();
            } else {
                Toast.makeText(this, R.string.permissions_missing, Toast.LENGTH_LONG).show();
            }
        }
    }

    // -------------------------------------------------------------
    // Dialpad Screen (Guest Kiosk Mode with Premium Typography)
    // -------------------------------------------------------------
    private void showDialpadScreen() {
        isInCall = false;
        releaseProximityWakeLock();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(BG_LIGHT);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        applySystemBarInsets(root, root, 20, 16, 16);
        setContentView(root);
        applyImmersiveMode();

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, dp(10));

        TextView modeBadge = new TextView(this);
        modeBadge.setText("🔒 " + getString(R.string.mode_active_title).toUpperCase(Locale.ROOT));
        modeBadge.setTextSize(13);
        modeBadge.setTypeface(AppFont.bold());
        modeBadge.setTextColor(TEAL_DEEP);
        modeBadge.setLetterSpacing(0.06f);
        header.addView(modeBadge);

        TextView modeSub = new TextView(this);
        modeSub.setText(R.string.mode_active_subtitle);
        modeSub.setTextSize(12);
        modeSub.setTypeface(AppFont.regular());
        modeSub.setTextColor(TEXT_MUTED);
        modeSub.setGravity(Gravity.CENTER);
        modeSub.setPadding(0, dp(2), 0, 0);
        header.addView(modeSub);
        root.addView(header);

        // Display row
        LinearLayout displayRow = new LinearLayout(this);
        displayRow.setOrientation(LinearLayout.HORIZONTAL);
        displayRow.setGravity(Gravity.CENTER_VERTICAL);
        displayRow.setPadding(dp(18), dp(8), dp(12), dp(8));
        GradientDrawable dispBg = new GradientDrawable();
        dispBg.setColor(SURFACE_WHITE);
        dispBg.setCornerRadius(dp(18));
        dispBg.setStroke(dp(1), BORDER_SUBTLE);
        displayRow.setBackground(dispBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            displayRow.setElevation(dp(2));
        }
        LinearLayout.LayoutParams dispParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        dispParams.setMargins(dp(6), 0, dp(6), dp(12));
        displayRow.setLayoutParams(dispParams);

        numberDisplay = new TextView(this);
        numberDisplay.setTextSize(22);
        numberDisplay.setTextColor(TEXT_INK);
        numberDisplay.setHint(R.string.dial_hint);
        numberDisplay.setHintTextColor(TEXT_TERTIARY);
        numberDisplay.setSingleLine(true);
        numberDisplay.setEllipsize(TextUtils.TruncateAt.END);
        numberDisplay.setTypeface(AppFont.bold());
        numberDisplay.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        displayRow.addView(numberDisplay, textParams);

        Button btnBackspace = new Button(this);
        btnBackspace.setText("⌫");
        btnBackspace.setTextSize(22);
        btnBackspace.setTextColor(TEAL_ACCENT);
        btnBackspace.setBackground(null);
        btnBackspace.setContentDescription(getString(R.string.clear_action));
        btnBackspace.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (dialedNumber.length() > 0) {
                dialedNumber.deleteCharAt(dialedNumber.length() - 1);
                updateDisplay();
            }
        });
        btnBackspace.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            dialedNumber.setLength(0);
            updateDisplay();
            return true;
        });
        displayRow.addView(btnBackspace, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(displayRow);
        updateDisplay();

        // Keypad Container
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams keypadParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        keypad.setLayoutParams(keypadParams);

        String[][] keys = {
                {"1", ""}, {"2", "ABC"}, {"3", "DEF"},
                {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
                {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
                {"*", ""}, {"0", "+"}, {"#", ""}
        };

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            row.setLayoutParams(rowParams);

            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                final String mainKey = keys[idx][0];
                final String subKey = keys[idx][1];

                LinearLayout keyBtn = new LinearLayout(this);
                keyBtn.setOrientation(LinearLayout.VERTICAL);
                keyBtn.setGravity(Gravity.CENTER);

                GradientDrawable keyBg = new GradientDrawable();
                keyBg.setColor(SURFACE_WHITE);
                keyBg.setCornerRadius(dp(20));
                keyBg.setStroke(dp(1), BORDER_SUBTLE);
                keyBtn.setBackground(keyBg);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    keyBtn.setElevation(dp(1.5f));
                }

                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                keyParams.setMargins(dp(6), dp(4), dp(6), dp(4));
                keyBtn.setLayoutParams(keyParams);

                TextView tvMain = new TextView(this);
                tvMain.setText(mainKey);
                tvMain.setTextSize(30);
                tvMain.setTypeface(AppFont.bold());
                tvMain.setTextColor(TEXT_INK);
                tvMain.setLetterSpacing(-0.02f);
                tvMain.setGravity(Gravity.CENTER);
                tvMain.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tvMain.setLayoutParams(mainParams);
                keyBtn.addView(tvMain);

                if (!subKey.isEmpty()) {
                    TextView tvSub = new TextView(this);
                    tvSub.setText(subKey);
                    tvSub.setTextSize(11);
                    tvSub.setTypeface(AppFont.semibold());
                    tvSub.setTextColor(TEXT_MUTED);
                    tvSub.setLetterSpacing(0.08f);
                    tvSub.setGravity(Gravity.CENTER);
                    tvSub.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                    LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    tvSub.setLayoutParams(subParams);
                    keyBtn.addView(tvSub);
                }

                keyBtn.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    dialedNumber.append(mainKey);
                    updateDisplay();
                });

                if ("0".equals(mainKey)) {
                    keyBtn.setOnLongClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        dialedNumber.append("+");
                        updateDisplay();
                        return true;
                    });
                }

                row.addView(keyBtn);
            }
            keypad.addView(row);
        }
        root.addView(keypad);

        // Call Button
        Button btnCall = new Button(this);
        btnCall.setText("📞 " + getString(R.string.call_action));
        btnCall.setTextSize(18);
        btnCall.setTextColor(Color.WHITE);
        btnCall.setTypeface(AppFont.bold());
        btnCall.setLetterSpacing(0.01f);
        btnCall.setAllCaps(false);
        btnCall.setGravity(Gravity.CENTER);
        btnCall.setPadding(0, 0, 0, 0);
        GradientDrawable callBg = new GradientDrawable();
        callBg.setColor(CALL_GREEN);
        callBg.setCornerRadius(dp(18));
        btnCall.setBackground(callBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btnCall.setElevation(dp(3));
        }
        LinearLayout.LayoutParams callParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        callParams.setMargins(dp(6), dp(10), dp(6), dp(10));
        btnCall.setLayoutParams(callParams);
        btnCall.setOnClickListener(v -> placePhoneCall());
        root.addView(btnCall);

        // Reclaim Button
        Button btnReclaim = new Button(this);
        btnReclaim.setText("🛡️ " + getString(R.string.action_reclaim_phone));
        btnReclaim.setTextSize(16);
        btnReclaim.setTextColor(Color.rgb(30, 41, 59));
        btnReclaim.setTypeface(AppFont.bold());
        btnReclaim.setLetterSpacing(0.01f);
        btnReclaim.setAllCaps(false);
        btnReclaim.setGravity(Gravity.CENTER);
        btnReclaim.setPadding(0, 0, 0, 0);
        GradientDrawable recBg = new GradientDrawable();
        recBg.setColor(Color.rgb(241, 245, 249));
        recBg.setCornerRadius(dp(16));
        recBg.setStroke(dp(1), Color.rgb(203, 213, 225));
        btnReclaim.setBackground(recBg);
        LinearLayout.LayoutParams recParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        recParams.setMargins(dp(6), 0, dp(6), dp(4));
        btnReclaim.setLayoutParams(recParams);
        btnReclaim.setOnClickListener(v -> initiateReclaimPhone());
        root.addView(btnReclaim);
    }

    private void updateDisplay() {
        if (numberDisplay != null) {
            numberDisplay.setText(dialedNumber.toString());
        }
    }

    private void placePhoneCall() {
        if (dialedNumber.length() == 0) {
            Toast.makeText(this, R.string.call_empty_number, Toast.LENGTH_SHORT).show();
            return;
        }

        activeCallNumber = dialedNumber.toString();
        isInCall = true;
        callPlacedTimestamp = System.currentTimeMillis();

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);
            } catch (Exception ignored) { }
        }

        registerCallListener();
        acquireProximityWakeLock();

        // Show the in-call kiosk overlay strictly over any native dialer
        if (callSandboxOverlay != null) {
            callSandboxOverlay.show(activeCallNumber, this);
        }

        Uri uri = Uri.parse("tel:" + Uri.encode(activeCallNumber));
        TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager != null && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Bundle extras = new Bundle();
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);
            try {
                telecomManager.placeCall(uri, extras);
            } catch (Exception e) {
                Log.e(TAG, "Error placing call via TelecomManager", e);
                try {
                    startActivity(new Intent(Intent.ACTION_CALL, uri));
                } catch (Exception ex) {
                    Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_CALL, uri));
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onHangUp() {
        hangUpCall();
    }

    @Override
    public void onReclaim() {
        initiateReclaimPhone();
    }

    private void hangUpCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null && checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager.endCall();
                } catch (Exception e) {
                    Log.e(TAG, "Error ending call", e);
                }
            }
        }
        onCallTerminated();
    }

    private void onCallTerminated() {
        isInCall = false;
        releaseProximityWakeLock();
        unregisterCallListener();

        if (callSandboxOverlay != null) {
            callSandboxOverlay.dismiss();
        }

        Toast.makeText(this, R.string.incall_ended, Toast.LENGTH_SHORT).show();
        if (isLockedMode) {
            applyImmersiveMode();
            showDialpadScreen();
            enterLockTaskMode();
            bringSelfToFront();
        } else {
            showHomeScreen();
        }
    }

    // -------------------------------------------------------------
    // Telephony Call State Monitoring
    // -------------------------------------------------------------
    private void handleCallStateChanged(int state) {
        mainHandler.post(() -> {
            if (!isInCall) return;
            Log.d(TAG, "handleCallStateChanged: state=" + state);
            if (callSandboxOverlay != null) {
                callSandboxOverlay.updateCallState(state);
            }

            if (state == TelephonyManager.CALL_STATE_IDLE) {
                long elapsed = System.currentTimeMillis() - callPlacedTimestamp;
                if (elapsed < 3000) {
                    Log.d(TAG, "Ignoring initial premature CALL_STATE_IDLE");
                    return;
                }
                Log.d(TAG, "Call ended via CALL_STATE_IDLE");
                onCallTerminated();
            }
        });
    }

    private void registerCallListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                TelephonyApi31Helper.register(tm, getMainExecutor(), this::handleCallStateChanged);
            } else {
                registerLegacyCallListener(tm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering call listener", e);
        }
    }

    private void unregisterCallListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TelephonyApi31Helper.unregister(tm);
        } else {
            unregisterLegacyCallListener(tm);
        }
    }

    @SuppressWarnings("deprecation")
    private void registerLegacyCallListener(TelephonyManager tm) {
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                handleCallStateChanged(state);
            }
        };
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @SuppressWarnings("deprecation")
    private void unregisterLegacyCallListener(TelephonyManager tm) {
        if (phoneStateListener != null) {
            try {
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) { }
            phoneStateListener = null;
        }
    }

    // -------------------------------------------------------------
    // Proximity Sensor
    // -------------------------------------------------------------
    private void initProximityWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "EthicOneCall:Proximity");
                proximityWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up proximity wake lock", e);
        }
    }

    private void acquireProximityWakeLock() {
        try {
            if (proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                proximityWakeLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseProximityWakeLock() {
        try {
            if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            }
        } catch (Exception ignored) { }
    }

    // -------------------------------------------------------------
    // Reclaim Phone & Owner Authentication
    // -------------------------------------------------------------
    private void initiateReclaimPhone() {
        // Temporarily hide overlay so BiometricPrompt is not blocked by anti-tapjacking protection
        if (callSandboxOverlay != null) {
            callSandboxOverlay.hideForBiometrics();
        }
        bringSelfToFront();

        promptBiometric(
                getString(R.string.auth_exit_title),
                getString(R.string.auth_exit_subtitle),
                () -> {
                    if (isInCall) {
                        hangUpCall();
                    }
                    if (callSandboxOverlay != null) {
                        callSandboxOverlay.dismiss();
                    }
                    exitLockTaskMode();
                    isLockedMode = false;
                    getSharedPreferences("ethic_state", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_locked", false).apply();
                    isInCall = false;
                    dialedNumber.setLength(0);
                    showHomeScreen();
                },
                () -> {
                    // Restore overlay if biometrics cancelled or failed while in call
                    if (callSandboxOverlay != null && isInCall) {
                        callSandboxOverlay.restoreFromBiometrics();
                    }
                }
        );
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveMode() {
        if (getWindow() == null || getWindow().getDecorView() == null) return;

        if (isLockedMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void promptBiometric(String title, String subtitle, Runnable onAuthenticated, Runnable onError) {
        isAuthenticating = true;
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle(subtitle);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG |
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            );
        } else {
            builder.setDeviceCredentialAllowed(true);
        }

        BiometricPrompt prompt = builder.build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                isAuthenticating = false;
                mainHandler.post(onAuthenticated);
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                isAuthenticating = false;
                Log.d(TAG, "Biometric error: " + errorCode + " - " + errString);
                if (onError != null) {
                    mainHandler.post(onError);
                }
            }
        });
    }

    // -------------------------------------------------------------
    // API 31+ Telephony Helper
    // -------------------------------------------------------------
    private static class TelephonyApi31Helper {
        private static Object callbackInstance;

        static void register(TelephonyManager tm, java.util.concurrent.Executor executor, CallStateConsumer consumer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
                    @Override
                    public void onCallStateChanged(int state) {
                        consumer.accept(state);
                    }
                }
                callbackInstance = new CallStateCallback();
                tm.registerTelephonyCallback(executor, (TelephonyCallback) callbackInstance);
            }
        }

        static void unregister(TelephonyManager tm) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callbackInstance instanceof TelephonyCallback) {
                try {
                    tm.unregisterTelephonyCallback((TelephonyCallback) callbackInstance);
                } catch (Exception ignored) { }
                callbackInstance = null;
            }
        }
    }

    @FunctionalInterface
    private interface CallStateConsumer {
        void accept(int state);
    }
}
