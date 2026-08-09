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

            updateStorageSummary();
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
                    loc.setSummary(uri != null && !uri.isEmpty()
                            ? uri
                            : StorageUtil.DEFAULT_PATH + " (tap to browse)");
                }
            } catch (Exception ignored) {
            }
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
        }
    }
}
