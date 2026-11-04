package com.jnet.screenrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.jnet.screenrecorder.overlay.BubbleService;
import com.jnet.screenrecorder.recorder.RecorderService;
import com.jnet.screenrecorder.settings.SettingsActivity;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_REQUEST_CAPTURE = "com.jnet.screenrecorder.REQUEST_CAPTURE";
    public static final String ACTION_REQUEST_SCREENSHOT = "com.jnet.screenrecorder.REQUEST_SCREENSHOT";

    private static final int REQUEST_OVERLAY = 1001;

    private MaterialButton btnOverlay, btnAudio, btnSettings, btnToggleRecord, btnScreenshot;
    private TextView tvOverlayStatus, tvAudioStatus, tvRecordStatus;
    private EditText etShotDelay;
    private boolean mPendingScreenshot = false;
    private int mPendingDelaySeconds = 0;

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        com.jnet.screenrecorder.ErrorLog.i("Capture result: code=" + result.getResultCode()
                                + " (RESULT_OK=" + Activity.RESULT_OK + ") data=" + (result.getData() != null));
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            // Store the projection so the service can use it.
                            RecorderService.setMediaProjection(Activity.RESULT_OK, result.getData());
                            if (mPendingScreenshot) {
                                // This capture was requested for a screenshot, not a recording.
                                mPendingScreenshot = false;
                                com.jnet.screenrecorder.ErrorLog.i("Screen capture granted for screenshot");
                                startScreenshotService(mPendingDelaySeconds);
                            } else {
                                Intent recIntent = new Intent(this, RecorderService.class)
                                        .setAction(RecorderService.ACTION_START)
                                        .putExtra("resultCode", Activity.RESULT_OK)
                                        .putExtra("data", result.getData())
                                        .putExtra(RecorderService.EXTRA_DELAY_SECONDS, mPendingDelaySeconds);
                                startForegroundServiceCompat(recIntent);
                                com.jnet.screenrecorder.ErrorLog.i("Starting recording service (delay="
                                        + mPendingDelaySeconds + "s)");
                                refreshRecordStatusSoon();
                            }
                        } else {
                            mPendingScreenshot = false;
                            com.jnet.screenrecorder.ErrorLog.e("Screen capture denied: code=" + result.getResultCode()
                                    + " data=" + (result.getData() != null));
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
        // Install the global crash handler so we can capture & copy the real error
        CrashHandler.install(this);
        // Bring any settings from older versions into the single "screenrecorder" file.
        Prefs.migrate(this);
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnOverlay = findViewById(R.id.btn_overlay);
        btnAudio = findViewById(R.id.btn_audio);
        btnSettings = findViewById(R.id.btn_settings);
        btnToggleRecord = findViewById(R.id.btn_toggle_record);
        btnScreenshot = findViewById(R.id.btn_screenshot);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvAudioStatus = findViewById(R.id.tv_audio_status);
        tvRecordStatus = findViewById(R.id.tv_record_status);
        etShotDelay = findViewById(R.id.et_shot_delay);

        // Show the saved screenshot delay (seconds).
        etShotDelay.setText(String.valueOf(Prefs.getScreenshotDelaySeconds(this)));

        btnToggleRecord.setOnClickListener(v -> startOrStopRecording());
        btnScreenshot.setOnClickListener(v -> takeScreenshotFromApp());

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

        // Handle request coming from the bubble / notification / QS tile.
        handleLaunchIntent(getIntent());

        // One-time permission onboarding: prompt for everything the app needs ONCE
        requestAllPermissionsIfFirstRun();
        // Ensure the save folders exist (storage permission is granted in the batch above)
        createStorageFolders();
        // If the user enabled "Show notification bar controls" previously, bring it
        // back on app open — but ONLY when the toggle is on (OFF by default), and
        // never crash if starting fails.
        autoStartNotificationBarIfEnabled();
    }

    /**
     * Safely starts the notification bar if the user has enabled it in Settings.
     * OFF by default, so a fresh install never auto-starts. Wrapped in try/catch so
     * a foreground-service start failure (Android 12+ / GrapheneOS) can never crash
     * the app — it just logs the error.
     */
    private void autoStartNotificationBarIfEnabled() {
        try {
            boolean enabled = getSharedPreferences("screenrecorder", MODE_PRIVATE)
                    .getBoolean("show_notification_bar", false);
            if (!enabled) {
                return;
            }
            if (com.jnet.screenrecorder.overlay.BubbleService.isRunning()) {
                return;
            }
            Intent intent = new Intent(this, com.jnet.screenrecorder.overlay.BubbleService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("auto-start notification bar failed (non-fatal)", e);
        }
    }

    /**
     * MainActivity uses launchMode="singleTask", so when the app is already open and
     * the user taps Start (from the notification or bubble), the ACTION_REQUEST_CAPTURE
     * intent is delivered here via onNewIntent() instead of onCreate(). Without this
     * handler the screen-capture permission prompt was silently dropped.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    /** Routes a service/notification/QS request through the screen-capture consent flow. */
    private void handleLaunchIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (ACTION_REQUEST_CAPTURE.equals(intent.getAction())) {
            if (RecorderService.isRecording()) {
                // Already recording — Start/Stop buttons act as Stop.
                stopRecordingFromApp();
                return;
            }
            mPendingScreenshot = false;
            mPendingDelaySeconds = Prefs.getStartDelaySeconds(this);
            requestScreenCapture();
        } else if (ACTION_REQUEST_SCREENSHOT.equals(intent.getAction())) {
            mPendingScreenshot = true;
            mPendingDelaySeconds = Prefs.getScreenshotDelaySeconds(this);
            requestScreenCapture();
        }
    }

    /**
     * Prompts for ALL required permissions at once, only on first launch.
     * The user accepts once (or denies) — we don't nag on every recording.
     */
    private void requestAllPermissionsIfFirstRun() {
        SharedPreferences prefs = getSharedPreferences("screenrecorder", MODE_PRIVATE);
        if (prefs.getBoolean("permissions_asked", false)) {
            return; // already asked once
        }
        prefs.edit().putBoolean("permissions_asked", true).apply();

        java.util.List<String> needed = new java.util.ArrayList<>();

        // Microphone — always required for audio recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        // Storage — required to create the save folders & store recordings.
        // On Android 10+ we use scoped storage (no runtime permission), but on
        // older devices (S8 / API 24-28) we MUST request WRITE_EXTERNAL_STORAGE.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // Notifications — Android 13+ requires runtime permission for recording notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (needed.isEmpty()) {
            // All granted — just create the folders.
            createStorageFolders();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Permissions")
                .setMessage("J~Net Screen Recorder needs your permission to save recordings.\n\n" +
                        "🎙 Microphone — to record audio\n" +
                        "📁 Storage — REQUIRED to create the save folders & store recordings\n" +
                        "🔔 Notifications — to show recording status\n\n" +
                        "If you deny storage, the app cannot create its folders and won't be able to save.\n" +
                        "You'll only be asked this once. Screen capture is asked when you start recording.")
                .setPositiveButton("Grant all", (d, w) -> {
                    requestPermissions(needed.toArray(new String[0]), 3001);
                    createStorageFolders();
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /** Creates the ScreenRecorder/Recordings and ScreenRecorder/Screenshots folders. */
    private void createStorageFolders() {
        try {
            boolean ok = StorageUtil.ensureFolders(this);
            if (ok) {
                Toast.makeText(this, "Storage folders ready", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            // Never crash on storage issues — recording will just need permission later.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOverlayStatus();
        updateAudioStatus();
        updateRecordStatus();
        // NOTE: bubble is NOT auto-started on resume — overlay mode is off by
        // default and the user must enable it in Settings. This prevents the
        // crash that happened when the bubble service auto-started.
    }

    private void toggleBubble() {
        // Crash-proof: only start the bubble if overlay permission is actually granted,
        // and wrap the whole thing in try/catch so any overlay issue can never crash the app.
        try {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable overlay permission first", Toast.LENGTH_SHORT).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())),
                        REQUEST_OVERLAY);
                return;
            }

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
                Toast.makeText(this, "Bubble shown — drag it or tap to expand", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            // Fallback: never crash on overlay issues. Recording still works via notification.
            Toast.makeText(this, "Overlay unavailable — use the notification to record", Toast.LENGTH_LONG).show();
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
            tvOverlayStatus.setText("Overlay: ✗ Not granted — tap to enable");
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

    /**
     * The big Start/Stop toggle at the top of the screen. Starting always asks for
     * screen-capture consent, then waits the configured start delay (default 0s)
     * before recording so the user can switch to the app they want to record.
     */
    private void startOrStopRecording() {
        if (RecorderService.isRecording()) {
            stopRecordingFromApp();
            return;
        }
        mPendingScreenshot = false;
        mPendingDelaySeconds = Prefs.getStartDelaySeconds(this);
        requestScreenCapture();
    }

    private void stopRecordingFromApp() {
        try {
            startService(new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_STOP));
            Toast.makeText(this, "Stopping recording", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Could not stop recording", e);
        }
        refreshRecordStatusSoon();
    }

    /**
     * Takes a screenshot after the delay entered on the main screen. If a recording
     * is already running the screenshot uses the live projection; otherwise the
     * user grants screen capture first and the delay starts right after.
     */
    private void takeScreenshotFromApp() {
        int delay = parseDelayInput();
        if (RecorderService.isRecording()) {
            startScreenshotService(delay);
            return;
        }
        mPendingScreenshot = true;
        mPendingDelaySeconds = delay;
        requestScreenCapture();
    }

    /** Reads and persists the delay from the EditText, clamped to 0-3600 seconds. */
    private int parseDelayInput() {
        int delay = Prefs.getScreenshotDelaySeconds(this);
        try {
            String raw = etShotDelay.getText().toString().trim();
            if (!raw.isEmpty()) {
                delay = Integer.parseInt(raw);
            }
        } catch (Exception ignored) {
        }
        delay = Prefs.clamp(delay, 0, 3600);
        Prefs.get(this).edit().putString("screenshot_delay_seconds", String.valueOf(delay)).apply();
        etShotDelay.setText(String.valueOf(delay));
        return delay;
    }

    private void startScreenshotService(int delaySeconds) {
        Intent intent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_SCREENSHOT)
                .putExtra(RecorderService.EXTRA_DELAY_SECONDS, delaySeconds);
        startForegroundServiceCompat(intent);
        if (delaySeconds > 0) {
            Toast.makeText(this, "Screenshot in " + delaySeconds + "s — switch to the app now",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Taking screenshot", Toast.LENGTH_SHORT).show();
        }
    }

    private void startForegroundServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Could not start recorder service", e);
            Toast.makeText(this, "Could not start the recorder service", Toast.LENGTH_LONG).show();
        }
    }

    private void updateRecordStatus() {
        if (tvRecordStatus == null || btnToggleRecord == null) {
            return;
        }
        boolean recording = RecorderService.isRecording();
        if (recording) {
            btnToggleRecord.setText(R.string.stop_recording);
            tvRecordStatus.setText("● Recording in progress");
            tvRecordStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
        } else {
            btnToggleRecord.setText(R.string.start_recording);
            tvRecordStatus.setText("Ready to record");
            tvRecordStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
        }
    }

    private void refreshRecordStatusSoon() {
        new Handler(Looper.getMainLooper()).postDelayed(this::updateRecordStatus, 600);
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
                // Do NOT auto-start the bubble here — that caused the crash.
                // The bubble only shows if the user enables overlay mode in Settings.
            }
            updateOverlayStatus();
        }
    }

    private void startBubbleService() {
        Intent intent = new Intent(this, BubbleService.class);
        // Use startService (not startForegroundService) to avoid
        // ForegroundServiceStartNotAllowedException when the app is still
        // returning from the overlay settings screen (backgrounded).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Bubble shown — drag it or tap to expand", Toast.LENGTH_SHORT).show();
    }
}
