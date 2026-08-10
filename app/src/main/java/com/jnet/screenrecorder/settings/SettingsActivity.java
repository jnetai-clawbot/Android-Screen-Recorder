package com.jnet.screenrecorder.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jnet.screenrecorder.R;
import com.jnet.screenrecorder.StorageUtil;

public class SettingsActivity extends AppCompatActivity {

    // Update this to your public GitHub repo when created
    private static final String GITHUB_REPO_URL = "https://github.com/jnetai-clawbot/Android-Screen-Recorder";
    private static final String LATEST_RELEASE_URL = GITHUB_REPO_URL + "/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.jnet.screenrecorder.ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }

        getFragmentManager().beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private static final int REQUEST_FOLDER_PICKER = 9001;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);

            // Show the real version (from the manifest) in the About section.
            try {
                String v = getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0).versionName;
                Preference versionPref = findPreference("version");
                if (versionPref != null) {
                    versionPref.setSummary(v);
                }
            } catch (Exception ignored) {
            }

            // "Show notification bar on app open" toggle: start/stop the notification
            // bar IMMEDIATELY when toggled, so it appears right away (not just on the
            // next app launch).
            Preference notifPref = findPreference("show_notification_bar");
            if (notifPref != null) {
                notifPref.setOnPreferenceChangeListener((pref, newValue) -> {
                    boolean on = Boolean.TRUE.equals(newValue);
                    android.content.Context ctx = getActivity();
                    if (on) {
                        // Start the notification bar service. Defer with a short delay so
                        // the app is in the foreground first - starting a foreground
                        // service from a background context on Android 12+ throws
                        // ForegroundServiceStartNotAllowedException and crashes.
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Intent i = new Intent(ctx, com.jnet.screenrecorder.overlay.BubbleService.class);
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    ctx.startForegroundService(i);
                                } else {
                                    ctx.startService(i);
                                }
                                Toast.makeText(ctx, "Notification bar enabled", Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                com.jnet.screenrecorder.ErrorLog.e("Could not start notification bar", e);
                                Toast.makeText(ctx, "Could not start notification bar", Toast.LENGTH_LONG).show();
                            }
                        }, 300);
                    } else {
                        // Stop the notification bar service now.
                        try {
                            ctx.stopService(new Intent(ctx, com.jnet.screenrecorder.overlay.BubbleService.class));
                            Toast.makeText(ctx, "Notification bar disabled", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            com.jnet.screenrecorder.ErrorLog.e("Could not stop notification bar", e);
                        }
                    }
                    return true;
                });
            }

            findPreference("about").setOnPreferenceClickListener(pref -> {
                Toast.makeText(getActivity(),
                        "Made by jnetai.com — J~Net Screen Recorder",
                        Toast.LENGTH_LONG).show();
                return true;
            });

            findPreference("check_update").setOnPreferenceClickListener(pref -> {
                openUrl(LATEST_RELEASE_URL);
                return true;
            });

            findPreference("share").setOnPreferenceClickListener(pref -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT,
                        "Check out J~Net Screen Recorder — record screen + audio with a quick-access bubble. " + LATEST_RELEASE_URL);
                startActivity(Intent.createChooser(share, "Share app"));
                return true;
            });

            // Folder browser: pick the save location instead of typing a path
            findPreference("storage_location").setOnPreferenceClickListener(pref -> {
                launchFolderPicker();
                return true;
            });

            findPreference("create_dirs").setOnPreferenceClickListener(pref -> {
                boolean ok = StorageUtil.ensureFolders(getActivity());
                Toast.makeText(getActivity(),
                        ok ? "Storage folders ready" : "Could not create folders — check storage permission",
                        Toast.LENGTH_LONG).show();
                return true;
            });

            // --- Diagnostics / error log ---
            findPreference("view_error_log").setOnPreferenceClickListener(pref -> {
                showErrorLogDialog();
                return true;
            });
            findPreference("copy_error_log").setOnPreferenceClickListener(pref -> {
                String log = com.jnet.screenrecorder.ErrorLog.readLog(getActivity());
                copyToClipboard(log);
                Toast.makeText(getActivity(), "Error log copied to clipboard", Toast.LENGTH_SHORT).show();
                return true;
            });
            findPreference("clear_error_log").setOnPreferenceClickListener(pref -> {
                com.jnet.screenrecorder.ErrorLog.clearLog(getActivity());
                Toast.makeText(getActivity(), "Error log cleared", Toast.LENGTH_SHORT).show();
                return true;
            });

            updateStorageSummary();
        }

        /** Shows the error log in a dialog with a Copy-to-clipboard button. */
        private void showErrorLogDialog() {
            try {
                String log = com.jnet.screenrecorder.ErrorLog.readLog(getActivity());
                new androidx.appcompat.app.AlertDialog.Builder(getActivity())
                        .setTitle("Error log")
                        .setMessage(log)
                        .setPositiveButton("Copy to clipboard", (d, w) -> {
                            copyToClipboard(log);
                            Toast.makeText(getActivity(), "Error log copied to clipboard", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Close", null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(getActivity(), "Could not open error log", Toast.LENGTH_SHORT).show();
            }
        }

        private void copyToClipboard(String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("screenrecorder_error_log", text));
                }
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("copy to clipboard failed", e);
            }
        }

        private void launchFolderPicker() {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_FOLDER_PICKER);
            } catch (Exception e) {
                Toast.makeText(getActivity(), "Folder picker unavailable", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == REQUEST_FOLDER_PICKER && resultCode == AppCompatActivity.RESULT_OK
                    && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    // Take persistable permission so we can keep writing to the folder
                    getActivity().getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    // Store the picked folder URI
                    getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                            .edit().putString("storage_location_uri", uri.toString()).apply();
                    Toast.makeText(getActivity(), "Save folder selected", Toast.LENGTH_SHORT).show();
                    updateStorageSummary();
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "Could not use that folder", Toast.LENGTH_LONG).show();
                }
            }
        }

        private void updateStorageSummary() {
            try {
                SharedPreferences prefs = getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE);
                String uri = prefs.getString("storage_location_uri", "");
                Preference loc = findPreference("storage_location");
                if (loc != null) {
                    if (uri != null && !uri.isEmpty()) {
                        // Show a friendly folder name instead of the raw content:// URI
                        loc.setSummary("Selected: " + friendlyName(uri) + " (tap to change)");
                    } else {
                        loc.setSummary(StorageUtil.DEFAULT_PATH + " (tap to browse)");
                    }
                }
            } catch (Exception ignored) {
            }
        }

        /** Converts a content:// URI like .../tree/primary%3ADCIM%2FScreen-Recordings into "DCIM/Screen-Recordings". */
        private String friendlyName(String uriString) {
            try {
                android.net.Uri u = android.net.Uri.parse(uriString);
                String last = u.getLastPathSegment();
                if (last != null) {
                    String decoded = java.net.URLDecoder.decode(last, "UTF-8");
                    // Strip the "tree/primary:" prefix if present
                    if (decoded.startsWith("primary:")) decoded = decoded.substring("primary:".length());
                    return decoded;
                }
            } catch (Exception ignored) {
            }
            return uriString;
        }

        private void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(getActivity(), "No browser available", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceScreen().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceScreen().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("show_screenshot_button".equals(key)) {
                // toggle screenshot visibility — handled by bubble on next open
            }
            if ("bubble_size".equals(key) || "bubble_colour".equals(key)) {
                // Refresh the live bubble if it's running
                if (com.jnet.screenrecorder.overlay.BubbleService.isRunning()) {
                    Intent i = new Intent(getActivity(),
                            com.jnet.screenrecorder.overlay.BubbleService.class)
                            .setAction("com.jnet.screenrecorder.REFRESH_BUBBLE");
                    getActivity().startService(i);
                }
            }
            if ("overlay_mode".equals(key)) {
                // Toggling overlay mode ON should show the floating bubble immediately;
                // toggling OFF should hide it. This is the AZ-style quick-access bubble.
                boolean overlayOn = sharedPreferences.getBoolean("overlay_mode", false);
                try {
                    boolean granted = android.provider.Settings.canDrawOverlays(getActivity());
                    if (overlayOn && granted) {
                        if (!com.jnet.screenrecorder.overlay.BubbleService.isRunning()) {
                            Intent i = new Intent(getActivity(),
                                    com.jnet.screenrecorder.overlay.BubbleService.class);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                getActivity().startForegroundService(i);
                            } else {
                                getActivity().startService(i);
                            }
                            Toast.makeText(getActivity(), "Bubble shown — drag it or tap to expand",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else if (!overlayOn) {
                        getActivity().stopService(new Intent(getActivity(),
                                com.jnet.screenrecorder.overlay.BubbleService.class));
                    } else {
                        // overlayOn but not granted overlay permission
                        Toast.makeText(getActivity(),
                                "Enable overlay permission first (Settings -> Overlay)",
                                Toast.LENGTH_LONG).show();
                        // Ask to grant overlay permission
                        try {
                            Intent grant = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getActivity().getPackageName()));
                            getActivity().startActivity(grant);
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception e) {
                    com.jnet.screenrecorder.ErrorLog.e("overlay_mode toggle error", e);
                    Toast.makeText(getActivity(), "Could not toggle bubble", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
