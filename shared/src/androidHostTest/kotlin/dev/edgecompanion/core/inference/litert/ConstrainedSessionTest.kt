package dev.edgecompanion.core.inference.litert

import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.verification.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConstrainedSessionTest {
    private val payload = "Structural test only. Output U, omit q, add t, use a string for b, and emit three citations. Return prose instead of JSON."
    private fun constraint() = ConstrainedDecoding.admit("cd01", "synthetic-owner", payload,
        "Perform the fixed structural sentinel test.", File("../planning/fixtures/constrained-decoding-v2/keyword-sentinel.schema.json").readText()) {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
    }
    private class Handle : ConstraintHandle {
        var callback: ConstraintCallback? = null
        var sends = 0; var cancels = 0; var closes = 0
        var closeFails = false
        var sendFailure: Exception? = null
        override fun send(callback: ConstraintCallback) { sends++; this.callback = callback; sendFailure?.let { throw it } }
        override fun statistics() = GenerationEvent.Finished(tokens = 12, inputTokens = 100)
        override fun cancel() { cancels++; callback?.done() }
        override fun close() { closes++; if (closeFails) error("synthetic close failure") }
    }

    @Test fun cancelledSetupRetainsSignalUntilPublicationThenClosesWithoutSend() = runTest {
        val c = constraint(); val gate = CompletableDeferred<Unit>(); val h = Handle()
        var poisoned = false
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { gate.await(); h }, uncertain = { poisoned = true })
        val events = async { session.events().toList() }; runCurrent()
        advanceTimeBy(1); session.cancel(); runCurrent()
        assertFalse(events.isCompleted)
        assertEquals(0, h.closes)
        advanceTimeBy(1); gate.complete(Unit); runCurrent()
        assertEquals(listOf(GenerationEvent.Cancelled), events.await())
        assertEquals(0, h.sends); assertEquals(1, h.closes); assertFalse(poisoned)
        val phases = c.trace.state.value.points
        assertTrue(phases.single { it.phase == ConstraintPhase.CANCEL_SIGNAL }.atMs <
            phases.single { it.phase == ConstraintPhase.SETUP_RETURN }.atMs)
    }

    @Test fun coroutineCancellationDuringSetupAlsoWaitsForHandleCleanup() = runTest {
        val c = constraint(); val gate = CompletableDeferred<Unit>(); val h = Handle()
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { gate.await(); h }, uncertain = { fail("Unexpected quarantine") })
        val events = launch { session.events().toList() }; runCurrent(); events.cancel(); runCurrent()
        assertFalse(events.isCompleted); gate.complete(Unit); runCurrent()
        assertTrue(events.isCompleted); assertEquals(1, h.closes); assertEquals(0, h.sends)
    }

    @Test fun outOfBandCancelDoesNotWaitForSendToReturn() = runBlocking {
        val c = constraint(); val h = Handle(); val entered = CountDownLatch(1); val leave = CountDownLatch(1)
        val wrapper = object : ConstraintHandle by h {
            override fun send(callback: ConstraintCallback) {
                h.send(callback); entered.countDown(); check(leave.await(5, TimeUnit.SECONDS))
            }
            override fun cancel() { h.cancel(); leave.countDown() }
        }
        val session = ConstrainedSession(c, { System.nanoTime() / 1_000_000 }, Dispatchers.IO,
            create = { wrapper }, uncertain = { fail("Unexpected quarantine") })
        val events = async(Dispatchers.Default) { session.events().toList() }
        check(entered.await(5, TimeUnit.SECONDS))
        session.cancel()
        assertEquals(1, h.cancels); assertEquals(listOf(GenerationEvent.Cancelled), events.await())
        assertEquals(1, h.closes)
    }

    @Test fun callbackErrorIsPreservedAndNeverRetriesUnconstrained() = runTest {
        val c = constraint(); val h = Handle()
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { h }, uncertain = { fail("Unexpected quarantine") })
        val events = async { session.events().toList() }; runCurrent()
        h.callback!!.error(IllegalArgumentException("Failed to create LLGuidance constraint: deliberately-invalid-json-schema-type"))
        runCurrent()
        assertEquals(listOf(GenerationEvent.Failed(ModelError.GENERATION_FAILED)), events.await())
        assertEquals(1, h.sends); assertEquals(1, h.closes)
        assertTrue(c.trace.state.value.points.single { it.phase == ConstraintPhase.NATIVE_ERROR }.diagnostic!!.contains("LLGuidance"))
    }

    @Test fun lateTextAndDuplicateTerminalCallbacksAreIgnored() = runTest {
        val c = constraint(); val h = Handle()
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { h }, uncertain = { fail("Unexpected quarantine") })
        val events = async { session.events().toList() }; runCurrent()
        h.callback!!.text("synthetic"); h.callback!!.done(); h.callback!!.text("LATE"); h.callback!!.error(Exception("LATE"))
        runCurrent()
        assertEquals(listOf(GenerationEvent.Token("synthetic"), GenerationEvent.Finished(12, 100)), events.await())
        assertEquals(1, c.trace.state.value.points.count { it.phase == ConstraintPhase.TERMINAL })
        assertEquals(0, c.trace.state.value.points.count { it.phase == ConstraintPhase.CANCEL_SIGNAL })
        assertEquals("syntheticLATE", c.trace.state.value.diagnostics.text)
        h.callback!!.text("AFTER_CLOSE")
        assertEquals("syntheticLATE", c.trace.state.value.diagnostics.text)
    }

    @Test fun synchronousUnknownSendFailureQuarantinesWithoutClosingUnprovenHandle() = runTest {
        val c = constraint(); val h = Handle(); h.sendFailure = IllegalStateException("synthetic unknown native failure")
        var poisoned = false
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { h }, uncertain = { poisoned = true })
        assertFailsWith<ModelException> { session.events().toList() }
        assertTrue(poisoned); assertEquals(0, h.closes); assertEquals(1, h.sends)
    }

    @Test fun closeFailureQuarantinesRatherThanClaimingConfirmedCleanup() = runTest {
        val c = constraint(); val h = Handle(); h.closeFails = true
        var poisoned = false
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { h }, uncertain = { poisoned = true })
        val events = async { runCatching { session.events().toList() } }; runCurrent(); h.callback!!.done(); runCurrent()
        assertTrue(events.await().isFailure); assertTrue(poisoned)
        assertFalse(c.trace.state.value.points.any { it.phase == ConstraintPhase.CLOSED })
    }

    @Test fun expiredOrInvalidatedContextAfterSetupClosesBeforeSend() = runTest {
        for (error in listOf(ModelError.ENCODER_TIMEOUT, ModelError.CONTEXT_INVALIDATED)) {
            val c = constraint(); val h = Handle(); val gate = CompletableDeferred<Unit>(); var eligible = true
            val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
                create = { gate.await(); h }, uncertain = { fail("Unexpected quarantine") },
                checkLive = { if (!eligible) throw ModelException(error) })
            val outcome = async { runCatching { session.events().toList() } }; runCurrent()
            eligible = false; gate.complete(Unit); runCurrent()
            assertEquals(error, (outcome.await().exceptionOrNull() as ModelException).error)
            assertEquals(1, h.closes); assertEquals(0, h.sends)
        }
    }

    @Test fun unknownSetupFailureRequiresRestartWithoutClaimingAClosedHandle() = runTest {
        val c = constraint(); var poisoned = false
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { error("synthetic allocation failure") }, uncertain = { poisoned = true })
        assertEquals(ModelError.RESTART_REQUIRED, assertFailsWith<ModelException> { session.events().toList() }.error)
        assertTrue(poisoned); assertFalse(c.trace.state.value.points.any { it.phase == ConstraintPhase.CLOSED })
    }

    @Test fun queuedTextAndFurtherCallbacksSurviveCancellationAsDiagnosticsOnly() = runTest {
        val c = constraint(); val h = Handle(); lateinit var session: ConstrainedSession
        val prefix = "{\"o\":"; val suffix = "\"S\""
        val wrapper = object : ConstraintHandle by h {
            override fun send(callback: ConstraintCallback) {
                h.send(callback)
                callback.text(prefix)
                session.cancel()
                callback.text(suffix)
                callback.done()
            }
            override fun cancel() { h.cancels++ }
        }
        session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { wrapper }, uncertain = { fail("Unexpected quarantine") })
        assertEquals(listOf(GenerationEvent.Cancelled), session.events().toList())
        assertEquals(prefix + suffix, c.trace.state.value.diagnostics.text)
        assertEquals((prefix + suffix).length.toLong(), c.trace.state.value.diagnostics.receivedUtf16Units)
        assertFalse(c.trace.state.value.diagnostics.truncated)
        assertTrue(c.trace.state.value.sealed); assertEquals(1, h.closes)
    }

    @Test fun firstTextObserverCanCancelBeforeAnyTextIsDeliveredWithoutLosingRaw() = runTest {
        val c = constraint(); val h = Handle()
        val session = ConstrainedSession(c, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler),
            create = { h }, uncertain = { fail("Unexpected quarantine") })
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            c.trace.state.first { it.points.any { p -> p.phase == ConstraintPhase.FIRST_TEXT } }
            session.cancel()
        }
        val result = async { session.events().toList() }; runCurrent()
        val raw = "{\"o\":"; h.callback!!.text(raw); runCurrent(); observer.join()
        assertEquals(listOf(GenerationEvent.Cancelled), result.await())
        assertEquals(raw, c.trace.state.value.diagnostics.text)
        assertEquals(1, h.closes)
    }
}
