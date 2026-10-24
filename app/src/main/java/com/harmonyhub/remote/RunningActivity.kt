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
                val id = strict(root, "id", 96)
                val name = clean(field(root, "name"), MAX_NAME)
                if (id.isEmpty()) return null
                val array = root.optJSONArray("buttons") ?: return null
                val buttons = (0 until minOf(array.length(), MAX_BUTTONS)).mapNotNull { i ->
                    val b = array.optJSONObject(i) ?: return@mapNotNull null
                    // A value that is too long is rejected, not cut short: a shortened command
                    // or device id could match a different saved one.
                    val button = LockButton(
                        key = strict(b, "key", 24), label = strict(b, "label", 40),
                        deviceId = strict(b, "deviceId", 64), command = strict(b, "command", 127),
                        transport = strict(b, "transport", 16),
                    )
                    button.takeIf { it.label.isNotEmpty() && it.deviceId.isNotEmpty() && it.command.isNotEmpty() && it.transport.isNotEmpty() }
                }
                if (buttons.isEmpty()) null else RunningActivity(id, name.ifEmpty { id }, buttons)
            } catch (e: org.json.JSONException) {
                null
            }
        }

        // On Android a JSON null reads back as the text "null"; treat it as missing.
        private fun field(o: JSONObject, key: String) = if (o.isNull(key)) "" else o.optString(key)
        private fun clean(text: String, max: Int) = text.filter { !it.isISOControl() }.trim().take(max)
        private fun strict(o: JSONObject, key: String, max: Int): String {
            val cleaned = field(o, key).filter { !it.isISOControl() }.trim()
            return if (cleaned.length > max) "" else cleaned
        }
    }
}
