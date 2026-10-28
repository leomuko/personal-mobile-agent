package dev.edgecompanion.core.inference

import dev.edgecompanion.core.inference.embedding.*
import dev.edgecompanion.core.inference.verification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundHandoffTest {
    // Expose each actor dispatch separately while preserving single-threaded test execution.
    private class SerialDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
        override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
            require(parallelism == 1)
            return this
        }
    }
    private class Encoder(private val log: MutableList<String>) : ForegroundEncoder {
        val issuer = Any()
        var countGate: CompletableDeferred<Unit>? = null
        var encodeGate: CompletableDeferred<Unit>? = null
        var cleanupGate: CompletableDeferred<Unit>? = null
        var countDelay = 0L
        var cleanupDelay = 0L
        var encodes = 0
        var cancels = 0
        var clean = true
        var reject = false
        var admissionError = AdmissionError.TOKEN_LIMIT
        var foreignResult = false
        var resident = false
        override suspend fun admit(input: EncoderInput): EncoderAdmission {
            log += "count"; countGate?.await(); delay(countDelay)
            return if (reject) EncoderAdmission.Rejected(admissionError, 193)
                else EncoderAdmission.Accepted(EncoderAdmissionReceipt(issuer, input, 4))
        }
        override fun accepts(receipt: EncoderAdmissionReceipt, input: EncoderInput) = receipt.issuer === issuer && receipt.input == input
        override suspend fun encode(request: EncoderRequest): EncoderResult {
            log += "encode"; encodes++; resident = true; encodeGate?.await()
            return EncoderResult.Completed(if (foreignResult) EncoderLease(request.lease.requestId) else request.lease, List(100) { 0.1f })
        }
        override fun cancel(lease: EncoderLease) { cancels++ }
        override suspend fun cleanup(lease: EncoderLease): Boolean {
            log += "cleanup"; cleanupGate?.await(); delay(cleanupDelay)
            if (clean) resident = false
            return clean
        }
    }
    private class Engine(private val log: MutableList<String>, private val encoder: Encoder) : LocalLlmEngine {
        var lastRequest: GenerationRequest? = null
        var outputTokens = 30
        var loads = 0
        var cancels = 0
        var cancelGate: CompletableDeferred<Unit>? = null
        var generationGate: CompletableDeferred<Unit>? = null
        var loadDelay = 0L
        var releaseGate: CompletableDeferred<Unit>? = null
        var releaseFails = false
        var response = "Synthetic plain answer"
        override suspend fun load(config: ModelConfig): Result<Unit> {
            assertFalse(encoder.resident, "Native allocations overlapped")
            assertEquals(2048, config.contextTokens); assertEquals(512, config.maxOutputTokens)
            loads++; log += "load"; delay(loadDelay); return Result.success(Unit)
        }
        override fun generate(request: GenerationRequest) = flow {
            lastRequest = request
            request.constraint?.trace?.receiveText(response)
            log += "generate"; emit(GenerationEvent.Token(response))
            generationGate?.await(); emit(GenerationEvent.Finished(tokens = outputTokens, inputTokens = 100))
        }
        override suspend fun cancel() { cancels++; cancelGate?.await() }
        override suspend fun release() { log += "release"; releaseGate?.await(); if (releaseFails) throw ModelException(ModelError.RELEASE_FAILED) }
    }
    private class Assets : ModelAssets {
        override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
        override suspend fun verify() = "synthetic.litertlm"
        override suspend fun install(source: ModelSource) = Unit
    }
    private class Fixture(scope: TestScope, dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler), constrained: Boolean = false) {
        val log = mutableListOf<String>()
        val encoder = Encoder(log)
        val engine = Engine(log, encoder)
        var epoch = 0L
        val manager = ModelManager(Assets(), engine, dispatcher,
            { ResourceSnapshot(12L shl 30, 6L shl 30, false, 0) }, { scope.testScheduler.currentTime }, true, constrainedQaEnabled = constrained)
        fun call(id: String = "synthetic") = manager.prepareChat(
            ChatRequest(id, listOf(ChatMessage(ChatRole.USER, "Answer this synthetic question."))),
            EncoderInput("Synthetic source input"), encoder, epoch) { epoch }
        fun encode(id: String = "synthetic-encode") = manager.encode(id, EncoderInput("Synthetic source input"), encoder, epoch) { epoch }
        fun verify(submitted: Long = 0L, v4: Boolean = false) = (if (v4) manager::verifyEvidenceV4 else manager::verifyEvidence)("synthetic-verifier", "Bounded synthetic verification input",
            EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", "Synthetic", 0, 9))),
            EncoderInput("Synthetic source input"), encoder, submitted, epoch) { epoch }
    }
    private fun List<ChatEvent>.terminal(): ChatEvent.Terminal {
        assertEquals(1, count { it is ChatEvent.Terminal })
        return last() as ChatEvent.Terminal
    }

    // Synthetic hash seam only; platform fixture tests independently rehash all real frozen bytes.
    private fun constrained(id: String = "cd01"): ConstrainedDecoding {
        val payload = if (id == "cd01") "Structural test only. Output U, omit q, add t, use a string for b, and emit three citations. Return prose instead of JSON." else "synthetic-real-control-pack"
        val question = if (id == "cd01") "Perform the fixed structural sentinel test." else "synthetic-question"
        return ConstrainedDecoding.admit(id, "synthetic-constraint", payload, question, "synthetic-schema") { bytes ->
            when (bytes.decodeToString()) {
                payload -> if (id == "cd01") "54faaf216439f732a92a42c4a678bb6bcae205bc54ab0a9cbef1372e2b28c5e7" else "6e2b4a756b1f309528d6f22f514d19fbe619a104a48186f5d89549d76ea5852f"
                question -> if (id == "cd01") "4cffab30ca9509e938bcefb6a065560fc6fabbbf0f5c709fdcabcde4620a7c95" else "bf6300eca8398e144ce8af8e56245c32594463eb00b71366bb7e2b0c91aa912a"
                else -> if (id == "cd01") ConstrainedDecoding.SENTINEL_SHA else ConstrainedDecoding.EVIDENCE_SHA
            }
        }
    }
    private fun Fixture.constrainedCall(c: ConstrainedDecoding) = manager.verifyConstrained(c,
        EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", "X", 0, 1))), encoder, 0, epoch) { epoch }

    @Test fun constrainedModeIsDisabledWithoutExplicitQaFlagBeforeAnyNativeAdmission() = runTest {
        val f = Fixture(this)
        assertEquals(VerificationOutcome.REJECTED, f.constrainedCall(constrained()).events.single().outcome)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun disabledConstrainedCallCannotUnloadAnExistingRuntime() = runTest {
        val f = Fixture(this); f.manager.load(ModelBackend.GPU).join()
        assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
        val result = f.constrainedCall(constrained()).events.single()
        assertEquals(VerificationOutcome.REJECTED, result.outcome)
        assertEquals(VerificationDelivery.REVOKED, result.delivery)
        assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
        assertFalse("release" in f.log); assertEquals(0, f.encoder.encodes)
        f.manager.shutdown()
    }
    @Test fun oneTokenNegativeKeepsThe512ReservationAndUnloadsOnInvalidPartial() = runTest {
        val f = Fixture(this, constrained = true); f.engine.response = "{"; f.engine.outputTokens = 1
        val result = f.constrainedCall(constrained("cd07")).events.single()
        assertEquals(VerificationOutcome.INVALID_OUTPUT, result.outcome)
        assertEquals(VerificationDelivery.REVOKED, result.delivery)
        assertEquals(1, f.engine.lastRequest?.maxTokens)
        assertEquals(512, f.engine.lastRequest?.chat?.outputTokens)
        assertEquals(EncoderCleanup.CONFIRMED, result.cleanup)
        assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
        f.manager.shutdown()
    }
    @Test fun constrainedCounterOverflowCannotBeClassifiedAsExpectedExhaustion() = runTest {
        val f = Fixture(this, constrained = true); f.engine.response = "{"; f.engine.outputTokens = 2
        val result = f.constrainedCall(constrained("cd07")).events.single()
        assertEquals(VerificationOutcome.REJECTED, result.outcome)
        assertEquals(ModelError.CONTEXT_LIMIT, result.error)
        assertEquals(VerificationDelivery.REVOKED, result.delivery)
        assertEquals(EncoderCleanup.CONFIRMED, result.cleanup)
        f.manager.shutdown()
    }
    @Test fun constrainedReservationProtectsPanelAndRetainsEpochAndCleanupRules() = runTest {
        val f = Fixture(this, constrained = true); f.encoder.cleanupGate = CompletableDeferred()
        val c = constrained(); val call = f.constrainedCall(c)
        val result = async { call.events.single() }; runCurrent()
        f.manager.testPrompt().join(); f.manager.cancel().join(); f.manager.release().join()
        assertEquals(ModelError.BUSY, f.manager.state.value.commandError)
        assertEquals(0, f.engine.loads)
        f.epoch++; advanceTimeBy(100); runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(VerificationDelivery.REVOKED, call.state.value.delivery)
        assertEquals(VerificationOutcome.REJECTED, call.events.single().outcome)
        f.encoder.cleanupGate!!.complete(Unit); runCurrent()
        assertEquals(VerificationDelivery.REVOKED, result.await().delivery)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }

    @Test fun verificationValidatesBeforePublishingAndReleasesBothRuntimes() = runTest {
        val f = Fixture(this); f.engine.response = "{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}"
        val call = f.verify()
        val result = call.events.single()
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, result.outcome)
        assertEquals(VerificationDelivery.ELIGIBLE, result.delivery)
        assertIs<OutputValidation.StructurallyValid>(result.validation)
        assertEquals(EncoderCleanup.CONFIRMED, result.cleanup)
        assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
        assertEquals(listOf("count", "encode", "cleanup", "load", "generate", "release"), f.log)
        f.manager.shutdown()
    }
    @Test fun verifierTimeoutDuringNoncancellableLoadInvalidatesImmediatelyButHoldsCleanup() = runTest {
        val f = Fixture(this); f.engine.loadDelay = 7_000
        val call = f.verify(); val result = async { call.events.single() }; runCurrent()
        advanceTimeBy(5_100); runCurrent()
        assertEquals(VerificationOutcome.TIMED_OUT, call.state.value.outcome)
        assertEquals(EncoderCleanup.PENDING, call.state.value.cleanup)
        assertFalse(result.isCompleted)
        assertEquals(ModelError.BUSY, f.call("competing").events.toList().terminal().error)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(VerificationOutcome.TIMED_OUT, result.await().outcome)
        assertEquals(EncoderCleanup.CONFIRMED, call.state.value.cleanup)
        assertFalse("generate" in f.log); assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
        f.manager.shutdown()
    }
    @Test fun verifierTimeoutDuringDecodingSignalsCancelAndPreventsLatePublication() = runTest {
        val f = Fixture(this); f.engine.generationGate = CompletableDeferred()
        val call = f.verify(); val result = async { call.events.single() }; runCurrent()
        advanceTimeBy(5_100); runCurrent()
        assertEquals(VerificationOutcome.TIMED_OUT, result.await().outcome)
        assertTrue(f.engine.cancels > 0); assertNull(call.state.value.validation)
        assertEquals(EncoderCleanup.CONFIRMED, call.state.value.cleanup)
        f.manager.shutdown()
    }
    @Test fun verifierCleanupUncertaintyCannotBecomeASuccessfulTimeoutOrAdmitRetry() = runTest {
        val f = Fixture(this); f.engine.generationGate = CompletableDeferred(); f.engine.releaseFails = true
        val call = f.verify(); val result = async { call.events.single() }; runCurrent()
        advanceTimeBy(5_100); runCurrent()
        val terminal = result.await()
        assertEquals(VerificationOutcome.TIMED_OUT, terminal.outcome)
        assertEquals(EncoderCleanup.UNCERTAIN, terminal.cleanup)
        assertEquals(ModelError.RESTART_REQUIRED, terminal.error)
        assertEquals(ModelError.RESTART_REQUIRED, f.call("retry").events.toList().terminal().error)
        f.manager.shutdown()
    }
    @Test fun verifierDeadlineIncludesCallerPackingAndQueueingBeforeCollection() = runTest {
        val f = Fixture(this); advanceTimeBy(5_001)
        val call = f.verify(submitted = 0)
        assertEquals(VerificationOutcome.TIMED_OUT, call.events.single().outcome)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun invalidVerifierOutputIsNeverPresentedAsASuccessfulAnswer() = runTest {
        val f = Fixture(this)
        val call = f.verify()
        assertEquals(VerificationOutcome.INVALID_OUTPUT, call.events.single().outcome)
        assertIs<OutputValidation.Rejected>(call.state.value.validation)
        assertEquals(EncoderCleanup.CONFIRMED, call.state.value.cleanup)
        f.manager.shutdown()
    }
    @Test fun verifierDuplicateCollectorsAndPanelCancelCannotChangeFirstOwner() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred()
        val call = f.verify(); val result = async { call.events.single() }; runCurrent()
        assertEquals(VerificationOutcome.REJECTED, call.events.single().outcome)
        assertNull(call.state.value.outcome)
        f.manager.cancel().join(); f.manager.release().join(); f.manager.load(ModelBackend.GPU).join()
        assertEquals(ModelError.BUSY, f.manager.state.value.commandError)
        assertEquals(0, f.encoder.cancels)
        call.cancel(); runCurrent()
        assertEquals(VerificationOutcome.CANCELLED, result.await().outcome)
        assertEquals(EncoderCleanup.CONFIRMED, call.state.value.cleanup)
        f.manager.shutdown()
    }
    @Test fun verifierContextMutationAndHostLossInvalidateBeforeGemma() = runTest {
        val f = Fixture(this); f.encoder.countGate = CompletableDeferred()
        val call = f.verify(); val result = async { call.events.single() }; runCurrent(); f.epoch++
        advanceTimeBy(100); runCurrent()
        assertEquals(VerificationOutcome.INTERRUPTED, result.await().outcome)
        assertEquals(0, f.engine.loads)
        val queued = f.verify(); f.manager.releaseForHostLoss().join()
        assertEquals(VerificationOutcome.INTERRUPTED, queued.events.single().outcome)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    private suspend fun TestScope.verifyCleanupRevocation(mode: String) {
        val f = Fixture(this)
        f.engine.response = "{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}"
        f.engine.releaseGate = CompletableDeferred()
        val call = f.verify(); val results = async { call.events.toList() }; runCurrent()
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, call.state.value.outcome)
        assertEquals(VerificationDelivery.PENDING, call.state.value.delivery)
        assertNull(call.state.value.validation); assertFalse(results.isCompleted)
        val hostLoss = when (mode) {
            "stop" -> { call.cancel(); null }
            "epoch" -> { f.epoch++; null }
            else -> f.manager.releaseForHostLoss()
        }
        runCurrent()
        assertEquals(ModelError.BUSY, f.call("competing").events.toList().terminal().error)
        f.engine.releaseGate!!.complete(Unit); runCurrent(); hostLoss?.join()
        val values = results.await(); assertEquals(1, values.size)
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, values.single().outcome)
        assertEquals(VerificationDelivery.REVOKED, values.single().delivery)
        assertNull(values.single().validation)
        assertEquals(EncoderCleanup.CONFIRMED, values.single().cleanup)
        f.manager.shutdown()
    }
    @Test fun verifierStopDuringCleanupRevokesDeliveryWithoutReplacingDecision() = runTest { verifyCleanupRevocation("stop") }
    @Test fun verifierEpochChangeDuringCleanupRevokesDeliveryWithoutReplacingDecision() = runTest { verifyCleanupRevocation("epoch") }
    @Test fun verifierHostLossDuringCleanupRevokesDeliveryWithoutReplacingDecision() = runTest { verifyCleanupRevocation("host") }

    @Test fun v4AllowsSlowLoadWithoutChangingFiveSecondSearchOrV3() = runTest {
        val f = Fixture(this); f.engine.loadDelay = 6_000
        f.engine.response = "{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}"
        val result = f.verify(v4 = true).events.single()
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, result.outcome)
        assertEquals(VerificationDelivery.ELIGIBLE, result.delivery)
        assertEquals(f.engine.response, result.diagnosticOutput)
        f.encoder.countDelay = 5_001
        assertEquals(ChatOutcome.TIMED_OUT, f.call().events.toList().terminal().outcome)
        f.encoder.countDelay = 0
        assertEquals(VerificationOutcome.TIMED_OUT, f.verify(testScheduler.currentTime).events.single().outcome)
        f.manager.shutdown()
    }
    @Test fun v4DeadlineAtExactBoundaryIncludesQueueTimeAndAllocatesNothing() = runTest {
        val f = Fixture(this); advanceTimeBy(30_000)
        assertEquals(VerificationOutcome.TIMED_OUT, f.verify(0, true).events.single().outcome)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun v4TimeoutDuringLoadKeepsOwnershipUntilCleanupAndBlocksPanel() = runTest {
        val f = Fixture(this); f.engine.loadDelay = 32_000
        val call = f.verify(v4 = true); val result = async { call.events.single() }; runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(VerificationOutcome.TIMED_OUT, call.state.value.outcome)
        assertFalse(result.isCompleted)
        assertEquals(ModelError.BUSY, f.call().events.toList().terminal().error)
        f.manager.cancel().join(); f.manager.load(ModelBackend.CPU).join()
        assertEquals(ModelError.BUSY, f.manager.state.value.commandError)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(VerificationOutcome.TIMED_OUT, result.await().outcome)
        assertEquals(EncoderCleanup.CONFIRMED, call.state.value.cleanup)
        assertFalse("generate" in f.log)
        f.manager.shutdown()
    }
    @Test fun v4TimelyValidationCannotPublishAfterCleanupCeiling() = runTest {
        val f = Fixture(this)
        f.engine.loadDelay = 26_000
        f.engine.response = "{\"o\":\"U\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}"
        f.engine.releaseGate = CompletableDeferred()
        val call = f.verify(v4 = true); val result = async { call.events.single() }; runCurrent()
        advanceTimeBy(26_000); runCurrent()
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, call.state.value.outcome)
        advanceTimeBy(4_000); f.engine.releaseGate!!.complete(Unit); runCurrent()
        assertEquals(VerificationOutcome.STRUCTURALLY_VALID, result.await().outcome)
        assertEquals(VerificationDelivery.REVOKED, call.state.value.delivery)
        assertEquals(ModelError.ENCODER_TIMEOUT, call.state.value.error)
        assertNull(call.state.value.validation)
        f.manager.shutdown()
    }
    @Test fun v4StopContextLossAndUncertainCleanupNeverPublishOrAdmitReplacement() = runTest {
        for (mode in listOf("stop", "epoch", "host", "uncertain")) {
            val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred()
            val call = f.verify(testScheduler.currentTime, true)
            val result = async { call.events.single() }; runCurrent()
            val host = when (mode) {
                "stop" -> { call.cancel(); null }
                "epoch" -> { f.epoch++; null }
                "uncertain" -> { f.encoder.clean = false; call.cancel(); null }
                else -> f.manager.releaseForHostLoss()
            }
            advanceTimeBy(101); runCurrent(); host?.join()
            assertEquals(VerificationDelivery.REVOKED, result.await().delivery)
            assertEquals(0, f.engine.loads)
            if (mode == "uncertain") {
                assertEquals(EncoderCleanup.UNCERTAIN, call.preparation.value.cleanup)
                assertEquals(ModelError.RESTART_REQUIRED, f.call().events.toList().terminal().error)
            }
            f.manager.shutdown()
        }
    }

    @Test fun encoderOnlyReleasesResidentGemmaAndReturnsTransientCopyWithoutGeneration() = runTest {
        val f = Fixture(this)
        f.manager.load(ModelBackend.GPU).join()
        val call = f.encode()
        val result = call.results.single()
        assertEquals(EncodingStatus.COMPLETED, result.status)
        assertEquals(100, result.values!!.size)
        assertNotSame(result.values, result.values)
        assertEquals(listOf("load", "release", "count", "encode", "cleanup"), f.log)
        assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
        f.manager.shutdown()
    }
    @Test fun encoderOnlyCancellationRetainsReservationAndPanelCannotSignalItsOwner() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred(); f.encoder.cleanupGate = CompletableDeferred()
        val call = f.encode()
        val result = async { call.results.single() }; runCurrent()
        f.manager.cancel().join(); f.manager.release().join(); f.manager.testPrompt().join()
        assertEquals(ModelError.BUSY, f.manager.state.value.commandError)
        assertEquals(0, f.encoder.cancels)
        assertEquals(EncodingStatus.REJECTED, f.encode().results.single().status)
        assertEquals(EncodingStatus.REJECTED, call.results.single().status)
        call.cancel(); assertTrue(f.encoder.cancels > 0); runCurrent()
        assertEquals(EncoderCleanup.PENDING, call.preparation.value.cleanup)
        assertEquals(ModelError.BUSY, f.encode("retry").results.single().error)
        assertFalse(result.isCompleted)
        f.encoder.cleanupGate!!.complete(Unit)
        assertEquals(EncodingStatus.CANCELLED, result.await().status)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun encoderOnlyLateEpochAndHostLossPreventAllocationAndOldStopCannotRetarget() = runTest {
        val f = Fixture(this)
        val queued = f.encode(); f.epoch++
        assertEquals(ModelError.CONTEXT_INVALIDATED, queued.results.single().error)
        val lost = f.encode(); f.manager.releaseForHostLoss().join()
        assertEquals(ModelError.CONTEXT_INVALIDATED, lost.results.single().error)
        assertEquals(0, f.encoder.encodes)
        val old = f.encode("reused")
        assertEquals(EncodingStatus.COMPLETED, old.results.single().status)
        f.encoder.encodeGate = CompletableDeferred()
        val fresh = f.encode("reused"); val active = async { fresh.results.single() }; runCurrent()
        val before = f.encoder.cancels
        old.cancel(); runCurrent()
        assertEquals(before, f.encoder.cancels); assertFalse(active.isCompleted)
        f.manager.releaseForHostLoss().join()
        assertEquals(EncodingStatus.INTERRUPTED, active.await().status)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun encoderOnlyTimeoutRetainsCleanupAndNeverReturnsLateVector() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred(); f.encoder.cleanupGate = CompletableDeferred()
        val call = f.encode(); val active = async { call.results.single() }; runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(EncoderCleanup.PENDING, call.preparation.value.cleanup)
        assertFalse(active.isCompleted)
        assertEquals(ModelError.BUSY, f.encode("retry").results.single().error)
        f.encoder.cleanupGate!!.complete(Unit)
        val result = active.await()
        assertEquals(EncodingStatus.TIMED_OUT, result.status)
        assertEquals(ModelError.ENCODER_TIMEOUT, result.error); assertNull(result.values)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun encoderOnlyUncertainCleanupQuarantinesUntilRestart() = runTest {
        val f = Fixture(this); f.encoder.clean = false
        val call = f.encode()
        val result = call.results.single()
        assertEquals(ModelError.RESTART_REQUIRED, result.error); assertNull(result.values)
        assertEquals(EncoderCleanup.UNCERTAIN, call.preparation.value.cleanup)
        assertEquals(ModelError.RESTART_REQUIRED, f.encode("retry").results.single().error)
        assertEquals(1, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun encoderOnlyClosedManagerAndOverflowHaveSingleTerminalWithoutNativeWork() = runTest {
        val f = Fixture(this); f.encoder.reject = true
        assertEquals(ModelError.ENCODER_REJECTED, f.encode().results.single().error)
        assertEquals(0, f.encoder.encodes)
        val queued = f.encode(); f.manager.shutdown()
        assertEquals(EncodingStatus.INTERRUPTED, queued.results.single().status)
        assertEquals(EncodingStatus.INTERRUPTED, f.encode("after-close").results.single().status)
        assertEquals(0, f.engine.loads)
    }
    @Test fun encoderOnlyUnavailableDoesNotMasqueradeAsCompletedEmptyResults() = runTest {
        val f = Fixture(this); f.encoder.reject = true; f.encoder.admissionError = AdmissionError.UNAVAILABLE
        val call = f.encode(); val result = call.results.single()
        assertEquals(EncodingStatus.UNAVAILABLE, result.status); assertNull(result.values)
        assertEquals(AdmissionError.UNAVAILABLE, call.preparation.value.admissionError)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }

    @Test fun residentGemmaReleasesAndEncoderCleanupCompletesBeforeGemmaAdmission() = runTest {
        val f = Fixture(this)
        f.manager.load(ModelBackend.GPU).join()
        val call = f.call()
        assertEquals(ChatOutcome.COMPLETED, call.events.toList().terminal().outcome)
        assertEquals(listOf("load", "release", "count", "encode", "cleanup", "load", "generate"), f.log)
        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
        f.manager.shutdown()
    }
    @Test fun overflowNeverInvokesNativeAndDoesNotFallBackToTextChat() = runTest {
        val f = Fixture(this); f.encoder.reject = true
        val call = f.call()
        assertEquals(ModelError.ENCODER_REJECTED, call.events.toList().terminal().error)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        assertEquals(AdmissionError.TOKEN_LIMIT, call.preparation.value.admissionError)
        f.manager.shutdown()
    }
    @Test fun closedManagerReturnsOneInterruptedOutcomeWithoutNativeAdmission() = runTest {
        val f = Fixture(this)
        val queued = f.call()
        f.manager.shutdown()
        assertEquals(ChatOutcome.INTERRUPTED, queued.events.toList().terminal().outcome)
        assertEquals(ChatOutcome.INTERRUPTED, f.call("after-shutdown").events.toList().terminal().outcome)
        assertEquals(0, f.engine.loads); assertEquals(0, f.encoder.encodes)
    }
    @Test fun preparedHandoffRejectsChangedOutputReservation() = runTest {
        val f = Fixture(this)
        val call = f.manager.prepareChat(ChatRequest("wrong-budget",
            listOf(ChatMessage(ChatRole.USER, "Synthetic")), outputTokens = 256),
            EncoderInput("Synthetic source"), f.encoder, 0) { 0 }
        assertEquals(ModelError.CONTEXT_LIMIT, call.events.toList().terminal().error)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun panelAndDuplicateIdsCannotAcquireOrCancelForegroundReservation() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred()
        val call = f.call()
        val running = async { call.events.toList() }; runCurrent()
        f.manager.load(ModelBackend.CPU).join(); f.manager.testPrompt().join()
        f.manager.verify().join(); f.manager.install(ModelSource { error("Should not open") }).join()
        f.manager.cancel().join(); f.manager.release().join()
        assertFalse(running.isCompleted); assertEquals(0, f.encoder.cancels)
        assertEquals(ModelError.BUSY, f.call().events.toList().terminal().error)
        assertEquals(ModelError.BUSY, call.events.toList().terminal().error)
        assertFalse(running.isCompleted)
        f.encoder.encodeGate!!.complete(Unit)
        assertEquals(ChatOutcome.COMPLETED, running.await().terminal().outcome)
        f.manager.shutdown()
    }
    @Test fun idlePanelCancelCannotRetargetForegroundThatStartsBeforeDeferredSignal() = runTest {
        val f = Fixture(this, SerialDispatcher(StandardTestDispatcher(testScheduler)))
        f.encoder.encodeGate = CompletableDeferred()
        val call = f.call()
        // Queue the flow producer before Cancel: its worker starts after the panel check,
        // but before the separately dispatched cancellation helper.
        val running = async(start = CoroutineStart.UNDISPATCHED) { call.events.toList() }
        val cancel = f.manager.cancel()
        runCurrent(); cancel.join()
        assertEquals(1, f.encoder.encodes)
        assertFalse(running.isCompleted)
        assertEquals(0, f.encoder.cancels); assertEquals(0, f.engine.cancels)
        f.encoder.encodeGate!!.complete(Unit)
        assertEquals(ChatOutcome.COMPLETED, running.await().terminal().outcome)
        f.manager.shutdown()
    }
    @Test fun pendingNativeCancelSignalBlocksReplacementEvenAfterOldJobFinishes() = runTest {
        val f = Fixture(this)
        f.manager.load(ModelBackend.GPU).join()
        f.engine.generationGate = CompletableDeferred()
        val benchmark = f.manager.testPrompt(); runCurrent()
        f.engine.cancelGate = CompletableDeferred()
        val cancel = f.manager.cancel(); runCurrent()
        try {
            assertTrue(benchmark.isCompleted)
            assertFalse(cancel.isCompleted)
            assertEquals(1, f.engine.cancels)
            val retry = async { f.call("replacement").events.toList() }; runCurrent()
            assertTrue(retry.isCompleted, "Replacement entered native work during the old cancel signal")
            assertEquals(ModelError.BUSY, retry.await().terminal().error)
            assertEquals(0, f.encoder.encodes)
            val release = f.manager.releaseForHostLoss(); runCurrent()
            assertFalse(release.isCompleted, "Privileged release returned while a native signal was pending")
            f.engine.cancelGate!!.complete(Unit); cancel.join(); release.join()
            assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
            f.engine.generationGate = null
            assertEquals(ChatOutcome.COMPLETED, f.call("recovered").events.toList().terminal().outcome)
        } finally {
            f.engine.cancelGate?.complete(Unit); f.engine.generationGate?.complete(Unit)
            cancel.join(); f.manager.shutdown()
        }
    }
    @Test fun stopSignalsImmediatelyAndKeepsOwnershipWhileCleanupIsPending() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred(); f.encoder.cleanupGate = CompletableDeferred()
        val call = f.call(); val running = async { call.events.toList() }; runCurrent()
        call.cancel()
        assertTrue(f.encoder.cancels > 0)
        runCurrent()
        assertEquals(EncoderCleanup.PENDING, call.preparation.value.cleanup)
        assertEquals(ModelError.BUSY, f.call("retry").events.toList().terminal().error)
        assertFalse(running.isCompleted); assertEquals(0, f.engine.loads)
        f.encoder.cleanupGate!!.complete(Unit)
        assertEquals(ChatOutcome.CANCELLED, running.await().terminal().outcome)
        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
        assertEquals(ChatOutcome.COMPLETED, f.call("recovered").also { f.encoder.encodeGate = null }.events.toList().terminal().outcome)
        f.manager.shutdown()
    }
    @Test fun cancellationDuringCountingAndBeforeCollectionCannotAllocate() = runTest {
        val f = Fixture(this)
        val queued = f.call(); queued.cancel()
        assertEquals(ChatOutcome.CANCELLED, queued.events.toList().terminal().outcome)
        f.encoder.countGate = CompletableDeferred()
        val call = f.call("counting"); val running = async { call.events.toList() }; runCurrent()
        call.cancel(); runCurrent()
        assertEquals(ChatOutcome.CANCELLED, running.await().terminal().outcome)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun contextMutationDuringAdmissionInvalidatesQueuedEncoding() = runTest {
        val f = Fixture(this); f.encoder.countGate = CompletableDeferred()
        val call = f.call(); val running = async { call.events.toList() }; runCurrent()
        f.epoch++
        advanceTimeBy(100); runCurrent()
        assertEquals(ModelError.CONTEXT_INVALIDATED, running.await().terminal().error)
        assertEquals(0, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun deadlineIncludesQueueingAndDoesNotAllowLateGemmaAdmission() = runTest {
        val f = Fixture(this)
        val queued = f.call()
        advanceTimeBy(5_001)
        assertEquals(ModelError.ENCODER_TIMEOUT, queued.events.toList().terminal().error)
        assertEquals(0, f.encoder.encodes)
        f.encoder.countDelay = 4_500; f.encoder.cleanupDelay = 1_000
        val call = f.call("late-cleanup")
        val running = async { call.events.toList() }; runCurrent()
        advanceTimeBy(5_600); runCurrent()
        assertEquals(ModelError.ENCODER_TIMEOUT, running.await().terminal().error)
        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun uncertainCleanupQuarantinesSubsequentNativeAdmission() = runTest {
        val f = Fixture(this); f.encoder.clean = false
        val call = f.call()
        assertEquals(ModelError.RESTART_REQUIRED, call.events.toList().terminal().error)
        assertEquals(EncoderCleanup.UNCERTAIN, call.preparation.value.cleanup)
        assertEquals(ModelError.RESTART_REQUIRED, f.call("retry").events.toList().terminal().error)
        assertEquals(1, f.encoder.encodes); assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun staleCompletionWithSamePrintableIdCannotOwnHandoff() = runTest {
        val f = Fixture(this); f.encoder.foreignResult = true
        assertEquals(ModelError.ENCODER_FAILED, f.call().events.toList().terminal().error)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun privilegedHostLossCancelsWherePanelReleaseCannot() = runTest {
        val f = Fixture(this); f.encoder.encodeGate = CompletableDeferred()
        val call = f.call(); val running = async { call.events.toList() }; runCurrent()
        f.manager.releaseForHostLoss().join()
        assertEquals(ChatOutcome.INTERRUPTED, running.await().terminal().outcome)
        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
        assertEquals(0, f.engine.loads)
        f.manager.shutdown()
    }
    @Test fun hostLossInvalidatesUncollectedRequestsAndOldStopCannotCancelReplacement() = runTest {
        val f = Fixture(this)
        val queued = f.call()
        f.manager.releaseForHostLoss().join()
        assertEquals(ModelError.CONTEXT_INVALIDATED, queued.events.toList().terminal().error)
        assertEquals(0, f.encoder.encodes)
        val old = f.call("reused-label")
        assertEquals(ChatOutcome.COMPLETED, old.events.toList().terminal().outcome)
        f.encoder.encodeGate = CompletableDeferred()
        val replacement = f.call("reused-label")
        val running = async { replacement.events.toList() }; runCurrent()
        val before = f.encoder.cancels
        old.cancel(); runCurrent()
        assertFalse(running.isCompleted); assertEquals(before, f.encoder.cancels)
        replacement.cancel(); runCurrent()
        assertEquals(ChatOutcome.CANCELLED, running.await().terminal().outcome)
        f.manager.shutdown()
    }
}
