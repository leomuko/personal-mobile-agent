package dev.edgecompanion.core.inference

import kotlin.test.*

class MemoryPolicyTest {
    private val policy = MemoryPolicies.Current
    private val healthy = ResourceSnapshot(12L shl 30, 6L shl 30, false, 0, 500L shl 20)

    @Test fun baselineKeepsExistingSafeguardsAndExportsTheirIdentity() {
        assertEquals(3L shl 30, policy.loadingFloorBytes)
        assertEquals(3L shl 30, policy.generationFloorBytes)
        assertEquals(2560L * 1024 * 1024, policy.supervisedLoadReserveBytes)
        assertEquals(3, policy.availableMemorySamples)
        assertEquals(1_000L, policy.sampleIntervalMs)
        val exported = policy.exportFields()
        assertEquals(policy.id, exported["id"])
        assertEquals(false, exported["calibrated"])
        assertEquals(ModelCatalog.gemma4.sha256, exported["modelSha256"])
        assertEquals(policy.loadingFloorBytes, exported["loadingFloorBytes"])
        assertEquals(true, exported["enforceAvailableMemoryFloor"])
    }
    @Test fun stagesAreExplicitAndPressureFlagsAreNotAllocationGuarantees() {
        val synthetic = policy.copy(id = "unit-test-only", generationFloorBytes = 2L shl 30)
        val loaded = healthy.copy(availableMemoryBytes = 2560L * 1024 * 1024)
        assertEquals(ModelError.LOW_MEMORY, synthetic.blockedReason(loaded, MemoryAdmission.LOADING))
        assertNull(synthetic.blockedReason(loaded, MemoryAdmission.GENERATION))
        assertEquals(ModelError.LOW_MEMORY, policy.blockedReason(loaded, MemoryAdmission.GENERATION))
        assertEquals(ModelError.LOW_MEMORY, synthetic.blockedReason(loaded.copy(lowMemory = true), MemoryAdmission.GENERATION))
    }
    @Test fun hysteresisResetsButOsPressureThermalAndUnknownSignalsAreImmediate() {
        val window = MemoryPressureWindow(policy)
        val low = healthy.copy(availableMemoryBytes = policy.generationFloorBytes - 1)
        assertNull(window.observe(low)); assertNull(window.observe(low))
        assertNull(window.observe(healthy)); assertNull(window.observe(low)); assertNull(window.observe(low))
        assertEquals(ModelError.LOW_MEMORY, window.observe(low))
        assertEquals(ModelError.LOW_MEMORY, window.observe(healthy.copy(lowMemory = true)))
        assertEquals(ModelError.THERMAL, window.observe(healthy.copy(thermalStatus = 3)))
        assertEquals(ModelError.RESOURCE_UNKNOWN, window.observe(healthy.copy(availableMemoryBytes = null)))
        assertEquals(ModelError.RESOURCE_UNKNOWN, window.observe(healthy.copy(thermalStatus = -1)))
    }
}
