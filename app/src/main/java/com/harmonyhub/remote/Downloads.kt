package com.harmonyhub.remote

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches release files from GitHub. The hash published next to a file only proves the download is
 * whole; what makes an update trustworthy is the signature the hub (or Android) checks afterwards.
 * Download links are never logged: they are signed and short-lived.
 */
class Downloads(private val timeoutMs: Int = 20_000) {
    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "HubRemote")
        return connection
    }

    /** A small text reply, such as the release list or a .sha256 file. */
    fun text(url: String, maxBytes: Int = 2_000_000): String {
        val connection = open(url)
        try {
            val status = connection.responseCode
            if (status == 403 || status == 429) throw UpdateException("GitHub is limiting requests. Try again in a while.")
            if (status != 200) throw UpdateException("GitHub answered with an error ($status).")
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw UpdateException("GitHub sent more than expected.")
                }
            }
            return out.toString("UTF-8")
        } catch (e: IOException) {
            throw UpdateException("Couldn't reach GitHub.")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Saves [url] to [dest], checking its size and SHA-256. The file is written beside [dest] and renamed
     * only when it is whole, so a half download is never mistaken for a finished one.
     */
    fun file(url: String, dest: File, size: Long, sha256: String, progress: (Long) -> Unit = {}) {
        val part = File(dest.path + ".part")
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw UpdateException("The download failed (${connection.responseCode}).")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (size > 0 && total > size) throw UpdateException("The download is larger than it should be.")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        progress(total)
                    }
                }
            }
            if (size > 0 && total != size) throw UpdateException("The download is incomplete.")
            if (!hex(digest.digest()).equals(sha256, ignoreCase = true)) throw UpdateException("The download does not match its checksum, so it was thrown away.")
            if (!part.renameTo(dest)) throw UpdateException("Couldn't save the download.")
        } catch (e: IOException) {
            throw UpdateException("The download was interrupted.")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The hash from a .sha256 file ("<64 hex>  name"), or null if it does not hold one. */
        fun parseChecksum(text: String): String? =
            text.trim().substringBefore(' ').substringBefore('\t').lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) }

        /** True when [file] exists and hashes to [sha256]: a file already downloaded can be reused. */
        fun matches(file: File, sha256: String): Boolean {
            if (!file.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return hex(digest.digest()).equals(sha256, ignoreCase = true)
        }
    }
}
