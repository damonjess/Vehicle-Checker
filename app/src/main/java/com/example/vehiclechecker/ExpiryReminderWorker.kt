package com.example.vehiclechecker

import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily background check: for every saved vehicle, post a notification when tax or MOT
 * is due within the next 14 days (or already expired).
 */
class ExpiryReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val favourites = db.vehicleDao().getFavourites()
        val now = System.currentTimeMillis()
        val window = TimeUnit.DAYS.toMillis(14)

        favourites.forEach { vehicle ->
            val soonExpiries = listOf(
                "MOT" to vehicle.motExpiryEpochMs,
                "Tax" to vehicle.taxDueEpochMs
            ).filter { (_, epoch) -> epoch != null && epoch - now < window }

            if (soonExpiries.isNotEmpty()) {
                val message = soonExpiries.joinToString(" and ") { (label, epoch) ->
                    val days = TimeUnit.MILLISECONDS.toDays(epoch!! - now)
                    if (days >= 0) "$label expires in $days days" else "$label expired"
                }
                showNotification(vehicle.registration, message, vehicle.registration.hashCode())
            }
        }

        // Purge cached JSON older than 30 days
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        db.cachedVehicleDao().purgeOlderThan(thirtyDaysAgo)

        return Result.success()
    }

    private fun showNotification(title: String, message: String, id: Int) {
        val context = applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // permission not granted; user must enable it in the app
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Expiry reminders", NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "expiry_reminders"
        const val WORK_NAME = "expiry_reminder_daily"
    }
}
