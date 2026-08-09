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

public class SettingsActivity extends AppCompatActivity {

    // Update this to your public GitHub repo when created
    private static final String GITHUB_REPO_URL = "https://github.com/jnetai-clawbot/Android-Screen-Recorder";
    private static final String LATEST_RELEASE_URL = GITHUB_REPO_URL + "/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        }
    }
}
