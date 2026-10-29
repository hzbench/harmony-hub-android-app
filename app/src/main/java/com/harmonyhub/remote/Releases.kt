package com.harmonyhub.remote

import org.json.JSONArray
import org.json.JSONException

/** One file attached to a GitHub release. */
data class Asset(val name: String, val url: String, val size: Long)

data class Release(
    val version: Version,
    val notes: String,
    val prerelease: Boolean,
    val assets: List<Asset>,
) {
    fun asset(name: String): Asset? = assets.firstOrNull { it.name == name }
}

/** What GitHub's release list says, and which release (if any) is worth offering. */
object Releases {
    /**
     * Published releases only (no drafts), newest first as GitHub lists them. A release whose tag is
     * not a version, or whose file is not served over https, is left out rather than trusted.
     */
    fun parse(json: String): List<Release> {
        val list = try { JSONArray(json) } catch (e: JSONException) { throw UpdateException("GitHub sent something unexpected.") }
        val out = mutableListOf<Release>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optBoolean("draft")) continue
            val version = Version.parse(item.optString("tag_name")) ?: continue
            val assets = mutableListOf<Asset>()
            val files = item.optJSONArray("assets") ?: JSONArray()
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(j) ?: continue
                val url = file.optString("browser_download_url")
                if (!url.startsWith("https://")) continue
                assets.add(Asset(file.optString("name"), url, file.optLong("size")))
            }
            out.add(Release(version, item.optString("body"), item.optBoolean("prerelease"), assets))
        }
        return out
    }

    /** The newest release above [installed] that is not [skipped]; with no known [installed], the newest one. */
    fun newer(releases: List<Release>, installed: Version?, includePre: Boolean, skipped: Version?): Release? =
        releases
            .filter { includePre || (!it.prerelease && it.version.pre == null) }
            .filter { it.version != skipped && (installed == null || it.version > installed) }
            .maxByOrNull { it.version }
}

class UpdateException(message: String) : Exception(message)
