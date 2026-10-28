package com.harmonyhub.remote

/**
 * Follows the phone's default network and says when it changes, so the WebView can be told
 * and the page can look for the hub again. Kept free of Android classes so it can be
 * unit-tested on the JVM.
 *
 * Any new default network counts, not only coming back from none: the hub is on the LAN, so
 * Wi-Fi off falls back to mobile data (still "online") and Wi-Fi back on must re-check it.
 * Android can also announce the new default before it reports the old one lost; a loss of a
 * network that is no longer the default must not read as "offline".
 */
class NetworkTracker {
    private var current: Any? = null

    /** A network was announced. Returns true when it is a different default network than before. */
    fun available(network: Any): Boolean {
        if (current == network) return false
        current = network
        return true
    }

    /** A network was lost. Returns true when the default network went away and none replaced it. */
    fun lost(network: Any): Boolean {
        if (current != network) return false
        current = null
        return true
    }

    /** Forget everything: the callback is unregistered while the screen is away, so the next announcement starts fresh. */
    fun reset() {
        current = null
    }

    val isOnline: Boolean get() = current != null
}
