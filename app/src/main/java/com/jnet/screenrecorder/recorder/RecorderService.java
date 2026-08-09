package com.jnet.screenrecorder.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.jnet.screenrecorder.R;
import com.jnet.screenrecorder.StorageUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {

    private static final String TAG = "ScreenRecorder";
    private static final String CHANNEL_ID = "screen_recorder";
    private static final int NOTIF_ID = 1;
    public static final String ACTION_START = "com.jnet.screenrecorder.START";
    public static final String ACTION_STOP = "com.jnet.screenrecorder.STOP";
    private static final String ACTION_SCREENSHOT = "com.jnet.screenrecorder.SCREENSHOT";

    private static final int SCREENSHOT_CODE = 2001;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private boolean mRecording = false;
    private File mOutputFile;

    private static MediaProjection sMediaProjection;
    private static int sResultCode;
    private static Intent sResultData;

    // --- Recording-time top bar overlay ---
    private WindowManager mWindowManager;
    private View mTimeBar;
    private TextView mTimeText;
    private Handler mTimeHandler;
    private long mStartTime;
    private Runnable mTimeUpdater;

    // --- Low-battery auto-stop ---
    private static final int LOW_BATTERY_THRESHOLD = 15; // percent
    private BroadcastReceiver mBatteryReceiver;
    private boolean mBatteryStopNotified = false;

    public static void setMediaProjection(int resultCode, Intent data) {
        sResultCode = resultCode;
        sResultData = data;
    }

    public static boolean hasProjection() {
        return sResultData != null;
    }

    public static int getProjectionResultCode() {
        return sResultCode;
    }

    public static Intent getProjectionData() {
        return sResultData;
    }

    public static boolean isRecording() {
        return sMediaProjection != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }

        if (ACTION_SCREENSHOT.equals(action)) {
            takeScreenshot();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) && !mRecording) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra("data", Intent.class)
                    : intent.getParcelableExtra("data");
            com.jnet.screenrecorder.ErrorLog.i("SRV start: extraCode=" + resultCode
                    + " extraData=" + (data != null)
                    + " staticHasProjection=" + hasProjection());
            // Fall back to the statically-stored projection (more reliable across
            // process restarts and avoids MediaProjection data not parceling via extra)
            if ((resultCode == -1 || data == null) && hasProjection()) {
                resultCode = getProjectionResultCode();
                data = getProjectionData();
            }
            if (data != null) {
                setMediaProjection(resultCode, data);
                startRecording();
            } else {
                com.jnet.screenrecorder.ErrorLog.e("Start recording: screen capture permission not granted");
                Toast.makeText(this, "Screen capture permission not granted", Toast.LENGTH_LONG).show();
                // Always foreground the service so startForegroundService() never crashes
                startForegroundCompat(NOTIF_ID, buildNotification("Tap to record"));
                stopSelf();
            }
        }

        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (mMediaProjection != null) {
            stopRecording();
        }

        // IMPORTANT: on Android 10+, getMediaProjection() requires the service to
        // already be running as a FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION service.
        // Call startForeground() with that type BEFORE requesting the projection,
        // otherwise it throws SecurityException.
        startForegroundCompat(NOTIF_ID, buildNotification("Starting recording..."));

        try {
            mMediaProjection = mProjectionManager.getMediaProjection(sResultCode, sResultData);
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Failed to get media projection", e);
            Log.e(TAG, "Failed to get media projection", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;

        File dir = getRecordingsDir();
        String name = "REC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        mOutputFile = new File(dir, name);

        mMediaRecorder = new MediaRecorder();

        SharedPreferences prefs = getSharedPreferences("screenrecorder", MODE_PRIVATE);
        boolean recordAudio = prefs.getBoolean("record_audio", true);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        if (recordAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (recordAudio) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        // Video quality settings
        mMediaRecorder.setVideoEncodingBitRate(
                Integer.parseInt(prefs.getString("video_bitrate", "8000000")));
        mMediaRecorder.setVideoFrameRate(
                Integer.parseInt(prefs.getString("video_fps", "30")));
        mMediaRecorder.setVideoSize(width, height);

        // Audio quality settings
        if (recordAudio) {
            mMediaRecorder.setAudioEncodingBitRate(
                    Integer.parseInt(prefs.getString("audio_bitrate", "128000")));
            mMediaRecorder.setAudioSamplingRate(44100);
        }

        mMediaRecorder.setOutputFile(mOutputFile.getAbsolutePath());

        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            com.jnet.screenrecorder.ErrorLog.e("MediaRecorder prepare failed", e);
            Log.e(TAG, "MediaRecorder prepare failed", e);
            mMediaRecorder.release();
            mMediaProjection.stop();
            mMediaProjection = null;
            Toast.makeText(this, "Failed to prepare recorder", Toast.LENGTH_SHORT).show();
            return;
        }

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder.getSurface(),
                null, null);

        mRecording = true;
        mMediaRecorder.start();
        startForegroundCompat(NOTIF_ID, buildNotification("● Recording"));
        showRecordingTimeBar();
        registerBatteryMonitor();
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        showDrmNoteIfApplicable();
    }

    /**
     * Shows a small red bar with white recording-time text at the very top of the
     * screen, aligned with the status bar / battery indicator, while recording.
     */
    private void showRecordingTimeBar() {
        try {
            mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mTimeBar = inflater.inflate(R.layout.overlay_recording_time, null);
            mTimeText = mTimeBar.findViewById(R.id.tv_rec_time);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);

            // Place at the top-right, in line with the status bar / battery indicator.
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = 8;
            params.y = 10; // just below the status bar icons

            mWindowManager.addView(mTimeBar, params);

            // Start ticking the time every second
            mStartTime = System.currentTimeMillis();
            mTimeHandler = new Handler();
            mTimeUpdater = new Runnable() {
                @Override
                public void run() {
                    if (mTimeText != null) {
                        long elapsed = System.currentTimeMillis() - mStartTime;
                        long sec = (elapsed / 1000) % 60;
                        long min = (elapsed / 60000) % 60;
                        long hr = elapsed / 3600000;
                        mTimeText.setText(String.format(Locale.US, "%02d:%02d:%02d", hr, min, sec));
                    }
                    if (mTimeHandler != null) {
                        mTimeHandler.postDelayed(this, 1000);
                    }
                }
            };
            mTimeHandler.post(mTimeUpdater);
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Could not show time bar", e);
            Log.e(TAG, "Could not show time bar", e);
        }
    }

    private void hideRecordingTimeBar() {
        if (mTimeHandler != null) {
            mTimeHandler.removeCallbacks(mTimeUpdater);
            mTimeHandler = null;
        }
        if (mTimeBar != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mTimeBar);
            } catch (Exception ignored) {
            }
            mTimeBar = null;
            mTimeText = null;
        }
    }

    /**
     * Registers a battery receiver that auto-stops the recording when the battery
     * gets low, so the MP4 file is saved cleanly and doesn't corrupt.
     */
    private void registerBatteryMonitor() {
        mBatteryStopNotified = false;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (scale <= 0) return;
                int percent = (int) ((level * 100f) / scale);
                if (percent <= LOW_BATTERY_THRESHOLD && mRecording && !mBatteryStopNotified) {
                    mBatteryStopNotified = true;
                    Toast.makeText(context,
                            "Battery low (" + percent + "%) — stopping recording to save file",
                            Toast.LENGTH_LONG).show();
                    stopRecording();
                }
            }
        };
        registerReceiver(mBatteryReceiver, filter);
    }

    private void unregisterBatteryMonitor() {
        if (mBatteryReceiver != null) {
            try {
                unregisterReceiver(mBatteryReceiver);
            } catch (Exception ignored) {
            }
            mBatteryReceiver = null;
        }
    }

    /**
     * Honest note: DRM-protected content (Netflix, Prime, Disney+, etc.) cannot be
     * recorded on Android. The OS (Widevine L1/L3) blanks the MediaProjection frames
     * at the hardware/trusted-execution level — no screen recorder can bypass this.
     * Non-DRM streams (Twitch, YouTube web, self-hosted/own content) record normally.
     */
    private void showDrmNoteIfApplicable() {
        // Tips are OFF by default. Only show if the user enabled "Show tips" in Settings.
        if (!getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("show_tips", false)) {
            return;
        }
        // Just inform the user DRM apps will record as a blank screen.
        Toast.makeText(this,
                "Tip: DRM apps (Netflix etc.) record blank — non-DRM streams work fine",
                Toast.LENGTH_LONG).show();
    }

    private void startForegroundCompat(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(id, notification);
        }
    }

    private void stopRecording() {
        if (!mRecording) return;

        try {
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (RuntimeException e) {
            com.jnet.screenrecorder.ErrorLog.e("Recorder stop error", e);
            Log.e(TAG, "Recorder stop error", e);
            if (mOutputFile != null && mOutputFile.exists()) {
                mOutputFile.delete();
            }
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        sMediaProjection = null;

        mRecording = false;

        // Clean up the recording-time bar and battery monitor
        hideRecordingTimeBar();
        unregisterBatteryMonitor();

        if (mOutputFile != null && mOutputFile.exists()) {
            scanFile(mOutputFile);
            Toast.makeText(this, "Saved: " + mOutputFile.getName(), Toast.LENGTH_LONG).show();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void takeScreenshot() {
        if (mMediaProjection == null) {
            Toast.makeText(this, "Start recording first, then screenshot", Toast.LENGTH_SHORT).show();
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;

        final ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = mMediaProjection.createVirtualDisplay(
                "ScreenshotDisplay",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        HandlerThread ht = new HandlerThread("screenshot");
        ht.start();
        Handler h = new Handler(ht.getLooper());

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    int w = image.getWidth();
                    int hgt = image.getHeight();
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * w;

                    Bitmap bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, hgt, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buffer);
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, hgt);
                    bmp.recycle();

                    saveScreenshot(cropped);
                }
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("Screenshot failed", e);
                Log.e(TAG, "Screenshot failed", e);
            } finally {
                if (image != null) image.close();
            }
        }, h);

        // Give it a moment to capture then cleanup
        h.postDelayed(() -> {
            vd.release();
            imageReader.close();
            ht.quitSafely();
        }, 300);
    }

    private void saveScreenshot(Bitmap bmp) {
        File dir = getScreenshotsDir();
        String name = "SCR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        File file = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            bmp.recycle();
            scanFile(file);
            Toast.makeText(this, "Screenshot saved: " + file.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            com.jnet.screenrecorder.ErrorLog.e("Save screenshot failed", e);
            Log.e(TAG, "Save screenshot failed", e);
            Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
        }
    }

    private File getRecordingsDir() {
        return StorageUtil.getRecordingsDir(this);
    }

    private File getScreenshotsDir() {
        return StorageUtil.getScreenshotsDir(this);
    }

    private void scanFile(File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(file));
        sendBroadcast(mediaScanIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, com.jnet.screenrecorder.MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_recording))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true);

        if (mRecording) {
            // Recording → show a STOP toggle
            Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
            PendingIntent stopPending = PendingIntent.getService(
                    this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_stop, getString(R.string.stop_recording), stopPending);
        } else {
            // Idle → show a START toggle (routes through MainActivity for screen-capture permission)
            Intent startIntent = new Intent(this, com.jnet.screenrecorder.MainActivity.class)
                    .setAction(com.jnet.screenrecorder.MainActivity.ACTION_REQUEST_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent startPending = PendingIntent.getActivity(
                    this, 2, startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_record, getString(R.string.start_recording), startPending);
        }

        return builder.build();
    }

    /** Refreshes the ongoing notification so its action matches the recording state. */
    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(mRecording ? "● Recording" : "Tap to record"));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
}
