package com.example.vehiclechecker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Weekly live re-check of the DVLA tax / MOT status for every saved (favourite) vehicle.
 *
 * Without this the expiry dates behind the daily reminder worker are frozen at whatever
 * the last manual search produced, so a car that passes its MOT or renews its tax keeps
 * notifying about the old date. The worker re-runs [VehicleScraper] (plain Jsoup, so no
 * WebView is needed off the main thread) and rewrites the stored dates.
 */
class StatusRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val saved = db.vehicleDao().getFavourites()
        if (saved.isEmpty()) {
            Log.d(TAG, "No saved vehicles; nothing to refresh")
            return Result.success()
        }

        Log.d(TAG, "Refreshing tax/MOT status for ${saved.size} saved vehicle(s)")
        var failures = 0

        saved.forEach { vehicle ->
            val fresh = VehicleScraper.scrapeVehicleData(vehicle.registration)
            val outcome = StatusRefresher.merge(
                existingTaxDueEpochMs = vehicle.taxDueEpochMs,
                existingMotExpiryEpochMs = vehicle.motExpiryEpochMs,
                fresh = fresh
            )

            // Targeted UPDATE: a background refresh must not reorder recent searches
            // or touch the favourite flag, so the row's timestamp is deliberately left.
            db.vehicleDao().updateExpiryDates(
                registration = vehicle.registration,
                taxDueEpochMs = outcome.taxDueEpochMs,
                motExpiryEpochMs = outcome.motExpiryEpochMs
            )

            outcome.cacheableVehicle?.let { freshVehicle ->
                val cached = db.cachedVehicleDao().get(vehicle.registration) ?: return@let
                db.cachedVehicleDao().upsert(
                    CachedVehicleEntity.fromData(freshVehicle, cached.toMot(), cached.aiReport)
                )
            }

            if (fresh.errorMessage != null) {
                failures++
                Log.w(TAG, "${vehicle.registration}: refresh failed - ${fresh.errorMessage}")
            } else {
                Log.d(
                    TAG,
                    "${vehicle.registration}: tax=${fresh.taxDueDate.ifBlank { "?" }} " +
                        "mot=${fresh.motExpiryDate.ifBlank { "?" }} -> " +
                        "${outcome.taxDueEpochMs}/${outcome.motExpiryEpochMs}" +
                        if (outcome.cacheableVehicle != null) " (cache updated)" else " (cache kept)"
                )
            }
        }

        // Every vehicle failing means the network or the DVLA service was unreachable —
        // retry with backoff rather than waiting a whole week, but give up after a few
        // attempts so an offline plate cannot hammer the service forever.
        return if (failures == saved.size && runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "status_refresh_weekly"
        const val NOW_WORK_NAME = "status_refresh_now"
        private const val TAG = "StatusRefreshWorker"
        private const val MAX_ATTEMPTS = 3
    }
}
