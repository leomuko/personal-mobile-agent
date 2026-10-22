package dev.edgecompanion.app

import dev.edgecompanion.app.support.*
import org.junit.Assert.*
import org.junit.Test

class V4CaseGateTest {
    private fun good() = V4CaseEvidence(true, true, true, true, true, true, false, true, true,
        12_000, 100, 1000, 13_100, 500, 100, "worker", true, 1100,
        listOf("CHECKING", "LOADING", "ENCODING").mapIndexed { i, p -> V4WorkerPoint(p, 1000L + i * 10, 100000, 1) },
        listOf(1120), 1, "memory-baseline-v1", 1001, 12_000, 0, 0,
        listOf("BASELINE", "LOADING", "PREFILL", "DECODING", "POST_RELEASE").mapIndexed { i, stage ->
            V4MemoryPoint(stage, if (i == 0) 0 else 119L + i, if (i == 0) 0 else 118L + i,
                "memory-baseline-v1", "GPU", false, 0, 6L shl 30, 1000000, 2000000, 3000000)
        })
    @Test fun completeHealthyCaseCanAdvance() { assertTrue(V4CaseGate.accepted(good(), emptySet())) }
    @Test fun sampleFailureStopsBeforeTheSecondRequestCanStart() {
        var started = 0
        for (evidence in listOf(good().copy(failed = 1), good())) {
            started++
            if (!V4CaseGate.accepted(evidence, emptySet())) break
        }
        assertEquals(1, started)
    }
    @Test fun deadlineCleanupOwnershipAndOutputFailuresBlockContinuation() {
        for (bad in listOf(good().copy(end = 31_000), good().copy(runtimeClean = false),
            good().copy(encoderClean = false), good().copy(deliveryEligible = false), good().copy(validationPresent = false),
            good().copy(diagnosticPresent = false), good().copy(resourcesSafe = false), good().copy(telemetryFailed = true),
            good().copy(deathAt = 1200), good().copy(worker = ""), good().copy(outputTokens = 513)))
            assertFalse(V4CaseGate.accepted(bad, emptySet()))
        assertFalse(V4CaseGate.accepted(good(), setOf("worker")))
    }
    @Test fun uncorrelatedMissingAndReversedTimingsCannotAdvance() {
        for (bad in listOf(good().copy(memoryStart = 99999999), good().copy(memory = good().memory.reversed()),
            good().copy(loads = listOf(1121)), good().copy(memory = emptyList()), good().copy(dropped = 1),
            good().copy(memory = good().memory.map { it.copy(elapsed = -1) })))
            assertFalse(V4CaseGate.accepted(bad, emptySet()))
    }
    @Test fun pressureProfileAdmitsLowAvailableRamOnlyWhileOsPressureIsFalse() {
        val id = "memory-android-pressure-test-v1"
        val pressure = good().copy(memoryPolicy = id, memory = good().memory.map { it.copy(policy = id, available = 1L shl 30) })
        assertTrue(V4CaseGate.accepted(pressure, emptySet(), id))
        assertFalse(V4CaseGate.accepted(pressure, emptySet()))
        assertFalse(V4CaseGate.accepted(pressure.copy(memory = pressure.memory.map { it.copy(lowMemory = true) }), emptySet(), id))
        assertFalse(V4CaseGate.accepted(pressure.copy(memory = pressure.memory.map { it.copy(available = -1) }), emptySet(), id))
        assertFalse(V4CaseGate.accepted(pressure.copy(memory = pressure.memory.map { it.copy(thermal = 3) }), emptySet(), id))
    }
}
