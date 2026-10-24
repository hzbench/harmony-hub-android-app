package com.harmonyhub.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HubAddressTest {
    @Test fun bareHostAndPortGetsSchemeAndSlash() =
        assertEquals("http://192.168.1.50:8080/", HubAddress.normalize("192.168.1.50:8080"))

    @Test fun fullUrlIsKept() =
        assertEquals("http://192.168.1.50:8080/", HubAddress.normalize("http://192.168.1.50:8080"))

    @Test fun httpsIsKept() =
        assertEquals("https://hub.local/", HubAddress.normalize("https://hub.local"))

    @Test fun surroundingSpacesAreIgnored() =
        assertEquals("http://hub.local:8080/", HubAddress.normalize("  hub.local:8080  "))

    @Test fun trailingGoPathIsStrippedWhenSaving() {
        assertEquals("http://127.0.0.1:8097/", HubAddress.normalize("http://127.0.0.1:8097/go/"))
        assertEquals("http://127.0.0.1:8097/", HubAddress.normalize("127.0.0.1:8097/go"))
    }

    @Test fun hostnameEndingInGoIsLeftAlone() {
        assertEquals("http://hubgo/", HubAddress.normalize("hubgo"))
        assertEquals("http://hub.go/", HubAddress.normalize("http://hub.go/"))
    }

    @Test fun entryUrlAddsGo() {
        assertEquals("http://192.168.1.50:8080/go/", HubAddress.entryUrl("http://192.168.1.50:8080/"))
        assertEquals("http://192.168.1.50:8080/go/", HubAddress.entryUrl("http://192.168.1.50:8080"))
    }

    @Test fun entryUrlDoesNotDoubleGo() =
        assertEquals("http://192.168.1.50:8080/go/", HubAddress.entryUrl("http://192.168.1.50:8080/go/"))

    @Test fun entryUrlKeepsAnExistingPath() =
        assertEquals("http://host/hub/go/", HubAddress.entryUrl("http://host/hub/"))

    @Test fun labelDropsSchemeAndSlash() {
        assertEquals("192.168.1.50:8080", HubAddress.label("http://192.168.1.50:8080/"))
        assertEquals("", HubAddress.label(null))
    }
}
