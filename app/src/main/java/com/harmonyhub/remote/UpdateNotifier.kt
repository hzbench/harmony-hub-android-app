package com.harmonyhub.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Tells the person an update is waiting, and runs the daily check. */
object UpdateNotifier {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2
    private const val WORK_NAME = "daily_update_check"

    /** Starts or stops the daily check to match the setting; safe to call every time the app opens. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (UpdateSettings(context).checkMode != CheckMode.DAILY) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailyCheck>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Says which updates were found; nothing when there are none or the person turned this off. */
    fun announce(context: Context, found: List<UpdateStatus>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val updates = found.filter { it.available != null }
        if (updates.isEmpty() || !UpdateSettings(context).notify || !LockScreen.canNotify(context)) {
            if (updates.isEmpty()) manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
        val text = updates.joinToString(", ") {
            context.getString(if (it.kind == UpdateKind.HUB) R.string.update_hub_available else R.string.update_app_available, it.available!!.version.toString())
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, UpdatesActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    class DailyCheck(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val results = Updates.check(applicationContext)
            announce(applicationContext, results)
            val settings = UpdateSettings(applicationContext)
            // The hub's bundle is a few megabytes; an app is a few as well. Only on Wi-Fi, only if asked for.
            if (settings.autoDownloadOnWifi && Updates.onUnmeteredNetwork(applicationContext)) {
                for (r in results) {
                    val release = r.available ?: continue
                    if (r.kind == UpdateKind.APP && Updates.isDebugBuild(applicationContext)) continue
                    try { Updates.download(applicationContext, r.kind, release) } catch (e: UpdateException) { /* offered again by hand */ }
                }
            }
            return Result.success()
        }
    }
}
