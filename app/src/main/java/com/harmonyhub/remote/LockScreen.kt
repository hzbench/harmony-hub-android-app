package com.harmonyhub.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * The Volume − / Play / Volume + notification that stays on the lock screen while an
 * activity is on. The hub's page says what is running (see MainActivity's bridge); a
 * button press goes to [LockScreenReceiver], which works with or without the app open.
 */
object LockScreen {
    // Silent notifications are hidden from many lock screens, so this channel is a normal
    // one that never makes a sound, buzz or light. (A channel's importance can't be raised
    // after it exists, hence a new id; the old silent one is removed.)
    private const val CHANNEL_ID = "remote_controls_lock"
    private const val OLD_CHANNEL_ID = "remote_controls"
    private const val NOTIFICATION_ID = 1
    const val ACTION_PRESS = "com.harmonyhub.remote.PRESS"
    const val EXTRA_INDEX = "index"
    /** The running activity, kept so a button still works after the app's process has gone. */
    const val KEY_RUNNING = "running_activity"
    private const val KEY_CHANNEL_MIGRATED = "lock_channel_migrated"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Shows the buttons for [running], or removes them when nothing is. */
    fun update(context: Context, running: RunningActivity?) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val json = running?.toJson()
        // The page may announce the same thing again; nothing to redo while it is already showing.
        if (json != null && json == prefs.getString(KEY_RUNNING, null) &&
            manager.activeNotifications.any { it.id == NOTIFICATION_ID }) return
        prefs.edit().apply { if (json == null) remove(KEY_RUNNING) else putString(KEY_RUNNING, json) }.apply()
        if (running == null || !canNotify(context)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!prefs.getBoolean(KEY_CHANNEL_MIGRATED, false)) {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            prefs.edit().putBoolean(KEY_CHANNEL_MIGRATED, true).apply()
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.lock_screen_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
        )
        manager.notify(NOTIFICATION_ID, build(context, running))
    }

    private fun build(context: Context, running: RunningActivity): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(running.name)
            .setContentText(context.getString(R.string.lock_screen_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(*compactActions(running)))
        running.buttons.forEachIndexed { index, button ->
            val press = PendingIntent.getBroadcast(
                context, index,
                Intent(ACTION_PRESS).setComponent(ComponentName(context, LockScreenReceiver::class.java)).putExtra(EXTRA_INDEX, index),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, iconFor(button.key)), button.label, press).build())
        }
        return builder.build()
    }

    /** Android shows at most three actions in the collapsed notification; Mute waits for the expanded one. */
    private fun compactActions(running: RunningActivity): IntArray =
        running.buttons.indices.filter { running.buttons.size <= 3 || running.buttons[it].key != "mute" }.take(3).toIntArray()

    private fun iconFor(key: String) = when (key) {
        "volumedown" -> R.drawable.ic_lock_volume_down
        "volumeup" -> R.drawable.ic_lock_volume_up
        "mute" -> R.drawable.ic_lock_mute
        else -> R.drawable.ic_lock_play
    }

    /** Presses button [index] of the remembered running activity. Blocking: call off the main thread. */
    fun press(context: Context, index: Int, cookie: String?): HubClient.Result {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val running = RunningActivity.parse(prefs.getString(KEY_RUNNING, null))
            ?: return HubClient.Result.Failed(context.getString(R.string.lock_screen_nothing_running))
        val button = running.buttons.getOrNull(index) ?: return HubClient.Result.Failed(context.getString(R.string.lock_screen_nothing_running))
        val hub = prefs.getString(MainActivity.KEY_HUB_URL, null) ?: return HubClient.Result.Failed(context.getString(R.string.lock_screen_no_hub))
        return HubClient(hub, cookie).press(button)
    }
}
