package com.thando1402.auralis;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AuralisTelemetryManager {

    private static boolean initialized = false;
    private static final String CRASH_LOG_FILE = "auralis_crash_log.txt";

    public static void init(Context context) {
        if (initialized) return;
        initialized = true;

        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            saveCrashReportLocally(appContext, throwable);
            forwardToFirebaseIfAvailable(throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static void saveCrashReportLocally(Context context, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String report = "=== AURALIS CRASH [" + timestamp + "] ===\n"
                    + "RAM Used: " + getRamUsageMb() + " MB | APK Size: " + getApkSizeFormatted(context) + "\n"
                    + sw.toString() + "\n\n";

            File logFile = new File(SongMetadataManager.getStorageDirectory(context), CRASH_LOG_FILE);
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(report.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void forwardToFirebaseIfAvailable(Throwable throwable) {
        try {
            Class<?> crashlyticsClass = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
            Object instance = crashlyticsClass.getMethod("getInstance").invoke(null);
            crashlyticsClass.getMethod("recordException", Throwable.class).invoke(instance, throwable);
        } catch (Exception ignored) {
            // Firebase Crashlytics not yet linked via google-services.json
        }
    }

    public static boolean isFirebaseLinked() {
        try {
            Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static File getLastCrashLogFile(Context context) {
        return new File(SongMetadataManager.getStorageDirectory(context), CRASH_LOG_FILE);
    }

    public static String getApkSizeFormatted(Context context) {
        try {
            String sourceDir = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).sourceDir;
            long bytes = new File(sourceDir).length();
            return Formatter.formatShortFileSize(context, bytes);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public static long getRamUsageMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    public static class StorageSnapshot {
        public long internalTotalBytes;
        public long internalFreeBytes;
        public long internalUsedBytes;
        public int internalUsedPercent;

        public boolean hasSdCard;
        public String sdCardRootPath;
        public long sdTotalBytes;
        public long sdFreeBytes;
        public long sdUsedBytes;
        public int sdUsedPercent;
    }

    public static StorageSnapshot getStorageSnapshot(Context context) {
        StorageSnapshot snap = new StorageSnapshot();
        StatFs internalStat = new StatFs(Environment.getDataDirectory().getPath());
        snap.internalTotalBytes = internalStat.getTotalBytes();
        snap.internalFreeBytes = internalStat.getAvailableBytes();
        snap.internalUsedBytes = Math.max(0, snap.internalTotalBytes - snap.internalFreeBytes);
        snap.internalUsedPercent = snap.internalTotalBytes > 0
                ? (int) ((snap.internalUsedBytes * 100L) / snap.internalTotalBytes) : 0;

        File[] extDirs = ContextCompat.getExternalFilesDirs(context, null);
        if (extDirs.length > 1 && extDirs[1] != null
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(extDirs[1]))) {
            snap.hasSdCard = true;
            String fullPath = extDirs[1].getAbsolutePath();
            int androidIdx = fullPath.indexOf("/Android");
            snap.sdCardRootPath = androidIdx > 0 ? fullPath.substring(0, androidIdx) : fullPath;

            StatFs sdStat = new StatFs(extDirs[1].getPath());
            snap.sdTotalBytes = sdStat.getTotalBytes();
            snap.sdFreeBytes = sdStat.getAvailableBytes();
            snap.sdUsedBytes = Math.max(0, snap.sdTotalBytes - snap.sdFreeBytes);
            snap.sdUsedPercent = snap.sdTotalBytes > 0
                    ? (int) ((snap.sdUsedBytes * 100L) / snap.sdTotalBytes) : 0;
        }
        return snap;
    }
}