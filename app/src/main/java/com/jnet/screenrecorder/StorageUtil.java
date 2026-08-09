package com.jnet.screenrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Resolves the configured storage location and auto-creates the default
 * ScreenRecorder/Recordings and ScreenRecorder/Screenshots folders if missing.
 */
public final class StorageUtil {

    private StorageUtil() {}

    /** Default absolute path. */
    public static final String DEFAULT_PATH = "/storage/emulated/0/DCIM/ScreenRecorder";

    /** Returns the configured base storage folder (from settings, defaulting to DCIM/ScreenRecorder). */
    public static File getBaseDir(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("screenrecorder", Context.MODE_PRIVATE);
        String path = prefs.getString("storage_location", DEFAULT_PATH);
        if (path == null || path.trim().isEmpty()) {
            path = DEFAULT_PATH;
        }
        File dir = new File(path);
        // Fall back to the default if the configured path is unusable.
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(DEFAULT_PATH);
        }
        return dir;
    }

    /** Returns the Recordings folder, creating it (and parents) if missing. */
    public static File getRecordingsDir(Context context) {
        File dir = new File(getBaseDir(context), "Recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            // Fall back to the app's private external dir, which is always writable
            // without any storage permission (scoped-storage safe on Android 10+).
            File fallback = new File(context.getExternalFilesDir(null), "Recordings");
            if (!fallback.exists()) fallback.mkdirs();
            return fallback;
        }
        return dir;
    }

    /** Returns the Screenshots folder, creating it (and parents) if missing. */
    public static File getScreenshotsDir(Context context) {
        File dir = new File(getBaseDir(context), "Screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            File fallback = new File(context.getExternalFilesDir(null), "Screenshots");
            if (!fallback.exists()) fallback.mkdirs();
            return fallback;
        }
        return dir;
    }

    /** Explicitly creates the base, Recordings and Screenshots folders if missing. */
    public static boolean ensureFolders(Context context) {
        // Try the configured DCIM path first; if it can't be created (scoped storage
        // on Android 10+), fall back to the app-private external dir which is always
        // writable without any storage permission.
        File base = getBaseDir(context);
        boolean baseOk = base.exists() || base.mkdirs();
        if (!baseOk) {
            base = context.getExternalFilesDir(null);
            baseOk = base != null && (base.exists() || base.mkdirs());
        }
        File rec = new File(base, "Recordings");
        boolean recOk = rec.exists() || rec.mkdirs();
        File scr = new File(base, "Screenshots");
        boolean scrOk = scr.exists() || scr.mkdirs();
        return baseOk && recOk && scrOk;
    }
}
