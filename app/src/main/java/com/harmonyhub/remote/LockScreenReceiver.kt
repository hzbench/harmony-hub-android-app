package com.harmonyhub.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast

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
        val pending = goAsync()
        Thread {
            try {
                val result = LockScreen.press(app, index, cookie)
                if (result is HubClient.Result.Failed) {
                    Handler(Looper.getMainLooper()).post { Toast.makeText(app, result.message, Toast.LENGTH_LONG).show() }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
