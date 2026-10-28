package dev.edgecompanion.core.inference.verification

import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Metadata only. The SDK setup/send boundaries are not internal GPU instruction probes. */
enum class ConstraintPhase { SETUP_ENTER, SETUP_RETURN, HANDLE_PUBLISHED, SEND_ENTER, SEND_RETURN,
    FIRST_TEXT, TERMINAL, CANCEL_SIGNAL, NATIVE_ERROR, CLOSED }
data class ConstraintPoint(val phase: ConstraintPhase, val atMs: Long, val diagnostic: String? = null)
data class ConstraintDiagnostics(val text: String = "", val receivedUtf16Units: Long = 0, val truncated: Boolean = false) {
    /** Lossless even if cancellation leaves a lone surrogate that UTF-8 JSON writing would replace. */
    val utf16Hex: String get() = text.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
}
data class ConstraintTraceState(val points: List<ConstraintPoint> = emptyList(),
    val diagnostics: ConstraintDiagnostics = ConstraintDiagnostics(), val sealed: Boolean = false)
class ConstraintTrace {
    private val mutable = MutableStateFlow(ConstraintTraceState())
    val state = mutable.asStateFlow()
    fun record(phase: ConstraintPhase, atMs: Long, diagnostic: String? = null) {
        mutable.update { if (it.sealed || it.points.size >= 64) it else
            it.copy(points = it.points + ConstraintPoint(phase, atMs, diagnostic?.take(1024))) }
    }
    /** Capture callback text independently of delivery eligibility; retain a bounded exact prefix. */
    fun receiveText(value: String) {
        mutable.update { if (it.sealed || value.isEmpty()) it else it.copy(diagnostics = appendRaw(it.diagnostics, value)) }
    }
    fun seal(): ConstraintDiagnostics {
        mutable.update { it.copy(sealed = true) }
        return mutable.value.diagnostics
    }
    private fun appendRaw(previous: ConstraintDiagnostics, value: String): ConstraintDiagnostics {
        val received = if (previous.receivedUtf16Units > Long.MAX_VALUE - value.length) Long.MAX_VALUE
            else previous.receivedUtf16Units + value.length
        if (previous.truncated) return previous.copy(receivedUtf16Units = received)
        val candidate = previous.text + value.take(EvidenceOutputValidator.MAX_BYTES + 1)
        var low = 0; var high = candidate.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (candidate.substring(0, mid).encodeToByteArray().size <= EvidenceOutputValidator.MAX_BYTES) low = mid else high = mid - 1
        }
        val truncated = value.length > EvidenceOutputValidator.MAX_BYTES + 1 || low < candidate.length
        if (truncated && low > 0 && candidate[low - 1].isHighSurrogate()) low--
        return ConstraintDiagnostics(candidate.substring(0, low), received, truncated)
    }
}

/** Immutable synthetic configuration, not an ownership credential or caller-supplied schema. */
class ConstrainedDecoding private constructor(
    val caseId: String, val generationId: String, val payload: String, val question: String,
    val schema: String, val schemaSha256: String, val decodeTokenCap: Int,
    val trace: ConstraintTrace,
) {
    fun check(request: ChatRequest) {
        if (request.id != generationId || request.backend != ModelBackend.GPU || request.contextTokens != 2048 ||
            request.outputTokens != 512 || request.messages != listOf(ChatMessage(ChatRole.USER, payload)))
            throw ModelException(ModelError.CONTEXT_LIMIT)
    }

    companion object {
        const val PROTOCOL = "evidence-constrained-decoding-v2-draft-1"
        const val LOCK = "5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9"
        const val EVIDENCE_SHA = "36232d7c46e2343ad0a5b40c6328e1ff3ff10b6ef76d457a93b245052390e691"
        const val SENTINEL_SHA = "e10adc86c84b6615df190205cf94b1baae0c0d1ef228dda2b5c5fe999ca5be53"
        const val INVALID_SHA = "041bb8c53280f55f249550a741e5493789a5d49c11d25a69eb00cf9eaccc06ab"
        private val packs = listOf(
            "54faaf216439f732a92a42c4a678bb6bcae205bc54ab0a9cbef1372e2b28c5e7",
            "6e2b4a756b1f309528d6f22f514d19fbe619a104a48186f5d89549d76ea5852f",
            "f726bf4936aa63d246f880b682e7ab98ac34b2484432948eff540c57a0963fee",
            "e6182455153544fa256e9907afabd384ecaf4980af1bb69686684e184f7d7add")
        private val questions = listOf(
            "4cffab30ca9509e938bcefb6a065560fc6fabbbf0f5c709fdcabcde4620a7c95",
            "bf6300eca8398e144ce8af8e56245c32594463eb00b71366bb7e2b0c91aa912a",
            "40f0994bf6db16dd07a576a33db12a684ca547c19ad53eb44d3add8e90f4096e",
            "88e0aaa82778905ea87899e0fc64d233ba25c884df876968b6518ca457e48566")

        /** Platform hashing is injected as with encoder admission; the adapter rechecks with SHA-256. */
        fun admit(caseId: String, generationId: String, payload: String, question: String, schema: String,
            sha256: (ByteArray) -> String): ConstrainedDecoding {
            require(caseId in (1..9).map { "cd0$it" } && generationId.isNotBlank())
            val index = when (caseId) { "cd01" -> 0; "cd05", "cd06", "cd08" -> 2; "cd09" -> 3; else -> 1 }
            require(payload.length <= 1312 && payload.encodeToByteArray().size <= 1312 && question.encodeToByteArray().size <= 180)
            require(schema.length <= 8192 && schema.encodeToByteArray().size <= 8192)
            require(sha256(payload.encodeToByteArray()) == packs[index] && sha256(question.encodeToByteArray()) == questions[index])
            val expected = when (caseId) { "cd01" -> SENTINEL_SHA; "cd02" -> INVALID_SHA; else -> EVIDENCE_SHA }
            require(sha256(schema.encodeToByteArray()) == expected)
            return ConstrainedDecoding(caseId, generationId, payload, question, schema, expected,
                if (caseId == "cd07") 1 else 512, ConstraintTrace())
        }
    }
}
