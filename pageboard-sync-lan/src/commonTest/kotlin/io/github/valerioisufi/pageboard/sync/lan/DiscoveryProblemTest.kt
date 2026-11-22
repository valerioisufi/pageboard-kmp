package io.github.valerioisufi.pageboard.sync.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryProblemTest {

    @Test
    fun oneProblemOfEachKindClearedOnceItWorks() {
        val problems = DiscoveryProblems()
        problems.report(DiscoveryProblem.BrowseFailed(1))
        problems.report(DiscoveryProblem.BrowseFailed(2))
        problems.report(DiscoveryProblem.NoLocalNetwork)
        assertEquals(listOf(DiscoveryProblem.BrowseFailed(2), DiscoveryProblem.NoLocalNetwork), problems.problems.value)
        problems.clear(DiscoveryProblem.BrowseFailed::class, DiscoveryProblem.PermissionDenied::class)
        assertEquals(listOf<DiscoveryProblem>(DiscoveryProblem.NoLocalNetwork), problems.problems.value)
        problems.clearAll()
        assertTrue(problems.problems.value.isEmpty())
    }

    @Test
    fun theFailuresOfBonjourSayWhatToDo() {
        assertEquals(DiscoveryProblem.PermissionDenied, bonjourProblem(-65570, announcing = false))
        assertEquals(DiscoveryProblem.MissingConfiguration(listOf("NSBonjourServices: _pageboard._tcp")), bonjourProblem(-72008, announcing = true))
        assertEquals(DiscoveryProblem.AdvertiseFailed(-72001, "NSNetServicesCollisionError"), bonjourProblem(-72001, announcing = true))
        assertEquals(DiscoveryProblem.BrowseFailed(-1, null), bonjourProblem(-1, announcing = false))
        assertEquals(DiscoveryProblem.BrowseFailed(null, null), bonjourProblem(null, announcing = false))
    }
}
