package com.thando1402.auralis;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AuralisTelemetryManager {

    private static volatile boolean initialized = false;
    private static final Object CRASH_LOCK = new Object();
    private static final String CRASH_LOG_FILE = "auralis_crash_log.txt";
    private static final String CRASH_QUEUE_JSON_FILE = "auralis_crash_reports.json";
    private static final String CRASH_SYNC_WORK_NAME = "auralis_crash_sync_work";
    private static final int MAX_QUEUED_REPORTS = 25;
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();

    public static void init(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        synchronized (CRASH_LOCK) {
            if (!initialized) {
                initialized = true;
                final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

                Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                    saveReportLocally(appContext, thread != null ? thread.getName() : "unknown", true, "UNCAUGHT_CRASH", throwable);
                    scheduleCrashReportSync(appContext);
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    }
                });

                registerNetworkAutoSync(appContext);
            }
        }

        // If any offline crash reports are waiting in filesDir from a prior crash, enqueue sync immediately
        if (getQueuedCrashReportCount(appContext) > 0) {
            scheduleCrashReportSync(appContext);
            flushPendingReportsAsync(appContext);
        }
    }

    public static void recordNonFatalError(Context context, String tag, Throwable throwable) {
        if (context == null || throwable == null) return;
        final Context appContext = context.getApplicationContext();
        SYNC_EXECUTOR.execute(() -> {
            saveReportLocally(appContext, Thread.currentThread().getName(), false, tag != null ? tag : "NON_FATAL", throwable);
            scheduleCrashReportSync(appContext);
            flushPendingReportsAsync(appContext);
        });
    }

    private static void registerNetworkAutoSync(Context appContext) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (getQueuedCrashReportCount(appContext) > 0) {
                        flushPendingReportsAsync(appContext);
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    public static void flushPendingReportsAsync(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        SYNC_EXECUTOR.execute(() -> {
            if (!PlaybackStateHelper.isOnline(appContext)) return;
            flushQueuedReportsBlocking(appContext);
        });
    }

    /**
     * Uploads all locally queued JSON error reports to both Firebase Crashlytics and Cloud Firestore
     * (`app_error_reports` collection using deterministic document IDs so retries never duplicate).
     */
    public static boolean flushQueuedReportsBlocking(Context appContext) {
        JSONArray pendingReports = readQueuedCrashReports(appContext);
        if (pendingReports.length() == 0) return true;

        try {
            FirebaseApp.initializeApp(appContext);
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCrashlyticsCollectionEnabled(true);

            FirebaseFirestore firestore = null;
            try {
                firestore = FirebaseFirestore.getInstance();
            } catch (Exception ignored) {
            }

            for (int i = 0; i < pendingReports.length(); i++) {
                JSONObject report = pendingReports.optJSONObject(i);
                if (report == null) continue;

                String reportId = report.optString("id", UUID.randomUUID().toString());
                String timestamp = report.optString("timestamp", "unknown");
                String androidVersion = report.optString("androidVersion", "unknown");
                String deviceModel = report.optString("deviceModel", "unknown");
                String threadName = report.optString("threadName", "main");
                String exceptionType = report.optString("exceptionType", "Throwable");
                String message = report.optString("message", "");
                String stackTrace = report.optString("stackTrace", "");
                String tag = report.optString("tag", "CRASH");
                boolean fatal = report.optBoolean("fatal", true);
                long ramUsedMb = report.optLong("ramUsedMb", 0L);
                String uid = report.optString("uid", "anonymous");
                String userName = report.optString("userName", "Listener");

                crashlytics.setUserId(uid);
                crashlytics.setCustomKey("offline_store_and_forward", true);
                crashlytics.setCustomKey("crash_timestamp", timestamp);
                crashlytics.setCustomKey("crash_android_version", androidVersion);
                crashlytics.setCustomKey("crash_device_model", deviceModel);
                crashlytics.setCustomKey("crash_thread", threadName);
                crashlytics.setCustomKey("crash_tag", tag);
                crashlytics.setCustomKey("crash_fatal", fatal);
                crashlytics.setCustomKey("crash_ram_used_mb", ramUsedMb);

                crashlytics.log("Offline " + (fatal ? "Crash" : "Error") + " [" + timestamp + "] on "
                        + deviceModel + " (" + androidVersion + "):\n" + stackTrace);

                String summary = exceptionType + ": " + message + " [Captured offline at " + timestamp + "]";
                crashlytics.recordException(new CrashReportSyncWorker.OfflineQueuedCrashException(summary));

                if (firestore != null) {
                    try {
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("id", reportId);
                        doc.put("timestamp", timestamp);
                        doc.put("epochMs", report.optLong("epochMs", System.currentTimeMillis()));
                        doc.put("fatal", fatal);
                        doc.put("tag", tag);
                        doc.put("deviceModel", deviceModel);
                        doc.put("androidVersion", androidVersion);
                        doc.put("threadName", threadName);
                        doc.put("exceptionType", exceptionType);
                        doc.put("message", message);
                        doc.put("stackTrace", stackTrace);
                        doc.put("ramUsedMb", ramUsedMb);
                        doc.put("uid", uid);
                        doc.put("userName", userName);
                        firestore.collection("app_error_reports").document(reportId).set(doc);
                    } catch (Exception ignored) {
                    }
                }
            }

            crashlytics.sendUnsentReports();
            clearQueuedCrashReports(appContext);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveReportLocally(Context context, String threadName, boolean fatal, String tag, Throwable throwable) {
        synchronized (CRASH_LOCK) {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                String stackTraceStr = sw.toString();

                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                String isoTimestamp = isoFormat.format(new Date());
                String androidVer = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
                String deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim();
                long ramMb = getRamUsageMb();
                String apkSize = getApkSizeFormatted(context);

                String uid = "offline_local";
                String email = "";
                try {
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user != null) {
                        uid = user.getUid();
                        email = user.getEmail() != null ? user.getEmail() : "";
                    }
                } catch (Exception ignored) {
                }

                // 1. Serialize structured crash payload to JSON in context.getFilesDir()
                File jsonQueueFile = getCrashQueueJsonFile(context);
                JSONArray queue = readJsonArrayLocked(jsonQueueFile);
                while (queue.length() >= MAX_QUEUED_REPORTS) {
                    queue.remove(0);
                }

                JSONObject crashObj = new JSONObject();
                crashObj.put("id", UUID.randomUUID().toString());
                crashObj.put("timestamp", isoTimestamp);
                crashObj.put("epochMs", System.currentTimeMillis());
                crashObj.put("fatal", fatal);
                crashObj.put("tag", tag);
                crashObj.put("androidVersion", androidVer);
                crashObj.put("deviceModel", deviceModel);
                crashObj.put("threadName", threadName);
                crashObj.put("exceptionType", throwable.getClass().getName());
                crashObj.put("message", throwable.getMessage() != null ? throwable.getMessage() : "");
                crashObj.put("stackTrace", stackTraceStr);
                crashObj.put("ramUsedMb", ramMb);
                crashObj.put("apkSize", apkSize);
                crashObj.put("uid", uid);
                crashObj.put("email", email);
                crashObj.put("userName", OpenRouterAiHelper.getProfileName(context));
                queue.put(crashObj);

                writeJsonArrayLocked(jsonQueueFile, queue);

                // 2. Also append human-readable entry for local Storage & Crash Diagnostics view
                String readableReport = "=== AURALIS " + (fatal ? "CRASH" : "NON-FATAL [" + tag + "]")
                        + " [" + isoTimestamp + "] ===\n"
                        + "Device: " + deviceModel + " | " + androidVer + "\n"
                        + "RAM Used: " + ramMb + " MB | APK Size: " + apkSize + "\n"
                        + stackTraceStr + "\n\n";

                File logFile = getLastCrashLogFile(context);
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write(readableReport.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void scheduleCrashReportSync(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(CrashReportSyncWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();

            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(CRASH_SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, syncRequest);
        } catch (Exception ignored) {
        }
    }

    public static File getCrashQueueJsonFile(Context context) {
        return new File(context.getFilesDir(), CRASH_QUEUE_JSON_FILE);
    }

    public static JSONArray readQueuedCrashReports(Context context) {
        synchronized (CRASH_LOCK) {
            return readJsonArrayLocked(getCrashQueueJsonFile(context));
        }
    }

    public static int getQueuedCrashReportCount(Context context) {
        return readQueuedCrashReports(context).length();
    }

    public static void clearQueuedCrashReports(Context context) {
        synchronized (CRASH_LOCK) {
            writeJsonArrayLocked(getCrashQueueJsonFile(context), new JSONArray());
        }
    }

    private static JSONArray readJsonArrayLocked(File file) {
        if (file == null || !file.exists()) return new JSONArray();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            String content = sb.toString().trim();
            return content.isEmpty() ? new JSONArray() : new JSONArray(content);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeJsonArrayLocked(File file, JSONArray array) {
        if (file == null) return;
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        } catch (Exception ignored) {
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
