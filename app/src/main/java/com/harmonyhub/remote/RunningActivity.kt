package com.harmonyhub.remote

import org.json.JSONObject

/** One lock-screen button: the saved command it presses and how to reach its device. */
data class LockButton(val key: String, val label: String, val deviceId: String, val command: String, val transport: String)

/**
 * The activity that is on, as the hub's page announces it, with the buttons to keep
 * on the lock screen. Kept free of Android classes so it can be unit-tested on the JVM.
 */
data class RunningActivity(val id: String, val name: String, val buttons: List<LockButton>) {
    fun toJson(): String = JSONObject().put("id", id).put("name", name).put("buttons", org.json.JSONArray(buttons.map {
        JSONObject().put("key", it.key).put("label", it.label).put("deviceId", it.deviceId).put("command", it.command).put("transport", it.transport)
    })).toString()

    companion object {
        const val MAX_NAME = 60
        private const val MAX_BUTTONS = 3

        /** The page sends an empty string when nothing is running. Anything unusable is "nothing". */
        fun parse(json: String?): RunningActivity? {
            if (json.isNullOrBlank()) return null
            return try {
                val root = JSONObject(json)
                val id = clean(root.optString("id"), 96)
                val name = clean(root.optString("name"), MAX_NAME)
                if (id.isEmpty()) return null
                val array = root.optJSONArray("buttons") ?: return null
                val buttons = (0 until minOf(array.length(), MAX_BUTTONS)).mapNotNull { i ->
                    val b = array.optJSONObject(i) ?: return@mapNotNull null
                    val button = LockButton(
                        key = clean(b.optString("key"), 24), label = clean(b.optString("label"), 40),
                        deviceId = clean(b.optString("deviceId"), 64), command = clean(b.optString("command"), 127),
                        transport = clean(b.optString("transport"), 16),
                    )
                    button.takeIf { it.label.isNotEmpty() && it.deviceId.isNotEmpty() && it.command.isNotEmpty() && it.transport.isNotEmpty() }
                }
                if (buttons.isEmpty()) null else RunningActivity(id, name.ifEmpty { id }, buttons)
            } catch (e: org.json.JSONException) {
                null
            }
        }

        private fun clean(text: String, max: Int) = text.filter { !it.isISOControl() }.trim().take(max)
    }
}
