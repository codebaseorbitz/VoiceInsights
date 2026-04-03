package com.example.voiceinsights

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CallRecordingScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CallScanWorker"
        private const val WORK_NAME = "call_recording_scan"
        private const val SCAN_INTERVAL_MINUTES = 5L

        /** Schedule a one-time scan that re-enqueues itself every 5 min */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallRecordingScanWorker>()
                .setInitialDelay(SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Call recording scan scheduled (every $SCAN_INTERVAL_MINUTES min)")
        }

        /** Trigger an immediate scan (e.g., after a phone call ends) */
        fun scanNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallRecordingScanWorker>()
                .build() // No delay

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.d(TAG, "Immediate call recording scan triggered")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val scanner = CallRecordingScanner(applicationContext)
            val importedCount = scanner.scanAndImport()

            if (importedCount > 0) {
                DriveUploadWorker.enqueue(applicationContext)
            }

            Log.d(TAG, "Scan complete: $importedCount new recordings imported")

            // Re-schedule self for the next scan
            schedule(applicationContext)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed: ${e.message}")
            // Re-schedule even on failure
            schedule(applicationContext)
            Result.retry()
        }
    }
}
