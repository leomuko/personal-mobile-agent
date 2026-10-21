package dev.edgecompanion.core.inference

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {
    private val healthy = ResourceSnapshot(12L shl 30, 6L shl 30, false, 0)
    private class Assets : ModelAssets {
        override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
        var path: String? = "verified.litertlm"
        override suspend fun verify() = path
        override suspend fun install(source: ModelSource) = Unit
    }
    private class Engine : LocalLlmEngine {
        var loads = 0
        var releases = 0
        var requests = 0
        var cancels = 0
        var nativeStopped = CompletableDeferred<Unit>()
        var hang = true
        var releaseGate: CompletableDeferred<Unit>? = null
        var releaseFailure = false
        var cancelTimeout = false
        var loadGate: CompletableDeferred<Unit>? = null
        var afterLoad: () -> Unit = {}
        override suspend fun load(config: ModelConfig): Result<Unit> { loads++; loadGate?.await(); afterLoad(); return Result.success(Unit) }
        override fun generate(request: GenerationRequest) = flow {
            requests++
            nativeStopped = CompletableDeferred()
            emit(GenerationEvent.Token("Synthetic"))
            if (hang) try { awaitCancellation() }
            finally { withContext(NonCancellable) {
                nativeStopped.await()
                if (cancelTimeout) throw ModelException(ModelError.CANCEL_TIMEOUT)
            } }
            else emit(GenerationEvent.Finished(1, 8))
        }
        override suspend fun cancel() { cancels++; nativeStopped.complete(Unit) }
        override suspend fun release() {
            releases++
            releaseGate?.await()
            if (releaseFailure) throw ModelException(ModelError.RELEASE_FAILED)
        }
    }
    private fun TestScope.manager(engine: Engine = Engine(), assets: Assets = Assets(),
        resources: () -> ResourceSnapshot = { healthy }, enabled: Boolean = true,
        telemetry: suspend (MemoryTracePoint) -> Unit = {}, policy: MemoryPolicy = MemoryPolicies.Current) =
        ModelManager(assets, engine, StandardTestDispatcher(testScheduler), resources,
            { testScheduler.currentTime }, enabled, idleMs = 60_000, memoryTelemetry = telemetry, memoryPolicy = policy)

    @Test fun pressureExperimentAllowsLoadAndChatBelowBaselineFloor() = runTest {
        var snapshot = healthy.copy(availableMemoryBytes = 2L shl 30, pressureThresholdBytes = 500L shl 20)
        val engine = Engine().apply {
            hang = false
            afterLoad = { snapshot = snapshot.copy(availableMemoryBytes = 1L shl 30) }
        }
        val manager = manager(engine, resources = { snapshot }, policy = MemoryPolicies.AndroidPressureTest)
        manager.load(ModelBackend.GPU).join()
        assertEquals(RuntimeStatus.LOADED, manager.state.value.status)
        manager.testPrompt().join()
        assertEquals(1, engine.requests)
        val terminal = manager.generateChat(ChatRequest("pressure", listOf(ChatMessage(ChatRole.USER, "Hello"))))
            .toList().last() as ChatEvent.Terminal
        assertEquals(ChatOutcome.COMPLETED, terminal.outcome)
        manager.release().join()
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        assertEquals(1, engine.releases)
        manager.shutdown()
    }
    @Test fun pressureExperimentImmediatelyInterruptsAndReleasesOnOsPressure() = runTest {
        var snapshot = healthy.copy(availableMemoryBytes = 1L shl 30, pressureThresholdBytes = 500L shl 20)
        val engine = Engine()
        val manager = manager(engine, resources = { snapshot }, policy = MemoryPolicies.AndroidPressureTest)
        val events = async { manager.generateChat(ChatRequest("pressure", listOf(ChatMessage(ChatRole.USER, "Hello")))).toList() }
        runCurrent()
        assertEquals(RuntimeStatus.GENERATING, manager.state.value.status)
        advanceTimeBy(5_000); runCurrent()
        assertFalse(events.isCompleted)
        snapshot = snapshot.copy(lowMemory = true)
        advanceTimeBy(1_000); runCurrent()
        val terminal = events.await().last() as ChatEvent.Terminal
        assertEquals(ChatOutcome.INTERRUPTED, terminal.outcome)
        assertEquals(ModelError.LOW_MEMORY, terminal.error)
        assertEquals(1, engine.cancels); assertEquals(1, engine.releases)
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.shutdown()
    }

    @Test fun manifestUsesLongAndExplicitContext() {
        val manifest = ModelCatalog.gemma4
        assertTrue(manifest.bytes > Int.MAX_VALUE)
        assertEquals(2588147712L + ModelManifest.STORAGE_RESERVE_BYTES, manifest.requiredFreeBytes)
        assertEquals(2048, manifest.configuredContextTokens)
        assertFailsWith<IllegalArgumentException> { manifest.copy(fileName = "../escape.litertlm") }
        assertFailsWith<IllegalArgumentException> { manifest.copy(sha256 = "bad") }
        assertFailsWith<IllegalArgumentException> { manifest.copy(bytes = Long.MAX_VALUE) }
    }
    @Test fun postLoadRejectionClosesBeforePublishingTerminalAndAllowsCleanRetry() = runTest {
        var snapshot = healthy
        val engine = Engine().apply { hang = false; afterLoad = { snapshot = healthy.copy(availableMemoryBytes = 2L shl 30) } }
        val manager = manager(engine, resources = { snapshot })
        val request = ChatRequest("memory", listOf(ChatMessage(ChatRole.USER, "Hello")), ModelBackend.GPU)
        val terminal = manager.generateChat(request).toList().single() as ChatEvent.Terminal
        assertEquals(ModelError.LOW_MEMORY, terminal.error)
        assertEquals(1, engine.loads); assertEquals(1, engine.releases); assertEquals(0, engine.requests)
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        snapshot = healthy; engine.afterLoad = {}
        assertEquals(ChatOutcome.COMPLETED, (manager.generateChat(request.copy(id = "retry")).toList().last() as ChatEvent.Terminal).outcome)
        manager.shutdown()
        assertEquals(2, engine.releases)
    }
    @Test fun memoryStagesCarryOwnershipAndTelemetryFailureCannotPoisonClosedEngine() = runTest {
        val points = mutableListOf<MemoryTracePoint>()
        val engine = Engine().apply { hang = false }
        val manager = manager(engine, telemetry = {
            points += it
            if (it.stage == MemoryStage.POST_RELEASE) error("Synthetic diagnostic failure")
        })
        manager.generateChat(ChatRequest("trace", listOf(ChatMessage(ChatRole.USER, "Hello")))).toList()
        manager.release().join()
        assertEquals(listOf(MemoryStage.BASELINE, MemoryStage.LOADING, MemoryStage.LOADED_IDLE,
            MemoryStage.PREFILL, MemoryStage.DECODING, MemoryStage.LOADED_IDLE, MemoryStage.POST_RELEASE), points.map { it.stage })
        assertFalse(points.first().engineOwned); assertTrue(points[2].engineOwned); assertFalse(points.last().engineOwned)
        assertTrue(points.all { it.policyId == MemoryPolicies.Current.id && it.backend == ModelBackend.GPU })
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        assertTrue(manager.state.value.telemetryFailed)
        manager.shutdown(); assertEquals(1, engine.releases)
    }
    @Test fun retryCannotEnterWhileRejectedLoadCleanupIsPending() = runTest {
        var snapshot = healthy
        val engine = Engine().apply { afterLoad = { snapshot = healthy.copy(lowMemory = true) }; releaseGate = CompletableDeferred() }
        val manager = manager(engine, resources = { snapshot })
        val request = ChatRequest("first", listOf(ChatMessage(ChatRole.USER, "Hello")))
        val first = async { manager.generateChat(request).toList() }
        runCurrent()
        assertEquals(RuntimeStatus.RELEASING, manager.state.value.status)
        val retry = manager.generateChat(request.copy(id = "retry")).toList().single() as ChatEvent.Terminal
        assertEquals(ModelError.BUSY, retry.error)
        assertFalse(first.isCompleted); assertEquals(1, engine.loads)
        engine.releaseGate!!.complete(Unit); first.await()
        manager.shutdown(); assertEquals(1, engine.releases)
    }
    @Test fun failedOrTimedOutCleanupQuarantinesRuntimeWithoutDoubleClose() = runTest {
        for (timeout in listOf(false, true)) {
            var snapshot = healthy
            val engine = Engine().apply {
                afterLoad = { snapshot = healthy.copy(lowMemory = true) }
                releaseFailure = !timeout
                if (timeout) releaseGate = CompletableDeferred()
            }
            val manager = manager(engine, resources = { snapshot })
            val result = async { manager.generateChat(ChatRequest("failed", listOf(ChatMessage(ChatRole.USER, "Hello")))).toList() }
            runCurrent()
            if (timeout) advanceTimeBy(5_001)
            val terminal = result.await().last() as ChatEvent.Terminal
            assertEquals(ModelError.RESTART_REQUIRED, terminal.error)
            assertEquals(ModelError.RELEASE_FAILED, manager.state.value.cleanupError)
            snapshot = healthy
            manager.load(ModelBackend.GPU).join()
            assertEquals(ModelError.RESTART_REQUIRED, manager.state.value.error)
            manager.shutdown(); assertEquals(1, engine.loads); assertEquals(1, engine.releases)
        }
    }
    @Test fun manualLoadRejectionAndConcurrentCancelReleaseCloseExactlyOnce() = runTest {
        var snapshot = healthy
        val engine = Engine().apply { afterLoad = { snapshot = healthy.copy(lowMemory = true) }; releaseGate = CompletableDeferred() }
        val manager = manager(engine, resources = { snapshot })
        val loading = manager.load(ModelBackend.GPU); runCurrent()
        val releasing = manager.release(); manager.cancel().join(); runCurrent()
        assertFalse(loading.isCompleted); assertFalse(releasing.isCompleted)
        engine.releaseGate!!.complete(Unit)
        loading.join(); releasing.join()
        assertEquals(1, engine.releases); assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.shutdown(); assertEquals(1, engine.releases)
    }
    @Test fun chatProducesCorrelatedTerminalAndRejectsConcurrentBenchmark() = runTest {
        val engine = Engine(); val manager = manager(engine)
        val request = ChatRequest("chat", listOf(ChatMessage(ChatRole.USER, "Hello")), ModelBackend.CPU)
        val events = mutableListOf<ChatEvent>()
        val chat = launch { manager.generateChat(request).collect { events += it } }
        runCurrent()
        manager.testPrompt().join()
        assertEquals(ModelError.BUSY, manager.state.value.commandError)
        assertEquals(1, engine.requests)
        manager.cancelChat("chat")
        chat.join()
        assertEquals(ChatOutcome.CANCELLED, (events.last() as ChatEvent.Terminal).outcome)
        assertTrue(events.all { it.id == "chat" })
        manager.shutdown()
    }
    @Test fun chatSuccessIsExplicitAndOversizedInputNeverLoads() = runTest {
        val engine = Engine().apply { hang = false }; val manager = manager(engine)
        val oversized = manager.generateChat(ChatRequest("large", listOf(ChatMessage(ChatRole.USER, "x".repeat(1313))))).toList()
        assertEquals(ChatOutcome.REJECTED, (oversized.single() as ChatEvent.Terminal).outcome)
        assertEquals(0, engine.loads)
        val events = manager.generateChat(ChatRequest("valid", listOf(ChatMessage(ChatRole.USER, "Hello")), ModelBackend.CPU)).toList()
        assertEquals(ChatOutcome.COMPLETED, (events.last() as ChatEvent.Terminal).outcome)
        assertEquals(1, events.count { it is ChatEvent.Terminal })
        manager.shutdown()
    }
    @Test fun cancelDuringLoadPublishesOwnershipAndNeverStartsGeneration() = runTest {
        val engine = Engine().apply { loadGate = CompletableDeferred() }; val manager = manager(engine)
        val events = mutableListOf<ChatEvent>()
        val chat = launch { manager.generateChat(ChatRequest("loading", listOf(ChatMessage(ChatRole.USER, "Hello")), ModelBackend.CPU))
            .collect { events += it } }
        runCurrent()
        assertEquals(RuntimeStatus.LOADING, manager.state.value.status)
        manager.cancelChat("loading")
        engine.loadGate!!.complete(Unit)
        chat.join()
        assertEquals(0, engine.requests)
        assertEquals(ChatOutcome.CANCELLED, (events.last() as ChatEvent.Terminal).outcome)
        manager.release().join()
        assertEquals(1, engine.releases)
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.shutdown()
    }
    @Test fun contextIncludesOutputAndCannotOverflow() {
        requireContextBudget(1536, 512, 2048)
        assertFailsWith<ModelException> { requireContextBudget(1537, 512, 2048) }
        assertFailsWith<ModelException> { requireContextBudget(Int.MAX_VALUE, Int.MAX_VALUE, 2048) }
    }
    @Test fun missingAssetNeverCallsNativeLoad() = runTest {
        val engine = Engine(); val manager = manager(engine, Assets().apply { path = null })
        manager.load(ModelBackend.CPU).join()
        assertEquals(ModelError.NOT_INSTALLED, manager.state.value.error)
        assertEquals(0, engine.loads)
        manager.shutdown()
    }
    @Test fun duplicateLoadAndGenerationAreRejected() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join(); manager.load(ModelBackend.CPU).join()
        assertEquals(1, engine.loads)
        manager.testPrompt(); runCurrent(); manager.testPrompt().join()
        assertEquals(1, engine.requests)
        assertEquals(RuntimeStatus.GENERATING, manager.state.value.status)
        assertEquals(ModelError.BUSY, manager.state.value.commandError)
        manager.verify().join()
        assertEquals(ModelError.BUSY, manager.state.value.commandError)
        assertEquals(RuntimeStatus.GENERATING, manager.state.value.status)
        manager.shutdown()
    }
    @Test fun importWhileLoadedRejectsCommandWithoutFailingRuntime() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        manager.install(ModelSource { error("Rejected import must not open a source") }).join()
        assertEquals(RuntimeStatus.LOADED, manager.state.value.status)
        assertEquals(ModelError.BUSY, manager.state.value.commandError)
        assertNull(manager.state.value.error)
        engine.hang = false
        manager.testPrompt().join()
        assertNull(manager.state.value.commandError)
        assertEquals(1, engine.requests)
        manager.shutdown()
    }
    @Test fun cancelIsNotQueuedBehindNativeGeneration() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        val generation = manager.testPrompt(); runCurrent()
        manager.cancel().join(); generation.join()
        assertTrue(engine.nativeStopped.isCompleted)
        assertEquals(RuntimeStatus.LOADED, manager.state.value.status)
        engine.hang = false
        manager.testPrompt().join()
        assertEquals(2, engine.requests)
        manager.shutdown()
    }
    @Test fun cancellationDuringPrefillTelemetryStopsBenchmarkPressurePolling() = runTest {
        var snapshot = healthy
        var polls = 0
        val prefillEntered = CompletableDeferred<Unit>()
        val engine = Engine()
        val manager = manager(engine, resources = { polls++; snapshot }, telemetry = {
            if (it.stage == MemoryStage.PREFILL) {
                prefillEntered.complete(Unit)
                awaitCancellation()
            }
        })
        manager.load(ModelBackend.GPU).join()
        val generation = manager.testPrompt()
        prefillEntered.await()
        val pollsBeforeWait = polls
        advanceTimeBy(1_001); runCurrent()
        val pollsDuringPrefill = polls
        assertTrue(pollsDuringPrefill > pollsBeforeWait)
        manager.cancel().join(); generation.join()
        manager.release().join()
        assertEquals(0, engine.requests)
        assertEquals(1, engine.releases)
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        val pollsAfterRelease = polls
        snapshot = healthy.copy(lowMemory = true)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(pollsAfterRelease, polls, "Cancelled benchmark must not retain its pressure monitor")
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        assertNull(manager.state.value.error)
        manager.shutdown()
    }
    @Test fun releaseWaitsForNativeCompletionAndCanReload() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join(); manager.testPrompt(); runCurrent()
        manager.release().join()
        assertTrue(engine.nativeStopped.isCompleted)
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.load(ModelBackend.CPU).join()
        assertEquals(2, engine.loads)
        manager.shutdown()
    }
    @Test fun unsafeOrUnknownResourcesDoNotLoad() = runTest {
        listOf(healthy.copy(thermalStatus = 3), healthy.copy(lowMemory = true),
            healthy.copy(thermalStatus = null), healthy.copy(totalMemoryBytes = 2L shl 30),
            healthy.copy(availableMemoryBytes = ResourceSnapshot.MIN_AVAILABLE_MEMORY_BYTES - 1)).forEach { snapshot ->
            val engine = Engine(); val manager = manager(engine, resources = { snapshot })
            manager.load(ModelBackend.CPU).join()
            assertEquals(RuntimeStatus.BLOCKED, manager.state.value.status)
            assertEquals(0, engine.loads)
            manager.shutdown()
        }
    }
    @Test fun thermalRiseCancelsInFlightWork() = runTest {
        var snapshot = healthy
        val engine = Engine(); val manager = manager(engine, resources = { snapshot })
        manager.load(ModelBackend.CPU).join(); val generation = manager.testPrompt(); runCurrent()
        snapshot = healthy.copy(thermalStatus = 3)
        advanceTimeBy(1001); runCurrent(); generation.join()
        assertTrue(engine.cancels > 0)
        manager.shutdown()
    }
    @Test fun briefAvailableMemoryDipDoesNotCancelGeneration() = runTest {
        var snapshot = healthy
        val engine = Engine(); val manager = manager(engine, resources = { snapshot })
        manager.load(ModelBackend.CPU).join(); val generation = manager.testPrompt(); runCurrent()
        snapshot = healthy.copy(availableMemoryBytes = ResourceSnapshot.MIN_AVAILABLE_MEMORY_BYTES - 1)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(0, engine.cancels)
        snapshot = healthy
        advanceTimeBy(1_001); runCurrent()
        assertEquals(0, engine.cancels)
        snapshot = healthy.copy(availableMemoryBytes = ResourceSnapshot.MIN_AVAILABLE_MEMORY_BYTES - 1)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(0, engine.cancels)
        assertEquals(RuntimeStatus.GENERATING, manager.state.value.status)
        manager.cancel().join(); generation.join(); manager.shutdown()
    }
    @Test fun sustainedAvailableMemoryPressureCancelsGeneration() = runTest {
        var snapshot = healthy
        val engine = Engine(); val manager = manager(engine, resources = { snapshot })
        manager.load(ModelBackend.CPU).join(); val generation = manager.testPrompt(); runCurrent()
        snapshot = healthy.copy(availableMemoryBytes = ResourceSnapshot.MIN_AVAILABLE_MEMORY_BYTES - 1)
        advanceTimeBy(3_001); runCurrent(); generation.join()
        assertTrue(engine.cancels > 0)
        assertEquals(ModelError.LOW_MEMORY, manager.state.value.error)
        manager.shutdown()
    }
    @Test fun osLowMemoryAndUnknownResourceSignalsCancelOnFirstSample() = runTest {
        listOf(healthy.copy(lowMemory = true), healthy.copy(availableMemoryBytes = null),
            healthy.copy(totalMemoryBytes = null), healthy.copy(thermalStatus = null)).forEach { unsafe ->
            var snapshot = healthy
            val engine = Engine(); val manager = manager(engine, resources = { snapshot })
            manager.load(ModelBackend.CPU).join(); val generation = manager.testPrompt(); runCurrent()
            snapshot = unsafe
            advanceTimeBy(1_001); runCurrent(); generation.join()
            assertTrue(engine.cancels > 0)
            assertEquals(unsafe.blockedReason(), manager.state.value.error)
            manager.shutdown()
        }
    }
    @Test fun idleUnloadUsesInjectedScheduler() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        advanceTimeBy(60_001); runCurrent()
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.shutdown()
    }
    @Test fun internalBuildGateRejectsAllNativeActions() = runTest {
        val engine = Engine(); val manager = manager(engine, enabled = false)
        manager.load(ModelBackend.CPU).join(); manager.testPrompt().join(); manager.release().join()
        assertEquals(0, engine.loads); assertEquals(0, engine.requests); assertEquals(0, engine.releases)
        manager.shutdown()
    }
    @Test fun releaseRejectsNewRequestsUntilNativeCloseFinishes() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        engine.releaseGate = CompletableDeferred()
        val release = manager.release(); runCurrent()
        manager.load(ModelBackend.GPU).join(); manager.testPrompt().join()
        assertEquals(1, engine.loads); assertEquals(0, engine.requests)
        assertEquals(RuntimeStatus.RELEASING, manager.state.value.status)
        assertEquals(ModelError.BUSY, manager.state.value.commandError)
        assertNull(manager.state.value.error)
        engine.releaseGate!!.complete(Unit); release.join()
        assertEquals(RuntimeStatus.UNLOADED, manager.state.value.status)
        manager.shutdown()
    }
    @Test fun uncertainReleasePreventsReload() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        engine.releaseFailure = true
        manager.release().join(); manager.load(ModelBackend.CPU).join()
        assertEquals(ModelError.RESTART_REQUIRED, manager.state.value.error)
        assertEquals(1, engine.loads)
        manager.shutdown()
    }
    @Test fun unconfirmedNativeCancellationQuarantinesRuntime() = runTest {
        val engine = Engine(); val manager = manager(engine)
        manager.load(ModelBackend.CPU).join()
        engine.cancelTimeout = true
        val generation = manager.testPrompt(); runCurrent()
        manager.cancel().join(); generation.join()
        assertEquals(ModelError.CANCEL_TIMEOUT, manager.state.value.error)
        manager.release().join()
        assertEquals(0, engine.releases)
        manager.load(ModelBackend.CPU).join()
        assertEquals(1, engine.loads)
        assertEquals(ModelError.RESTART_REQUIRED, manager.state.value.error)
        manager.shutdown()
    }
}
