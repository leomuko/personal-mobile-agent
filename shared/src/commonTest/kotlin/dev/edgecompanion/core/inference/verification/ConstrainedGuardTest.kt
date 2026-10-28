package dev.edgecompanion.core.inference.verification

import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.embedding.*
import kotlin.test.*

class ConstrainedGuardTest {
    private fun point(p: ConstraintPhase, at: Long, message: String? = null) = ConstraintPoint(p, at, message)
    private val normal = listOf(point(ConstraintPhase.SETUP_ENTER, 1), point(ConstraintPhase.SETUP_RETURN, 3),
        point(ConstraintPhase.HANDLE_PUBLISHED, 3), point(ConstraintPhase.SEND_ENTER, 4), point(ConstraintPhase.SEND_RETURN, 6),
        point(ConstraintPhase.FIRST_TEXT, 5), point(ConstraintPhase.TERMINAL, 9), point(ConstraintPhase.CLOSED, 10))
    private val valid = OutputValidation.StructurallyValid(EvidenceOutput(EvidenceState.U, listOf(EvidenceDecision("a", EvidenceState.U, emptyList()))))
    private fun result(outcome: VerificationOutcome, validation: OutputValidation? = null, raw: String? = null) = VerificationProgress(
        outcome, validation, cleanup = EncoderCleanup.CONFIRMED, delivery = if (validation == valid) VerificationDelivery.ELIGIBLE else VerificationDelivery.REVOKED,
        diagnosticOutput = raw)
    private fun classify(id: String, r: VerificationProgress, points: List<ConstraintPoint> = normal, tokens: Int = 10, wall: Long = 11) =
        ConstrainedGuard.classify(id, r, points, tokens, wall, true, true, true, true)

    @Test fun exactDeadlineAndUncertainCleanupNeverPassEvenExpectedNegative() {
        val r = result(VerificationOutcome.STRUCTURALLY_VALID, valid)
        assertTrue(classify("cd03", r, wall = 29999).passed)
        for (time in listOf(30000L, 30001L, -1L)) assertFalse(classify("cd03", r, wall = time).passed)
        assertFalse(classify("cd03", r.copy(cleanup = EncoderCleanup.UNCERTAIN)).passed)
        assertFalse(ConstrainedGuard.classify("cd03", r, normal, 10, 11, true, false, true, true).passed)
    }
    @Test fun positiveRequiresRuntimeEligibilityAndShapeNotJustSchema() {
        val r = result(VerificationOutcome.STRUCTURALLY_VALID, valid)
        for (id in listOf("cd01", "cd03", "cd08", "cd09")) {
            assertTrue(classify(id, r).passed)
            assertFalse(classify(id, r.copy(delivery = VerificationDelivery.REVOKED)).passed)
            assertFalse(ConstrainedGuard.classify(id, r, normal, 10, 11, true, true, true, false).passed)
        }
    }
    @Test fun genericErrorDoesNotProveInvalidSchemaCompilation() {
        val points = normal.filter { it.phase != ConstraintPhase.FIRST_TEXT } +
            point(ConstraintPhase.NATIVE_ERROR, 6, "Failed to create LLGuidance constraint: deliberately-invalid-json-schema-type")
        assertTrue(classify("cd02", result(VerificationOutcome.FAILED), points, tokens = 0).passed)
        assertFalse(classify("cd02", result(VerificationOutcome.FAILED), points.map { if (it.phase == ConstraintPhase.NATIVE_ERROR)
            it.copy(diagnostic = "GENERATION_FAILED") else it }).passed)
        assertFalse(classify("cd02", result(VerificationOutcome.FAILED, raw = "partial"), points).passed)
    }
    @Test fun allThreeCancelWindowsMustBeObservedAndLateSignalsFail() {
        val r = result(VerificationOutcome.CANCELLED, raw = "{\"o\":")
        for ((id, at) in listOf("cd04" to 2L, "cd05" to 4L, "cd06" to 6L)) {
            val points = if (id == "cd04") normal.filter { it.phase !in setOf(ConstraintPhase.SEND_ENTER, ConstraintPhase.SEND_RETURN, ConstraintPhase.FIRST_TEXT, ConstraintPhase.TERMINAL) }
                else if (id == "cd05") normal.map { if (it.phase == ConstraintPhase.SEND_ENTER) it.copy(atMs = 3) else it } else normal
            assertTrue(classify(id, r, points + point(ConstraintPhase.CANCEL_SIGNAL, at)).passed, id)
            assertFalse(classify(id, r, points + point(ConstraintPhase.CANCEL_SIGNAL, 10)).passed, id)
        }
    }
    @Test fun arbitraryErrorEmptyOutputAndEosDoNotProveTokenExhaustion() {
        val r = result(VerificationOutcome.INVALID_OUTPUT, OutputValidation.Rejected(OutputRejection.JSON), "{")
        assertTrue(classify("cd07", r, tokens = 1).passed)
        for (raw in listOf("", "<eos>", "error")) assertFalse(classify("cd07", r.copy(diagnosticOutput = raw), tokens = 1).passed)
        assertFalse(classify("cd07", r, tokens = 0).passed)
        assertFalse(classify("cd07", r, normal + point(ConstraintPhase.NATIVE_ERROR, 7, "arbitrary failure"), tokens = 1).passed)
    }
    @Test fun v4DeadlineRechecksPublicationAfterCleanupAtAllThreeBoundaries() {
        for (time in listOf(29999L, 30000L, 30001L)) {
            var now = 0L
            val run = VerificationRun(EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", "X", 0, 1))),
                { now }, 0, VerificationPolicy.V4)
            run.append("{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}")
            run.validate(20); run.cleaned(true, 1); now = time; run.publish(true)
            assertEquals(if (time < 30000) VerificationDelivery.ELIGIBLE else VerificationDelivery.REVOKED, run.state.value.delivery)
        }
    }

