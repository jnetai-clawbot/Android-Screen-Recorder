package com.jnet.screenrecorder;

import android.app.Activity;
import android.content.SharedPreferences;

/**
 * Applies the user's chosen theme (Dark / Light / Follow system)
 * to an activity BEFORE setContentView is called.
 */
public final class ThemeUtil {

    private ThemeUtil() {}

    /**
     * Call before super.onCreate()/setContentView() in each activity.
     * Reads the "theme_mode" preference and sets the theme accordingly.
     *
     * The default is dark with light-green text (like the Settings screen),
     * so the app launches in dark mode on first run.
     */
    public static void apply(Activity activity) {
        // The Settings screen is always dark with light-green text by default.
        if (activity instanceof com.jnet.screenrecorder.settings.SettingsActivity) {
            activity.setTheme(R.style.Theme_ScreenRecorder_Dark);
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences("screenrecorder", Activity.MODE_PRIVATE);
        String mode = prefs.getString("theme_mode", "dark"); // default: dark with light-green text

        switch (mode) {
            case "light":
                activity.setTheme(R.style.Theme_ScreenRecorder);
                break;
            case "dark":
            case "system":
            default:
                // Default (and on first launch) is the dark theme with light-green text,
                // matching the Settings page. "system" keeps dark unless user picks light.
                activity.setTheme(R.style.Theme_ScreenRecorder_Dark);
                break;
        }
    }
}
