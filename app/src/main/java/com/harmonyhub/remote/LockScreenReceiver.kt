package com.harmonyhub.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.widget.Toast

/** Presses are handled one at a time, in the order they were tapped. */
private object PressQueue {
    val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** A press that waited this long behind slower ones is dropped rather than sent late. */
    const val MAX_WAIT_MS = 5000L
}

/** Receives a tap on one of the lock-screen buttons and presses it on the hub. */
class LockScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockScreen.ACTION_PRESS) return
        val index = intent.getIntExtra(LockScreen.EXTRA_INDEX, -1)
        val app = context.applicationContext
        val hub = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).getString(MainActivity.KEY_HUB_URL, null)
        // The pairing cookie lives in the WebView's store; read it here, on the main thread.
        val cookie = try {
            hub?.let { CookieManager.getInstance().getCookie(it) }
        } catch (e: RuntimeException) {
            null
        }
        val received = SystemClock.elapsedRealtime()
        val pending = goAsync()
        val say = { message: String -> Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }; Unit }
        PressQueue.executor.execute {
            try {
                if (SystemClock.elapsedRealtime() - received > PressQueue.MAX_WAIT_MS) {
                    say(app.getString(R.string.lock_screen_slow))
                } else {
                    val result = LockScreen.press(app, index, cookie)
                    if (result is HubClient.Result.Failed) say(result.message)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
