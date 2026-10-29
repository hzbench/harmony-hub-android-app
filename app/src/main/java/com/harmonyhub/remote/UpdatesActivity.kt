package com.harmonyhub.remote

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's Settings screen: look for updates to the hub's software and to this app, install them,
 * and choose how often to look. Everything is native because a release file cannot be fetched from
 * the hub's web page (GitHub's download links redirect to a host the page may not talk to).
 */
class UpdatesActivity : AppCompatActivity() {
    private lateinit var settings: UpdateSettings
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var statuses: List<UpdateStatus>? = null
    private var busy: String? = null   // what is going on, shown in place of the buttons

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = UpdateSettings(this)
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(resolveColor(androidx.appcompat.R.attr.colorPrimary))
            setTitleTextColor(0xFFFFFFFF.toInt())
            title = getString(R.string.settings_title)
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, resolveDimension(androidx.appcompat.R.attr.actionBarSize)))
            addView(ScrollView(this@UpdatesActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // coming back from the system's "install unknown apps" page
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun resolveColor(attr: Int) = android.util.TypedValue().also { theme.resolveAttribute(attr, it, true) }.data
    private fun resolveDimension(attr: Int) = android.util.TypedValue().also { theme.resolveAttribute(attr, it, true) }.getDimension(resources.displayMetrics).toInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun line(text: String, secondary: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
        if (secondary) alpha = 0.7f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(heading(getString(R.string.updates_heading)))
        for (kind in listOf(UpdateKind.HUB, UpdateKind.APP)) body.addView(kindCard(kind))
        val last = settings.lastCheckAt
        body.addView(line(
            if (last == 0L) getString(R.string.update_never_checked)
            else getString(R.string.update_last_check, DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), settings.lastCheckResult),
            secondary = true
        ))
        body.addView(button(getString(R.string.update_check_now)) { runCheck() }.apply { isEnabled = busy == null })

        body.addView(heading(getString(R.string.update_options)))
        body.addView(line(getString(R.string.update_check_when)))
        body.addView(RadioGroup(this).apply {
            val labels = listOf(CheckMode.MANUAL to R.string.check_manual, CheckMode.ON_OPEN to R.string.check_on_open, CheckMode.DAILY to R.string.check_daily)
            for ((mode, label) in labels) addView(RadioButton(this@UpdatesActivity).apply { id = View.generateViewId(); text = getString(label); isChecked = settings.checkMode == mode; tag = mode })
            setOnCheckedChangeListener { group, checked ->
                settings.checkMode = group.findViewById<View>(checked).tag as CheckMode
                UpdateNotifier.schedule(applicationContext)
            }
        })
        body.addView(check(R.string.update_auto_download, settings.autoDownloadOnWifi) { settings.autoDownloadOnWifi = it })
        body.addView(check(R.string.update_notify, settings.notify) { settings.notify = it })
        body.addView(check(R.string.update_pre, settings.includePre) { settings.includePre = it; statuses = null })
        body.addView(line(getString(R.string.update_always_ask), secondary = true))
        body.addView(heading(getString(R.string.update_manual_heading)))
        body.addView(line(getString(R.string.update_manual_text), secondary = true))
    }

    private fun check(label: Int, on: Boolean, changed: (Boolean) -> Unit) = CheckBox(this).apply {
        text = getString(label)
        isChecked = on
        setOnCheckedChangeListener { _, value -> changed(value) }
    }

    private fun kindCard(kind: UpdateKind): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val name = getString(if (kind == UpdateKind.HUB) R.string.update_hub_name else R.string.update_app_name)
        card.addView(TextView(this).apply { text = name; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val status = statuses?.firstOrNull { it.kind == kind }
        when {
            busy != null && busyKind == kind -> {
                card.addView(line(busy!!))
                return card
            }
            status == null -> {
                val found = settings.found(kind)
                card.addView(line(if (found != null) getString(R.string.update_found_earlier, found.toString()) else getString(R.string.update_not_checked), secondary = true))
                if (found != null) card.addView(offerButtons(kind, null, found))
            }
            status.problem != null -> card.addView(line(status.problem, secondary = true))
            status.available == null -> card.addView(line(getString(R.string.update_up_to_date, status.installed?.toString() ?: "?"), secondary = true))
            else -> {
                val release = status.available
                card.addView(line(getString(R.string.update_available, release.version.toString(), status.installed?.toString() ?: getString(R.string.update_unknown_version))))
                if (release.notes.isNotBlank()) card.addView(line(release.notes.trim().take(700), secondary = true))
                card.addView(offerButtons(kind, release, release.version))
            }
        }
        return card
    }

    private var busyKind: UpdateKind? = null

    private fun offerButtons(kind: UpdateKind, release: Release?, version: Version): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        if (kind == UpdateKind.APP && Updates.isDebugBuild(this@UpdatesActivity)) {
            addView(line(getString(R.string.update_debug_build), secondary = true))
            return@apply
        }
        addView(button(getString(R.string.update_install)) { if (release != null) confirmInstall(kind, release) else runCheck(then = kind) }.apply { isEnabled = busy == null })
        addView(button(getString(R.string.update_skip)) {
            settings.skip(kind, version)
            settings.setFound(kind, null)
            statuses = statuses?.map { if (it.kind == kind) it.copy(available = null) else it }
            render()
        }.apply { isEnabled = busy == null })
    }

    private fun runCheck(then: UpdateKind? = null) {
        busy = getString(R.string.update_checking); busyKind = null
        render()
        worker.execute {
            val found = Updates.check(applicationContext)
            ui.post {
                busy = null
                if (isDestroyed) return@post
                statuses = found
                UpdateNotifier.announce(applicationContext, found)
                render()
                if (then != null) found.firstOrNull { it.kind == then }?.available?.let { confirmInstall(then, it) }
            }
        }
    }

    private fun confirmInstall(kind: UpdateKind, release: Release) {
        val message = if (kind == UpdateKind.HUB) R.string.update_hub_confirm else R.string.update_app_confirm
        AlertDialog.Builder(this)
            .setMessage(getString(message, release.version.toString()))
            .setPositiveButton(R.string.update_install) { _, _ -> install(kind, release) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun install(kind: UpdateKind, release: Release) {
        if (kind == UpdateKind.APP && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.update_allow_installs)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
            return
        }
        busyKind = kind
        busy = getString(R.string.update_downloading)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a hub update must not be cut short by the screen going off
        render()
        val say = { text: String -> ui.post { if (!isDestroyed) { busy = text; render() } }; Unit }
        worker.execute {
            var done: String
            try {
                val file = Updates.download(applicationContext, kind, release) { got, total ->
                    if (total > 0) say(getString(R.string.update_downloading_percent, (got * 100 / total).toInt()))
                }
                if (kind == UpdateKind.HUB) {
                    done = when (val outcome = Updates.installHub(applicationContext, file, say)) {
                        is HubUpdater.Outcome.Updated -> getString(R.string.update_hub_done, outcome.version)
                        is HubUpdater.Outcome.RolledBack -> getString(R.string.update_hub_rolled_back, outcome.version, outcome.result)
                        is HubUpdater.Outcome.Unclear -> getString(R.string.update_hub_unclear, outcome.version, outcome.result)
                    }
                    file.delete()
                } else {
                    ui.post { handOverApk(file) }
                    done = getString(R.string.update_app_handed_over)
                }
            } catch (e: UpdateException) {
                done = e.message ?: getString(R.string.update_failed)
            }
            ui.post {
                busy = null; busyKind = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@post
                settings.setFound(kind, null)
                statuses = statuses?.map { if (it.kind == kind) it.copy(available = null) else it }
                render()
                AlertDialog.Builder(this).setMessage(done).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    /** Android shows its own confirmation, and refuses anything not signed with this app's key. */
    private fun handOverApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this).setMessage(R.string.update_no_installer).setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
