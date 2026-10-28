package com.volleyballlab.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoachPresenceTest {
    private val now = 1_800_000_000_000L
    private fun lease(status: CoachAvailability, age: Long = 10_000L, expires: Long = now + 90_000) =
        CoachPresenceLease(status, now - age, expires)

    @Test fun busyWinsAcrossLiveDevices() {
        assertEquals(CoachAvailability.Busy, CoachPresencePolicy.resolve(listOf(lease(CoachAvailability.Online), lease(CoachAvailability.Busy)), now))
    }
    @Test fun closingOneDeviceDoesNotHideAnActiveDevice() {
        assertEquals(CoachAvailability.Online, CoachPresencePolicy.resolve(listOf(lease(CoachAvailability.Offline), lease(CoachAvailability.Online)), now))
    }
    @Test fun abruptDisconnectExpiresWithoutAnotherSnapshot() {
        val records = listOf(lease(CoachAvailability.Busy, expires = now + 1))
        assertEquals(CoachAvailability.Busy, CoachPresencePolicy.resolve(records, now))
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.resolve(records, now + 1))
    }
    @Test fun staleAndFarFutureTimestampsCannotKeepSomeoneOnline() {
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.resolve(listOf(lease(CoachAvailability.Online, age = 120_000), lease(CoachAvailability.Busy, age = -60_000)), now))
    }
    @Test fun anUnacknowledgedOrMissingLeaseIsOffline() {
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.resolve(emptyList(), now))
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.resolve(listOf(CoachPresenceLease(CoachAvailability.Online, 0, now + 10_000)), now))
    }
    @Test fun beingBusyDoesNotDefeatBackgroundDisconnectOrInactivity() {
        assertEquals(CoachAvailability.Busy, CoachPresencePolicy.localStatus(true, true, true, 0))
        assertEquals(CoachAvailability.Online, CoachPresencePolicy.localStatus(true, true, false, 0))
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.localStatus(false, true, true, 0))
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.localStatus(true, false, true, 0))
        assertEquals(CoachAvailability.Offline, CoachPresencePolicy.localStatus(true, true, true, CoachPresencePolicy.IDLE_MS))
    }
}
