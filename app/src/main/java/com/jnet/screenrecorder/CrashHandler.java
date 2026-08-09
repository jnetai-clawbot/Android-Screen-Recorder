package com.jnet.screenrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global uncaught-exception handler.
 *
 * When the app crashes, this captures the full stack trace, shows it in a dialog
 * with a "Copy error to clipboard" button, and writes it to a log file on disk so
 * we can see exactly why it crashed. It also re-throws to the default handler so
 * the crash is still reported normally.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "ScreenRecorderCrash";
    private static final String LOG_FILE = "crash_log.txt";

    private final Thread.UncaughtExceptionHandler mDefaultHandler;
    private final Context mAppContext;

    public CrashHandler(Context context) {
        mAppContext = context.getApplicationContext();
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    /** Installs this handler as the global uncaught-exception handler. */
    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String stack = getStackTrace(throwable);
        String report = buildReport(thread, throwable, stack);

        // 1. Write to a log file on disk
        writeLogFile(report);

        // 2. Log to logcat
        Log.e(TAG, report);

        // 3. Show a dialog with the error + copy-to-clipboard, then exit
        showCrashDialog(report);

        // 4. Let the default handler finish (so the OS still reports the crash)
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, throwable);
        }
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    private String buildReport(Thread thread, Throwable throwable, String stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== J~Net Screen Recorder Crash Report ===\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n");
        sb.append("Thread: ").append(thread.getName()).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("App version: ").append(getAppVersion()).append("\n\n");
        sb.append("Exception: ").append(throwable.getClass().getName())
                .append(": ").append(throwable.getMessage()).append("\n\n");
        sb.append(stack);
        return sb.toString();
    }

    private String getAppVersion() {
        try {
            return mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void writeLogFile(String report) {
        try {
            java.io.File dir = mAppContext.getFilesDir();
            java.io.File file = new java.io.File(dir, LOG_FILE);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            fos.write(("\n\n" + report).getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Could not write crash log", e);
        }
    }

    private void showCrashDialog(final String report) {
        try {
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    AlertDialog.Builder builder = new AlertDialog.Builder(mAppContext);
                    builder.setTitle("App crashed");
                    builder.setMessage("An error occurred. Copy it and send it to the developer:\n\n" + report);
                    builder.setPositiveButton("Copy error to clipboard", (d, w) -> {
                        copyToClipboard(report);
                        Toast.makeText(mAppContext, "Error copied to clipboard", Toast.LENGTH_LONG).show();
                    });
                    builder.setNegativeButton("Close", (d, w) -> {});
                    builder.setCancelable(false);
                    AlertDialog dialog = builder.create();
                    // Use a normal dialog window type — NOT overlay, which requires
                    // overlay permission and crashes on first open before it's granted.
                    dialog.show();
                } catch (Exception e) {
                    Log.e(TAG, "Could not show crash dialog", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Could not post crash dialog", e);
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) mAppContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("crash_report", text));
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not copy to clipboard", e);
        }
    }
}
