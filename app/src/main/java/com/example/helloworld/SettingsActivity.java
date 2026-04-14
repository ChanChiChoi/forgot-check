package com.example.helloworld;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.appcompat.widget.SwitchCompat;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "checkin_settings";
    private static final String KEY_VIBRATION = "vibration_enabled";
    private static final String KEY_POPUP = "popup_enabled";
    private static final String KEY_NOTIFICATION = "notification_enabled";
    private static final String KEY_COUNTDOWN = "countdown_enabled";
    private static final String KEY_COUNTDOWN_SECONDS = "countdown_seconds";
    private static final String KEY_DEBUG = "debug_enabled";
    private static final int DEFAULT_COUNTDOWN_SECONDS = 10;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        SwitchCompat switchVibration = findViewById(R.id.switchVibration);
        SwitchCompat switchPopup = findViewById(R.id.switchPopup);
        SwitchCompat switchNotification = findViewById(R.id.switchNotification);
        SwitchCompat switchCountdown = findViewById(R.id.switchCountdown);
        SwitchCompat switchDebug = findViewById(R.id.switchDebug);
        TextInputEditText etCountdownSeconds = findViewById(R.id.etCountdownSeconds);

        // Load saved values (all default to true/enabled)
        switchVibration.setChecked(prefs.getBoolean(KEY_VIBRATION, true));
        switchPopup.setChecked(prefs.getBoolean(KEY_POPUP, true));
        switchNotification.setChecked(prefs.getBoolean(KEY_NOTIFICATION, true));
        switchCountdown.setChecked(prefs.getBoolean(KEY_COUNTDOWN, true));
        switchDebug.setChecked(prefs.getBoolean(KEY_DEBUG, false));

        // Load countdown seconds (default 10)
        int savedSeconds = prefs.getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS);
        etCountdownSeconds.setText(String.valueOf(savedSeconds));

        // Save on toggle
        switchVibration.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(KEY_VIBRATION, isChecked).apply());
        switchPopup.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(KEY_POPUP, isChecked).apply());
        switchNotification.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(KEY_NOTIFICATION, isChecked).apply());
        switchCountdown.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(KEY_COUNTDOWN, isChecked).apply());
        switchDebug.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(KEY_DEBUG, isChecked).apply());

        // Save countdown seconds on text change
        etCountdownSeconds.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveCountdownSeconds(etCountdownSeconds);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Also save when leaving the page
        TextInputEditText etCountdownSeconds = findViewById(R.id.etCountdownSeconds);
        saveCountdownSeconds(etCountdownSeconds);
    }

    private void saveCountdownSeconds(TextInputEditText editText) {
        String text = editText.getText() != null ? editText.getText().toString().trim() : "";
        if (!TextUtils.isEmpty(text)) {
            try {
                int seconds = Integer.parseInt(text);
                if (seconds > 0 && seconds <= 300) { // max 5 minutes
                    prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, seconds).apply();
                }
            } catch (NumberFormatException e) {
                // Invalid input, ignore
            }
        }
    }

    /**
     * Helper: check if vibration is enabled
     */
    public static boolean isVibrationEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VIBRATION, true);
    }

    /**
     * Helper: check if popup is enabled
     */
    public static boolean isPopupEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_POPUP, true);
    }

    /**
     * Helper: check if notification is enabled
     */
    public static boolean isNotificationEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION, true);
    }

    /**
     * Helper: check if countdown is enabled
     */
    public static boolean isCountdownEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COUNTDOWN, true);
    }

    /**
     * Helper: get countdown duration in milliseconds
     */
    public static long getCountdownMillis(Context context) {
        int seconds = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS);
        if (seconds <= 0) seconds = DEFAULT_COUNTDOWN_SECONDS;
        return seconds * 1000L;
    }

    /**
     * Helper: check if debug mode is enabled
     */
    public static boolean isDebugEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEBUG, false);
    }
}
