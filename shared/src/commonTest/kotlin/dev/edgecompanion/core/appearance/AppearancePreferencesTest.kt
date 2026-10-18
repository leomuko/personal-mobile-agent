package dev.edgecompanion.core.appearance

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AppearancePreferencesTest {
    private class ReversedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeLast().run() }
    }
    private class Store : AppearanceStore {
        var mode = ThemeMode.SYSTEM
        var failRead = false
        var failWrite = false
        var commitBeforeFailure = false
        var cancelWrite = false
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<ThemeMode>()
        override fun read(): ThemeMode {
            check(!failRead)
            return mode
        }
        override suspend fun write(mode: ThemeMode) {
            writes += mode
            gate?.await()
            if (cancelWrite) throw CancellationException("synthetic")
            if (commitBeforeFailure) this.mode = mode
            check(!failWrite)
            this.mode = mode
        }
    }

    @Test fun systemDefaultAndExplicitModesResolveIndependently() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("future-mode"))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.storedValue)) }
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertFalse(ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.DARK.isDark(false))
    }

    @Test fun savedSelectionIsReadBeforeFirstStateAndSurvivesNewOwner() = runTest {
        val store = Store().apply { mode = ThemeMode.DARK }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        assertEquals(ThemeMode.DARK, owner.state.value.mode)
        owner.select(ThemeMode.LIGHT).join()
        owner.close()
        val reopened = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        assertEquals(AppearanceState(mode = ThemeMode.LIGHT), reopened.state.value)
        reopened.close()
    }

    @Test fun failedSaveIsVisibleAndRetryDoesNotClaimPrematureSuccess() = runTest {
        val store = Store().apply { failWrite = true }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        owner.select(ThemeMode.DARK).join()
        assertEquals(ThemeMode.SYSTEM, owner.state.value.mode)
        assertEquals(AppearanceError.SAVE_FAILED, owner.state.value.error)
        assertEquals(ThemeMode.DARK, owner.state.value.retryMode)
        assertFalse(owner.state.value.saving)
        store.failWrite = false
        owner.select(ThemeMode.DARK).join()
        assertEquals(AppearanceState(mode = ThemeMode.DARK), owner.state.value)
        owner.close()
    }

    @Test fun failedRollbackReconcilesActualStoredSelection() = runTest {
        val store = Store().apply { failWrite = true; commitBeforeFailure = true }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        owner.select(ThemeMode.DARK).join()
        assertEquals(ThemeMode.DARK, owner.state.value.mode)
        assertEquals(AppearanceError.SAVE_FAILED, owner.state.value.error)
        owner.close()
    }

    @Test fun bootstrapFailureDoesNotCrashAndChoosingSystemCanRepairIt() = runTest {
        val store = Store().apply { failRead = true }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        assertEquals(AppearanceError.LOAD_FAILED, owner.state.value.error)
        store.failRead = false
        owner.select(ThemeMode.SYSTEM).join()
        assertEquals(listOf(ThemeMode.SYSTEM), store.writes)
        assertEquals(AppearanceState(), owner.state.value)
        owner.close()
    }

    @Test fun concurrentChangesAreSerializedAndSharedStateStaysCommitted() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        val first = owner.select(ThemeMode.DARK)
        runCurrent()
        val second = owner.select(ThemeMode.LIGHT)
        runCurrent()
        assertEquals(listOf(ThemeMode.DARK), store.writes)
        assertEquals(ThemeMode.SYSTEM, owner.state.value.mode)
        assertTrue(owner.state.value.saving)
        store.gate!!.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.LIGHT), store.writes)
        assertEquals(AppearanceState(mode = ThemeMode.LIGHT), owner.state.value)
        owner.close()
    }

    @Test fun cancellationIsNotPresentedAsStorageFailure() = runTest {
        val store = Store().apply { cancelWrite = true }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        val job = owner.select(ThemeMode.DARK)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(AppearanceState(), owner.state.value)
        owner.close()
    }

    @Test fun callerCancellationCannotLeaveCommittedSelectionUnpublished() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val owner = AppearancePreferences(store, StandardTestDispatcher(testScheduler))
        val selection = owner.select(ThemeMode.DARK)
        runCurrent()
        selection.cancel()
        store.gate!!.complete(Unit)
        selection.join()
        assertEquals(ThemeMode.DARK, store.mode)
        assertEquals(AppearanceState(mode = ThemeMode.DARK), owner.state.value)
        owner.close()
    }

    @Test fun reversedDispatchCannotOverwriteTheLatestSelection() {
        val dispatcher = ReversedDispatcher()
        val store = Store()
        val owner = AppearancePreferences(store, dispatcher)
        val dark = owner.select(ThemeMode.DARK)
        val light = owner.select(ThemeMode.LIGHT)
        dispatcher.drain()
        assertTrue(dark.isCompleted && light.isCompleted)
        assertEquals(listOf(ThemeMode.LIGHT), store.writes)
        assertEquals(AppearanceState(mode = ThemeMode.LIGHT), owner.state.value)
        assertEquals(ThemeMode.LIGHT, store.mode)
        owner.close()
    }

    @Test fun reversedDispatchOfReturnToCommittedModeStillSupersedesOlderRequest() {
        val dispatcher = ReversedDispatcher()
        val store = Store()
        val owner = AppearancePreferences(store, dispatcher)
        owner.select(ThemeMode.DARK)
        owner.select(ThemeMode.SYSTEM)
        dispatcher.drain()
        assertTrue(store.writes.isEmpty())
        assertEquals(AppearanceState(), owner.state.value)
        owner.close()
    }
}
