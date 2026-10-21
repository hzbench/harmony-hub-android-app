package com.harmonyhub.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Minimal WebView wrapper around the harmony-hub-control local web UI.
 *
 * The entire remote UI (pairing, devices, activities, settings — see the
 * reviewed app.js/index.html/app.css) is served live by the hub at
 * http://<hub-ip>:8080/ and loaded here completely unmodified. This
 * wrapper adds exactly one native capability on top of it: hardware
 * volume-key capture while this screen is in the foreground, forwarded
 * into the page's own command pipeline via window.harmonyHardwareVolume()
 * (a small additive hook in app.js). See PLAN.md's "Spec: minimal WebView
 * wrapper with volume-key control" for the full design.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Only takes effect while this Activity is actually in the
        // foreground — Android drops it automatically the moment you
        // leave the screen, so normal screen-off timeout resumes then.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        webView = findViewById(R.id.webView)
        configureWebView(webView)

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        val hubUrl = prefs.getString(KEY_HUB_URL, null)
        if (hubUrl.isNullOrBlank()) {
            promptForHubAddress()
        } else {
            webView.loadUrl(hubUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        // app.js uses localStorage for per-viewer conveniences (theme,
        // last controller name) — harmless, reviewed state only.
        webView.settings.domStorageEnabled = true
        // Same-origin first-party cookies only (the pairing cookie). The
        // UI makes no third-party requests during normal remote use.
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            // Default behavior (load everything in this WebView) is
            // correct here: the hub's own UI never links off-hub during
            // normal remote use.
        }
    }

    private fun promptForHubAddress(existing: String? = null) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.prompt_hub_address)
            setText(existing?.removePrefix("http://")?.removeSuffix("/") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.prompt_hub_address)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isEmpty()) {
                    promptForHubAddress(existing)
                    return@setPositiveButton
                }
                val url = normalizeHubUrl(typed)
                prefs.edit().putString(KEY_HUB_URL, url).apply()
                webView.loadUrl(url)
            }
            .show()
    }

    private fun normalizeHubUrl(typed: String): String {
        var url = typed
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url = "$url/"
        return url
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CHANGE_HUB, Menu.NONE, R.string.action_change_hub)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CHANGE_HUB) {
            promptForHubAddress(prefs.getString(KEY_HUB_URL, null))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Foreground-only hardware volume-key capture. Consumed here so the
     * phone's own media volume never changes while this screen is open;
     * forwarded into the page's existing command pipeline. Only fires
     * while this Activity is on screen — that's the chosen scope, not an
     * accident (see PLAN.md: background/locked capture was explicitly
     * not pursued, as it would need a foreground service and/or an
     * Accessibility Service permission).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
            else -> null
        }
        if (direction != null) {
            webView.evaluateJavascript(
                "window.harmonyHardwareVolume && window.harmonyHardwareVolume('$direction');",
                null
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val PREFS_NAME = "harmony_remote"
        private const val KEY_HUB_URL = "hub_url"
        private const val MENU_CHANGE_HUB = 1
    }
}
