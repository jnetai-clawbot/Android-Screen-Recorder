package com.jnet.screenrecorder.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.jnet.screenrecorder.MainActivity;
import com.jnet.screenrecorder.R;
import com.jnet.screenrecorder.recorder.RecorderService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Floating quick-access bubble that overlays on top of other apps.
 * Tapping expands it to show: record / stop / screenshot / settings buttons.
 */
public class BubbleService extends Service {

    private static final String TAG = "BubbleService";
    private static final String CHANNEL_ID = "screen_recorder_bubble";
    private static final int NOTIF_ID = 2;
    private static final int REQUEST_CAPTURE = 1002;

    private WindowManager mWindowManager;
    private View mBubbleView;        // the small circular bubble
    private View mExpandedView;      // the expanded panel with buttons
    private boolean mExpanded = false;
    private boolean mRecording = false;

    private static boolean sRunning = false;

    // screenshot capture state
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mProjectionManager;

    private float mTouchStartX, mTouchStartY;
    private int mStartX, mStartY;
    private boolean mDragging;

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
        createBubble();
        startForegroundCompat(NOTIF_ID, buildNotification());
    }

    private void startForegroundCompat(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(id, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (RecorderService.isRecording()) {
                mRecording = true;
            }
        }
        return START_STICKY;
    }

    private void createBubble() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // --- Collapsed bubble ---
        mBubbleView = inflater.inflate(R.layout.bubble_collapsed, null);
        ImageButton btnExpand = mBubbleView.findViewById(R.id.btn_bubble);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        mBubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchStartX = event.getRawX();
                    mTouchStartY = event.getRawY();
                    mStartX = params.x;
                    mStartY = params.y;
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - mTouchStartX;
                    float dy = event.getRawY() - mTouchStartY;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        mDragging = true;
                        params.x = mStartX + (int) dx;
                        params.y = mStartY + (int) dy;
                        try {
                            mWindowManager.updateViewLayout(mBubbleView, params);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mDragging) {
                        // tap -> toggle expanded
                        toggleExpanded();
                    }
                    return true;
            }
            return false;
        });

        try {
            mWindowManager.addView(mBubbleView, params);
        } catch (Exception e) {
            Log.e(TAG, "Overlay permission missing", e);
            Toast.makeText(this, "Enable overlay permission", Toast.LENGTH_LONG).show();
            stopSelf();
        }

        // --- Expanded panel ---
        mExpandedView = inflater.inflate(R.layout.bubble_expanded, null);

        ImageButton btnRecord = mExpandedView.findViewById(R.id.btn_record);
        ImageButton btnStop = mExpandedView.findViewById(R.id.btn_stop);
        ImageButton btnScreenshot = mExpandedView.findViewById(R.id.btn_screenshot);
        ImageButton btnSettings = mExpandedView.findViewById(R.id.btn_settings);
        ImageButton btnClose = mExpandedView.findViewById(R.id.btn_close);

        // Respect "show screenshot button" setting
        boolean showScreenshot = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("show_screenshot_button", true);
        btnScreenshot.setVisibility(showScreenshot ? View.VISIBLE : View.GONE);

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnScreenshot.setOnClickListener(v -> {
            toggleExpanded();
            takeScreenshot();
        });
        btnSettings.setOnClickListener(v -> {
            toggleExpanded();
            Intent i = new Intent(this, com.jnet.screenrecorder.settings.SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        btnClose.setOnClickListener(v -> stopSelf());

        mExpandedView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                if (mExpanded) toggleExpanded();
                return true;
            }
            return false;
        });
    }

    private void toggleExpanded() {
        if (mExpanded) {
            // collapse
            mWindowManager.removeView(mExpandedView);
            mExpanded = false;
            // show collapsed bubble
            try {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mBubbleView.getLayoutParams();
                mWindowManager.updateViewLayout(mBubbleView, p);
            } catch (Exception ignored) {
            }
        } else {
            // expand
            WindowManager.LayoutParams expandedParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            expandedParams.gravity = Gravity.TOP | Gravity.START;

            // position expanded panel where the bubble is
            WindowManager.LayoutParams bparams = (WindowManager.LayoutParams) mBubbleView.getLayoutParams();
            expandedParams.x = bparams.x;
            expandedParams.y = bparams.y;

            try {
                mWindowManager.addView(mExpandedView, expandedParams);
                mExpanded = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to expand", e);
            }
        }
    }

    private void startRecording() {
        toggleExpanded();
        // We cannot startActivityForResult from a service directly, so route through MainActivity.
        Intent mainIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_REQUEST_CAPTURE);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(mainIntent);
    }

    private void stopRecording() {
        toggleExpanded();
        Intent stopIntent = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_STOP);
        startService(stopIntent);
        mRecording = false;
        Toast.makeText(this, "Stopping recording", Toast.LENGTH_SHORT).show();
    }

    private void takeScreenshot() {
        if (!RecorderService.hasProjection()) {
            Toast.makeText(this, "Grant screen capture first (start a recording once)", Toast.LENGTH_LONG).show();
            return;
        }
        if (mMediaProjection == null) {
            try {
                mMediaProjection = mProjectionManager.getMediaProjection(
                        RecorderService.getProjectionResultCode(),
                        RecorderService.getProjectionData());
            } catch (Exception e) {
                Log.e(TAG, "Screenshot projection error", e);
                Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;

        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        android.hardware.display.VirtualDisplay vd = mMediaProjection.createVirtualDisplay(
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

                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            w + rowPadding / pixelStride, hgt, android.graphics.Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buffer);
                    android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, hgt);
                    bmp.recycle();
                    saveScreenshot(cropped);
                }
            } catch (Exception e) {
                Log.e(TAG, "Screenshot failed", e);
            } finally {
                if (image != null) image.close();
            }
        }, h);

        h.postDelayed(() -> {
            vd.release();
            imageReader.close();
            ht.quitSafely();
        }, 400);
    }

    private void saveScreenshot(android.graphics.Bitmap bmp) {
        File dir = com.jnet.screenrecorder.StorageUtil.getScreenshotsDir(this);
        String name = "SCR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        File file = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            bmp.recycle();
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(android.net.Uri.fromFile(file));
            sendBroadcast(mediaScanIntent);
            Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Save screenshot failed", e);
            Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.show_bubble))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (mExpanded && mExpandedView != null) {
            try {
                mWindowManager.removeView(mExpandedView);
            } catch (Exception ignored) {
            }
        }
        if (mBubbleView != null) {
            try {
                mWindowManager.removeView(mBubbleView);
            } catch (Exception ignored) {
            }
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        super.onDestroy();
    }
}
