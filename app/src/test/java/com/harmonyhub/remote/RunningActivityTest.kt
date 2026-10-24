package com.harmonyhub.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunningActivityTest {
    private fun button(key: String, label: String) =
        """{"key":"$key","label":"$label","deviceId":"1","command":"Cmd$key","transport":"ir"}"""

    @Test fun parsesTheThreeButtonsInOrder() {
        val running = RunningActivity.parse(
            """{"id":"watch","name":"Watch TV","buttons":[${button("volumedown", "Volume down")},${button("play", "Play")},${button("volumeup", "Volume up")}]}"""
        )!!
        assertEquals("Watch TV", running.name)
        assertEquals(listOf("volumedown", "play", "volumeup"), running.buttons.map { it.key })
        assertEquals(LockButton("play", "Play", "1", "Cmdplay", "ir"), running.buttons[1])
    }

    @Test fun nothingRunningIsNull() {
        assertNull(RunningActivity.parse(""))
        assertNull(RunningActivity.parse(null))
        assertNull(RunningActivity.parse("   "))
    }

    @Test fun garbageIsNull() {
        assertNull(RunningActivity.parse("not json"))
        assertNull(RunningActivity.parse("[]"))
        assertNull(RunningActivity.parse("""{"id":"a","name":"A"}"""))
        assertNull(RunningActivity.parse("""{"name":"A","buttons":[${button("play", "Play")}]}"""))
    }

    @Test fun incompleteButtonsAreDroppedAndNoButtonsIsNull() {
        val partial = RunningActivity.parse(
            """{"id":"a","name":"A","buttons":[{"label":"Play"},${button("play", "Play")},{"label":"X","deviceId":"1","command":"","transport":"ir"}]}"""
        )!!
        assertEquals(1, partial.buttons.size)
        assertNull(RunningActivity.parse("""{"id":"a","name":"A","buttons":[{"label":"Play"}]}"""))
        assertNull(RunningActivity.parse("""{"id":"a","name":"A","buttons":[]}"""))
    }

    @Test fun aJsonNullIsMissingNotTheTextNull() {
        // Android reads a JSON null back as the text "null"; it must not become a device id.
        val nulls = """{"id":"a","name":"A","buttons":[{"label":"Play","deviceId":null,"command":"Play","transport":"ir"},{"label":"Play","deviceId":"1","command":null,"transport":"ir"}]}"""
        assertNull(RunningActivity.parse(nulls))
        assertNull(RunningActivity.parse("""{"id":null,"name":"A","buttons":[${button("play", "Play")}]}"""))
        assertEquals("a", RunningActivity.parse("""{"id":"a","name":null,"buttons":[${button("play", "Play")}]}""")!!.name)
    }

    @Test fun tooLongValuesAreRejectedNotShortened() {
        val long = "x".repeat(200)
        val tooLongCommand = """{"label":"Play","deviceId":"1","command":"$long","transport":"ir"}"""
        assertNull(RunningActivity.parse("""{"id":"a","name":"A","buttons":[$tooLongCommand]}"""))
        val mixed = RunningActivity.parse("""{"id":"a","name":"A","buttons":[$tooLongCommand,${button("play", "Play")}]}""")!!
        assertEquals(listOf("play"), mixed.buttons.map { it.key })
        assertNull(RunningActivity.parse("""{"id":"$long","name":"A","buttons":[${button("play", "Play")}]}"""))
    }

    @Test fun atMostThreeButtons() {
        val many = (1..6).joinToString(",") { button("k$it", "B$it") }
        assertEquals(3, RunningActivity.parse("""{"id":"a","name":"A","buttons":[$many]}""")!!.buttons.size)
    }

    @Test fun nameIsCleanedCappedAndFallsBackToTheId() {
        assertEquals("Den", RunningActivity.parse("""{"id":"a","name":"D\ten\n","buttons":[${button("play", "Play")}]}""")!!.name)
        assertEquals(RunningActivity.MAX_NAME, RunningActivity.parse("""{"id":"a","name":"${"x".repeat(200)}","buttons":[${button("play", "Play")}]}""")!!.name.length)
        assertEquals("a", RunningActivity.parse("""{"id":"a","name":"  ","buttons":[${button("play", "Play")}]}""")!!.name)
    }

    @Test fun roundTripsThroughJson() {
        val original = RunningActivity("a", "A", listOf(LockButton("play", "Play", "rk", "Play", "roku")))
        assertEquals(original, RunningActivity.parse(original.toJson()))
    }
}
