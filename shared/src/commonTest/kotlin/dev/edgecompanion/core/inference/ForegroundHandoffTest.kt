package dev.edgecompanion.core.inference

import dev.edgecompanion.core.inference.embedding.*
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
        var loads = 0
        var cancels = 0
        var cancelGate: CompletableDeferred<Unit>? = null
        var generationGate: CompletableDeferred<Unit>? = null
        override suspend fun load(config: ModelConfig): Result<Unit> {
            assertFalse(encoder.resident, "Native allocations overlapped")
            assertEquals(2048, config.contextTokens); assertEquals(512, config.maxOutputTokens)
            loads++; log += "load"; return Result.success(Unit)
        }
        override fun generate(request: GenerationRequest) = flow {
            log += "generate"; emit(GenerationEvent.Token("Synthetic plain answer"))
            generationGate?.await(); emit(GenerationEvent.Finished())
        }
        override suspend fun cancel() { cancels++; cancelGate?.await() }
        override suspend fun release() { log += "release" }
    }
    private class Assets : ModelAssets {
        override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
        override suspend fun verify() = "synthetic.litertlm"
        override suspend fun install(source: ModelSource) = Unit
    }
    private class Fixture(scope: TestScope, dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)) {
        val log = mutableListOf<String>()
        val encoder = Encoder(log)
        val engine = Engine(log, encoder)
        var epoch = 0L
        val manager = ModelManager(Assets(), engine, dispatcher,
            { ResourceSnapshot(12L shl 30, 6L shl 30, false, 0) }, { scope.testScheduler.currentTime }, true)
        fun call(id: String = "synthetic") = manager.prepareChat(
            ChatRequest(id, listOf(ChatMessage(ChatRole.USER, "Answer this synthetic question."))),
            EncoderInput("Synthetic source input"), encoder, epoch) { epoch }
        fun encode(id: String = "synthetic-encode") = manager.encode(id, EncoderInput("Synthetic source input"), encoder, epoch) { epoch }
    }
    private fun List<ChatEvent>.terminal(): ChatEvent.Terminal {
        assertEquals(1, count { it is ChatEvent.Terminal })
        return last() as ChatEvent.Terminal
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
            f.engine.cancelGate!!.complete(Unit); cancel.join()
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
