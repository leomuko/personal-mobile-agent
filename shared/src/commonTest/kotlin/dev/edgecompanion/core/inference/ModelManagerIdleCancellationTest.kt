package dev.edgecompanion.core.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerIdleCancellationTest {
    @Test fun idleCleanupWaitsForPendingCancellationAndReleasesExactlyOnce() = runTest {
        val f = Fixture(this)
        try {
            val cancellation = cancelPastIdleDeadline(f)
            f.engine.cancelGate.complete(Unit)
            drainCurrent(f.dispatcher)
            cancellation.await()
            assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
            assertNull(f.engine.resident)
            assertEquals(listOf("load:8192", "generate:8192", "cancel-start", "cancel-done", "release:8192"),
                f.engine.events)
            advanceTimeBy(100)
            drainCurrent(f.dispatcher)
            assertEquals(1, f.engine.events.count { it.startsWith("release:") })
        } finally { shutdown(f) }
    }

    @Test fun publicAndHostLossReleaseOwnCleanupWhileCancellationIsPending() = runTest {
        for (hostLoss in listOf(false, true)) {
            val f = Fixture(this)
            try {
                val cancellation = cancelPastIdleDeadline(f)
                val release = if (hostLoss) f.manager.releaseForHostLoss() else f.manager.release()
                drainCurrent(f.dispatcher)
                assertFalse(release.isCompleted)
                assertTrue(f.engine.cancelling)
                assertEquals(listOf("load:8192", "generate:8192", "cancel-start"), f.engine.events)
                f.engine.cancelGate.complete(Unit)
                drainCurrent(f.dispatcher)
                cancellation.await()
                release.join()
                assertEquals(RuntimeStatus.UNLOADED, f.manager.state.value.status)
                assertNull(f.engine.resident)
                assertEquals(listOf("load:8192", "generate:8192", "cancel-start", "cancel-done", "release:8192"),
                    f.engine.events)
            } finally { shutdown(f) }
        }
    }

    @Test fun replacementSurvivesIdleWaiterAfterCancellationSettles() = runTest {
        for (panel in listOf(true, false)) {
            val f = Fixture(this)
            try {
                val cancellation = cancelPastIdleDeadline(f)
                f.engine.cancelGate.complete(Unit)
                f.dispatcher.runNext() // Complete the native signal before resuming the idle waiter.
                assertFalse(f.engine.cancelling)
                assertEquals("cancel-done", f.engine.events.last())
                f.engine.hangGeneration = false
                val gate = CompletableDeferred<Unit>()
                f.engine.loadGate = gate
                val reply = if (panel) null else async {
                    f.manager.generateChat(request("replacement", 4096)).toList()
                }
                val loading = if (panel) f.manager.load(ModelBackend.GPU) else null
                runCurrent()
                f.dispatcher.runLast() // Admit replacement before queued cancellation/idle continuations.
                assertEquals(RuntimeStatus.LOADING, f.manager.state.value.status)
                drainCurrent(f.dispatcher)
                gate.complete(Unit)
                drainCurrent(f.dispatcher)
                cancellation.await()
                loading?.join()
                if (reply != null) assertEquals(ChatOutcome.COMPLETED, reply.await().terminal().outcome)
                val context = if (panel) 2048 else 4096
                assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
                assertEquals(context, f.engine.resident?.contextTokens)
                assertEquals(512, f.engine.resident?.maxOutputTokens)
                val expected = listOf("load:8192", "generate:8192", "cancel-start", "cancel-done",
                    "release:8192", "load:$context") + if (panel) emptyList() else listOf("generate:4096")
                assertEquals(expected, f.engine.events)
            } finally { shutdown(f) }
        }
    }

    private suspend fun TestScope.cancelPastIdleDeadline(f: Fixture): Deferred<Unit> {
        val reply = async { f.manager.generateChat(request("cancelled", 8192)).toList() }
        drainCurrent(f.dispatcher)
        assertEquals(RuntimeStatus.GENERATING, f.manager.state.value.status)
        val cancellation = async { f.manager.cancelChat("cancelled") }
        drainCurrent(f.dispatcher)
        assertEquals(ChatOutcome.CANCELLED, reply.await().terminal().outcome)
        assertFalse(cancellation.isCompleted)
        assertTrue(f.engine.cancelling)
        advanceTimeBy(10)
        drainCurrent(f.dispatcher)
        assertEquals(RuntimeStatus.LOADED, f.manager.state.value.status)
        assertEquals(listOf("load:8192", "generate:8192", "cancel-start"), f.engine.events)
        return cancellation
    }

    private suspend fun TestScope.shutdown(f: Fixture) {
        f.engine.cancelGate.complete(Unit)
        f.engine.loadGate?.complete(Unit)
        val stop = async { f.manager.shutdown() }
        drainCurrent(f.dispatcher)
        stop.await()
    }

    private fun TestScope.drainCurrent(dispatcher: QueueDispatcher) {
        repeat(100) {
            runCurrent()
            if (dispatcher.pendingCount == 0) return
            dispatcher.runNext()
        }
        fail("Dispatcher did not settle")
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

    private class Fixture(scope: TestScope) {
        val dispatcher = QueueDispatcher(StandardTestDispatcher(scope.testScheduler))
        val engine = Engine()
        private val assets = object : ModelAssets {
            override val state = MutableStateFlow(ModelAssetState(AssetStatus.INSTALLED))
            override suspend fun verify() = "synthetic.litertlm"
            override suspend fun install(source: ModelSource) = Unit
        }
        val manager = ModelManager(assets, engine, dispatcher,
            { ResourceSnapshot(12L shl 30, 6L shl 30, false, 0) }, { scope.testScheduler.currentTime }, true,
            idleMs = 10)
    }

    private class Engine : LocalLlmEngine {
        val events = mutableListOf<String>()
        val cancelGate = CompletableDeferred<Unit>()
        var loadGate: CompletableDeferred<Unit>? = null
        var resident: ModelConfig? = null
            private set
        var hangGeneration = true
        var cancelling = false
            private set

        override suspend fun load(config: ModelConfig): Result<Unit> {
            assertFalse(cancelling)
            assertNull(resident)
            events += "load:${config.contextTokens}"
            loadGate?.await()
            resident = config
            return Result.success(Unit)
        }
        override fun generate(request: GenerationRequest) = flow {
            val config = assertNotNull(resident)
            assertEquals(config.contextTokens, request.chat?.contextTokens)
            assertEquals(config.maxOutputTokens, request.maxTokens)
            events += "generate:${config.contextTokens}"
            emit(GenerationEvent.Token("Synthetic reply"))
            if (hangGeneration) awaitCancellation()
            emit(GenerationEvent.Finished(1, 5))
        }
        override suspend fun cancel() {
            cancelling = true
            events += "cancel-start"
            try { cancelGate.await() }
            finally { cancelling = false; events += "cancel-done" }
        }
        override suspend fun release() {
            assertFalse(cancelling, "Release overlapped an unfinished cancellation signal")
            events += "release:${assertNotNull(resident).contextTokens}"
            resident = null
        }
    }

    private fun request(id: String, context: Int) =
        ChatRequest(id, listOf(ChatMessage(ChatRole.USER, "Synthetic question")), contextTokens = context)

    private fun List<ChatEvent>.terminal() = filterIsInstance<ChatEvent.Terminal>().single()
}
