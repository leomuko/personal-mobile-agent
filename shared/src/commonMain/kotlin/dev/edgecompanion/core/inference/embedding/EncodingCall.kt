package dev.edgecompanion.core.inference.embedding

import dev.edgecompanion.core.inference.ModelError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EncodingStatus { COMPLETED, CANCELLED, INTERRUPTED, TIMED_OUT, UNAVAILABLE, REJECTED, FAILED }

/** One terminal result per collection; vectors are transient and never imply semantic readiness. */
class EncodingOutcome internal constructor(val status: EncodingStatus, val error: ModelError? = null,
    values: List<Float>? = null) {
    private val snapshot = values?.toList()
    val values: List<Float>? get() = snapshot?.toList()
}

class EncodingCall internal constructor(val results: Flow<EncodingOutcome>,
    val preparation: StateFlow<ForegroundState>, private val stop: () -> Unit) {
    fun cancel() = stop()
}
