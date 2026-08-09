package com.jnet.screenrecorder.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.jnet.screenrecorder.R;

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
            if (resultCode != -1 && data != null) {
                setMediaProjection(resultCode, data);
                startRecording();
            } else {
                Toast.makeText(this, "Screen capture permission not granted", Toast.LENGTH_LONG).show();
            }
        }

        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (mMediaProjection != null) {
            stopRecording();
        }

        try {
            mMediaProjection = mProjectionManager.getMediaProjection(sResultCode, sResultData);
        } catch (Exception e) {
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
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        showDrmNoteIfApplicable();
    }

    /**
     * Honest note: DRM-protected content (Netflix, Prime, Disney+, etc.) cannot be
     * recorded on Android. The OS (Widevine L1/L3) blanks the MediaProjection frames
     * at the hardware/trusted-execution level — no screen recorder can bypass this.
     * Non-DRM streams (Twitch, YouTube web, self-hosted/own content) record normally.
     */
    private void showDrmNoteIfApplicable() {
        if (!getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("record_streams", true)) {
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
            Log.e(TAG, "Save screenshot failed", e);
            Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
        }
    }

    private File getRecordingsDir() {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File dir = new File(base, "ScreenRecorder/Recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getScreenshotsDir() {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File dir = new File(base, "ScreenRecorder/Screenshots");
        if (!dir.exists()) dir.mkdirs();
        return dir;
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

        Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_recording))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(0, getString(R.string.stop_recording), stopPending);

        return builder.build();
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
