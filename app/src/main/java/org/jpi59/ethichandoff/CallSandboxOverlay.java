/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethichandoff;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Manages the full-screen secure in-call overlay window (TYPE_APPLICATION_OVERLAY).
 * Ensures that during lending, the guest strictly sees Ethic One Call's in-call
 * sandbox UI, completely covering any native dialer or background apps.
 */
public class CallSandboxOverlay {

    private static final String TAG = "CallSandboxOverlay";

    public interface Listener {
        void onHangUp();
        void onReclaim();
    }

    // Quiet Luxury Color Palette
    private static final int TEAL_DEEP = Color.rgb(10, 77, 64);      // #0A4D40
    private static final int TEAL_ACCENT = Color.rgb(15, 118, 110);  // #0F766E
    private static final int BG_LIGHT = Color.rgb(248, 250, 252);    // #F8FAFC
    private static final int SURFACE_WHITE = Color.WHITE;
    private static final int BORDER_SUBTLE = Color.rgb(226, 232, 240); // #E2E8F0
    private static final int TEXT_INK = Color.rgb(15, 23, 42);       // #0F172A
    private static final int TEXT_MUTED = Color.rgb(71, 85, 105);    // #475569
    private static final int CALL_GREEN = Color.rgb(21, 128, 61);    // #15803D
    private static final int END_RED = Color.rgb(220, 38, 38);       // #DC2626
    private static final int MUTE_AMBER = Color.rgb(217, 119, 6);    // #D97706

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder dtmfInput = new StringBuilder();

    private View overlayRoot;
    private TextView tvStatus;
    private TextView tvDuration;
    private View btnMute;
    private TextView tvMuteIcon;
    private TextView tvMuteLabel;
    private View mainControls;
    private View keypadContainer;
    private TextView tvDtmfDisplay;

