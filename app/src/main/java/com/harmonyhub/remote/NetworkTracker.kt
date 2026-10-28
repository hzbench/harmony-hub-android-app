package com.harmonyhub.remote

/**
 * Follows the phone's default network and says when "is there a network" changes, so the
 * WebView can be told. Kept free of Android classes so it can be unit-tested on the JVM.
 *
 * Android can announce the new default network before it reports the old one lost; a loss
 * of a network that is no longer the default must not read as "offline".
 */
class NetworkTracker {
    private var current: Any? = null

    /** A network became the default. Returns true when that turned the phone from offline to online. */
    fun available(network: Any): Boolean {
        val wasOffline = current == null
        current = network
        return wasOffline
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
