package com.harmonyhub.remote

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Presses a saved command on the hub without the web page: the same two calls the page
 * makes (read the session's request token, then send the command), using the pairing
 * cookie the WebView already holds. Pure JVM so it can be tested against a local server.
 */
class HubClient(hubUrl: String, private val cookie: String?, private val budgetMs: Long = 8000) {
    sealed class Result {
        object Sent : Result()
        data class Failed(val message: String) : Result()
    }

    private val http = HubHttp(hubUrl, cookie)

    /** Both calls share one budget, so a button press never outlasts the ~10 s a broadcast is allowed. */
    fun press(button: LockButton): Result {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        return try {
            val session = request("GET", "api/v1/session", null, null, deadline)
            if (session.status != 200) return failed(session.status)
            val info = JSONObject(session.body)
            val role = info.optString("role")
            if (role == "pending") return Result.Failed("Waiting for the hub's owner to approve this phone.")
            if (role != "owner" && role != "control") return Result.Failed("This phone isn't paired with the hub yet.")
            val body = JSONObject().put("deviceId", button.deviceId).put("command", button.command)
                .put("transport", button.transport).put("mode", "tap").toString()
            val sent = request("POST", "api/v1/commands/send", body, info.optString("csrf"), deadline)
            if (sent.status in 200..299) Result.Sent else failed(sent.status)
        } catch (e: java.io.IOException) {
            Result.Failed("Couldn't reach the hub.")
        } catch (e: org.json.JSONException) {
            Result.Failed("The hub sent something unexpected.")
        }
    }

    private fun failed(status: Int) = Result.Failed(when (status) {
        401 -> "This phone isn't paired with the hub yet."
        403 -> "The hub refused the request."
        409, 429 -> "The hub is busy. Try again in a moment."
        else -> "The hub answered with an error ($status)."
    })

    private fun request(method: String, path: String, body: String?, csrf: String?, deadline: Long): HubHttp.Reply {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) throw java.net.SocketTimeoutException("out of time")
        return http.request(method, path, body, csrf, left)
    }
}

/** One request to the hub with the pairing cookie: JSON in, status and body out. Pure JVM. */
class HubHttp(hubUrl: String, private val cookie: String?) {
    class Reply(val status: Int, val body: String)

    private val base = if (hubUrl.endsWith("/")) hubUrl else "$hubUrl/"

    fun request(method: String, path: String, body: String?, csrf: String?, timeoutMs: Long): Reply {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = minOf(3000L, timeoutMs).toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.instanceFollowRedirects = false
            if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Harmony-CSRF", csrf ?: "")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return Reply(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
