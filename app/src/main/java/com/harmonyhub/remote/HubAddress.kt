package com.harmonyhub.remote

/**
 * How the hub's address is typed, stored and turned into the page the app
 * loads. Kept free of Android classes so it can be unit-tested on the JVM.
 *
 * The stored address is the hub itself (`http://192.168.1.50:8080/`); the app
 * adds the path of the daily-use UI when it loads.
 */
object HubAddress {
    /** The daily-use remote UI the hub serves under /go/ (the original UI stays at the root). */
    const val ENTRY_PATH = "go/"

    /** What is saved for whatever was typed: a scheme, a trailing slash, no /go/. */
    fun normalize(typed: String): String {
        var url = typed.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url = "$url/"
        // Only a real /go/ path segment: a host that merely ends in "go" is left alone.
        return if (url.endsWith("/$ENTRY_PATH")) url.removeSuffix(ENTRY_PATH) else url
    }

    /** The page to load for a saved address (which may or may not already end in /go/). */
    fun entryUrl(hubUrl: String): String =
        if (hubUrl.endsWith("/$ENTRY_PATH")) hubUrl else hubUrl.trimEnd('/') + "/$ENTRY_PATH"

    /** The address as shown to people: no scheme, no trailing slash. */
    fun label(hubUrl: String?): String =
        hubUrl?.removePrefix("http://")?.removeSuffix("/") ?: ""

    /** Longest hub name the app shows (the hub enforces the same limit). */
    const val NAME_MAX = 40

    /** What the toolbar shows: the hub's own name, or its address while it has none. */
    fun title(name: String?, hubUrl: String?): String =
        name?.filter { !it.isISOControl() }?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }
            ?: label(hubUrl)
}
