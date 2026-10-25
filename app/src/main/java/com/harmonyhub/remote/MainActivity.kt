package com.harmonyhub.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
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
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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

    // Android 13+ asks before an app may show notifications; the lock-screen buttons are one.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) LockScreen.update(this, RunningActivity.parse(prefs.getString(LockScreen.KEY_RUNNING, null)))
        }

    // The page's file input (Setup > Software update) asks for a file; WebView does nothing
    // unless the app answers. At most one request is open; its callback must be answered once.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else null
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Only takes effect while this Activity is actually in the
        // foreground — Android drops it automatically the moment you
        // leave the screen, so normal screen-off timeout resumes then.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        showHubTitle()
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

        // The hub's page tells the app the hub's name so it can head the toolbar.
        webView.addJavascriptInterface(HubBridge(), "HarmonyNative")

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
                // Save the pairing cookie to disk so a lock-screen button can use it
                // even after this app's process has been stopped.
                CookieManager.getInstance().flush()
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

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                if (fileCallback != null) {   // a picker is already open; its result is not this request's
                    callback.onReceiveValue(null)
                    return true
                }
                fileCallback = callback
                // The page asks for .json, which many pickers can't match by type; let the person
                // see every file, and the page checks what it was given.
                val intent = params.createIntent().setType("*/*")
                try {
                    fileChooser.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)   // answered, so WebView must not handle it again
                }
                return true
            }
        }
    }

    /** The toolbar shows the hub's name, or its address until the hub has one. */
    private fun showHubTitle() {
        supportActionBar?.title =
            HubAddress.title(prefs.getString(KEY_HUB_NAME, null), prefs.getString(KEY_HUB_URL, null))
    }

    /** Asks once, the first time there is something to show; a "no" is not asked again. */
    private fun askToShowNotifications() {
        if (Build.VERSION.SDK_INT < 33 || LockScreen.canNotify(this) || prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Called by the hub's own page (a bridge thread, not the UI thread). */
    private inner class HubBridge {
        /** The activity that is on and its lock-screen buttons, as JSON; empty when none is. */
        @JavascriptInterface
        fun setRunningActivity(json: String) {
            runOnUiThread {
                val running = RunningActivity.parse(json)
                // A cookie set by a request (pairing, say) isn't on disk until flushed, and a
                // button pressed after this process has gone needs it there.
                CookieManager.getInstance().flush()
                LockScreen.update(this@MainActivity, running)
                if (running != null) askToShowNotifications()
            }
        }

        /** The Setup screen's "Hub address" row. */
        @JavascriptInterface
        fun changeHubAddress() {
            runOnUiThread { promptForHubAddress(prefs.getString(KEY_HUB_URL, null)) }
        }

        /** Remotes hide the toolbar (the page's own header takes over); everything else shows it. */
        @JavascriptInterface
        fun setToolbarVisible(visible: Boolean) {
            runOnUiThread { if (visible) supportActionBar?.show() else supportActionBar?.hide() }
        }

        /** "connecting", "online" or "offline": the icon on the right of the toolbar. */
        @JavascriptInterface
        fun setConnection(state: String) {
            runOnUiThread { showConnection(state) }
        }

        @JavascriptInterface
        fun setHubName(name: String) {
            runOnUiThread {
                prefs.edit().putString(KEY_HUB_NAME, name.take(HubAddress.NAME_MAX)).apply()
                showHubTitle()
            }
        }
    }

    private fun hubLabel(): String = HubAddress.label(prefs.getString(KEY_HUB_URL, null))

    private fun load(url: String) {
        loadFailed = false
        failedUrl = null
        supportActionBar?.show()   // the page hides it on remotes; a fresh load starts on a list
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
        // With no address saved there is nothing to go back to, so Cancel only appears when changing one.
        val canCancel = !existing.isNullOrBlank()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.prompt_hub_address)
            .setView(input)
            .setCancelable(canCancel)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isEmpty()) {
                    promptForHubAddress(existing)
                    return@setPositiveButton
                }
                val url = HubAddress.normalize(typed)
                // A different hub has a different name; the page reports it once loaded.
                val editor = prefs.edit().putString(KEY_HUB_URL, url)
                if (url != existing) editor.remove(KEY_HUB_NAME)
                editor.apply()
                // The old hub's running activity, devices and commands mean nothing on another hub.
                if (url != existing) LockScreen.update(this, null)
                showHubTitle()
                load(url)
            }
        if (canCancel) dialog.setNegativeButton(R.string.button_cancel, null)
        dialog.show()
    }

    // The connection icon. Hidden until the page first reports a state, so a hub running an
    // older page (which never does) doesn't show a permanent "connecting".
    private var connectionItem: MenuItem? = null
    private var connectionState: String? = null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        connectionItem = menu.add(Menu.NONE, MENU_CONNECTION, Menu.NONE, R.string.connection_connecting).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
        }
        connectionState?.let { showConnection(it) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CONNECTION) {
            // Same as tapping the page's own icon.
            webView.evaluateJavascript("window.harmonyCheckConnection && window.harmonyCheckConnection()", null)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showConnection(state: String) {
        connectionState = state
        val item = connectionItem ?: return
        val (icon, color, label) = when (state) {
            "online" -> Triple(R.drawable.ic_conn_online, 0xFF4CC38A.toInt(), R.string.connection_online)
            "offline" -> Triple(R.drawable.ic_conn_offline, 0xFFFF6B6B.toInt(), R.string.connection_offline)
            else -> Triple(R.drawable.ic_conn_connecting, 0xFFFFB84D.toInt(), R.string.connection_connecting)
        }
        item.setIcon(icon)
        item.iconTintList = ColorStateList.valueOf(color)
        item.title = getString(label)
        item.contentDescription = getString(label)
        item.isVisible = true
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
        const val PREFS_NAME = "harmony_remote"
        const val KEY_HUB_URL = "hub_url"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val KEY_HUB_NAME = "hub_name"
        private const val MENU_CONNECTION = 1
    }
}
