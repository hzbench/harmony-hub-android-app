package com.harmonyhub.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrackerTest {
    @Test fun startsOffline() {
        assertFalse(NetworkTracker().isOnline)
    }

    @Test fun firstNetworkTurnsItOnline() {
        val tracker = NetworkTracker()
        assertTrue(tracker.available("wifi"))
        assertTrue(tracker.isOnline)
    }

    @Test fun losingTheOnlyNetworkTurnsItOffline() {
        val tracker = NetworkTracker().apply { available("wifi") }
        assertTrue(tracker.lost("wifi"))
        assertFalse(tracker.isOnline)
    }

    @Test fun comingBackAfterALossIsReportedAgain() {
        val tracker = NetworkTracker().apply { available("wifi"); lost("wifi") }
        assertTrue(tracker.available("wifi"))
    }

    @Test fun aSecondAnnouncementWhileOnlineIsNotAChange() {
        val tracker = NetworkTracker().apply { available("wifi") }
        assertFalse(tracker.available("wifi"))
    }

    @Test fun switchingNetworksIsAChangeAndStaysOnlineWhenTheOldOneIsReportedLostLate() {
        val tracker = NetworkTracker().apply { available("wifi") }
        assertTrue(tracker.available("cell"))
        assertFalse(tracker.lost("wifi"))
        assertTrue(tracker.isOnline)
    }

    @Test fun wifiOffFallsBackToMobileDataAndWifiBackIsStillAChange() {
        val tracker = NetworkTracker().apply { available("wifi") }
        assertTrue(tracker.available("cell"))
        assertFalse(tracker.lost("wifi"))
        assertTrue(tracker.available("wifi"))
        assertFalse(tracker.lost("cell"))
        assertTrue(tracker.isOnline)
    }

    @Test fun losingANetworkThatWasNeverTheDefaultChangesNothing() {
        val tracker = NetworkTracker()
        assertFalse(tracker.lost("wifi"))
        assertFalse(tracker.isOnline)
    }

    @Test fun afterAResetTheNextNetworkIsReportedAgain() {
        val tracker = NetworkTracker().apply { available("wifi"); reset() }
        assertFalse(tracker.isOnline)
        assertTrue(tracker.available("wifi"))
    }
}
