package com.harmonyhub.remote

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VersionTest {
    @Test fun parsesTagsAndPlainVersions() {
        assertEquals(Version(0, 4, 0), Version.parse("v0.4.0"))
        assertEquals(Version(1, 2, 3, "rc1"), Version.parse("1.2.3-rc1"))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse(null))
    }

    @Test fun numbersCompareAsNumbersNotText() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.9")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("0.99.99")!!)
    }

    @Test fun aPreReleaseComesBeforeItsRelease() {
        assertTrue(Version.parse("1.0.0-rc1")!! < Version.parse("1.0.0")!!)
        assertTrue(Version.parse("1.0.0-rc2")!! > Version.parse("1.0.0-rc1")!!)
        assertTrue(Version.parse("1.0.1-rc1")!! > Version.parse("1.0.0")!!)
    }
}

class ReleasesTest {
    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, url: String = "https://github.com/x/y/releases/download/$tag/f.apk") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes $tag","assets":[{"name":"f.apk","browser_download_url":"$url","size":5}]}"""

    private fun list(vararg items: String) = Releases.parse("[" + items.joinToString(",") + "]")

    @Test fun leavesOutDraftsBadTagsAndNonHttpsFiles() {
        val parsed = list(release("v0.5.0", draft = true), release("nightly"), release("v0.4.0", url = "http://x/f.apk"), release("v0.3.0"))
        assertEquals(listOf(Version(0, 4, 0), Version(0, 3, 0)), parsed.map { it.version })
        assertTrue("the plain-http file was dropped", parsed[0].assets.isEmpty())
        assertEquals("f.apk", parsed[1].asset("f.apk")?.name)
    }

    @Test fun offersTheNewestAboveWhatIsInstalled() {
        val all = list(release("v0.5.0"), release("v0.6.0"), release("v0.4.0"))
        assertEquals(Version(0, 6, 0), Releases.newer(all, Version(0, 4, 0), false, null)?.version)
        assertNull(Releases.newer(all, Version(0, 6, 0), false, null))
        assertEquals("with no known installed version the newest is offered", Version(0, 6, 0), Releases.newer(all, null, false, null)?.version)
    }

    @Test fun preReleasesOnlyWhenAskedFor() {
        val all = list(release("v0.6.0", pre = true), release("v0.5.0"), release("v0.7.0-rc1"))
        assertEquals(Version(0, 5, 0), Releases.newer(all, Version(0, 4, 0), false, null)?.version)
        assertEquals(Version(0, 7, 0, "rc1"), Releases.newer(all, Version(0, 4, 0), true, null)?.version)
    }

    @Test fun aSkippedVersionIsNotOfferedButANewerOneIs() {
        val all = list(release("v0.6.0"), release("v0.5.0"))
        assertEquals(Version(0, 5, 0), Releases.newer(all, Version(0, 4, 0), false, Version(0, 6, 0))?.version)
        assertNull(Releases.newer(list(release("v0.5.0")), Version(0, 4, 0), false, Version(0, 5, 0)))
    }

    @Test fun garbageIsAnErrorNotACrash() {
        try { Releases.parse("<html>"); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("unexpected")) }
    }
}

class CheckScheduleTest {
    @Test fun onlyChecksOnOpenInThatModeAndNotMoreThanHourly() {
        val hour = CheckSchedule.MIN_GAP_MS
        assertTrue(CheckSchedule.dueOnOpen(CheckMode.ON_OPEN, 0, 5 * hour))
        assertFalse(CheckSchedule.dueOnOpen(CheckMode.ON_OPEN, 5 * hour, 5 * hour + 1000))
        assertTrue(CheckSchedule.dueOnOpen(CheckMode.ON_OPEN, 5 * hour, 6 * hour))
        assertTrue("a clock that went backwards checks again", CheckSchedule.dueOnOpen(CheckMode.ON_OPEN, 9 * hour, 5 * hour))
        assertFalse(CheckSchedule.dueOnOpen(CheckMode.MANUAL, 0, 5 * hour))
        assertFalse(CheckSchedule.dueOnOpen(CheckMode.DAILY, 0, 5 * hour))
    }
}

class DownloadsTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "hubremote-test-${System.nanoTime()}").apply { mkdirs() }
    private var server: TinyServer? = null
    @After fun cleanUp() { server?.stop(); dir.deleteRecursively() }

    private fun sha(text: String) = Downloads.hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun serve(body: String): String {
        server = TinyServer { _, _, _, _ -> 200 to body }
        return "http://127.0.0.1:${server!!.port}/file"
    }

    @Test fun readsTheHashFromAChecksumFile() {
        val hash = sha("x")
        assertEquals(hash, Downloads.parseChecksum("$hash  hub-remote-0.5.0.apk\n"))
        assertEquals(hash, Downloads.parseChecksum(hash.uppercase()))
        assertNull(Downloads.parseChecksum("not a hash"))
    }

    @Test fun savesAFileThatMatchesItsHash() {
        val url = serve("hello update")
        val dest = File(dir, "a.apk")
        Downloads().file(url, dest, 12, sha("hello update"))
        assertEquals("hello update", dest.readText())
        assertTrue(Downloads.matches(dest, sha("hello update")))
        assertFalse(File(dir, "a.apk.part").exists())
    }

    @Test fun aWrongHashLeavesNothingBehind() {
        val url = serve("hello update")
        val dest = File(dir, "a.apk")
        try { Downloads().file(url, dest, 12, sha("something else")); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("checksum")) }
        assertFalse(dest.exists())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun aWrongSizeIsRefused() {
        val url = serve("hello update")
        try { Downloads().file(url, File(dir, "a"), 5, sha("hello update")); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("larger")) }
        try { Downloads().file(url, File(dir, "b"), 50, sha("hello update")); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("incomplete")) }
    }

    @Test fun anErrorStatusOrAnUnreachableServerIsAnUpdateError() {
        server = TinyServer { _, _, _, _ -> 404 to "no" }
        try { Downloads().file("http://127.0.0.1:${server!!.port}/x", File(dir, "a"), 0, sha("")); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("404")) }
        val dead = "http://127.0.0.1:${server!!.port}/x"
        server!!.stop()
        try { Downloads().text(dead); fail() } catch (e: UpdateException) { assertEquals("Couldn't reach GitHub.", e.message) }
    }

    @Test fun rateLimitingIsNamedPlainly() {
        server = TinyServer { _, _, _, _ -> 403 to "{}" }
        try { Downloads().text("http://127.0.0.1:${server!!.port}/x"); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("limiting")) }
    }
}

