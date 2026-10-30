package com.thando1402.auralis;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Store-and-Forward WorkManager Worker that runs when NetworkType.CONNECTED is satisfied.
 * Flushes locally serialized JSON crash & non-fatal error reports from context.getFilesDir()
 * to Firebase Crashlytics and Cloud Firestore (`app_error_reports`).
 */
public class CrashReportSyncWorker extends Worker {

    public static class OfflineQueuedCrashException extends Exception {
        public OfflineQueuedCrashException(String message) {
            super(message);
        }
    }

    public CrashReportSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean ok = AuralisTelemetryManager.flushQueuedReportsBlocking(getApplicationContext());
        return ok ? Result.success() : Result.retry();
    }
}
