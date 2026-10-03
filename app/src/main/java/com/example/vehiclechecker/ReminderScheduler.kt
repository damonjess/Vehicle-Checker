package com.example.vehiclechecker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    /** Runs the expiry check once a day while the app is installed. */
    fun scheduleDailyCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ExpiryReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Re-reads the live DVLA tax / MOT status for saved vehicles once a week so the daily
     * check above runs on real dates rather than the ones captured at the last search.
     * Delays the first run so app launch doesn't immediately hit the service.
     */
    fun scheduleWeeklyStatusRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<StatusRefreshWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(2, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            StatusRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Dev/debug: run the status refresh now instead of waiting for the weekly schedule. */
    fun runStatusRefreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            StatusRefreshWorker.NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<StatusRefreshWorker>().build()
        )
    }
}
