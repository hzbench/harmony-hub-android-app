package com.harmonyhub.remote

import android.content.Context
import android.content.SharedPreferences

enum class CheckMode { MANUAL, ON_OPEN, DAILY }

/** The options on the Settings screen, kept in the app's preferences. */
class UpdateSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE))

    var checkMode: CheckMode
        get() = CheckMode.values().firstOrNull { it.name == prefs.getString("update_check", null) } ?: CheckMode.ON_OPEN
        set(value) = prefs.edit().putString("update_check", value.name).apply()

    /** Download a found update by itself, but only on an unmetered network; off means ask first. */
    var autoDownloadOnWifi: Boolean
        get() = prefs.getBoolean("update_auto_download", false)
        set(value) = prefs.edit().putBoolean("update_auto_download", value).apply()

    var notify: Boolean
        get() = prefs.getBoolean("update_notify", true)
        set(value) = prefs.edit().putBoolean("update_notify", value).apply()

    var includePre: Boolean
        get() = prefs.getBoolean("update_pre", false)
        set(value) = prefs.edit().putBoolean("update_pre", value).apply()

    fun skipped(kind: UpdateKind): Version? = Version.parse(prefs.getString("update_skip_${kind.key}", null))
    fun skip(kind: UpdateKind, version: Version?) =
        prefs.edit().apply { if (version == null) remove("update_skip_${kind.key}") else putString("update_skip_${kind.key}", version.toString()) }.apply()

    /** When the last check finished (milliseconds), and a line saying how it went. */
    var lastCheckAt: Long
        get() = prefs.getLong("update_last_at", 0)
        set(value) = prefs.edit().putLong("update_last_at", value).apply()
    var lastCheckResult: String
        get() = prefs.getString("update_last_result", "") ?: ""
        set(value) = prefs.edit().putString("update_last_result", value).apply()

    /** The newest versions the last check found (so the screen can show them before checking again). */
    fun found(kind: UpdateKind): Version? = Version.parse(prefs.getString("update_found_${kind.key}", null))
    fun setFound(kind: UpdateKind, version: Version?) =
        prefs.edit().apply { if (version == null) remove("update_found_${kind.key}") else putString("update_found_${kind.key}", version.toString()) }.apply()
}

enum class UpdateKind(val key: String, val repositoryId: Long, val assetPrefix: String, val assetSuffix: String) {
    // GitHub's repository ids, not names: the account was renamed once, and a name can change hands.
    HUB("hub", 1428724338L, "hub-release-", ".json"),
    APP("app", 1428725546L, "hub-remote-", ".apk");

    fun assetName(version: Version) = "$assetPrefix$version$assetSuffix"
    val listUrl get() = "https://api.github.com/repositories/$repositoryId/releases?per_page=15"
}

object CheckSchedule {
    const val MIN_GAP_MS = 60 * 60 * 1000L

    /** Whether opening the app should check now: only in "when the app opens" mode, and not more than hourly. */
    fun dueOnOpen(mode: CheckMode, lastCheckAt: Long, now: Long) =
        mode == CheckMode.ON_OPEN && (lastCheckAt == 0L || now - lastCheckAt >= MIN_GAP_MS || now < lastCheckAt)
}
