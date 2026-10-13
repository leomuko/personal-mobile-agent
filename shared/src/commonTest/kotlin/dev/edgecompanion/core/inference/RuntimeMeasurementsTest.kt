package dev.edgecompanion.core.inference

import kotlin.test.*

class RuntimeMeasurementsTest {
    @Test fun smokeCannotClaimAcceptancePercentile() {
        assertEquals(LatencySummary(1, null, null), summarizeLatency(listOf(1), 3000))
        assertEquals(LatencySummary(0, null, null), summarizeLatency(emptyList(), 3000))
    }
    @Test fun usesNearestRankAndPreservesFailingGate() {
        assertEquals(LatencySummary(20, 19, false), summarizeLatency((1L..20).toList().reversed(), 18))
        assertEquals(true, summarizeLatency((1L..20).toList(), 19).gatePassed)
    }
    @Test fun rejectsInvalidMeasurements() {
        assertFailsWith<IllegalArgumentException> { summarizeLatency(listOf(-1), 3) }
    }
}
