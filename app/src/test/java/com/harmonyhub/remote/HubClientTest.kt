package com.harmonyhub.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A one-thread HTTP server on a plain socket (the JDK's own is not on the unit-test classpath). */
private class TinyServer(private val respond: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>) {
    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = socket.localPort
    private val thread = Thread {
        try {
            while (!socket.isClosed) {
                val client = socket.accept()
                client.use {
                    val input = it.getInputStream().bufferedReader()
                    val line = input.readLine() ?: return@use
                    val (method, path) = line.split(" ").let { p -> p[0] to p[1] }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = input.readLine()
                        if (header.isNullOrEmpty()) break
                        headers[header.substringBefore(":").lowercase()] = header.substringAfter(":").trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = CharArray(length).also { buf -> input.read(buf, 0, length) }.concatToString()
                    val (status, reply) = respond(method, path, headers, body)
                    val bytes = reply.toByteArray()
                    it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        } catch (e: java.io.IOException) {
            // closed
        }
    }.apply { isDaemon = true; start() }
    fun stop() = socket.close()
}

class HubClientTest {
    private lateinit var server: TinyServer
    private val seen = mutableListOf<Triple<String, String, Map<String, String>>>()   // method, path, headers
    private val bodies = mutableListOf<String>()
    private var sessionStatus = 200
    private var sessionBody = """{"ok":true,"role":"owner","csrf":"token-123"}"""
    private var sendStatus = 202
    private val button = LockButton("volumeup", "Volume up", "1", "VolumeUp", "ir")

    @Before fun start() {
        server = TinyServer { method, path, headers, body ->
            seen.add(Triple(method, path, headers))
            bodies.add(body)
            if (path == "/api/v1/session") sessionStatus to sessionBody else sendStatus to """{"id":"op1"}"""
        }
    }

    @After fun stop() = server.stop()

    private fun url() = "http://127.0.0.1:${server.port}/"

    @Test fun readsTheTokenThenSendsTheCommandWithTheCookie() {
        val result = HubClient(url(), "harmony=secret").press(button)
        assertEquals(HubClient.Result.Sent, result)
        assertEquals(listOf("GET" to "/api/v1/session", "POST" to "/api/v1/commands/send"), seen.map { it.first to it.second })
        assertTrue(seen.all { it.third["cookie"] == "harmony=secret" })
        assertEquals("token-123", seen[1].third["x-harmony-csrf"])
        assertEquals("application/json", seen[1].third["content-type"])
        val sent = org.json.JSONObject(bodies[1])
        assertEquals(listOf("1", "VolumeUp", "ir", "tap"), listOf("deviceId", "command", "transport", "mode").map { sent.getString(it) })
    }

    @Test fun worksWithoutACookieHeaderWhenThereIsNone() {
        assertEquals(HubClient.Result.Sent, HubClient(url(), null).press(button))
        assertTrue(seen.none { it.third.containsKey("cookie") })
    }

    @Test fun anUnpairedPhoneIsToldSoAndSendsNothing() {
        sessionStatus = 401
        val result = HubClient(url(), "harmony=old").press(button) as HubClient.Result.Failed
        assertTrue(result.message.contains("paired"))
        assertEquals(1, seen.size)
        sessionStatus = 200; sessionBody = """{"role":"pending","csrf":"x"}"""
        seen.clear()
        assertTrue((HubClient(url(), "harmony=x").press(button) as HubClient.Result.Failed).message.contains("paired"))
        assertEquals(1, seen.size)
    }

    @Test fun aRefusedCommandIsAFailure() {
        sendStatus = 403
        assertEquals("The hub refused the request.", (HubClient(url(), "harmony=a").press(button) as HubClient.Result.Failed).message)
        sendStatus = 500
        assertTrue((HubClient(url(), "harmony=a").press(button) as HubClient.Result.Failed).message.contains("500"))
    }

    @Test fun anUnreachableHubIsAFailureNotACrash() {
        val dead = url()
        server.stop()
        assertEquals("Couldn't reach the hub.", (HubClient(dead, "harmony=a").press(button) as HubClient.Result.Failed).message)
    }

    @Test fun anUnexpectedReplyIsAFailure() {
        sessionBody = "<html>nope</html>"
        assertEquals("The hub sent something unexpected.", (HubClient(url(), null).press(button) as HubClient.Result.Failed).message)
    }

    @Test fun addsTheTrailingSlashItNeeds() {
        assertEquals(HubClient.Result.Sent, HubClient(url().removeSuffix("/"), null).press(button))
    }
}