    private Listener listener;
    private ToneGenerator toneGenerator;
    private boolean isMuteOn = false;
    private boolean isKeypadShowing = false;
    private long callStartTime = 0L;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayRoot != null && tvDuration != null) {
                long elapsed = (System.currentTimeMillis() - callStartTime) / 1000;
                long mins = elapsed / 60;
                long secs = elapsed % 60;
                tvDuration.setText(String.format(Locale.getDefault(), "●  %02d:%02d", mins, secs));
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    public CallSandboxOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return overlayRoot != null;
    }

    public void show(String phoneNumber, Listener listener) {
        dismiss();
        this.listener = listener;
        this.dtmfInput.setLength(0);
        this.isMuteOn = false;
        this.isKeypadShowing = false;
        this.callStartTime = System.currentTimeMillis();

        initToneGenerator();
        overlayRoot = buildView(phoneNumber);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.FILL;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        try {
            windowManager.addView(overlayRoot, params);
            mainHandler.post(timerRunnable);
            Log.d(TAG, "CallSandboxOverlay displayed on top");
        } catch (Exception e) {
            Log.e(TAG, "Error displaying CallSandboxOverlay", e);
        }
    }

    public void updateCallState(int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.incall_in_call);
                tvStatus.setTextColor(CALL_GREEN);
            }
        }
    }

    public void hideForBiometrics() {
        if (overlayRoot != null) {
            overlayRoot.setVisibility(View.GONE);
        }
    }

    public void restoreFromBiometrics() {
        if (overlayRoot != null) {
            overlayRoot.setVisibility(View.VISIBLE);
        }
    }

    public void dismiss() {
        mainHandler.removeCallbacks(timerRunnable);
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) { }
            overlayRoot = null;
        }
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception ignored) { }
            toneGenerator = null;
        }
        tvStatus = null;
        tvDuration = null;
        btnMute = null;
        tvMuteIcon = null;
        tvMuteLabel = null;
        mainControls = null;
        keypadContainer = null;
        tvDtmfDisplay = null;
    }

    private int dp(float n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }

    private View buildView(String phoneNumber) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(BG_LIGHT);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // System bar insets handling
        root.setOnApplyWindowInsetsListener((v, insets) -> {
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
            root.setPadding(dp(20), Math.max(dp(24), top + dp(4)), dp(20), Math.max(dp(20), bottom + dp(14)));
            return insets;
        });

        // Intercept Back key on overlay
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (isKeypadShowing) {
                    hideKeypad();
                }
                return true; // Consume Back key
            }
            return false;
        });

        // Header: Mode Badge
        TextView modeBadge = new TextView(context);
        modeBadge.setText("🔒 " + context.getString(R.string.incall_title).toUpperCase(Locale.ROOT));
        modeBadge.setTextSize(13);
        modeBadge.setTypeface(AppFont.bold());
        modeBadge.setTextColor(TEAL_DEEP);
        modeBadge.setLetterSpacing(0.08f);
        modeBadge.setGravity(Gravity.CENTER);
        root.addView(modeBadge);

        // Spacer
        View spaceTop = new View(context);
        root.addView(spaceTop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        // Recipient Number
        TextView tvNumber = new TextView(context);
        tvNumber.setText(phoneNumber);
        tvNumber.setTextSize(26);
        tvNumber.setTextColor(TEXT_INK);
        tvNumber.setTypeface(AppFont.bold());
        tvNumber.setLetterSpacing(-0.01f);
        tvNumber.setGravity(Gravity.CENTER);
        tvNumber.setSingleLine(true);
        tvNumber.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(tvNumber);

        // Call Status
        tvStatus = new TextView(context);
        tvStatus.setText(R.string.incall_calling);
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(TEAL_ACCENT);
        tvStatus.setTypeface(AppFont.semibold());
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(6), 0, 0);
        root.addView(tvStatus);

        // Call Duration
        tvDuration = new TextView(context);
        tvDuration.setText("●  00:00");
        tvDuration.setTextSize(16);
        tvDuration.setTextColor(TEXT_MUTED);
        tvDuration.setTypeface(AppFont.bold());
        tvDuration.setGravity(Gravity.CENTER);
        tvDuration.setPadding(0, dp(4), 0, dp(10));
        root.addView(tvDuration);

        // Audio Hint
        LinearLayout audioHintCard = new LinearLayout(context);
        audioHintCard.setOrientation(LinearLayout.HORIZONTAL);
        audioHintCard.setGravity(Gravity.CENTER);
        audioHintCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable audioBg = new GradientDrawable();
        audioBg.setColor(Color.rgb(240, 253, 244));
        audioBg.setCornerRadius(dp(12));
        audioBg.setStroke(dp(1), Color.rgb(187, 247, 208));
        audioHintCard.setBackground(audioBg);
        LinearLayout.LayoutParams audioParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        audioParams.setMargins(0, 0, 0, dp(14));
        audioHintCard.setLayoutParams(audioParams);

        TextView tvAudioHint = new TextView(context);
        tvAudioHint.setText(R.string.incall_audio_hint);
        tvAudioHint.setTextSize(13);
        tvAudioHint.setTypeface(AppFont.medium());
        tvAudioHint.setTextColor(Color.rgb(22, 101, 52));
        tvAudioHint.setGravity(Gravity.CENTER);
        audioHintCard.addView(tvAudioHint);
        root.addView(audioHintCard);

        // Main Content Container
        LinearLayout contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentContainer.setLayoutParams(contentParams);
        root.addView(contentContainer);

        // Main In-Call Controls
        mainControls = buildMainControlsView();
        contentContainer.addView(mainControls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // DTMF Keypad View
        keypadContainer = buildKeypadView();
        keypadContainer.setVisibility(View.GONE);
        contentContainer.addView(keypadContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // End Call Button (Ruby Red Button)
        Button btnEndCall = new Button(context);
        btnEndCall.setText("📵 " + context.getString(R.string.action_end_call));
        btnEndCall.setTextSize(18);
        btnEndCall.setTextColor(Color.WHITE);
        btnEndCall.setTypeface(AppFont.bold());
        btnEndCall.setLetterSpacing(0.01f);
        btnEndCall.setAllCaps(false);
        btnEndCall.setGravity(Gravity.CENTER);
        btnEndCall.setPadding(0, 0, 0, 0);
        GradientDrawable endCallBg = new GradientDrawable();
        endCallBg.setColor(END_RED);
        endCallBg.setCornerRadius(dp(18));
        btnEndCall.setBackground(endCallBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btnEndCall.setElevation(dp(3));
        }
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        endParams.setMargins(dp(6), dp(10), dp(6), dp(8));
        btnEndCall.setLayoutParams(endParams);
        btnEndCall.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (listener != null) listener.onHangUp();
        });
        root.addView(btnEndCall);

        // Bottom Reclaim Phone Bar
        Button btnReclaim = new Button(context);
        btnReclaim.setText("🛡️ " + context.getString(R.string.action_reclaim_phone));
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
        btnReclaim.setOnClickListener(v -> {
            if (listener != null) listener.onReclaim();
        });
        root.addView(btnReclaim);

        return root;
    }

    private View buildMainControlsView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Mute Button
        btnMute = new LinearLayout(context);
        ((LinearLayout) btnMute).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) btnMute).setGravity(Gravity.CENTER);
        btnMute.setPadding(dp(12), dp(16), dp(12), dp(16));
        updateMuteButtonUi();
        btnMute.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            toggleMute();
        });

        tvMuteIcon = new TextView(context);
        tvMuteIcon.setText("🎙️");
        tvMuteIcon.setTextSize(26);
        tvMuteIcon.setGravity(Gravity.CENTER);
        ((LinearLayout) btnMute).addView(tvMuteIcon);

        tvMuteLabel = new TextView(context);
        tvMuteLabel.setText(R.string.action_mute);
        tvMuteLabel.setTextSize(13);
        tvMuteLabel.setTypeface(AppFont.semibold());
        tvMuteLabel.setGravity(Gravity.CENTER);
        tvMuteLabel.setPadding(0, dp(6), 0, 0);
        ((LinearLayout) btnMute).addView(tvMuteLabel);

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p1.setMargins(dp(8), 0, dp(8), 0);
        row.addView(btnMute, p1);

        // Keypad Button
        LinearLayout btnKeypad = new LinearLayout(context);
        btnKeypad.setOrientation(LinearLayout.VERTICAL);
        btnKeypad.setGravity(Gravity.CENTER);
        btnKeypad.setPadding(dp(12), dp(16), dp(12), dp(16));
        GradientDrawable keyBg = new GradientDrawable();
        keyBg.setColor(SURFACE_WHITE);
        keyBg.setCornerRadius(dp(18));
        keyBg.setStroke(dp(1), BORDER_SUBTLE);
        btnKeypad.setBackground(keyBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btnKeypad.setElevation(dp(1.5f));
        }
        btnKeypad.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            showKeypad();
        });

        TextView tvKeyIcon = new TextView(context);
        tvKeyIcon.setText("🔢");
        tvKeyIcon.setTextSize(26);
        tvKeyIcon.setGravity(Gravity.CENTER);
        btnKeypad.addView(tvKeyIcon);

        TextView tvKeyLabel = new TextView(context);
        tvKeyLabel.setText(R.string.action_keypad);
        tvKeyLabel.setTextSize(13);
        tvKeyLabel.setTextColor(TEXT_INK);
        tvKeyLabel.setTypeface(AppFont.semibold());
        tvKeyLabel.setGravity(Gravity.CENTER);
        tvKeyLabel.setPadding(0, dp(6), 0, 0);
        btnKeypad.addView(tvKeyLabel);

        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p2.setMargins(dp(8), 0, dp(8), 0);
        row.addView(btnKeypad, p2);

        return row;
    }

    private View buildKeypadView() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);

        tvDtmfDisplay = new TextView(context);
        tvDtmfDisplay.setTextSize(22);
        tvDtmfDisplay.setTextColor(TEAL_DEEP);
        tvDtmfDisplay.setTypeface(AppFont.bold());
        tvDtmfDisplay.setLetterSpacing(0.04f);
        tvDtmfDisplay.setGravity(Gravity.CENTER);
        tvDtmfDisplay.setPadding(0, 0, 0, dp(6));
        tvDtmfDisplay.setSingleLine(true);
        layout.addView(tvDtmfDisplay);

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"*", "0", "#"}
        };

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            row.setLayoutParams(rowP);

            for (int c = 0; c < 3; c++) {
                final String k = keys[r][c];
                Button b = new Button(context);
                b.setText(k);
                b.setTextSize(20);
                b.setTextColor(TEXT_INK);
                b.setTypeface(AppFont.bold());
                b.setGravity(Gravity.CENTER);
                GradientDrawable bBg = new GradientDrawable();
                bBg.setColor(SURFACE_WHITE);
                bBg.setCornerRadius(dp(14));
                bBg.setStroke(dp(1), BORDER_SUBTLE);
                b.setBackground(bBg);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    b.setElevation(dp(1));
                }
                LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                bParams.setMargins(dp(4), dp(2), dp(4), dp(2));
                b.setLayoutParams(bParams);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    playDtmf(k.charAt(0));
                    dtmfInput.append(k);
                    if (tvDtmfDisplay != null) tvDtmfDisplay.setText(dtmfInput.toString());
                });
                row.addView(b);
            }
            layout.addView(row);
        }

        Button btnHide = new Button(context);
        btnHide.setText(R.string.action_hide_keypad);
        btnHide.setTextSize(13);
        btnHide.setTextColor(TEAL_ACCENT);
        btnHide.setTypeface(AppFont.semibold());
        btnHide.setAllCaps(false);
        btnHide.setBackground(null);
        btnHide.setOnClickListener(v -> hideKeypad());
        layout.addView(btnHide);

        return layout;
    }

    private void showKeypad() {
        isKeypadShowing = true;
        if (mainControls != null) mainControls.setVisibility(View.GONE);
        if (keypadContainer != null) keypadContainer.setVisibility(View.VISIBLE);
    }

    private void hideKeypad() {
        isKeypadShowing = false;
        if (keypadContainer != null) keypadContainer.setVisibility(View.GONE);
        if (mainControls != null) mainControls.setVisibility(View.VISIBLE);
    }

    private void toggleMute() {
        isMuteOn = !isMuteOn;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setMicrophoneMute(isMuteOn);
            } catch (Exception ignored) { }
        }
        updateMuteButtonUi();
    }

    private void updateMuteButtonUi() {
        if (btnMute == null) return;
        GradientDrawable bg = new GradientDrawable();
        if (isMuteOn) {
            bg.setColor(MUTE_AMBER);
            bg.setCornerRadius(dp(18));
        } else {
            bg.setColor(SURFACE_WHITE);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), BORDER_SUBTLE);
        }
        btnMute.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btnMute.setElevation(dp(1.5f));
        }
        if (tvMuteIcon != null) {
            tvMuteIcon.setTextColor(isMuteOn ? Color.WHITE : TEXT_INK);
        }
        if (tvMuteLabel != null) {
            tvMuteLabel.setText(isMuteOn ? R.string.action_mute_on : R.string.action_mute);
            tvMuteLabel.setTextColor(isMuteOn ? Color.WHITE : TEXT_INK);
        }
    }

    private void initToneGenerator() {
        if (toneGenerator == null) {
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
            } catch (Exception ignored) { }
        }
    }

    private void playDtmf(char digit) {
        if (toneGenerator == null) return;
        int tone = -1;
        switch (digit) {
            case '0': tone = ToneGenerator.TONE_DTMF_0; break;
            case '1': tone = ToneGenerator.TONE_DTMF_1; break;
            case '2': tone = ToneGenerator.TONE_DTMF_2; break;
            case '3': tone = ToneGenerator.TONE_DTMF_3; break;
            case '4': tone = ToneGenerator.TONE_DTMF_4; break;
            case '5': tone = ToneGenerator.TONE_DTMF_5; break;
            case '6': tone = ToneGenerator.TONE_DTMF_6; break;
            case '7': tone = ToneGenerator.TONE_DTMF_7; break;
            case '8': tone = ToneGenerator.TONE_DTMF_8; break;
            case '9': tone = ToneGenerator.TONE_DTMF_9; break;
            case '*': tone = ToneGenerator.TONE_DTMF_S; break;
            case '#': tone = ToneGenerator.TONE_DTMF_P; break;
        }
        if (tone != -1) {
            try {
                toneGenerator.startTone(tone, 150);
            } catch (Exception ignored) { }
        }
    }
}
