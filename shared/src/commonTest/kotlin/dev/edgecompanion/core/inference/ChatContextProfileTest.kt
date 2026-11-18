package dev.edgecompanion.core.inference

import dev.edgecompanion.core.inference.embedding.EncoderAdmission
import dev.edgecompanion.core.inference.embedding.EncoderAdmissionReceipt
import dev.edgecompanion.core.inference.embedding.EncoderInput
import dev.edgecompanion.core.inference.embedding.EncoderLease
import dev.edgecompanion.core.inference.embedding.EncoderRequest
import dev.edgecompanion.core.inference.embedding.EncoderResult
import dev.edgecompanion.core.inference.embedding.ForegroundEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class ChatContextProfileTest {
    @Test fun supportedProfilesEnforceTheirExactAsciiBound() {
        val bounds = mapOf(
            ChatContextProfile.STANDARD to 1312,
            ChatContextProfile.EXTENDED_4096 to 3360,
            ChatContextProfile.EXTENDED_8192 to 7456,
        )
        assertEquals(bounds.keys, ChatContextProfile.entries.toSet())
        for ((profile, bytes) in bounds) {
            val request = GemmaChatBudget.build("x".repeat(bytes), emptyList(), "bound", profile)
            assertEquals(profile.contextTokens.toLong() - 512, GemmaChatBudget.validate(request))
            assertEquals(512, request.outputTokens)
            assertEquals(ModelError.CONTEXT_LIMIT, assertFailsWith<ModelException> {
                GemmaChatBudget.build("x".repeat(bytes + 1), emptyList(), "overflow", profile)
            }.error)
        }
        val standard = request("default")
        assertEquals(2048 to 512, standard.contextTokens to standard.outputTokens)
        for (invalid in listOf(standard.copy(contextTokens = 3072), standard.copy(outputTokens = 511),
            standard.copy(contextTokens = ModelCatalog.gemma4.artifactContextTokens))) {
            assertEquals(ModelError.CONTEXT_LIMIT, assertFailsWith<ModelException> {
                GemmaChatBudget.validate(invalid)
            }.error)
        }
    }

    @Test fun extendedHistoryKeepsMoreRecentWholeTurnsInConversationOrder() {
        val history = (1..9).map { "Question $it " + "x".repeat(260) to "Answer $it " + "y".repeat(260) }
        val standard = GemmaChatBudget.build("Next?", history, "standard", ChatContextProfile.STANDARD)
        val extended = GemmaChatBudget.build("Next?", history, "extended", ChatContextProfile.EXTENDED_8192)
        for ((request, kept) in listOf(standard to 1, extended to history.size)) {
            val expected = history.takeLast(kept).flatMap { (user, assistant) ->
                listOf(ChatMessage(ChatRole.USER, user), ChatMessage(ChatRole.ASSISTANT, assistant))
            }
            assertEquals(expected + ChatMessage(ChatRole.USER, "Next?"), request.messages)
        }
    }

    @Test fun qaHandoffAndOrdinaryChatUseTheirOwnConfigurations() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(ChatOutcome.COMPLETED, f.handoff(request("qa-before")).terminal().outcome)
            assertEquals(ChatOutcome.COMPLETED, f.manager.generateChat(request("ordinary", 4096)).toList().terminal().outcome)
            assertEquals(ChatOutcome.COMPLETED, f.handoff(request("qa-after")).terminal().outcome)
            assertEquals(listOf("load:2048", "generate:2048", "release:2048", "load:4096", "generate:4096",
                "release:4096", "load:2048", "generate:2048"), f.engine.events)
        } finally { f.manager.shutdown() }
    }

    @Test fun preparedHandoffRejectsExtendedContextBeforeReclaimingTheResidentEngine() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            assertEquals(ModelError.CONTEXT_LIMIT, f.handoff(request("qa-extended", 4096)).terminal().error)
            assertEquals(listOf("load:2048"), f.engine.events)
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
        } finally { f.manager.shutdown() }
    }

    @Test fun panelLoadReclaimsAnExtendedEngineAndKeepsItsManifestConfiguration() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(ChatOutcome.COMPLETED, f.manager.generateChat(request("ordinary", 8192)).toList().terminal().outcome)
            f.manager.load(ModelBackend.GPU).join()
            assertEquals(listOf("load:8192", "generate:8192", "release:8192", "load:2048"), f.engine.events)
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
        } finally { f.manager.shutdown() }
    }

    @Test fun cancellingWhileReleaseIsPendingPreventsReloadAndKeepsOtherOwnersOut() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            val released = CompletableDeferred<Unit>()
            f.engine.releaseGate = released
            val first = async { f.manager.generateChat(request("reload", 4096)).toList() }
            runCurrent()
            assertEquals(RuntimeStatus.RELEASING, f.manager.state.value.status)
            f.manager.cancelChat("reload")
            assertFalse(first.isCompleted)
            assertEquals(ModelError.BUSY, f.manager.generateChat(request("racer", 8192)).toList().terminal().error)
            assertEquals(listOf("load:2048", "release:2048"), f.engine.events)
            released.complete(Unit)
            assertEquals(ChatOutcome.CANCELLED, first.await().terminal().outcome)
            assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
            assertNull(f.engine.resident)
            f.engine.releaseGate = null
            assertEquals(ChatOutcome.COMPLETED, f.manager.generateChat(request("retry", 4096)).toList().terminal().outcome)
            assertEquals(listOf("load:2048", "release:2048", "load:4096", "generate:4096"), f.engine.events)
        } finally {
            f.engine.releaseGate?.complete(Unit)
            f.manager.shutdown()
        }
    }

    @Test fun cancellingWhileReplacementLoadIsPendingPublishesOnlyTheNewIdentity() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            val loaded = CompletableDeferred<Unit>()
            f.engine.loadGate = loaded
            val first = async { f.manager.generateChat(request("reload", 4096)).toList() }
            runCurrent()
            assertEquals(RuntimeStatus.LOADING, f.manager.state.value.status)
            f.manager.cancelChat("reload")
            assertFalse(first.isCompleted)
            assertEquals(ModelError.BUSY, f.manager.generateChat(request("racer", 8192)).toList().terminal().error)
            loaded.complete(Unit)
            assertEquals(ChatOutcome.CANCELLED, first.await().terminal().outcome)
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
            assertEquals(4096, f.engine.resident?.contextTokens)
            assertEquals(listOf("load:2048", "release:2048", "load:4096"), f.engine.events)
            assertEquals(ChatOutcome.COMPLETED, f.manager.generateChat(request("retry", 4096)).toList().terminal().outcome)
            assertEquals("generate:4096", f.engine.events.last())
            assertEquals(4, f.engine.events.size)
        } finally {
            f.engine.loadGate?.complete(Unit)
            f.manager.shutdown()
        }
    }

    @Test fun failedReloadReleaseQuarantinesWithoutASecondLoadOrClose() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            f.engine.releaseFails = true
            assertEquals(ModelError.RESTART_REQUIRED, f.manager.generateChat(request("reload", 4096)).toList().terminal().error)
            assertEquals(ModelError.RELEASE_FAILED, f.manager.state.value.cleanupError)
            assertEquals(ModelError.RESTART_REQUIRED, f.manager.generateChat(request("retry")).toList().terminal().error)
        } finally { f.manager.shutdown() }
        assertEquals(listOf("load:2048", "release:2048"), f.engine.events)
    }

    @Test fun reloadChecksLoadingAdmissionAfterConfirmedRelease() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.GPU).join()
            f.resources = f.resources.copy(lowMemory = true)
            assertEquals(ModelError.LOW_MEMORY, f.manager.generateChat(request("reload", 4096)).toList().terminal().error)
            assertEquals(listOf("load:2048", "release:2048"), f.engine.events)
            assertNull(f.engine.resident)
        } finally { f.manager.shutdown() }
    }

    @Test fun backendMismatchRejectsWithoutReleasingOrReusingTheEngine() = runTest {
        val f = Fixture(this)
        try {
            f.manager.load(ModelBackend.CPU).join()
            assertEquals(ModelError.BUSY, f.manager.generateChat(request("different-backend", 4096)).toList().terminal().error)
            assertEquals(listOf("load:2048"), f.engine.events)
            assertEquals(ModelBackend.CPU, f.engine.resident?.backend)
        } finally { f.manager.shutdown() }
    }

    @Test fun panelPromptsRejectExtendedContextsWithoutResettingIdle() = runTest {
        for (context in listOf(4096, 8192)) {
            val f = Fixture(this, idleMs = 10)
            try {
                assertEquals(ChatOutcome.COMPLETED,
                    f.manager.generateChat(request("extended-panel-$context", context)).toList().terminal().outcome)
                val resident = assertNotNull(f.engine.resident)
                val events = f.engine.events.toList()
                advanceTimeBy(9)
                runCurrent()
                for (prompt in listOf(ModelTestPrompts.SHORT, ModelTestPrompts.CANCELLATION)) {
                    f.manager.testPrompt(prompt).join()
                    assertEquals(events, f.engine.events)
                    assertEquals(ModelError.BUSY, f.manager.state.value.commandError)
                    assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
                    assertTrue(f.engine.resident === resident)
                }
                advanceTimeBy(1)
                runCurrent()
                assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
                assertEquals(events + "release:$context", f.engine.events)
            } finally { f.manager.shutdown() }
        }
    }

    @Test fun panelPromptsUseTheStandardContextAfterOrdinaryChat() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(ChatOutcome.COMPLETED,
                f.manager.generateChat(request("standard-panel")).toList().terminal().outcome)
            for (prompt in listOf(ModelTestPrompts.SHORT, ModelTestPrompts.CANCELLATION)) {
                f.manager.testPrompt(prompt).join()
                assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
                assertNull(f.manager.state.value.commandError)
                assertEquals("panel-prompt:2048/512", f.engine.events.last())
            }
            assertEquals(listOf("load:2048", "generate:2048", "panel-prompt:2048/512", "panel-prompt:2048/512"),
                f.engine.events)
        } finally { f.manager.shutdown() }
    }

    @Test fun expiredIdleReleaseCannotUnloadPanelReplacement() = runTest {
        val dispatcher = QueueDispatcher(StandardTestDispatcher(testScheduler))
        val f = Fixture(this, dispatcher, idleMs = 10)
        try {
            val initial = async { f.manager.generateChat(request("before-panel", 8192)).toList() }
            drainCurrent(dispatcher)
            assertEquals(ChatOutcome.COMPLETED, initial.await().terminal().outcome)
            advanceTimeBy(10)
            runCurrent()
            assertEquals(1, dispatcher.pendingCount)
            val gate = CompletableDeferred<Unit>()
            f.engine.loadGate = gate
            val replacement = f.manager.load(ModelBackend.GPU)
            dispatcher.runNext() // Expired idle continuation, before the queued panel command.
            dispatcher.runNext() // Panel replacement reaches its gated load.
            assertEquals(RuntimeStatus.LOADING, f.manager.state.value.status)
            assertFalse(replacement.isCompleted)
            drainCurrent(dispatcher) // An old detached release can now wait behind the panel.
            gate.complete(Unit)
            drainCurrent(dispatcher)
            replacement.join()
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
            assertEquals(2048, f.engine.resident?.contextTokens)
            assertEquals(512, f.engine.resident?.maxOutputTokens)
            assertEquals(listOf("load:8192", "generate:8192", "release:8192", "load:2048"), f.engine.events)
        } finally {
            f.engine.loadGate?.complete(Unit)
            val stop = async { f.manager.shutdown() }
            drainCurrent(dispatcher)
            stop.await()
        }
    }

    @Test fun expiredIdleReleaseCannotUnloadChatReplacement() = runTest {
        val dispatcher = QueueDispatcher(StandardTestDispatcher(testScheduler))
        val f = Fixture(this, dispatcher, idleMs = 10)
        try {
            val initial = async { f.manager.generateChat(request("before-chat", 8192)).toList() }
            drainCurrent(dispatcher)
            assertEquals(ChatOutcome.COMPLETED, initial.await().terminal().outcome)
            advanceTimeBy(10)
            runCurrent()
            assertEquals(1, dispatcher.pendingCount)
            val gate = CompletableDeferred<Unit>()
            f.engine.loadGate = gate
            val replacement = async { f.manager.generateChat(request("replacement", 4096)).toList() }
            runCurrent() // Queue the chat owner behind the expired timer.
            dispatcher.runNext()
            dispatcher.runNext()
            assertEquals(RuntimeStatus.LOADING, f.manager.state.value.status)
            assertFalse(replacement.isCompleted)
            gate.complete(Unit)
            dispatcher.runLast() // Finish chat before dispatching any old detached release.
            drainCurrent(dispatcher)
            assertEquals(ChatOutcome.COMPLETED, replacement.await().terminal().outcome)
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
            assertEquals(4096, f.engine.resident?.contextTokens)
            assertEquals(512, f.engine.resident?.maxOutputTokens)
            assertEquals(listOf("load:8192", "generate:8192", "release:8192", "load:4096", "generate:4096"),
                f.engine.events)
        } finally {
            f.engine.loadGate?.complete(Unit)
            val stop = async { f.manager.shutdown() }
            drainCurrent(dispatcher)
            stop.await()
        }
    }

    @Test fun idleReleaseUnloadsCurrentEngineAtItsOwnDeadline() = runTest {
        val f = Fixture(this, idleMs = 10)
        try {
            assertEquals(ChatOutcome.COMPLETED,
                f.manager.generateChat(request("idle-current", 8192)).toList().terminal().outcome)
            advanceTimeBy(9)
            runCurrent()
            assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
            assertEquals(listOf("load:8192", "generate:8192"), f.engine.events)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
            assertNull(f.engine.resident)
            assertEquals(listOf("load:8192", "generate:8192", "release:8192"), f.engine.events)
        } finally { f.manager.shutdown() }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class QueueDispatcher(private val timers: TestDispatcher) : CoroutineDispatcher(), Delay by timers {
        private val pending = ArrayDeque<Runnable>()
        val pendingCount: Int get() = pending.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
            require(parallelism == 1)
            return this
        }
        fun runNext() { pending.removeFirst().run() }
        fun runLast() { pending.removeLast().run() }
    }

    private fun TestScope.drainCurrent(dispatcher: QueueDispatcher) {
        repeat(100) {
            runCurrent()
            if (dispatcher.pendingCount == 0) return
            dispatcher.runNext()
        }
        fail("Dispatcher did not settle")
    }

    private class Fixture(scope: TestScope,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler), idleMs: Long = 60_000) {
        val engine = Engine()
        var resources = ResourceSnapshot(12L shl 30, 6L shl 30, false, 0)
        private val assets = object : ModelAssets {
            override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
            override suspend fun verify() = "synthetic.litertlm"
            override suspend fun install(source: ModelSource) = Unit
        }
        val manager = ModelManager(assets, engine, dispatcher,
            { resources }, { scope.testScheduler.currentTime }, true, idleMs = idleMs)
        private val encoder = object : ForegroundEncoder {
            override suspend fun admit(input: EncoderInput): EncoderAdmission {
                assertNull(engine.resident)
                return EncoderAdmission.Accepted(EncoderAdmissionReceipt(this, input, 4))
            }
            override fun accepts(receipt: EncoderAdmissionReceipt, input: EncoderInput) =
                receipt.issuer === this && receipt.input == input
            override suspend fun encode(request: EncoderRequest) =
                EncoderResult.Completed(request.lease, List(100) { 0.1f })
            override fun cancel(lease: EncoderLease) = Unit
            override suspend fun cleanup(lease: EncoderLease) = true
        }
        suspend fun handoff(request: ChatRequest) =
            manager.prepareChat(request, EncoderInput("Synthetic source"), encoder, 0) { 0 }.events.toList()
    }

    private class Engine : LocalLlmEngine {
        val events = mutableListOf<String>()
        var resident: ModelConfig? = null
            private set
        var loadGate: CompletableDeferred<Unit>? = null
        var releaseGate: CompletableDeferred<Unit>? = null
        var releaseFails = false

        override suspend fun load(config: ModelConfig): Result<Unit> {
            assertNull(resident, "Replacement loaded before release completed")
            events += "load:${config.contextTokens}"
            loadGate?.await()
            assertEquals(512, config.maxOutputTokens)
            resident = config
            return Result.success(Unit)
        }
        override fun generate(request: GenerationRequest) = flow {
            val loaded = assertNotNull(resident)
            val chat = request.chat
            if (chat == null) {
                assertEquals(loaded.maxOutputTokens, request.maxTokens)
                events += "panel-prompt:${loaded.contextTokens}/${request.maxTokens}"
            } else {
                assertEquals(loaded.backend, chat.backend)
                assertEquals(loaded.contextTokens, chat.contextTokens)
                assertEquals(loaded.maxOutputTokens, chat.outputTokens)
                events += "generate:${chat.contextTokens}"
            }
            emit(GenerationEvent.Token("Synthetic reply"))
            emit(GenerationEvent.Finished(5, 10))
        }
        override suspend fun cancel() = Unit
        override suspend fun release() {
            events += "release:${assertNotNull(resident).contextTokens}"
            releaseGate?.await()
            if (releaseFails) throw ModelException(ModelError.RELEASE_FAILED)
            resident = null
        }
    }

    private fun request(id: String, context: Int = 2048) =
        ChatRequest(id, listOf(ChatMessage(ChatRole.USER, "Synthetic question"))).let {
            if (context == 2048) it else it.copy(contextTokens = context)
        }

    private fun List<ChatEvent>.terminal() = filterIsInstance<ChatEvent.Terminal>().single()
}
