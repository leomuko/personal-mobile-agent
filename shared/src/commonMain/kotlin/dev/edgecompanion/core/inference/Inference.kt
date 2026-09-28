package dev.edgecompanion.core.inference

import kotlinx.coroutines.flow.Flow

data class ModelConfig(val path: String, val sha256: String, val contextTokens: Int = 4096)
data class GenerationRequest(val prompt: String, val maxTokens: Int = 256)
sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Finished(val tokens: Int) : GenerationEvent
    data class Failed(val reason: String) : GenerationEvent
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