class HubUpdaterTest {
    /** Time that only moves when the updater sleeps, so the hub's restart costs nothing. */
    private class FakeClock : Clock {
        var t = 0L
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private val clock = FakeClock()
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<JSONObject>()
    private var role = "owner"
    private var downFrom = Long.MAX_VALUE     // the hub stops answering at this time...
    private var downUntil = 0L                // ...and answers again at this one
    private var statusVersion = "0.5.0"
    private var statusResult = "Release passed startup health checks"
    private var pendingPolls = 0
    private var server: TinyServer? = null
    @After fun stop() { server?.stop() }

    private fun file(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)
    private val big = ByteArray(20_000) { (it % 251).toByte() }

    private fun bundle(version: String = "0.5.0", sizeOf: Int = big.size) = JSONObject()
        .put("manifest", JSONObject().put("version", version).put("files", org.json.JSONArray().put(JSONObject().put("name", "migrations.json").put("size", sizeOf))).toString())
        .put("signature", "ab".repeat(32))
        .put("files", JSONObject().put("migrations.json", file(big))).toString()

    private fun updater(): HubUpdater {
        server = TinyServer { method, path, headers, body ->
            if (clock.t in downFrom until downUntil) return@TinyServer 503 to "down"
            calls.add("$method $path")
            if (body.isNotEmpty()) bodies.add(JSONObject(body))
            when (path) {
                "/api/v1/session" -> 200 to """{"ok":true,"role":"$role","csrf":"tok"}"""
                "/api/v1/updates/status" -> {
                    val pending = pendingPolls-- > 0
                    200 to """{"ok":true,"version":"$statusVersion","pending":$pending,"result":"$statusResult"}"""
                }
                "/api/v1/updates/apply" -> { downFrom = clock.t + 1; downUntil = clock.t + 20_000; 200 to """{"ok":true}""" }
                else -> if (headers["x-harmony-csrf"] == "tok") 200 to """{"ok":true}""" else 403 to """{"ok":false,"error":"bad token"}"""
            }
        }
        return HubUpdater("http://127.0.0.1:${server!!.port}/", "harmony=c", clock)
    }

    @Test fun sendsTheBundleInChunksThenWaitsForTheHubToComeBack() {
        val said = mutableListOf<String>()
        val outcome = updater().install(bundle()) { said.add(it) }
        assertEquals(HubUpdater.Outcome.Updated("0.5.0"), outcome)
        val chunks = bodies.filter { it.has("hex") }
        assertEquals(3, chunks.size)   // 20000 bytes in chunks of 8192
        assertEquals(listOf(0, 8192, 16384), chunks.map { it.getInt("offset") })
        assertEquals(8192 * 2, chunks[0].getString("hex").length)
        assertEquals("00010203", chunks[0].getString("hex").take(8))
        assertTrue(calls.indexOf("POST /api/v1/updates/begin") < calls.indexOf("POST /api/v1/updates/chunk"))
        assertTrue(calls.contains("POST /api/v1/updates/apply"))
        assertTrue(said.any { it.contains("restarting") } && said.any { it.contains("back") })
    }

    @Test fun waitsOutAStartupCheckThatIsStillRunning() {
        pendingPolls = 3
        assertEquals(HubUpdater.Outcome.Updated("0.5.0"), updater().install(bundle()) {})
    }

    @Test fun reportsARollback() {
        statusVersion = "0.4.0"; statusResult = "Rolled back after a failed health check"
        val outcome = updater().install(bundle()) {}
        assertEquals(HubUpdater.Outcome.RolledBack("0.4.0", "Rolled back after a failed health check"), outcome)
    }

    @Test fun reportsAVersionThatIsNotTheOneSent() {
        statusVersion = "0.4.0"
        assertTrue(updater().install(bundle()) {} is HubUpdater.Outcome.Unclear)
    }

    @Test fun onlyTheOwnerMayInstallAndNothingIsSentOtherwise() {
        role = "control"
        try { updater().install(bundle()) {}; fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("owner")) }
        assertTrue(calls.none { it.contains("updates/begin") })
        role = "pending"; calls.clear()
        try { updater().install(bundle()) {}; fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("approve")) }
    }

    @Test fun aDamagedBundleIsFoundBeforeTheHubIsTold() {
        for (text in listOf("not json", bundle(sizeOf = 5), """{"manifest":"{}","signature":"x","files":{}}""")) {
            try { updater().install(text) {}; fail("accepted $text") } catch (e: UpdateException) { /* expected */ }
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun aHubThatNeverComesBackIsAnError() {
        val u = updater()
        // apply takes the hub down for good
        downUntil = Long.MAX_VALUE
        server!!.stop()
        server = null
        try { u.install(bundle()) {}; fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("reach") || e.message!!.contains("come back")) }
    }

    @Test fun readsTheInstalledVersion() {
        val status = updater().status()
        assertEquals(HubUpdater.Status("0.5.0", false, "Release passed startup health checks"), status)
    }
}
