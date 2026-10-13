package dev.edgecompanion.core.inference

data class ModelManifest(
    val id: String,
    val revision: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val artifactContextTokens: Int,
    val configuredContextTokens: Int,
    val maxOutputTokens: Int,
) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(revision.matches(Regex("[a-f0-9]{40}")))
        require(fileName.matches(Regex("[a-zA-Z0-9._-]+\\.litertlm")))
        require(bytes > 0 && bytes <= Long.MAX_VALUE - STORAGE_RESERVE_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(configuredContextTokens in 1..artifactContextTokens)
        require(maxOutputTokens in 1 until configuredContextTokens)
    }

    // One streamed staging file is renamed, not copied. Existing files already reduce free space.
    val requiredFreeBytes: Long get() = bytes + STORAGE_RESERVE_BYTES

    companion object { const val STORAGE_RESERVE_BYTES = 512L * 1024 * 1024 }
}

object ModelCatalog {
    const val RUNTIME_VERSION = "0.17.1"
    const val LICENSE = "Apache-2.0"
    val gemma4 = ModelManifest(
        id = "gemma-4-e2b-it-mobile-text",
        revision = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        fileName = "gemma-4-E2B-it.litertlm",
        bytes = 2588147712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        artifactContextTokens = 32000, configuredContextTokens = 2048, maxOutputTokens = 512,
    )
}

enum class ModelBackend { CPU, GPU }
enum class ModelError {
    NOT_INSTALLED, BUSY, SIZE_MISMATCH, HASH_MISMATCH, STORAGE_FULL, IMPORT_FAILED,
    UNSUPPORTED_DEVICE, LOW_MEMORY, THERMAL, RESOURCE_UNKNOWN, LOAD_FAILED,
    GENERATION_FAILED, CONTEXT_LIMIT, CANCEL_TIMEOUT, RELEASE_FAILED, RESTART_REQUIRED,
}
class ModelException(val error: ModelError) : Exception(error.name)

enum class AssetStatus { NOT_INSTALLED, IMPORTING, VERIFYING, INSTALLED, REPAIR_REQUIRED, FAILED }
data class ModelAssetState(val status: AssetStatus = AssetStatus.NOT_INSTALLED,
    val copiedBytes: Long = 0, val error: ModelError? = null)
enum class RuntimeStatus { UNLOADED, LOADING, LOADED, GENERATING, CANCELLING, RELEASING, BLOCKED, FAILED }
data class ModelRuntimeState(val status: RuntimeStatus = RuntimeStatus.UNLOADED,
    val backend: ModelBackend = ModelBackend.CPU, val output: String = "", val error: ModelError? = null,
    val commandError: ModelError? = null,
    val loadMs: Long? = null, val firstTokenMs: Long? = null, val generationMs: Long? = null,
    val outputTokens: Int? = null, val inputTokens: Int? = null, val decodeTokensPerSecond: Double? = null)

data class ResourceSnapshot(val totalMemoryBytes: Long?, val availableMemoryBytes: Long?,
    val lowMemory: Boolean, val thermalStatus: Int?) {
    companion object { const val MIN_AVAILABLE_MEMORY_BYTES = 3L * 1024 * 1024 * 1024 }

    fun blockedReason(): ModelError? = when {
        thermalStatus == null || totalMemoryBytes == null || availableMemoryBytes == null -> ModelError.RESOURCE_UNKNOWN
        thermalStatus >= 3 -> ModelError.THERMAL
        totalMemoryBytes < 7L * 1024 * 1024 * 1024 -> ModelError.UNSUPPORTED_DEVICE
        lowMemory || availableMemoryBytes < MIN_AVAILABLE_MEMORY_BYTES -> ModelError.LOW_MEMORY
        else -> null
    }
}

fun requireContextBudget(inputTokens: Int, outputTokens: Int, contextTokens: Int) {
    if (inputTokens < 0 || outputTokens <= 0 || inputTokens.toLong() + outputTokens > contextTokens)
        throw ModelException(ModelError.CONTEXT_LIMIT)
}
