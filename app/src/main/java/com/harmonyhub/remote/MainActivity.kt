package com.harmonyhub.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Minimal WebView wrapper around the harmony-hub-control local web UI.
 *
 * The remote UI is served live by the hub and loaded here completely
 * unmodified: the daily-use screens at http://<hub-ip>:8080/go/ (go.html,
 * go.js, go.css), which link into the original pages at the root for pairing,
 * device setup and settings. This wrapper adds exactly one native capability
 * on top of it: hardware volume-key capture while this screen is in the
 * foreground, forwarded into the page's own command pipeline via
 * window.harmonyHardwareVolume() (defined in go.js, and still in app.js for
 * the original pages). See PLAN.md's "Spec: minimal WebView wrapper with
 * volume-key control" for the full design.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var errorState: View
    private lateinit var errorMessage: TextView
    private lateinit var errorRetry: Button
    private lateinit var loadingState: View
    private lateinit var loadingMessage: TextView
    // The page that is loading failed. onPageFinished also fires for Android's own
    // error page, and for an HTTP error the error callback comes *before*
    // onPageStarted, so a failure is only forgotten when a different page starts
    // or when we start a load ourselves.
    private var loadFailed = false
    private var failedUrl: String? = null

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
        errorState = findViewById(R.id.errorState)
        errorMessage = findViewById(R.id.errorMessage)
        errorRetry = findViewById(R.id.errorRetry)
        loadingState = findViewById(R.id.loadingState)
        loadingMessage = findViewById(R.id.loadingMessage)
        errorRetry.setOnClickListener { retryLoad() }
        findViewById<Button>(R.id.errorChangeAddress).setOnClickListener {
            promptForHubAddress(prefs.getString(KEY_HUB_URL, null))
        }
        configureWebView(webView)

        onBackPressedDispatcher.addCallback(this) {
            // The page gets first say: its editor closes a sheet or asks before
            // discarding unsaved changes. Only when it didn't handle Back do we
            // navigate back (or leave the app).
            webView.evaluateJavascript(
                "window.harmonyHandleBack ? window.harmonyHandleBack() : false"
            ) { handled ->
                if (handled == "true") return@evaluateJavascript
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        val hubUrl = prefs.getString(KEY_HUB_URL, null)
        if (hubUrl.isNullOrBlank()) {
            promptForHubAddress()
        } else {
            load(hubUrl)
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
            // normal remote use. The only additions are the error screen's
            // hooks, which look at the page itself and ignore sub-resources.
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != failedUrl) loadFailed = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showError(request.url.toString(), getString(R.string.error_unreachable, hubLabel()))
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                // A hub that predates the new UI has no /go/ page.
                val message = if (code == 404) R.string.error_no_ui else R.string.error_http
                showError(request.url.toString(), getString(message, hubLabel(), code))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!loadFailed) hideError()
            }
        }

        // Without a WebChromeClient, Android's default is to silently
        // dismiss window.alert()/confirm() as "cancelled" — no dialog at
        // all. app.js relies on confirm() for every destructive action
        // (delete device, delete activity, discard draft, restore
        // backup, reboot), so without this they're all dead buttons here.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun hubLabel(): String = HubAddress.label(prefs.getString(KEY_HUB_URL, null))

    private fun load(url: String) {
        loadFailed = false
        failedUrl = null
        showLoading()
        webView.loadUrl(HubAddress.entryUrl(url))
    }

    private fun showLoading() {
        loadingMessage.text = getString(R.string.loading_hub, hubLabel())
        errorState.visibility = View.GONE
        loadingState.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
    }

    private fun showError(url: String, message: String) {
        loadFailed = true
        failedUrl = url
        errorMessage.text = message
        loadingState.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        errorState.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorState.visibility = View.GONE
        loadingState.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun retryLoad() {
        val hubUrl = prefs.getString(KEY_HUB_URL, null)
        if (hubUrl.isNullOrBlank()) {
            promptForHubAddress()
            return
        }
        load(hubUrl)
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
                val url = HubAddress.normalize(typed)
                prefs.edit().putString(KEY_HUB_URL, url).apply()
                load(url)
            }
            .show()
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
