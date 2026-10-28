package dev.edgecompanion.app

import dev.edgecompanion.app.support.*
import org.junit.Assert.*
import org.junit.Test

class ConstraintAllocationGateTest {
    private fun good(text: Boolean = false): ConstraintAllocationEvidence {
        val stages = listOf("BASELINE", "LOADING", "PREFILL") + (if (text) listOf("DECODING") else emptyList()) + "POST_RELEASE"
        val memory = stages.mapIndexed { i, stage -> V4MemoryPoint(stage, 10L + i * 10, 10L + i * 10,
            ConstraintAllocationGate.POLICY, "GPU", false, 0, 1, 1, 1, 1) }
        return ConstraintAllocationEvidence(100, 200, "synthetic-worker", true, 119,
            listOf("CHECKING", "LOADING", "ENCODING").mapIndexed { i, p -> V4WorkerPoint(p, 101L + i, 1, 1) },
            listOf(120), 100, 60, memory, 1, ConstraintAllocationGate.POLICY, 0, 0, text)
    }
    @Test fun expectedNegativeNeedsNoInventedDecodingStage() {
        assertTrue(ConstraintAllocationGate.accepted(good(), emptySet()))
        assertTrue(ConstraintAllocationGate.accepted(good(true), emptySet()))
        assertFalse(ConstraintAllocationGate.accepted(good().copy(firstTextObserved = true), emptySet()))
    }
    @Test fun pressurePolicyDoesNotRelaxOsThermalSignalOrCleanupEvidence() {
        val c = good()
        for (bad in listOf(c.copy(end = 30100), c.copy(deathConfirmed = false), c.copy(deathAt = 121), c.copy(failed = 1),
            c.copy(memory = c.memory.map { it.copy(lowMemory = true) }), c.copy(memory = c.memory.map { it.copy(thermal = 3) }),
            c.copy(memory = c.memory.map { it.copy(threshold = 0) }), c.copy(loads = listOf(121))))
            assertFalse(ConstraintAllocationGate.accepted(bad, emptySet()))
        assertFalse(ConstraintAllocationGate.accepted(c, setOf("synthetic-worker")))
    }
}
