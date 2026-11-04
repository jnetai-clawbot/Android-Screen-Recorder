package com.jnet.screenrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Single source of truth for app preferences.
 *
 * Historically the Settings screen wrote to the default PreferenceManager file
 * ("com.jnet.screenrecorder_preferences") while the rest of the app read from a
 * file called "screenrecorder", so most settings silently did nothing. Everything
 * now uses {@link #NAME}, and {@link #migrate(Context)} copies any values that
 * were saved by older versions.
 */
public final class Prefs {

    public static final String NAME = "screenrecorder";

    private static final String LEGACY_SETTINGS = "settings";
    private static final String MIGRATED_KEY = "prefs_migrated_v2";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Copies old preference files into {@link #NAME} once. Safe to call often. */
    public static synchronized void migrate(Context context) {
        SharedPreferences target = get(context);
        if (target.getBoolean(MIGRATED_KEY, false)) {
            return;
        }
        SharedPreferences.Editor editor = target.edit();

        // Old Settings screen used the default PreferenceManager file.
        copyInto(editor, target,
                android.preference.PreferenceManager.getDefaultSharedPreferences(context));

        // Older bubble code used a separate "settings" file for one toggle.
        copyInto(editor, target, context.getSharedPreferences(LEGACY_SETTINGS, Context.MODE_PRIVATE));

        editor.putBoolean(MIGRATED_KEY, true);
        editor.apply();
    }

    private static void copyInto(SharedPreferences.Editor editor, SharedPreferences target,
                                 SharedPreferences source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || target.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value != null) {
                editor.putString(key, value.toString());
            }
        }
    }

    /**
     * Reads an integer preference that may be stored as a String (EditTextPreference)
     * or as a number (older code). Returns {@code defaultValue} on bad input.
     */
    public static int getInt(Context context, String key, int defaultValue) {
        Object value;
        try {
            value = get(context).getAll().get(key);
        } catch (Exception e) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return (int) (long) (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Start-recording delay in seconds (default 0). */
    public static int getStartDelaySeconds(Context context) {
        return clamp(getInt(context, "start_delay_seconds", 0), 0, 3600);
    }

    /** Screenshot delay in seconds (default 3). */
    public static int getScreenshotDelaySeconds(Context context) {
        return clamp(getInt(context, "screenshot_delay_seconds", 3), 0, 3600);
    }
}
