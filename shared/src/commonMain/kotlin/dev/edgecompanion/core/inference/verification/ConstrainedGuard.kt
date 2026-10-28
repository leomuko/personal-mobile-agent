package dev.edgecompanion.core.inference.verification

import dev.edgecompanion.core.inference.embedding.EncoderCleanup

data class ConstraintGuardResult(val passed: Boolean, val classification: String)

/** Expected negative guard checks are never successful replies or semantic judgments. */
object ConstrainedGuard {
    fun classify(caseId: String, result: VerificationProgress, points: List<ConstraintPoint>,
        sdkOutputTokens: Int?, wholeMs: Long, runtimeUnloaded: Boolean, resourcesHealthy: Boolean,
        encoderClean: Boolean, shapeMatched: Boolean): ConstraintGuardResult {
        fun blocked(reason: String) = ConstraintGuardResult(false, reason)
        if (points.any { it.atMs < 0 } || points.filter { it.phase != ConstraintPhase.NATIVE_ERROR }.groupBy { it.phase }.any { it.value.size != 1 })
            return blocked("DUPLICATE_OR_INVALID_PHASE")
        if (sdkOutputTokens != null && sdkOutputTokens !in 0..(if (caseId == "cd07") 1 else 512)) return blocked("TOKEN_COUNT")
        if (wholeMs !in 0 until 30_000 || result.diagnosticTruncated || result.cleanup != EncoderCleanup.CONFIRMED || !encoderClean ||
            !runtimeUnloaded || !resourcesHealthy) return blocked("DEADLINE_RESOURCE_OR_CLEANUP")
        val closed = points.singleOrNull { it.phase == ConstraintPhase.CLOSED } ?: return blocked("MISSING_CLOSE")
        val setup = points.singleOrNull { it.phase == ConstraintPhase.SETUP_ENTER } ?: return blocked("MISSING_SETUP")
        val returned = points.singleOrNull { it.phase == ConstraintPhase.SETUP_RETURN } ?: return blocked("MISSING_SETUP_RETURN")
        val published = points.singleOrNull { it.phase == ConstraintPhase.HANDLE_PUBLISHED } ?: return blocked("MISSING_PUBLICATION")
        if (setup.atMs > returned.atMs || returned.atMs > published.atMs || published.atMs > closed.atMs) return blocked("PHASE_ORDER")
        val cancel = points.singleOrNull { it.phase == ConstraintPhase.CANCEL_SIGNAL }
        val send = points.singleOrNull { it.phase == ConstraintPhase.SEND_ENTER }
        val terminal = points.singleOrNull { it.phase == ConstraintPhase.TERMINAL }
        val first = points.singleOrNull { it.phase == ConstraintPhase.FIRST_TEXT }
        val sendReturn = points.singleOrNull { it.phase == ConstraintPhase.SEND_RETURN }
        if ((send == null) != (sendReturn == null) || (send != null && (send.atMs < published.atMs ||
                sendReturn!!.atMs < send.atMs || sendReturn.atMs > closed.atMs)) ||
            ((first != null || terminal != null) && send == null) ||
            (first != null && (first.atMs < send!!.atMs || terminal == null || first.atMs > terminal.atMs)) ||
            (terminal != null && terminal.atMs < send!!.atMs) ||
            listOfNotNull(cancel, terminal, first).any { it.atMs > closed.atMs }) return blocked("PHASE_ORDER")
        val errors = points.filter { it.phase == ConstraintPhase.NATIVE_ERROR }
        val raw = result.diagnosticOutput.orEmpty()
        if (result.diagnosticReceivedUtf16Units != null && !result.diagnosticTruncated && result.diagnosticReceivedUtf16Units != raw.length.toLong())
            return blocked("DIAGNOSTIC_LOSS")
        val positive = caseId in setOf("cd01", "cd03", "cd08", "cd09")
        if (positive) return if (cancel == null && errors.isEmpty() && send != null && terminal != null && first != null &&
            result.outcome == VerificationOutcome.STRUCTURALLY_VALID && result.delivery == VerificationDelivery.ELIGIBLE &&
            result.validation is OutputValidation.StructurallyValid && shapeMatched && sdkOutputTokens in 1..512)
            ConstraintGuardResult(true, "POSITIVE_STRUCTURAL_GUARD") else blocked("INVALID_POSITIVE")
        if (result.delivery != VerificationDelivery.REVOKED || result.validation is OutputValidation.StructurallyValid)
            return blocked("NEGATIVE_DELIVERY_NOT_REVOKED")
        return when (caseId) {
            "cd02" -> if (cancel == null && first == null && raw.isEmpty() && send != null && terminal != null &&
                (sdkOutputTokens == null || sdkOutputTokens == 0) &&
                result.outcome == VerificationOutcome.FAILED && errors.size == 1 && errors.any {
                    it.diagnostic.orEmpty().contains("Failed to create LLGuidance constraint:") &&
                    it.diagnostic.orEmpty().contains("deliberately-invalid-json-schema-type") &&
                    it.atMs >= send.atMs && it.atMs <= terminal.atMs
                }) ConstraintGuardResult(true, "IDENTIFIED_SCHEMA_REJECTION") else blocked("UNIDENTIFIED_COMPILATION_FAILURE")
            "cd04", "cd05", "cd06" -> {
                val inPhase = cancel != null && when (caseId) {
                    "cd04" -> send == null && first == null && setup.atMs < cancel.atMs && cancel.atMs < returned.atMs
                    "cd05" -> send != null && terminal != null && send.atMs < cancel.atMs && cancel.atMs < terminal.atMs &&
                        (first == null || cancel.atMs < first.atMs)
                    else -> first != null && terminal != null && raw.isNotEmpty() && first.atMs <= cancel.atMs && cancel.atMs < terminal.atMs
                }
                if (inPhase && result.outcome == VerificationOutcome.CANCELLED)
                    ConstraintGuardResult(true, "TARGETED_CANCELLATION") else blocked("MISSED_CANCEL_PHASE")
            }
            "cd07" -> if (cancel == null && errors.isEmpty() && terminal != null && sdkOutputTokens == 1 &&
                first != null && raw.trimStart().startsWith("{") &&
                result.outcome == VerificationOutcome.INVALID_OUTPUT && result.validation is OutputValidation.Rejected)
                ConstraintGuardResult(true, "ONE_TOKEN_EXHAUSTION_GUARD") else blocked("EXHAUSTION_NOT_ESTABLISHED")
            else -> blocked("UNKNOWN_CASE")
        }
    }
}
