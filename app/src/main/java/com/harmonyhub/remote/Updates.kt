package com.harmonyhub.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import java.io.File

/** What a check found for one thing (the hub's software, or this app). */
data class UpdateStatus(
    val kind: UpdateKind,
    val installed: Version?,
    /** Set when something newer, and not skipped, can be installed. */
    val available: Release?,
    /** Why the check could not tell (hub unreachable, GitHub limiting requests, ...). */
    val problem: String? = null,
)

/** The checks, the downloads and the installs, with the phone's own state around them. */
object Updates {
    private val downloads = Downloads()

    fun dir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    private fun hubUrl(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).getString(MainActivity.KEY_HUB_URL, null)

    private fun hubUpdater(context: Context): HubUpdater? {
        val url = hubUrl(context)?.let { HubAddress.normalize(it) } ?: return null
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (e: RuntimeException) { null }
        return HubUpdater(url, cookie)
    }

    /** This app's version without a debug suffix; a debug build is a different app and is not offered updates. */
    fun appVersion(context: Context): Version? =
        Version.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName?.removeSuffix("-debug"))

    fun isDebugBuild(context: Context) = context.packageName.endsWith(".debug")

    fun onUnmeteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Checks the hub's software and this app, recording the outcome in the settings. */
    fun check(context: Context): List<UpdateStatus> {
        val settings = UpdateSettings(context)
        val results = listOf(UpdateKind.HUB, UpdateKind.APP).map { kind ->
            try {
                val installed = when (kind) {
                    UpdateKind.APP -> appVersion(context)
                    UpdateKind.HUB -> {
                        val updater = hubUpdater(context) ?: throw UpdateException("No hub address is set.")
                        Version.parse(updater.status().version)
                    }
                }
                val skipped = settings.skipped(kind)
                val releases = Releases.parse(downloads.text(kind.listUrl))
                val available = Releases.newer(releases, installed, settings.includePre, skipped)
                settings.setFound(kind, available?.version)
                UpdateStatus(kind, installed, available)
            } catch (e: UpdateException) {
                UpdateStatus(kind, null, null, e.message)
            }
        }
        settings.lastCheckAt = System.currentTimeMillis()
        settings.lastCheckResult = results.joinToString("; ") { r ->
            val what = if (r.kind == UpdateKind.HUB) "Hub" else "App"
            when {
                r.problem != null -> "$what: ${r.problem}"
                r.available != null -> "$what: ${r.available.version} available"
                else -> "$what: up to date"
            }
        }
        return results
    }

    /** Downloads [release]'s file for [kind] (or reuses one already here), checked against its published hash. */
    fun download(context: Context, kind: UpdateKind, release: Release, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val name = kind.assetName(release.version)
        val asset = release.asset(name) ?: throw UpdateException("This release has no $name file.")
        val sumAsset = release.asset("$name.sha256") ?: throw UpdateException("This release has no checksum for $name, so it is not installed.")
        val sum = Downloads.parseChecksum(downloads.text(sumAsset.url, 1024)) ?: throw UpdateException("The checksum for $name is not readable.")
        val dest = File(dir(context), name)
        if (Downloads.matches(dest, sum)) return dest
        // Older downloads are of no use once a newer one is wanted.
        dir(context).listFiles()?.filter { it.name.startsWith(kind.assetPrefix) }?.forEach { it.delete() }
        downloads.file(asset.url, dest, asset.size, sum) { progress(it, asset.size) }
        return dest
    }

    /** Installs a downloaded hub bundle; the work happens on the calling (background) thread. */
    fun installHub(context: Context, bundle: File, say: (String) -> Unit): HubUpdater.Outcome {
        val updater = hubUpdater(context) ?: throw UpdateException("No hub address is set.")
        return updater.install(bundle.readText(), say)
    }
}
