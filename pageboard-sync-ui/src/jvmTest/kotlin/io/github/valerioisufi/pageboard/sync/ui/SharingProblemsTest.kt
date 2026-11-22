package io.github.valerioisufi.pageboard.sync.ui

import io.github.valerioisufi.pageboard.sync.lan.DiscoveryProblem
import io.github.valerioisufi.pageboard.sync.lan.LanDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharingProblemsTest {

    private fun device(id: String, shared: Boolean = true, connected: Boolean = false, failures: Int = 0) =
        LanDevice(id, id, "192.168.0.248:28461", shared, otherVersion = false, connected = connected, failures = failures, failure = if (failures > 0) "timeout" else null)

    @Test
    fun whatStopsEverythingComesFirstAndOneFailureOfTheDiscoveryIsEnough() {
        val problems = sharingProblems(
            listOf(DiscoveryProblem.BrowseFailed(-72007, "NSNetServicesTimeoutError"), DiscoveryProblem.AdvertiseFailed(1), DiscoveryProblem.PermissionDenied),
            devices = emptyList(), connected = false, lookedLong = true, internetOff = true
        )
        // Blocked: "nobody found" would say nothing more
        assertEquals(listOf(SharingProblem.PermissionDenied, SharingProblem.DiscoveryFailed("NSNetServicesTimeoutError, -72007")), problems)
    }

    @Test
    fun aDeviceSharingThatDoesNotAnswerIsToldOnceItFailedTwice() {
        val once = device("mac", failures = 1)
        assertTrue(sharingProblems(emptyList(), listOf(once), connected = false, lookedLong = false, internetOff = false).isEmpty())
        val silent = device("mac", failures = 2)
        val other = device("tv", shared = false, failures = 5)
        assertEquals(
            listOf(SharingProblem.NotAnswering(listOf(silent))),
            sharingProblems(emptyList(), listOf(silent, other), connected = false, lookedLong = true, internetOff = false)
        )
        // Connected after all: nothing to say
        assertTrue(sharingProblems(emptyList(), listOf(silent.copy(connected = true)), connected = true, lookedLong = true, internetOff = false).isEmpty())
    }

    @Test
    fun nobodyFoundIsSaidAfterAWhileOnly() {
        assertTrue(sharingProblems(emptyList(), emptyList(), connected = false, lookedLong = false, internetOff = true).isEmpty())
        assertEquals(listOf(SharingProblem.NobodyFound(internetOff = true)), sharingProblems(emptyList(), emptyList(), connected = false, lookedLong = true, internetOff = true))
        // Someone connected over the internet: nothing to say
        assertTrue(sharingProblems(emptyList(), emptyList(), connected = true, lookedLong = true, internetOff = false).isEmpty())
        // A device sharing found: it is said not to answer instead, when it does not
        assertTrue(sharingProblems(emptyList(), listOf(device("mac")), connected = false, lookedLong = true, internetOff = false).isEmpty())
    }
}
