package dev.edgecompanion.core.inference.verification

import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.embedding.*
import kotlinx.coroutines.flow.*

enum class VerificationOutcome { STRUCTURALLY_VALID, INVALID_OUTPUT, TIMED_OUT, CANCELLED, INTERRUPTED, REJECTED, FAILED }
enum class VerificationDelivery { PENDING, ELIGIBLE, REVOKED }
/** Fixed investigation contracts, never a configurable search deadline. */
internal enum class VerificationPolicy(val deadlineMs: Long, val includeCleanup: Boolean) {
    V3(5_000, false), V4(30_000, true)
}
data class VerificationProgress(val outcome: VerificationOutcome? = null, val validation: OutputValidation? = null,
    val decisionMs: Long? = null, val validationMs: Long? = null,
    val firstTextMs: Long? = null,
    val cleanup: EncoderCleanup = EncoderCleanup.PENDING, val cleanupMs: Long? = null,
    val delivery: VerificationDelivery = VerificationDelivery.PENDING,
    val diagnosticOutput: String? = null,
    val diagnosticTruncated: Boolean = false, val diagnosticReceivedUtf16Units: Long? = null,
    val diagnosticUtf16Hex: String? = null,
    val error: ModelError? = null)
class VerificationCall internal constructor(val events: Flow<VerificationProgress>, val state: StateFlow<VerificationProgress>,
    val preparation: StateFlow<ForegroundState>, private val stop: () -> Unit) {
    fun cancel() = stop()
}

/** One terminal decision; native cleanup is independent and cannot replace that decision. */
internal class VerificationRun(private val context: EvidenceContext, private val nowMs: () -> Long, private val entered: Long,
    private val policy: VerificationPolicy = VerificationPolicy.V3) {
    private val mutable = MutableStateFlow(VerificationProgress())
    val state = mutable.asStateFlow()
    private val text = StringBuilder()
    private var candidate: OutputValidation? = null
    fun invalidate(error: ModelError) {
        revoke(error)
        decide(if (error == ModelError.ENCODER_TIMEOUT) VerificationOutcome.TIMED_OUT
            else VerificationOutcome.INTERRUPTED, error = error)
    }
    fun cancel() { revoke(); decide(VerificationOutcome.CANCELLED) }
    private fun revoke(error: ModelError? = null) {
        mutable.update { it.copy(delivery = VerificationDelivery.REVOKED,
            validation = it.validation?.takeIf { value -> value is OutputValidation.Rejected }, error = it.error ?: error) }
    }
    fun publish(eligible: Boolean) {
        if (policy.includeCleanup && nowMs() - entered !in 0 until policy.deadlineMs) invalidate(ModelError.ENCODER_TIMEOUT)
        if (!eligible) revoke(ModelError.CONTEXT_INVALIDATED)
        mutable.update { if (it.delivery == VerificationDelivery.REVOKED || it.cleanup != EncoderCleanup.CONFIRMED) it
            else it.copy(delivery = VerificationDelivery.ELIGIBLE,
                validation = if (it.outcome == VerificationOutcome.STRUCTURALLY_VALID) candidate else it.validation) }
    }
    fun append(delta: String) {
        checkDeadline()
        mutable.update { it.copy(firstTextMs = it.firstTextMs ?: (nowMs() - entered)) }
        if (text.length + delta.length > EvidenceOutputValidator.MAX_BYTES) invalidSize()
        text.append(delta)
        if (text.toString().encodeToByteArray().size > EvidenceOutputValidator.MAX_BYTES) invalidSize()
    }
    private fun invalidSize(): Nothing {
        decide(VerificationOutcome.INVALID_OUTPUT, OutputValidation.Rejected(OutputRejection.SIZE))
        throw ModelException(ModelError.GENERATION_FAILED)
    }
    fun checkDeadline() {
        if (nowMs() - entered !in 0 until policy.deadlineMs) {
            invalidate(ModelError.ENCODER_TIMEOUT)
            throw ModelException(ModelError.ENCODER_TIMEOUT)
        }
        if (state.value.outcome != null) throw ModelException(state.value.error ?: ModelError.CONTEXT_INVALIDATED)
    }
    fun validate(tokens: Int?) {
        checkDeadline()
        val start = nowMs()
        val result = EvidenceOutputValidator.validate(text.toString(), context, tokens ?: -1)
        checkDeadline()
        candidate = result
        mutable.update { it.copy(validationMs = nowMs() - start) }
        decide(if (result is OutputValidation.StructurallyValid) VerificationOutcome.STRUCTURALLY_VALID
            else VerificationOutcome.INVALID_OUTPUT, if (result is OutputValidation.Rejected) result else null)
    }
    fun terminal(event: ChatEvent.Terminal) {
        if (event.outcome != ChatOutcome.COMPLETED) revoke(event.error)
        val outcome = when (event.outcome) {
            ChatOutcome.COMPLETED -> VerificationOutcome.FAILED // Validation must have supplied the result.
            ChatOutcome.CANCELLED -> VerificationOutcome.CANCELLED
            ChatOutcome.INTERRUPTED -> VerificationOutcome.INTERRUPTED
            ChatOutcome.REJECTED -> VerificationOutcome.REJECTED
            ChatOutcome.TIMED_OUT -> VerificationOutcome.TIMED_OUT
            ChatOutcome.FAILED -> VerificationOutcome.FAILED
        }
        decide(outcome, error = event.error)
    }
    fun cleaned(confirmed: Boolean, durationMs: Long, diagnostics: ConstraintDiagnostics? = null) {
        if (!confirmed) revoke(ModelError.RESTART_REQUIRED)
        if (diagnostics != null && (diagnostics.truncated ||
                (state.value.outcome == VerificationOutcome.STRUCTURALLY_VALID && diagnostics.text != text.toString())))
            revoke(ModelError.GENERATION_FAILED)
        mutable.update { it.copy(cleanup = if (confirmed) EncoderCleanup.CONFIRMED else EncoderCleanup.UNCERTAIN,
            cleanupMs = durationMs, diagnosticOutput = if (policy == VerificationPolicy.V4) diagnostics?.text ?: text.toString() else null,
            diagnosticTruncated = diagnostics?.truncated ?: false, diagnosticReceivedUtf16Units = diagnostics?.receivedUtf16Units,
            diagnosticUtf16Hex = diagnostics?.utf16Hex,
            error = if (confirmed) it.error else ModelError.RESTART_REQUIRED) }
    }
    private fun decide(outcome: VerificationOutcome, validation: OutputValidation? = null, error: ModelError? = null) {
        mutable.update { if (it.outcome != null) it else it.copy(outcome = outcome, validation = validation,
            decisionMs = nowMs() - entered, error = error) }
    }
}
