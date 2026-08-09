package com.jnet.screenrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.jnet.screenrecorder.overlay.BubbleService;
import com.jnet.screenrecorder.settings.SettingsActivity;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_REQUEST_CAPTURE = "com.jnet.screenrecorder.REQUEST_CAPTURE";

    private static final int REQUEST_OVERLAY = 1001;

    private MaterialButton btnOverlay, btnAudio, btnSettings;
    private TextView tvOverlayStatus, tvAudioStatus;

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            // Forward the projection to the recorder and start recording
                            com.jnet.screenrecorder.recorder.RecorderService
                                    .setMediaProjection(RESULT_OK, result.getData());
                            Intent recIntent = new Intent(this,
                                    com.jnet.screenrecorder.recorder.RecorderService.class)
                                    .setAction(com.jnet.screenrecorder.recorder.RecorderService.ACTION_START)
                                    .putExtra("resultCode", RESULT_OK)
                                    .putExtra("data", result.getData());
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(recIntent);
                            } else {
                                startService(recIntent);
                            }
                        } else {
                            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show();
                        }
                    });

    // Legacy external-storage write permission (only needed on Android 7-9, e.g. Samsung S8)
    private final ActivityResultLauncher<String> storagePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Storage permission needed to save recordings on this device", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> audioPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                updateAudioStatus();
                if (granted) {
                    Toast.makeText(this, "Microphone access granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Microphone access denied — audio won't be recorded", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle request coming from the bubble's Record button
        if (getIntent() != null && ACTION_REQUEST_CAPTURE.equals(getIntent().getAction())) {
            requestScreenCapture();
        }

        btnOverlay = findViewById(R.id.btn_overlay);
        btnAudio = findViewById(R.id.btn_audio);
        btnSettings = findViewById(R.id.btn_settings);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvAudioStatus = findViewById(R.id.tv_audio_status);

        btnOverlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())),
                        REQUEST_OVERLAY);
            } else {
                toggleBubble();
            }
        });

        btnAudio.setOnClickListener(v -> requestAudioPermission());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Old devices (Android 7-9) need runtime storage permission to save files
        requestLegacyStorageIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOverlayStatus();
        updateAudioStatus();
    }

    private void toggleBubble() {
        boolean running = BubbleService.isRunning();
        Intent intent = new Intent(this, BubbleService.class);
        if (running) {
            stopService(intent);
            Toast.makeText(this, "Bubble hidden", Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Bubble shown — tap it to record", Toast.LENGTH_SHORT).show();
        }
        updateOverlayStatus();
    }

    private void updateOverlayStatus() {
        boolean granted = Settings.canDrawOverlays(this);
        boolean bubbleRunning = BubbleService.isRunning();
        if (granted) {
            btnOverlay.setText(bubbleRunning ? R.string.hide_bubble : R.string.show_bubble);
            tvOverlayStatus.setText("Overlay: " + (bubbleRunning ? "● Bubble active" : "✓ Granted"));
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, bubbleRunning ? R.color.primary : R.color.green));
        } else {
            btnOverlay.setText(R.string.enable_overlay);
            tvOverlayStatus.setText("Overlay: ✗ Not granted");
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
        }
    }

    private void updateAudioStatus() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            btnAudio.setText("✓ Microphone granted");
            tvAudioStatus.setText("Audio: ✓ Enabled");
            tvAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
        } else {
            btnAudio.setText("Grant microphone access");
            tvAudioStatus.setText("Audio: ✗ Not granted");
            tvAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            captureLauncher.launch(mpm.createScreenCaptureIntent());
        } catch (Exception e) {
            Toast.makeText(this, "Could not start screen capture", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestLegacyStorageIfNeeded() {
        // On Android 10+ (API 29+) we use scoped storage via MediaStore, no permission needed.
        // On Android 7-9 (API 24-28, e.g. Samsung S8) we need WRITE_EXTERNAL_STORAGE to save files.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2002);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show();
            }
            updateOverlayStatus();
        }
    }
}