    @Test fun contradictoryFirstTextAndTerminalTimingsCannotQualify() {
        val decodingBeforeSend = normal.map { if (it.phase == ConstraintPhase.FIRST_TEXT) it.copy(atMs = 3) else it } + point(ConstraintPhase.CANCEL_SIGNAL, 3)
        assertFalse(classify("cd06", result(VerificationOutcome.CANCELLED, raw = "{"), decodingBeforeSend).passed)
        val afterTerminal = normal.map { if (it.phase == ConstraintPhase.FIRST_TEXT) it.copy(atMs = 10) else it }
        assertFalse(classify("cd01", result(VerificationOutcome.STRUCTURALLY_VALID, valid), afterTerminal).passed)
        assertFalse(classify("cd06", result(VerificationOutcome.CANCELLED), normal + point(ConstraintPhase.CANCEL_SIGNAL, 6)).passed)
        val synchronous = normal.map { when (it.phase) {
            ConstraintPhase.TERMINAL -> it.copy(atMs = 6)
            ConstraintPhase.SEND_RETURN -> it.copy(atMs = 8)
            else -> it
        } }
        assertTrue(classify("cd03", result(VerificationOutcome.STRUCTURALLY_VALID, valid), synchronous).passed)
    }

    @Test fun diagnosticsPreserveRevokedTextAndNeverPromoteLateOrTruncatedOutput() {
        val context = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", "X", 0, 1)))
        val trace = ConstraintTrace(); trace.receiveText("{\"o\":")
        val run = VerificationRun(context, { 0 }, 0, VerificationPolicy.V4)
        run.cancel(); run.cleaned(true, 0, trace.seal()); run.publish(true)
        assertEquals("{\"o\":", run.state.value.diagnosticOutput)
        assertEquals(VerificationDelivery.REVOKED, run.state.value.delivery)
        assertNull(run.state.value.validation)
        val raw = "{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}"
        val validRun = VerificationRun(context, { 0 }, 0, VerificationPolicy.V4)
        validRun.append(raw); validRun.validate(20)
        validRun.cleaned(true, 0, ConstraintDiagnostics(raw + "LATE", (raw.length + 4).toLong()))
        validRun.publish(true)
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, validRun.state.value.outcome)
        assertEquals(VerificationDelivery.REVOKED, validRun.state.value.delivery)
        assertNull(validRun.state.value.validation)
        assertEquals(raw + "LATE", validRun.state.value.diagnosticOutput)
    }

    @Test fun boundedRawCapturePreservesUnicodePrefixesAndSealsAtomically() {
        val trace = ConstraintTrace()
        trace.receiveText("\uD83D"); trace.receiveText("\uDE00")
        assertEquals("😀", trace.state.value.diagnostics.text)
        assertFalse(trace.state.value.diagnostics.truncated)
        assertEquals("d83dde00", trace.state.value.diagnostics.utf16Hex)
        assertEquals("d83d", ConstraintDiagnostics("\uD83D", 1).utf16Hex)
        val overflow = ConstraintTrace(); overflow.receiveText("A".repeat(1535)); overflow.receiveText("😀")
        assertTrue(overflow.state.value.diagnostics.truncated)
        assertEquals("A".repeat(1535), overflow.state.value.diagnostics.text)
        overflow.receiveText("later")
        val snapshot = overflow.seal()
        assertEquals(1542L, snapshot.receivedUtf16Units)
        overflow.receiveText("after seal"); overflow.record(ConstraintPhase.FIRST_TEXT, 1)
        assertEquals(snapshot, overflow.state.value.diagnostics); assertTrue(overflow.state.value.points.isEmpty())
        assertTrue(snapshot.text.encodeToByteArray().size <= EvidenceOutputValidator.MAX_BYTES)
        assertFalse(classify("cd06", result(VerificationOutcome.CANCELLED, raw = snapshot.text).copy(diagnosticTruncated = true), normal + point(ConstraintPhase.CANCEL_SIGNAL, 6)).passed)
    }
}
