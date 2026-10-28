package dev.edgecompanion.core.inference

import kotlinx.coroutines.flow.Flow

data class ModelConfig(val path: String, val sha256: String, val contextTokens: Int,
    val backend: ModelBackend = ModelBackend.CPU, val maxOutputTokens: Int = 512) {
    init { require(contextTokens > maxOutputTokens && maxOutputTokens > 0) }
}
data class GenerationRequest(val prompt: String, val maxTokens: Int = 512, val chat: ChatRequest? = null,
    val constraint: dev.edgecompanion.core.inference.verification.ConstrainedDecoding? = null,
    val constraintEligibility: (() -> Unit)? = null)
sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Finished(val tokens: Int? = null, val inputTokens: Int? = null,
        val decodeTokensPerSecond: Double? = null) : GenerationEvent
    data object Cancelled : GenerationEvent
    data class Failed(val error: ModelError) : GenerationEvent
}
interface LocalLlmEngine {
    suspend fun load(config: ModelConfig): Result<Unit>
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
    suspend fun cancel()
    suspend fun release()
}

data class BenchmarkSample(val cold: Boolean, val firstTokenMs: Long, val elapsedMs: Long, val tokens: Int) {
    init { require(firstTokenMs >= 0 && elapsedMs >= firstTokenMs && tokens >= 0) }
    val tokensPerSecond: Double? get() = if (elapsedMs > firstTokenMs && tokens > 1)
        (tokens - 1) * 1000.0 / (elapsedMs - firstTokenMs) else null
}
