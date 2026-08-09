package com.jnet.screenrecorder.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.provider.MediaStore;
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
    private View mScreenshotBubble;  // separate draggable screenshot bubble
    private boolean mExpanded = false;
    private boolean mRecording = false;

    // separate screenshot bubble drag state
    private float mShotStartX, mShotStartY;
    private int mShotBaseX, mShotBaseY;
    private boolean mShotDragging;

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

    /**
     * Re-applies the bubble size, colour and location from the current settings
     * to a live-running bubble (used when settings change while it's visible).
     */
    public void refreshFromSettings() {
        SharedPreferences prefs = getSharedPreferences("screenrecorder", MODE_PRIVATE);
        if (mBubbleView != null) {
            ImageButton btn = mBubbleView.findViewById(R.id.btn_bubble);
            if (btn != null) {
                int size = parseSize(prefs.getString("bubble_size", "44"));
                int px = (int) (size * getResources().getDisplayMetrics().density);
                // bubble_collapsed.xml root is a FrameLayout -> use FrameLayout.LayoutParams
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(px, px);
                btn.setLayoutParams(lp);
                int colour = parseColour(prefs.getString("bubble_colour", "0xE61565C0"));
                btn.getBackground().mutate().setTint(colour);
            }
        }
    }

    /** Parses a colour value stored as a string like "0xE61565C0" into an int ARGB. */
    private int parseColour(String hex) {
        try {
            String s = hex.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                s = s.substring(2);
            }
            return (int) Long.parseLong(s, 16);
        } catch (Exception e) {
            return 0xE61565C0;
        }
    }

    /** Parses a bubble size stored as a string like "44" into an int (dp). */
    private int parseSize(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 44;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
        // IMPORTANT: call startForeground() BEFORE adding any overlay views.
        // On Android 14+ a foreground service must be foregrounded promptly after
        // startForegroundService(); adding overlay windows first can crash the
        // service (ForegroundServiceTypeException / BadTokenException).
        startForegroundCompat(NOTIF_ID, buildNotification());
        createBubble();
    }

    private void startForegroundCompat(int id, Notification notification) {
        // NOTE: do NOT use FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION here.
        // The bubble service has no MediaProjection of its own — using that type
        // without an active projection throws ForegroundServiceTypeException,
        // which crashed the app the moment the bubble started after overlay grant.
        startForeground(id, notification);
    }

    /**
     * Temporarily re-foregrounds the bubble service with the MEDIA_PROJECTION type,
     * which Android 10+ requires before getMediaProjection() can be called.
     * Called only while taking a screenshot.
     */
    private void startForegroundWithProjectionType() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("startForeground(projection type) failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.jnet.screenrecorder.REFRESH_BUBBLE".equals(action)) {
                refreshFromSettings();
                return START_STICKY;
            }
            if (RecorderService.isRecording()) {
                mRecording = true;
            }
        }
        return START_STICKY;
    }

    private void createBubble() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        SharedPreferences prefs = getSharedPreferences("screenrecorder", MODE_PRIVATE);

        // --- Collapsed bubble ---
        mBubbleView = inflater.inflate(R.layout.bubble_collapsed, null);
        ImageButton btnExpand = mBubbleView.findViewById(R.id.btn_bubble);

        // Apply saved bubble size (dp)
        int bubbleSize = parseSize(prefs.getString("bubble_size", "44"));
        // bubble_collapsed.xml root is a FrameLayout -> use FrameLayout.LayoutParams
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        (int) (bubbleSize * getResources().getDisplayMetrics().density),
                        (int) (bubbleSize * getResources().getDisplayMetrics().density));
        btnExpand.setLayoutParams(lp);

        // Apply saved bubble colour
        int bubbleColour = parseColour(prefs.getString("bubble_colour", "0xE61565C0"));
        btnExpand.getBackground().mutate().setTint(bubbleColour);

        // Semi-transparent until pressed, then fully visible (via state list animator)
        btnExpand.setAlpha(0.55f);
        btnExpand.setClickable(false); // let the FrameLayout handle all touches
        btnExpand.setFocusable(false);

        // Make the whole bubble (FrameLayout) clickable so touch events reach it
        mBubbleView.setClickable(true);
        mBubbleView.setFocusable(true);
        mBubbleView.setFocusableInTouchMode(true);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        // Restore saved bubble location (if any)
        params.x = prefs.getInt("bubble_x", 100);
        params.y = prefs.getInt("bubble_y", 300);

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
                    if (mDragging) {
                        // Save the bubble location so it's restored next launch
                        getSharedPreferences("screenrecorder", MODE_PRIVATE)
                                .edit()
                                .putInt("bubble_x", params.x)
                                .putInt("bubble_y", params.y)
                                .apply();
                    } else {
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
            com.jnet.screenrecorder.ErrorLog.e("Overlay permission missing / addView failed", e);
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

        // Start/Stop toggle: Record shown when idle, Stop shown while recording
        btnRecord.setVisibility(mRecording ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(mRecording ? View.VISIBLE : View.GONE);
        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnScreenshot.setOnClickListener(v -> {
            // Toggle the camera bubble: show it if hidden, hide it if shown.
            // Tapping the camera bubble then takes an instant screenshot (1 tap).
            toggleScreenshotBubble();
        });
        btnSettings.setOnClickListener(v -> {
            toggleExpanded();
            Intent i = new Intent(this, com.jnet.screenrecorder.settings.SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        btnClose.setOnClickListener(v -> stopSelf());

        // Show the separate draggable screenshot bubble (if overlay mode is on)
        showScreenshotBubble();

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
            mWindowManager.removeView(mExpandedView);
            mExpanded = false;
            // show collapsed bubble
            try {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mBubbleView.getLayoutParams();
                mWindowManager.updateViewLayout(mBubbleView, p);
            } catch (Exception ignored) {
            }
        } else {
            // Sync Record/Stop buttons with the REAL recording state before showing
            mRecording = RecorderService.isRecording();
            ImageButton recBtn = mExpandedView.findViewById(R.id.btn_record);
            ImageButton stopBtn = mExpandedView.findViewById(R.id.btn_stop);
            if (recBtn != null) recBtn.setVisibility(mRecording ? View.GONE : View.VISIBLE);
            if (stopBtn != null) stopBtn.setVisibility(mRecording ? View.VISIBLE : View.GONE);

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
                com.jnet.screenrecorder.ErrorLog.e("Failed to expand bubble", e);
                Log.e(TAG, "Failed to expand", e);
            }
        }
    }

    private void startRecording() {
        // If we are already recording, this button acts as STOP instead of
        // starting a new recording.
        if (RecorderService.isRecording()) {
            stopRecording();
            return;
        }
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
        // Hide both bubbles so they don't appear in the screenshot.
        hideBubblesForScreenshot();
        if (mMediaProjection == null) {
            try {
                // Android 10+ requires the service to be a foreground service of type
                // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection().
                // The bubble normally runs WITHOUT that type (to avoid a crash on start),
                // so temporarily re-foreground with the mediaProjection type here.
                startForegroundWithProjectionType();
                mMediaProjection = mProjectionManager.getMediaProjection(
                        RecorderService.getProjectionResultCode(),
                        RecorderService.getProjectionData());
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("Screenshot projection error", e);
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
                    // Re-show the bubbles now that the screenshot has been captured.
                    showBubblesAfterScreenshot();
                }
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("Screenshot capture failed", e);
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

    /** Toggles the camera screenshot bubble on/off. */
    private void toggleScreenshotBubble() {
        if (mScreenshotBubble != null) {
            hideScreenshotBubble();
        } else {
            showScreenshotBubble();
        }
    }

    /** Removes the camera screenshot bubble if it is showing. */
    private void hideScreenshotBubble() {
        if (mScreenshotBubble != null) {
            try {
                mWindowManager.removeView(mScreenshotBubble);
            } catch (Exception ignored) {
            }
            mScreenshotBubble = null;
        }
    }

    /** Hides both the main bubble and the screenshot bubble so they don't appear in a screenshot. */
    private void hideBubblesForScreenshot() {
        try {
            if (mBubbleView != null) mWindowManager.removeView(mBubbleView);
        } catch (Exception ignored) {
        }
        try {
            if (mScreenshotBubble != null) mWindowManager.removeView(mScreenshotBubble);
        } catch (Exception ignored) {
        }
    }

    /** Re-shows the main bubble (and the screenshot bubble if it was showing) after a screenshot. */
    private void showBubblesAfterScreenshot() {
        // Re-add the main bubble at its saved position.
        if (mBubbleView != null) {
            try {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mBubbleView.getLayoutParams();
                mWindowManager.addView(mBubbleView, p);
            } catch (Exception ignored) {
            }
        }
        // Re-add the screenshot bubble at its saved position.
        if (mScreenshotBubble != null) {
            try {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mScreenshotBubble.getLayoutParams();
                mWindowManager.addView(mScreenshotBubble, p);
            } catch (Exception ignored) {
            }
        }
    }

    private void showScreenshotBubble() {
        // Add a separate draggable screenshot bubble. This should show whenever the
        // screenshot toggle is tapped, regardless of overlay_mode, so the camera
        // bubble always appears for one-tap screenshots.
        try {
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if (mScreenshotBubble != null) return;
            mScreenshotBubble = inflater.inflate(R.layout.bubble_collapsed, null);
            ImageButton btn = mScreenshotBubble.findViewById(R.id.btn_bubble);
            btn.setImageResource(R.drawable.ic_camera);
            btn.getBackground().mutate().setTint(0xE62E7D32); // green
            // Make BOTH the camera icon and the bubble around it clickable.
            // The ImageButton handles the tap (screenshot); the FrameLayout handles
            // dragging. This way tapping anywhere on the bubble works.
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(v -> takeScreenshot());
            mScreenshotBubble.setClickable(true);
            mScreenshotBubble.setFocusable(true);
            mScreenshotBubble.setFocusableInTouchMode(true);

            WindowManager.LayoutParams sp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            sp.gravity = Gravity.TOP | Gravity.START;
            sp.x = getSharedPreferences("screenrecorder", MODE_PRIVATE).getInt("shot_x", 100);
            sp.y = getSharedPreferences("screenrecorder", MODE_PRIVATE).getInt("shot_y", 500);

            mScreenshotBubble.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mShotStartX = event.getRawX(); mShotStartY = event.getRawY();
                        mShotBaseX = sp.x; mShotBaseY = sp.y; mShotDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - mShotStartX;
                        float dy = event.getRawY() - mShotStartY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            mShotDragging = true;
                            sp.x = mShotBaseX + (int) dx; sp.y = mShotBaseY + (int) dy;
                            try { mWindowManager.updateViewLayout(mScreenshotBubble, sp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Only save the drag position if we actually dragged; the
                        // tap-to-screenshot is handled by the ImageButton click.
                        if (mShotDragging) {
                            getSharedPreferences("screenrecorder", MODE_PRIVATE).edit()
                                    .putInt("shot_x", sp.x).putInt("shot_y", sp.y).apply();
                        }
                        return true;
                }
                return false;
            });

            mWindowManager.addView(mScreenshotBubble, sp);
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Could not show screenshot bubble", e);
            Log.e(TAG, "Could not show screenshot bubble", e);
        }
    }

    private void saveScreenshot(android.graphics.Bitmap bmp) {
        String name = "SCR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        // Save to the visible configured storage location via MediaStore so the
        // screenshot is accessible in Gallery and goes to the same place selected
        // in Settings (same approach as recordings). Falls back to the app-private
        // dir only if MediaStore is unavailable.
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/ScreenRecorder/Screenshots");
            android.net.Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                    bmp.recycle();
                    Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("MediaStore screenshot failed, using fallback", e);
        }
        // Fallback: app-private screenshots dir (always writable)
        try {
            File dir = com.jnet.screenrecorder.StorageUtil.getScreenshotsDir(this);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                bmp.recycle();
                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScanIntent.setData(android.net.Uri.fromFile(file));
                sendBroadcast(mediaScanIntent);
                Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("Save screenshot failed", e);
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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.show_bubble))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true);

        // Backup Start/Stop controls in the notification bar (AZ-style)
        boolean recording = RecorderService.isRecording();
        if (recording) {
            // Stop action
            Intent stopIntent = new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_STOP);
            PendingIntent stopPending = PendingIntent.getService(
                    this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_stop, getString(R.string.stop_recording), stopPending);
        } else {
            // Start action -> route through MainActivity for screen-capture permission
            Intent startIntent = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_REQUEST_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent startPending = PendingIntent.getActivity(
                    this, 2, startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_record, getString(R.string.start_recording), startPending);
        }

        return builder.build();
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
        if (mScreenshotBubble != null) {
            try {
                mWindowManager.removeView(mScreenshotBubble);
            } catch (Exception ignored) {
            }
            mScreenshotBubble = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        super.onDestroy();
    }
}
