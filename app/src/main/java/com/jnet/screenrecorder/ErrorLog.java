package com.jnet.screenrecorder;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight runtime error/info logger.
 *
 * Non-fatal errors (recording failures, permission issues, projection errors, etc.)
 * are appended to a log file on disk so the user can review them in the Settings
 * page ("Diagnostics -> View error log") and copy them to the clipboard.
 *
 * Crashes are captured separately by {@link CrashHandler} and written to the same file.
 */
public final class ErrorLog {

    public static final String LOG_FILE = "crash_log.txt"; // shared with CrashHandler

    private static Context sContext;

    private ErrorLog() {}

    /** Call once from Application/MainActivity to store the app context. */
    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static void i(String message) {
        log("INFO", message);
    }

    public static void e(String message) {
        log("ERROR", message);
    }

    public static void e(String message, Throwable t) {
        log("ERROR", message + (t != null ? "\n" + Log.getStackTraceString(t) : ""));
    }

    private static synchronized void log(String level, String message) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] [" + level + "] " + message + "\n";
        if (sContext != null) {
            writeToFile(line);
        }
        Log.println(level.equals("ERROR") ? Log.ERROR : Log.INFO, "ScreenRecorder", message);
    }

    /** Appends a line to the on-disk log (same file CrashHandler uses). */
    private static void writeToFile(String line) {
        try {
            File dir = sContext.getFilesDir();
            File file = new File(dir, LOG_FILE);
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write(line.getBytes());
            fos.close();
            // Cap the file so it doesn't grow forever (keep the most recent ~200KB)
            if (file.length() > 200 * 1024) {
                trimFile(file);
            }
        } catch (Exception ignored) {
        }
    }

    /** Keeps only the tail of an oversized log file. */
    private static void trimFile(File file) {
        try {
            String content = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            if (content.length() > 150 * 1024) {
                String tail = content.substring(content.length() - 150 * 1024);
                FileOutputStream fos = new FileOutputStream(file, false);
                fos.write(tail.getBytes());
                fos.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads the full error log file (or a friendly message if empty). */
    public static String readLog(Context context) {
        try {
            File dir = context.getFilesDir();
            File file = new File(dir, LOG_FILE);
            if (!file.exists()) {
                return "No errors logged yet. Non-fatal errors and crashes will appear here.";
            }
            String content = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8").trim();
            if (content.isEmpty()) {
                return "No errors logged yet. Non-fatal errors and crashes will appear here.";
            }
            return content;
        } catch (Exception e) {
            return "Could not read error log: " + e.getMessage();
        }
    }

    /** Deletes the error log file. */
    public static void clearLog(Context context) {
        try {
            File file = new File(context.getFilesDir(), LOG_FILE);
            if (file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }
}
