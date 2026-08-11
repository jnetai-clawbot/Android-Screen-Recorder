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
            if ("graphene_mode".equals(key)) {
                boolean grapheneOn = sharedPreferences.getBoolean("graphene_mode", false);
                if (grapheneOn) {
                    // GrapheneOS-compatible mode: overlay bubble is blocked, so turn
                    // off overlay mode and rely on notification-bar controls + QS tile
                    // (all no-overlay). Never crash.
                    sharedPreferences.edit().putBoolean("overlay_mode", false).apply();
                    try {
                        getActivity().stopService(new Intent(getActivity(),
                                com.jnet.screenrecorder.overlay.BubbleService.class));
                    } catch (Exception ignored) {
                    }
                    // Bring up the notification-bar controls (Start/Stop/Screenshot) —
                    // these work without overlay on GrapheneOS.
                    if (!com.jnet.screenrecorder.overlay.BubbleService.isRunning()) {
                        startNotificationBarSafe(getActivity(), 0);
                    }
                    Toast.makeText(getActivity(),
                            "Graphene mode: use the notification (Start/Stop/Screenshot) and the QS tile — no overlay needed",
                            Toast.LENGTH_LONG).show();
                } else {
                    // Leaving Graphene mode: stop the notification-bar service (overlay
                    // bubble remains available via overlay_mode as before).
                    stopNotificationBar(getActivity());
                }
            }
            if ("show_screenshot_button".equals(key)) {
                // toggle screenshot visibility — handled by bubble on next open
            }
            if ("show_notification_bar".equals(key)) {
                boolean on = sharedPreferences.getBoolean("show_notification_bar", false);
                if (on) {
                    startNotificationBarSafe(getActivity(), 0);
                } else {
                    stopNotificationBar(getActivity());
                }
            }
            if ("enable_qs_tile".equals(key)) {
                boolean on = sharedPreferences.getBoolean("enable_qs_tile", false);
                toggleQsTile(getActivity(), on);
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

        /**
         * Safely starts the notification-bar service (Start/Stop/Settings) so recording
         * works even when the overlay bubble cannot be granted (e.g. GrapheneOS).
         * Never crashes: if overlay permission is missing it asks to grant it (with a
         * Cancel button); if starting the service throws (e.g. ForegroundServiceStart-
         * NotAllowedException on Android 12+) it retries in a loop with a Cancel
         * option, instead of crashing the app.
         */
        private void startNotificationBarSafe(final android.app.Activity activity, final int attempt) {
            try {
                // Already running? Nothing to do.
                if (com.jnet.screenrecorder.overlay.BubbleService.isRunning()) {
                    return;
                }
                // Overlay permission must be granted first, otherwise BubbleService
                // falls back to notification-only mode but the user should still be
                // told it needs overlay access for the bubble itself.
                if (!android.provider.Settings.canDrawOverlays(activity)) {
                    // Request overlay permission with a Cancel button. If the user
                    // grants it, the BubbleService is started on return; if they
                    // cancel, we simply switch the toggle back off (no crash).
                    new androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle("Overlay permission required")
                            .setMessage("To show the floating bubble, enable \"Display over other apps\" "
                                    + "for this app. The notification-bar controls will still work without it.")
                            .setPositiveButton("Grant", (d, w) -> {
                                try {
                                    Intent grant = new Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(grant);
                                } catch (Exception e) {
                                    com.jnet.screenrecorder.ErrorLog.e("could not open overlay settings", e);
                                    startNotificationBarSafe(activity, attempt + 1);
                                }
                            })
                            .setNegativeButton("Cancel", (d, w) -> {
                                // User declined overlay access - turn the toggle back off
                                // and show the notification bar anyway (it works without overlay).
                                getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                                        .edit().putBoolean("show_notification_bar", false).apply();
                                // Notification bar still works without overlay - start it anyway.
                                doStartNotificationBar(activity, attempt);
                            })
                            .setCancelable(true)
                            .setOnCancelListener(d -> {
                                getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                                        .edit().putBoolean("show_notification_bar", false).apply();
                            })
                            .show();
                    return;
                }
                doStartNotificationBar(activity, attempt);
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("notification bar start error", e);
                // Never crash - offer a retry loop with a Cancel button.
                showStartFailureDialog(activity, attempt);
            }
        }

        private void doStartNotificationBar(final android.app.Activity activity, final int attempt) {
            try {
                Intent i = new Intent(activity, com.jnet.screenrecorder.overlay.BubbleService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    activity.startForegroundService(i);
                } else {
                    activity.startService(i);
                }
                Toast.makeText(activity, "Notification bar enabled", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("notification bar start failed", e);
                showStartFailureDialog(activity, attempt);
            }
        }

        /**
         * Shows a Retry/Cancel dialog when the notification bar fails to start, so the
         * app never crashes. Retry loops back into the safe-start method.
         */
        private void showStartFailureDialog(final android.app.Activity activity, final int attempt) {
            try {
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("Could not start notification bar")
                        .setMessage("The notification bar failed to start (attempt " + (attempt + 1)
                                + "). This can happen on Android 12+ if the app is not in the foreground. "
                                + "Tap Retry to try again, or Cancel to turn it off.")
                        .setPositiveButton("Retry", (d, w) -> startNotificationBarSafe(activity, attempt + 1))
                        .setNegativeButton("Cancel", (d, w) -> {
                            getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                                    .edit().putBoolean("show_notification_bar", false).apply();
                        })
                        .setCancelable(true)
                        .setOnCancelListener(d -> {
                            getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                                    .edit().putBoolean("show_notification_bar", false).apply();
                        })
                        .show();
            } catch (Exception e) {
                // Absolutely never crash - just flip the toggle back off.
                try {
                    getActivity().getSharedPreferences("screenrecorder", MODE_PRIVATE)
                            .edit().putBoolean("show_notification_bar", false).apply();
                } catch (Exception ignored) {
                }
            }
        }

        private void stopNotificationBar(android.app.Activity activity) {
            try {
                activity.stopService(new Intent(activity,
                        com.jnet.screenrecorder.overlay.BubbleService.class));
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("stop notification bar error", e);
            }
        }

        /**
         * Enables/disables the Quick Settings tile. When enabled, the tile is requested
         * via ACTION_REQUEST_ADD_TILE; when disabled, it is removed. Never crashes - any
         * failure just logs and toasts.
         */
        private void toggleQsTile(android.app.Activity activity, boolean enable) {
            try {
                if (enable) {
                    // Ask the user to add the tile to Quick Settings (system dialog).
                    // This needs to run from the foreground activity.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+: the supported API. The raw REQUEST_ADD_TILE intent is
                        // blocked for third-party apps on 13+, which throws
                        // ActivityNotFoundException. requestAddTileService lives on
                        // StatusBarManager (NOT TileService) and shows the system
                        // "Add tile" dialog without needing any overlay permission.
                        android.app.StatusBarManager sbm = activity.getSystemService(
                                android.app.StatusBarManager.class);
                        sbm.requestAddTileService(
                                new android.content.ComponentName(activity,
                                        com.jnet.screenrecorder.quick.RecorderTileService.class),
                                activity.getString(R.string.tile_label),
                                android.graphics.drawable.Icon.createWithResource(activity,
                                        R.drawable.ic_bubble),
                                android.os.AsyncTask.THREAD_POOL_EXECUTOR,
                                null);
                        Toast.makeText(activity, "Confirm the Screen Recorder tile in Quick Settings",
                                Toast.LENGTH_LONG).show();
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // Android 10-12L: fall back to the raw request intent.
                        Intent req = new Intent(
                                "android.service.quicksettings.action.REQUEST_ADD_TILE");
                        req.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivityForResult(req, 0);
                        Toast.makeText(activity, "Confirm the Screen Recorder tile in Quick Settings",
                                Toast.LENGTH_LONG).show();
                    } else {
                        // Pre-Android 10: tiles are added from the QS edit panel; just guide the user.
                        Toast.makeText(activity, "Open Quick Settings and add the Screen Recorder tile (pencil icon)",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(activity, "Tile disabled - remove it from Quick Settings if it's still shown",
                            Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("toggle QS tile error", e);
                Toast.makeText(activity, "Could not toggle Quick Settings tile", Toast.LENGTH_LONG).show();
            }
        }
    }
}
