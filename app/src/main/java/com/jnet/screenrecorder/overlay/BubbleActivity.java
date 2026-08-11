package com.jnet.screenrecorder.overlay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

import com.jnet.screenrecorder.MainActivity;
import com.jnet.screenrecorder.R;
import com.jnet.screenrecorder.recorder.RecorderService;

/**
 * Hosts the control UI inside the system-managed bubble (Android Bubbles API,
 * API 30+). The Bubbles API floats this Activity over other apps WITHOUT needing
 * SYSTEM_ALERT_WINDOW overlay permission — the system provides and drags the bubble.
 *
 * This is the primary no-overlay bubble route (GrapheneOS-safe on stock Android;
 * note GrapheneOS hardened config may still suppress bubbles entirely, in which
 * case use the notification controls / QS tile instead).
 */
public class BubbleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bubble_expanded);

        // Move the activity behind the system bubble so the bubble is visible.
        moveTaskToBack(true);

        boolean recording = RecorderService.isRecording();

        ImageButton btnRecord = findViewById(R.id.btn_record);
        ImageButton btnStop = findViewById(R.id.btn_stop);
        ImageButton btnScreenshot = findViewById(R.id.btn_screenshot);
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        ImageButton btnClose = findViewById(R.id.btn_close);

        btnRecord.setVisibility(recording ? android.view.View.GONE : android.view.View.VISIBLE);
        btnStop.setVisibility(recording ? android.view.View.VISIBLE : android.view.View.GONE);

        // Respect "show screenshot button" setting
        boolean showScreenshot = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("show_screenshot_button", true);
        btnScreenshot.setVisibility(showScreenshot ? android.view.View.VISIBLE : android.view.View.GONE);

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnScreenshot.setOnClickListener(v -> takeScreenshot());
        btnSettings.setOnClickListener(v -> {
            Intent i = new Intent(this, com.jnet.screenrecorder.settings.SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        btnClose.setOnClickListener(v -> BubbleService.stopBubble(this));
    }

    private void startRecording() {
        if (RecorderService.isRecording()) {
            stopRecording();
            return;
        }
        // Route through MainActivity for screen-capture consent (no overlay needed).
        Intent mainIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_REQUEST_CAPTURE);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(mainIntent);
    }

    private void stopRecording() {
        Intent stopIntent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_STOP);
        startService(stopIntent);
        Toast.makeText(this, R.string.stop_recording, Toast.LENGTH_SHORT).show();
    }

    private void takeScreenshot() {
        Intent shotIntent = new Intent(this, RecorderService.class)
                .setAction("com.jnet.screenrecorder.SCREENSHOT");
        startService(shotIntent);
    }
}
