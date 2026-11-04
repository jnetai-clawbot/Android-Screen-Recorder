package com.jnet.screenrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports and imports all user settings as a human-readable JSON file.
 *
 * Only real settings are saved. Transient state (bubble position, one-time
 * onboarding flags, folder permissions) is skipped so a backup can be restored
 * on another device without breaking anything.
 */
public final class SettingsBackup {

    public static final int FORMAT_VERSION = 1;

    private static final Set<String> SKIP_KEYS = new HashSet<>(Arrays.asList(
            "bubble_x", "bubble_y", "shot_x", "shot_y",
            "permissions_asked", "prefs_migrated_v2",
            "storage_location_uri"
    ));

    private SettingsBackup() {
    }

    /** Writes the current settings as JSON. Returns the number of settings saved. */
    public static int exportTo(Context context, OutputStream out) throws Exception {
        JSONObject settings = new JSONObject();
        int count = 0;
        for (Map.Entry<String, ?> entry : Prefs.get(context).getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null || SKIP_KEYS.contains(key)) {
                continue;
            }
            if (value instanceof Boolean || value instanceof Number || value instanceof String) {
                settings.put(key, value);
                count++;
            }
        }

        JSONObject root = new JSONObject();
        root.put("app", "J~Net Screen Recorder");
        root.put("format_version", FORMAT_VERSION);
        root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                .format(new Date()));
        root.put("settings", settings);

        out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        out.flush();
        return count;
    }

    /**
     * Restores settings from a JSON file. Accepts both the wrapped format written
     * by {@link #exportTo} and a flat JSON object of key/value pairs.
     * Returns the number of settings applied.
     */
    public static int importFrom(Context context, InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("The selected file is empty");
        }

        JSONObject root = new JSONObject(text);
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) {
            settings = root;
        }

        SharedPreferences.Editor editor = Prefs.get(context).edit();
        int count = 0;
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || SKIP_KEYS.contains(key)) {
                continue;
            }
            Object value = settings.opt(key);
            if (value == null || value == JSONObject.NULL) {
                continue;
            }
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else {
                // Settings values are strings (ListPreference / EditTextPreference),
                // so store numbers as strings too and avoid ClassCastException in the
                // preference UI.
                editor.putString(key, String.valueOf(value));
            }
            count++;
        }
        editor.apply();
        return count;
    }
}
