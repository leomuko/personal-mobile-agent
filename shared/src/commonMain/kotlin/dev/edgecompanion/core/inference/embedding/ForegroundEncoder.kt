package dev.edgecompanion.core.inference.embedding

import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Object identity grants authority; the printable ID is diagnostic correlation only. */
class EncoderLease internal constructor(val requestId: String)
class EncoderRequest internal constructor(val lease: EncoderLease, val receipt: EncoderAdmissionReceipt)
sealed interface EncoderResult {
    class Completed(val lease: EncoderLease, values: List<Float>) : EncoderResult {
        private val snapshot = values.toList()
        val values: List<Float> get() = snapshot.toList()
    }
    data object Cancelled : EncoderResult
    data object TimedOut : EncoderResult
    data class Rejected(val error: AdmissionError) : EncoderResult
    data object Failed : EncoderResult
}
interface ForegroundEncoder {
    suspend fun admit(input: EncoderInput): EncoderAdmission
    fun accepts(receipt: EncoderAdmissionReceipt, input: EncoderInput): Boolean
    suspend fun encode(request: EncoderRequest): EncoderResult
    /** Nonblocking signal, never queued behind encoding or a scheduling mutex. */
    fun cancel(lease: EncoderLease)
    /** True only after confirmed native cleanup/death, including interrupted binding. */
    suspend fun cleanup(lease: EncoderLease): Boolean
}
enum class PreparationStage { QUEUED, RECLAIMING, COUNTING, ENCODING, CLEANUP, GEMMA, FINISHED }
enum class EncoderCleanup { CONFIRMED, PENDING, UNCERTAIN }
data class ForegroundState(val stage: PreparationStage = PreparationStage.QUEUED,
    val cleanup: EncoderCleanup = EncoderCleanup.CONFIRMED, val error: ModelError? = null,
    val admissionError: AdmissionError? = null, val retrievalMs: Long? = null)
class ForegroundCall internal constructor(val events: Flow<ChatEvent>, val preparation: StateFlow<ForegroundState>,
    private val stop: () -> Unit) {
    fun cancel() = stop()
}

/** Policy lives here; native lifetime is a platform adapter and ModelManager remains scheduler. */
internal class ForegroundPreparation(
    id: String, private val input: EncoderInput, private val encoder: ForegroundEncoder,
    private val epoch: Long, private val currentEpoch: () -> Long, private val nowMs: () -> Long,
    private val lifetimeValid: () -> Boolean,
) {
    private val entered = nowMs()
    private data class StopCause(val error: ModelError?, val user: Boolean = false)
    private val stopCause = MutableStateFlow<StopCause?>(null)
    private val lease = EncoderLease(id)
    private val mutable = MutableStateFlow(ForegroundState())
    val state = mutable.asStateFlow()
    private var startedEncoding = false
    private var completedCleanup = false
    private var handoff = false
    private val claimed = MutableStateFlow(false)
    val userCancelled: Boolean get() = stopCause.value?.user == true
    fun claim(): Boolean = claimed.compareAndSet(false, true)
    fun cancelByUser() { stopCause.compareAndSet(null, StopCause(null, user = true)); signalNative() }

    fun invalidate(reason: ModelError? = null) {
        if (stopCause.compareAndSet(null, StopCause(reason ?: ModelError.CONTEXT_INVALIDATED)) && reason != null)
            mutable.update { it.copy(error = reason) }
        signalNative()
    }
    private fun signalNative() {
        // Scope cancellation/reconciliation happens separately; the native signal is immediate.
        if (state.value.cleanup == EncoderCleanup.PENDING) try { encoder.cancel(lease) } catch (_: Exception) { }
    }
    fun checkLive() {
        if (!lifetimeValid() || currentEpoch() != epoch) invalidate(ModelError.CONTEXT_INVALIDATED)
        stopCause.value?.let { throw ModelException(it.error ?: ModelError.CONTEXT_INVALIDATED) }
        if (!handoff && (nowMs() - entered < 0 || nowMs() - entered >= DEADLINE_MS)) {
            invalidate(ModelError.ENCODER_TIMEOUT)
            throw ModelException(ModelError.ENCODER_TIMEOUT)
        }
    }
    fun reclaiming() { checkLive(); mutable.update { it.copy(stage = PreparationStage.RECLAIMING) } }

    suspend fun prepare(): List<Float> {
        checkLive()
        val vector = try {
            withTimeout((DEADLINE_MS - (nowMs() - entered)).coerceAtLeast(1)) {
                mutable.update { it.copy(stage = PreparationStage.COUNTING) }
                val admitted = encoder.admit(input)
                checkLive()
                val receipt = when (admitted) {
                    is EncoderAdmission.Accepted -> admitted.receipt
                    is EncoderAdmission.Rejected -> {
                        mutable.update { it.copy(admissionError = admitted.error) }
                        throw ModelException(ModelError.ENCODER_REJECTED)
                    }
                }
                if (!encoder.accepts(receipt, input)) {
                    mutable.update { it.copy(admissionError = AdmissionError.MISMATCH) }
                    throw ModelException(ModelError.ENCODER_REJECTED)
                }
                checkLive()
                startedEncoding = true
                mutable.update { it.copy(stage = PreparationStage.ENCODING, cleanup = EncoderCleanup.PENDING) }
                val values = when (val result = encoder.encode(EncoderRequest(lease, receipt))) {
                    is EncoderResult.Completed -> {
                        val values = result.values
                        val norm = kotlin.math.sqrt(values.sumOf { it.toDouble() * it })
                        if (result.lease !== lease || values.size != 100 || values.any { !it.isFinite() } || kotlin.math.abs(norm - 1) > 0.01)
                            throw ModelException(ModelError.ENCODER_FAILED)
                        values
                    }
                    EncoderResult.Cancelled -> throw CancellationException("Encoder cancelled")
                    EncoderResult.TimedOut -> throw ModelException(ModelError.ENCODER_TIMEOUT)
                    is EncoderResult.Rejected -> {
                        mutable.update { it.copy(admissionError = result.error) }
                        throw ModelException(ModelError.ENCODER_REJECTED)
                    }
                    EncoderResult.Failed -> throw ModelException(ModelError.ENCODER_FAILED)
                }
                checkLive()
                values
            }
        } catch (_: TimeoutCancellationException) { invalidate(ModelError.ENCODER_TIMEOUT); throw ModelException(ModelError.ENCODER_TIMEOUT) }
        finally { cleanup() }
        checkLive()
        return vector
    }

    suspend fun cleanup(): Boolean = withContext(NonCancellable) {
        if (!startedEncoding || completedCleanup) return@withContext state.value.cleanup == EncoderCleanup.CONFIRMED
        mutable.update { it.copy(stage = PreparationStage.CLEANUP) }
        try { encoder.cancel(lease) } catch (_: Exception) { }
        val confirmed = try { withTimeout(CLEANUP_MS) { encoder.cleanup(lease) } } catch (_: Exception) { false }
        completedCleanup = true
        mutable.update { it.copy(cleanup = if (confirmed) EncoderCleanup.CONFIRMED else EncoderCleanup.UNCERTAIN) }
        confirmed
    }
    fun admitGemma() {
        checkLive()
        check(state.value.cleanup == EncoderCleanup.CONFIRMED)
        mutable.update { it.copy(retrievalMs = nowMs() - entered, stage = PreparationStage.GEMMA) }
        handoff = true
    }
    fun finished(error: ModelError?) { mutable.update { it.copy(stage = PreparationStage.FINISHED, error = error) } }

    companion object { const val DEADLINE_MS = 5_000L; const val CLEANUP_MS = 5_000L }
}
