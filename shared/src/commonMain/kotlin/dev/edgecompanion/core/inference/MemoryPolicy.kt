package dev.edgecompanion.core.inference

enum class MemoryAdmission { LOADING, GENERATION }
enum class MemoryStage { BASELINE, LOADING, LOADED_IDLE, PREFILL, DECODING, CANCELLING, POST_RELEASE }
data class MemoryTracePoint(val stage: MemoryStage, val backend: ModelBackend, val runtimeStatus: RuntimeStatus,
    val engineOwned: Boolean, val monotonicMs: Long, val policyId: String)

data class MemoryPolicy(
    val id: String,
    val loadingFloorBytes: Long,
    val generationFloorBytes: Long,
    val supervisedLoadReserveBytes: Long,
    val minimumTotalBytes: Long,
    val availableMemorySamples: Int,
    val sampleIntervalMs: Long,
    val severeThermalStatus: Int,
    val maxSkinCelsius: Int,
    val enforceAvailableMemoryFloor: Boolean = true,
) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(loadingFloorBytes > 0 && generationFloorBytes > 0 && supervisedLoadReserveBytes >= 0)
        require(loadingFloorBytes <= Long.MAX_VALUE - supervisedLoadReserveBytes)
        require(minimumTotalBytes > 0 && availableMemorySamples > 0 && sampleIntervalMs > 0)
        require(severeThermalStatus > 0 && maxSkinCelsius > 0)
    }

    fun blockedReason(snapshot: ResourceSnapshot, stage: MemoryAdmission): ModelError? = with(snapshot) {
        when {
            thermalStatus == null || thermalStatus < 0 || totalMemoryBytes == null || totalMemoryBytes <= 0 ||
                availableMemoryBytes == null || availableMemoryBytes < 0 -> ModelError.RESOURCE_UNKNOWN
            !enforceAvailableMemoryFloor && (pressureThresholdBytes == null || pressureThresholdBytes <= 0) -> ModelError.RESOURCE_UNKNOWN
            thermalStatus >= severeThermalStatus -> ModelError.THERMAL
            totalMemoryBytes < minimumTotalBytes -> ModelError.UNSUPPORTED_DEVICE
            lowMemory || (enforceAvailableMemoryFloor && availableMemoryBytes < when (stage) {
                MemoryAdmission.LOADING -> loadingFloorBytes
                MemoryAdmission.GENERATION -> generationFloorBytes
            }) -> ModelError.LOW_MEMORY
            else -> null
        }
    }

    fun exportFields(): Map<String, Any> = mapOf(
        "schema" to 1, "id" to id, "calibrated" to false,
        "enforceAvailableMemoryFloor" to enforceAvailableMemoryFloor,
        "loadingFloorBytes" to loadingFloorBytes, "generationFloorBytes" to generationFloorBytes,
        "supervisedLoadReserveBytes" to supervisedLoadReserveBytes, "minimumTotalBytes" to minimumTotalBytes,
        "availableMemorySamples" to availableMemorySamples, "sampleIntervalMs" to sampleIntervalMs,
        "severeThermalStatus" to severeThermalStatus, "maxSkinCelsius" to maxSkinCelsius,
        "modelSha256" to ModelCatalog.gemma4.sha256, "runtimeVersion" to ModelCatalog.RUNTIME_VERSION,
        "contextTokens" to ModelCatalog.gemma4.configuredContextTokens, "outputTokens" to ModelCatalog.gemma4.maxOutputTokens,
    )
}

object MemoryPolicies {
    // Versioning makes the existing safeguards auditable; this is not a calibrated replacement.
    val Current = MemoryPolicy("memory-baseline-v1", 3L shl 30, 3L shl 30,
        2560L * 1024 * 1024, 7L shl 30, 3, 1_000, 3, 45)

    // Supervised QA experiment only; false OS pressure is not an allocation guarantee.
    val AndroidPressureTest = Current.copy(id = "memory-android-pressure-test-v1", enforceAvailableMemoryFloor = false)
}

class MemoryPressureWindow(private val policy: MemoryPolicy) {
    private var consecutive = 0
    fun observe(snapshot: ResourceSnapshot): ModelError? {
        val reason = policy.blockedReason(snapshot, MemoryAdmission.GENERATION)
        consecutive = if (reason == ModelError.LOW_MEMORY && !snapshot.lowMemory) consecutive + 1 else 0
        return if (reason == ModelError.LOW_MEMORY && !snapshot.lowMemory && consecutive < policy.availableMemorySamples)
            null else reason
    }
}
