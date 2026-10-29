package com.harmonyhub.remote

import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Waiting, so tests can run the hub's restart without taking minutes. */
interface Clock {
    fun now(): Long
    fun sleep(ms: Long)
}

object SystemClock : Clock {
    override fun now() = System.currentTimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * Installs a hub release bundle the way the page's Setup > Software update does (and
 * tools/push_release.py): begin, the files in hex chunks, apply, then wait for the hub to restart and
 * pass its own startup check. The hub verifies the bundle's signature itself and goes back to the old
 * software if the new one does not start, so this only moves bytes and reports which happened.
 */
class HubUpdater(hubUrl: String, cookie: String?, private val clock: Clock = SystemClock) {
    data class Status(val version: String, val pending: Boolean, val result: String)

    sealed class Outcome {
        data class Updated(val version: String) : Outcome()
        data class RolledBack(val version: String, val result: String) : Outcome()
        data class Unclear(val version: String, val result: String) : Outcome()
    }

    private class Bundle(val manifest: String, val signature: String, val version: String, val files: List<Pair<String, ByteArray>>)

    private val http = HubHttp(hubUrl, cookie)

    private fun call(method: String, path: String, body: JSONObject?, csrf: String?, timeoutMs: Long = 15_000): JSONObject {
        val reply = try {
            http.request(method, "api/v1/$path", body?.toString(), csrf, timeoutMs)
        } catch (e: IOException) {
            throw UpdateException("Couldn't reach the hub.")
        }
        val json = try { JSONObject(reply.body) } catch (e: JSONException) { null }
        if (reply.status !in 200..299 || json?.optBoolean("ok", true) == false) {
            throw UpdateException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "The hub answered with an error (${reply.status}).")
        }
        return json ?: throw UpdateException("The hub sent something unexpected.")
    }

    /** The installed software's version and whether its startup check is still running. */
    fun status(): Status {
        val json = call("GET", "updates/status", null, null)
        return Status(json.optString("version"), json.optBoolean("pending"), json.optString("result"))
    }

    /** The request token for writes, or an error if this phone is not the hub's owner. */
    private fun ownerToken(): String {
        val session = call("GET", "session", null, null)
        return when (session.optString("role")) {
            "owner" -> session.optString("csrf")
            "pending" -> throw UpdateException("Waiting for the hub's owner to approve this phone.")
            "control" -> throw UpdateException("Only the hub's owner can install software updates.")
            else -> throw UpdateException("This phone isn't paired with the hub yet.")
        }
    }

    fun install(bundleText: String, say: (String) -> Unit): Outcome {
        val bundle = parse(bundleText)
        val csrf = ownerToken()
        call("POST", "updates/begin", JSONObject().put("manifest", bundle.manifest).put("signature", bundle.signature), csrf)
        for ((name, raw) in bundle.files) {
            var offset = 0
            while (offset < raw.size) {
                val end = minOf(raw.size, offset + CHUNK_BYTES)
                call("POST", "updates/chunk", JSONObject().put("name", name).put("offset", offset).put("hex", hex(raw, offset, end)), csrf)
                say("Sending $name… ${end * 100 / raw.size}%")
                offset = end
            }
        }
        call("POST", "updates/apply", JSONObject(), csrf)
        return waitForHub(bundle.version, say)
    }

    private fun answers(): Boolean = try { call("GET", "session", null, null, 4000); true } catch (e: UpdateException) { false }

    private fun waitForHub(version: String, say: (String) -> Unit): Outcome {
        say("The hub is restarting…")
        var end = clock.now() + 60_000
        while (clock.now() < end && answers()) clock.sleep(2000)
        end = clock.now() + 300_000
        var back = answers()
        while (!back && clock.now() < end) { clock.sleep(2000); back = answers() }
        if (!back) throw UpdateException("The hub has not come back after five minutes. If the new software fails to start it restores the old one by itself; check the hub.")
        say("The hub is back. Waiting for its startup check…")
        end = clock.now() + 180_000
        var status: Status? = null
        while (true) {
            status = try { status() } catch (e: UpdateException) { null }
            if ((status != null && !status.pending) || clock.now() >= end) break
            clock.sleep(2000)
        }
        if (status == null || status.pending) throw UpdateException("The hub is running but its startup check has not finished. Open Setup later to see the result.")
        return when {
            status.result.startsWith("Rolled back") -> Outcome.RolledBack(status.version, status.result)
            status.version == version -> Outcome.Updated(status.version)
            else -> Outcome.Unclear(status.version, status.result)
        }
    }

    companion object {
        /** Sent as hex, so twice as long; the hub takes up to 32768 hex characters a chunk. */
        const val CHUNK_BYTES = 8192

        private fun hex(raw: ByteArray, from: Int, to: Int): String {
            val digits = "0123456789abcdef"
            val out = StringBuilder((to - from) * 2)
            for (i in from until to) { val b = raw[i].toInt() and 0xff; out.append(digits[b shr 4]).append(digits[b and 15]) }
            return out.toString()
        }

        private fun parse(text: String): Bundle {
            try {
                val release = JSONObject(text)
                val manifestText = release.getString("manifest")
                val files = release.getJSONObject("files")
                val manifest = JSONObject(manifestText)
                val version = manifest.getString("version")
                val entries = manifest.getJSONArray("files")
                if (version.isEmpty() || entries.length() == 0) throw UpdateException("That release has no version or file list.")
                val blobs = (0 until entries.length()).map { i ->
                    val entry = entries.getJSONObject(i)
                    val name = entry.getString("name")
                    val raw = try {
                        java.util.Base64.getDecoder().decode(files.getString(name))
                    } catch (e: IllegalArgumentException) {
                        throw UpdateException("$name in that release is damaged.")
                    } catch (e: JSONException) {
                        throw UpdateException("The release is missing files it lists: $name.")
                    }
                    if (entry.has("size") && raw.size != entry.getInt("size")) throw UpdateException("$name in that release is not the size it should be.")
                    name to raw
                }
                return Bundle(manifestText, release.getString("signature"), version, blobs)
            } catch (e: JSONException) {
                throw UpdateException("That file is not a hub release bundle.")
            }
        }
    }
}
