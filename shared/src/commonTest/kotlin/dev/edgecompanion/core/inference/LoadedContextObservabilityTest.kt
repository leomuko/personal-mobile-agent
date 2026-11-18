package dev.edgecompanion.core.inference

import dev.edgecompanion.core.inference.embedding.*
import dev.edgecompanion.core.inference.verification.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoadedContextObservabilityTest {
    private class Assets : ModelAssets {
        override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
        override suspend fun verify() = "synthetic.litertlm"
        override suspend fun install(source: ModelSource) = Unit
    }
    private class Engine : LocalLlmEngine {
        var loadGate: CompletableDeferred<Unit>? = null
        var releaseGate: CompletableDeferred<Unit>? = null
        var generateGate: CompletableDeferred<Unit>? = null
        var loadFails = false
        var releaseFails = false
        var afterLoad: () -> Unit = {}
        override suspend fun load(config: ModelConfig): Result<Unit> {
            loadGate?.await()
            if (loadFails) return Result.failure(ModelException(ModelError.LOAD_FAILED))
            afterLoad()
            return Result.success(Unit)
        }
        override fun generate(request: GenerationRequest) = flow {
            emit(GenerationEvent.Token("Synthetic"))
            generateGate?.await()
            emit(GenerationEvent.Finished(1, 8))
        }
        override suspend fun cancel() = Unit
        override suspend fun release() {
            releaseGate?.await()
            if (releaseFails) throw ModelException(ModelError.RELEASE_FAILED)
        }
    }
    private class Encoder : ForegroundEncoder {
        private val issuer = Any()
        override suspend fun admit(input: EncoderInput) =
            EncoderAdmission.Accepted(EncoderAdmissionReceipt(issuer, input, 4))
        override fun accepts(receipt: EncoderAdmissionReceipt, input: EncoderInput) =
            receipt.issuer === issuer && receipt.input == input
        override suspend fun encode(request: EncoderRequest) = EncoderResult.Completed(request.lease, List(100) { 0.1f })
        override fun cancel(lease: EncoderLease) = Unit
        override suspend fun cleanup(lease: EncoderLease) = true
    }
    private class Fixture(scope: TestScope) {
        val engine = Engine()
        val points = mutableListOf<MemoryTracePoint>()
        val statesAtEvent = mutableListOf<ModelRuntimeState>()
        var resources = ResourceSnapshot(12L shl 30, 6L shl 30, false, 0)
        var telemetryFails = false
        val manager: ModelManager = ModelManager(Assets(), engine, StandardTestDispatcher(scope.testScheduler),
            { resources }, { scope.testScheduler.currentTime }, true, idleMs = 10, memoryTelemetry = {
                points += it
                statesAtEvent += managerState()
                if (telemetryFails) error("Synthetic telemetry failure")
            })
        // Invoked only after construction by the injected telemetry callback.
        private fun managerState(): ModelRuntimeState = manager.state.value
        fun request(id: String, profile: ChatContextProfile = ChatContextProfile.STANDARD) =
            GemmaChatBudget.build("Synthetic question", emptyList(), id, profile)
        fun sizes(context: Int?) {
            assertEquals(context, manager.state.value.loadedContextTokens)
            assertEquals(context?.let { 512 }, manager.state.value.loadedOutputTokens)
            // Assertions run outside recordMemory, whose telemetry-failure containment is intentional.
            points.zip(statesAtEvent).forEach { (point, state) ->
                assertEquals(state.loadedContextTokens, point.contextTokensAtEvent)
                assertEquals(state.loadedOutputTokens, point.outputTokensAtEvent)
                assertEquals(point.engineOwned, point.contextTokensAtEvent != null)
                assertEquals(point.engineOwned, point.outputTokensAtEvent != null)
            }
        }
    }

    @Test fun panelAndChatReloadPublishOnlyTheirResidentSizes() = runTest {
        val f = Fixture(this)
        try {
            f.sizes(null)
            f.manager.load(ModelBackend.GPU).join(); f.sizes(2048)
            for (profile in listOf(ChatContextProfile.EXTENDED_4096, ChatContextProfile.EXTENDED_8192)) {
                val events = f.manager.generateChat(f.request(profile.name, profile)).toList()
                assertEquals(ChatOutcome.COMPLETED, (events.last() as ChatEvent.Terminal).outcome)
                f.sizes(profile.contextTokens)
            }
            f.manager.testPrompt().join()
            assertEquals(ModelError.BUSY, f.manager.state.value.commandError); f.sizes(8192)
            f.manager.load(ModelBackend.GPU).join(); f.sizes(2048)
            f.manager.testPrompt().join(); f.sizes(2048)
            f.manager.release().join(); f.sizes(null)
            assertEquals(listOf(4096, 8192, 2048), f.points.filter { it.stage == MemoryStage.PREFILL }
                .map { it.contextTokensAtEvent })
            assertTrue(f.points.filter { it.stage in setOf(MemoryStage.BASELINE, MemoryStage.LOADING, MemoryStage.POST_RELEASE) }
                .all { it.contextTokensAtEvent == null && it.outputTokensAtEvent == null })
        } finally { f.manager.shutdown() }
    }

    @Test fun inFlightLoadAndReleaseExposeSizesOnlyAfterConfirmedTransitions() = runTest {
        val f = Fixture(this)
        try {
            f.engine.loadGate = CompletableDeferred()
            val load = f.manager.load(ModelBackend.GPU); runCurrent()
            assertEquals(RuntimeStatus.LOADING, f.manager.state.value.status); f.sizes(null)
            f.engine.loadGate!!.complete(Unit); load.join(); f.sizes(2048)
            f.engine.releaseGate = CompletableDeferred()
            val release = f.manager.release(); runCurrent()
            assertEquals(RuntimeStatus.RELEASING, f.manager.state.value.status); f.sizes(2048)
            f.engine.releaseGate!!.complete(Unit); release.join(); f.sizes(null)
        } finally { f.engine.loadGate?.complete(Unit); f.engine.releaseGate?.complete(Unit); f.manager.shutdown() }
    }

    @Test fun cancelledNativeLoadPublishesItsSizesUntilIdleCleanup() = runTest {
        val f = Fixture(this)
        try {
            f.engine.loadGate = CompletableDeferred()
            val reply = async { f.manager.generateChat(f.request("cancel", ChatContextProfile.EXTENDED_8192)).toList() }
            runCurrent(); f.sizes(null)
            f.manager.cancelChat("cancel")
            f.engine.loadGate!!.complete(Unit)
            assertEquals(ChatOutcome.CANCELLED, (reply.await().last() as ChatEvent.Terminal).outcome)
            f.sizes(8192)
            advanceTimeBy(10); runCurrent(); f.sizes(null)
            assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
        } finally { f.engine.loadGate?.complete(Unit); f.manager.shutdown() }
    }

    @Test fun cancelledReloadReleaseClearsTheOldSizesWithoutPublishingTheRequestedSizes() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            f.engine.releaseGate = CompletableDeferred()
            val reply = async { f.manager.generateChat(f.request("reload", ChatContextProfile.EXTENDED_8192)).toList() }
            runCurrent()
            assertEquals(RuntimeStatus.RELEASING, f.manager.state.value.status); f.sizes(2048)
            f.manager.cancelChat("reload")
            f.engine.releaseGate!!.complete(Unit)
            assertEquals(ChatOutcome.CANCELLED, (reply.await().last() as ChatEvent.Terminal).outcome)
            f.sizes(null)
            assertTrue(f.points.none { it.contextTokensAtEvent == 8192 })
        } finally { f.engine.releaseGate?.complete(Unit); f.manager.shutdown() }
    }

    @Test fun generatingAndCancellationEventsKeepTheActualResidentSizes() = runTest {
        val f = Fixture(this)
        try {
            f.engine.generateGate = CompletableDeferred()
            val reply = async { f.manager.generateChat(f.request("stream", ChatContextProfile.EXTENDED_8192)).toList() }
            runCurrent()
            assertEquals(RuntimeStatus.GENERATING, f.manager.state.value.status); f.sizes(8192)
            f.manager.cancelChat("stream")
            assertEquals(ChatOutcome.CANCELLED, (reply.await().last() as ChatEvent.Terminal).outcome)
            f.sizes(8192)
            for (stage in listOf(MemoryStage.PREFILL, MemoryStage.DECODING, MemoryStage.CANCELLING)) {
                val point = f.points.first { it.stage == stage }
                assertEquals(8192, point.contextTokensAtEvent); assertEquals(512, point.outputTokensAtEvent)
            }
            f.manager.release().join(); f.sizes(null)
        } finally { f.engine.generateGate?.complete(Unit); f.manager.shutdown() }
    }

    @Test fun idleAndHostLossReleaseClearStateAndTraceSizes() = runTest {
        for (hostLoss in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.manager.generateChat(f.request("release", ChatContextProfile.EXTENDED_4096)).toList()
                f.sizes(4096)
                if (hostLoss) f.manager.releaseForHostLoss().join() else { advanceTimeBy(10); runCurrent() }
                f.sizes(null)
                assertEquals(MemoryStage.POST_RELEASE, f.points.last().stage)
                assertNull(f.points.last().contextTokensAtEvent); assertNull(f.points.last().outputTokensAtEvent)
            } finally { f.manager.shutdown() }
        }
    }

    @Test fun failedLoadAndPostLoadResourceRejectionLeaveNoResidentSizes() = runTest {
        for (nativeFailure in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.engine.loadFails = nativeFailure
                if (!nativeFailure) f.engine.afterLoad = { f.resources = f.resources.copy(lowMemory = true) }
                val events = f.manager.generateChat(f.request("failed", ChatContextProfile.EXTENDED_8192)).toList()
                assertEquals(if (nativeFailure) ModelError.LOAD_FAILED else ModelError.LOW_MEMORY,
                    (events.last() as ChatEvent.Terminal).error)
                f.sizes(null)
                if (!nativeFailure) {
                    assertEquals(8192, f.points.first { it.stage == MemoryStage.LOADED_IDLE }.contextTokensAtEvent)
                    assertNull(f.points.last().contextTokensAtEvent)
                }
            } finally { f.manager.shutdown() }
        }
    }

    @Test fun uncertainCleanupRetainsLastOwnedSizesWithoutClaimingAnUnload() = runTest {
        val f = Fixture(this)
        f.manager.generateChat(f.request("uncertain", ChatContextProfile.EXTENDED_8192)).toList()
        f.engine.releaseFails = true
        f.manager.release().join()
        assertEquals(RuntimeStatus.FAILED, f.manager.state.value.status)
        assertEquals(ModelError.RESTART_REQUIRED, f.manager.state.value.error); f.sizes(8192)
        assertTrue(f.points.none { it.stage == MemoryStage.POST_RELEASE })
        f.manager.shutdown()
    }

    @Test fun telemetryFailureDoesNotRestoreSizesAfterConfirmedCleanup() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join(); f.sizes(2048)
            f.telemetryFails = true
            f.manager.release().join(); f.sizes(null)
            assertTrue(f.manager.state.value.telemetryFailed)
        } finally { f.manager.shutdown() }
    }

    @Test fun preparedAndVerificationPathsKeepStandardSizesAndClearOnCleanup() = runTest {
        val f = Fixture(this)
        try {
            f.manager.generateChat(f.request("extended", ChatContextProfile.EXTENDED_4096)).toList()
            val events = f.manager.prepareChat(f.request("prepared"), EncoderInput("Synthetic input"), Encoder(), 0) { 0 }.events.toList()
            assertEquals(ChatOutcome.COMPLETED, (events.last() as ChatEvent.Terminal).outcome); f.sizes(2048)
            val context = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", "Synthetic", 0, 9)))
            for (v4 in listOf(false, true)) {
                val start = f.points.size
                val call = if (v4) f.manager.verifyEvidenceV4("v4", "Synthetic", context,
                    EncoderInput("Synthetic"), Encoder(), testScheduler.currentTime, 0) { 0 }
                else f.manager.verifyEvidence("v3", "Synthetic", context,
                    EncoderInput("Synthetic"), Encoder(), testScheduler.currentTime, 0) { 0 }
                val result = call.events.single()
                // The deliberately non-JSON fake answer must reach inference and strict validation.
                assertEquals(VerificationOutcome.INVALID_OUTPUT, result.outcome)
                assertEquals(EncoderCleanup.CONFIRMED, result.cleanup)
                f.sizes(null)
                val current = f.points.drop(start)
                for (stage in listOf(MemoryStage.BASELINE, MemoryStage.LOADING)) {
                    val point = current.single { it.stage == stage }
                    assertNull(point.contextTokensAtEvent); assertNull(point.outputTokensAtEvent)
                }
                for (stage in listOf(MemoryStage.PREFILL, MemoryStage.DECODING)) {
                    val point = current.single { it.stage == stage }
                    assertEquals(2048, point.contextTokensAtEvent); assertEquals(512, point.outputTokensAtEvent)
                }
                assertEquals(MemoryStage.POST_RELEASE, current.last().stage)
                assertNull(current.last().contextTokensAtEvent); assertNull(current.last().outputTokensAtEvent)
            }
        } finally { f.manager.shutdown() }
    }
}
