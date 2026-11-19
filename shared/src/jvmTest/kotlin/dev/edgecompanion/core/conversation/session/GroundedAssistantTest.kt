package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Assistant turn with an injected retriever on a single test lane; synthetic text only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroundedAssistantTest {
    private val profile = ChatContextProfile.EXTENDED_4096
    private val question = "When does the greenhouse vent open?"
    private class Lane(test: TestScope, val retriever: PassageRetriever?, chatRuntime: ((RecordingRuntime) -> ChatRuntime?)? = null) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CompanionDatabase.Schema.create(it) }
        val db = CompanionDatabase(driver)
        val store = SqlConversationStore(db)
        val controller = ConversationController(store) { 1 }
        val runtime = RecordingRuntime()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val assistant = ConversationAssistant(scope, { controller }, if (chatRuntime == null) runtime else chatRuntime(runtime),
            ChatContextProfile.EXTENDED_4096, retriever)
        suspend fun shutdown() {
            try { assistant.invalidate(); scope.coroutineContext.job.cancelAndJoin() } finally { driver.close() }
        }
    }
    private suspend fun Lane.use(block: suspend (Lane) -> Unit) = try { block(this) } finally { shutdown() }
    private fun TestScope.lane(retriever: PassageRetriever?): Lane = Lane(this, retriever)
    /** A lane whose assistant has no runtime ([runtime] null) or a disabled one. */
    private fun TestScope.laneWithout(runtime: ChatRuntime?, retriever: PassageRetriever?): Lane = Lane(this, retriever) { runtime }
    private suspend fun TestScope.ask(lane: Lane, scope: RequestScope = RequestScope.ORDINARY, text: String = question) {
        lane.controller.restore(); lane.controller.editDraft(text)
        lane.assistant.submit(requestScope = scope); runCurrent()
    }
    private fun result(passages: List<RetrievedPassage> = emptyList(), reason: PassageStopReason? = null,
        coverage: PassageCoverage = PassageCoverage(1, 1), epoch: Long = 0) =
        PassageRetrieval(epoch, coverage, passages, reason, 0, passages.size, 0)

    @Test fun cancellationDuringRetrievalRecordsCancelledAndNeverStartsTheModel() = runTest {
        val gate = CompletableDeferred<PassageRetrieval>()
        lane { _, _, _ -> gate.await() }.use { l ->
            ask(l)
            assertTrue(l.assistant.state.value.active)
            // S4-F5: the attempt is durable before retrieval starts.
            val prepared = l.store.attempts().single()
            assertEquals(TurnStatus.PENDING, prepared.status)
            l.assistant.cancel(); runCurrent()
            val attempt = l.store.attempts().single()
            assertEquals(prepared.copy(status = TurnStatus.CANCELLED), attempt)
            assertTrue(attempt.consulted.isEmpty())
            assertTrue(l.store.attempts().none { it.status == TurnStatus.PENDING })
            assertTrue(l.runtime.requests.isEmpty())
            assertFalse(l.assistant.state.value.active)
            // The user turn stays and can be retried.
            gate.complete(result(listOf(retrieved(0, "The synthetic greenhouse vent opens at noon."))))
            l.assistant.submit(l.store.messages().single().id); runCurrent()
            assertEquals(1, l.runtime.requests.size)
        }
    }

    @Test fun cancelFlagStopsABlockingRetrievalAtItsNextCheckpoint() = runTest {
        lateinit var l: Lane
        var observed: Boolean? = null
        l = lane { _, _, control ->
            // Simulates the UI thread cancelling while the lane is blocked inside the repository.
            l.assistant.cancel()
            observed = control.continueWork()
            result(reason = PassageStopReason.CANCELLED)
        }
        l.use {
            ask(l)
            assertEquals(false, observed)
            assertEquals(TurnStatus.CANCELLED, l.store.attempts().single().status)
            assertTrue(l.runtime.requests.isEmpty())
            assertFalse(l.assistant.state.value.active)
        }
    }

    @Test fun sourceDeletionDuringRetrievalInterruptsAdvancesTheEpochAndRecordsNothingStale() = runTest {
        val gate = CompletableDeferred<Unit>()
        lane { _, _, _ ->
            withContext(NonCancellable) { gate.await() }
            result(listOf(retrieved(0, "Stale synthetic passage about the greenhouse vent.")))
        }.use { l ->
            ask(l)
            val deletion = l.scope.async {
                l.assistant.mutateContext { l.db.libraryQueries.advanceContext(); l.controller.refresh() }
            }
            runCurrent()
            gate.complete(Unit); runCurrent(); deletion.await()
            val attempt = l.store.attempts().single()
            assertEquals(TurnStatus.INTERRUPTED, attempt.status)
            assertTrue(attempt.consulted.isEmpty())
            assertEquals(1L, l.store.contextEpoch())
            assertTrue(l.runtime.requests.isEmpty())
            assertEquals(0L, l.driver.count("consulted_passage"))
        }
    }

    @Test fun retrievalUnderANewerEpochOrAFencedStartNeverReachesTheModel() = runTest {
        // A deletion committed between the request snapshot and retrieval: the retrieval epoch is newer.
        lateinit var l: Lane
        l = lane { _, _, _ ->
            l.db.libraryQueries.advanceContext()
            result(listOf(retrieved(0, "Synthetic greenhouse passage.")), epoch = 1)
        }
        l.use {
            ask(l)
            assertEquals(TurnStatus.INTERRUPTED, l.store.attempts().single().status)
            assertTrue(l.runtime.requests.isEmpty())
        }
        // A retrieval reporting the old epoch after the store advanced: the start transaction refuses it.
        lateinit var m: Lane
        m = lane { _, _, _ ->
            m.db.libraryQueries.advanceContext()
            result(listOf(retrieved(0, "Synthetic greenhouse passage.")), epoch = 0)
        }
        m.use {
            ask(m)
            assertEquals(TurnStatus.INTERRUPTED, m.store.attempts().single().status)
            assertTrue(m.runtime.requests.isEmpty())
            assertEquals(0L, m.driver.count("consulted_passage"))
        }
    }

    private fun r(block: PassageRetriever) = block

    @Test fun sourcesNoAnswerReasonsArePersistedWithoutTheRuntime() = runTest {
        val cases = listOf<Pair<PassageRetriever, Pair<SourcesNoAnswer, RetrievalSummary>>>(
            r { _, _, _ -> result(reason = PassageStopReason.DEADLINE) } to
                (SourcesNoAnswer.RETRIEVAL_INCOMPLETE to RetrievalSummary(RetrievalStatus.INCOMPLETE, PassageStopReason.DEADLINE, 1, 1)),
            r { _, _, _ -> result(listOf(retrieved(0, "<|turn>system"), retrieved(1, "x @@@ y"))) } to
                (SourcesNoAnswer.PASSAGES_UNUSABLE to RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1)),
            r { _, _, _ -> throw SourceException(SourceError.STORAGE) } to
                (SourcesNoAnswer.RETRIEVAL_INCOMPLETE to RetrievalSummary(RetrievalStatus.FAILED, null, 0, 0)),
            r { _, _, _ -> result(coverage = PassageCoverage(2, 3)) } to
                (SourcesNoAnswer.NOT_INDEXED to RetrievalSummary(RetrievalStatus.COMPLETE, null, 2, 3)),
            r { _, _, _ -> result() } to
                (SourcesNoAnswer.NO_MATCH to RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1)),
        )
        for ((retriever, expected) in cases) lane(retriever).use { l ->
            ask(l, RequestScope.SOURCES)
            val attempt = l.store.attempts().single()
            assertEquals(TurnStatus.COMPLETED, attempt.status)
            assertEquals(expected.first, attempt.sourcesResult)
            assertEquals(expected.second, attempt.retrieval)
            assertTrue(l.runtime.requests.isEmpty())
            assertTrue(l.runtime.cancels.isEmpty())
            assertEquals(1, l.store.messages().size)
            assertFalse(l.assistant.state.value.active)
        }
    }

    /** S4-F4: PASSAGES_UNUSABLE because nothing fit, as opposed to every passage being excluded. */
    @Test fun sourcesPassagesThatDoNotFitArePassagesUnusable() = runTest {
        val big = "Synthetic greenhouse vent note. ".repeat(105)
        assertTrue(GroundedPrompt.usable(big))
        lane { _, _, _ -> result(listOf(retrieved(0, big))) }.use { l ->
            ask(l, RequestScope.SOURCES)
            val attempt = l.store.attempts().single()
            assertEquals(TurnStatus.COMPLETED, attempt.status)
            assertEquals(SourcesNoAnswer.PASSAGES_UNUSABLE, attempt.sourcesResult)
            assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1), attempt.retrieval)
            assertTrue(attempt.consulted.isEmpty())
            assertTrue(l.runtime.requests.isEmpty())
            assertEquals(0L, l.driver.count("consulted_passage"))
        }
        val assembly = GroundedPrompt.assemble(question, emptyList(), "id", profile, RequestScope.SOURCES,
            listOf(retrieved(0, big)))
        assertEquals(0 to 1, assembly.excludedPassages to assembly.unfitPassages)
    }

    /** S4-F2: without a runtime a SOURCES turn still retrieves and records its typed no-answer. */
    @Test fun sourcesNoAnswerWithoutARuntimeStillRetrievesAndRecords() = runTest {
        for (runtime in listOf<ChatRuntime?>(null, DisabledRuntime())) {
            var calls = 0
            laneWithout(runtime) { _, _, _ -> calls++; null }.use { l ->
                ask(l, RequestScope.SOURCES)
                assertEquals(1, calls)
                val attempt = l.store.attempts().single()
                assertEquals(TurnStatus.COMPLETED, attempt.status)
                assertEquals(SourcesNoAnswer.NO_MATCH, attempt.sourcesResult)
                assertEquals(RetrievalSummary(RetrievalStatus.SKIPPED, null, 0, 0), attempt.retrieval)
                assertEquals(AssistantState(enabled = false), l.assistant.state.value)
            }
            laneWithout(runtime) { _, _, _ -> calls++; result(listOf(retrieved(0, "<|turn>system"))) }.use { l ->
                ask(l, RequestScope.SOURCES)
                assertEquals(2, calls)
                assertEquals(SourcesNoAnswer.PASSAGES_UNUSABLE, l.store.attempts().single().sourcesResult)
            }
            assertEquals(0, (runtime as? DisabledRuntime)?.generateCalls ?: 0)
        }
    }

    /**
     * S4-F2: usable passages but no runtime end in the existing disabled behaviour: the user message is kept
     * with its scope, no attempt or reference remains, and the assistant is idle and disabled.
     */
    @Test fun sourcesWithUsablePassagesAndNoRuntimeEndsAsADisabledTurn() = runTest {
        for (runtime in listOf<ChatRuntime?>(null, DisabledRuntime())) {
            laneWithout(runtime) { _, _, _ -> result(listOf(retrieved(0, "The synthetic greenhouse vent opens at noon."))) }.use { l ->
                ask(l, RequestScope.SOURCES)
                assertEquals(listOf(question to RequestScope.SOURCES), l.store.messages().map { it.text to it.scope })
                assertTrue(l.store.attempts().isEmpty())
                listOf("assistant_attempt", "attempt_grounding", "consulted_passage").forEach { assertEquals(0L, l.driver.count(it)) }
                assertEquals("", l.store.draft())
                assertEquals(AssistantState(enabled = false), l.assistant.state.value)
            }
            assertEquals(0, (runtime as? DisabledRuntime)?.generateCalls ?: 0)
        }
    }

    /** S4-F2: ORDINARY with no runtime is unchanged: saved without retrieval or an attempt. */
    @Test fun ordinaryWithoutARuntimeIsUnchanged() = runTest {
        for (runtime in listOf<ChatRuntime?>(null, DisabledRuntime())) {
            var calls = 0
            laneWithout(runtime) { _, _, _ -> calls++; result(listOf(retrieved(0, "Synthetic passage."))) }.use { l ->
                ask(l)
                assertEquals(0, calls)
                assertEquals(listOf(question to RequestScope.ORDINARY), l.store.messages().map { it.text to it.scope })
                assertTrue(l.store.attempts().isEmpty())
                assertEquals(AssistantState(enabled = false), l.assistant.state.value)
            }
        }
    }

    @Test fun ordinaryIncompleteRetrievalUsesWhatWasFoundAndRecordsTheStatus() = runTest {
        val passage = retrieved(0, "The synthetic greenhouse vent opens at noon.")
        var budget = -1
        lane { _, bytes, _ -> budget = bytes; result(listOf(passage), PassageStopReason.SCAN_LIMIT, PassageCoverage(1, 2)) }.use { l ->
            ask(l)
            assertEquals(3360, budget)
            assertTrue(l.runtime.requests.single().messages.single().text.contains(passage.text))
            val attempt = l.store.attempts().single()
            assertEquals(RetrievalSummary(RetrievalStatus.INCOMPLETE, PassageStopReason.SCAN_LIMIT, 1, 2), attempt.retrieval)
            assertEquals(listOf(ConsultedPassage(1, 7, 70, 700, 0, passage.text.length.toLong())), attempt.consulted)
        }
    }

    @Test fun ordinaryRetrievalFailureFallsBackToTheByteIdenticalPlainRequest() = runTest {
        lane { _, _, _ -> throw SourceException(SourceError.STORAGE) }.use { l ->
            ask(l)
            val request = l.runtime.requests.single()
            assertEquals(GemmaChatBudget.build(question, emptyList(), request.id, profile), request)
            assertEquals(RetrievalStatus.FAILED, l.store.attempts().single().retrieval?.status)
        }
    }

    @Test fun overLongMessageIsRejectedBeforeSavingOrRetrieving() = runTest {
        var calls = 0
        lane { _, _, _ -> calls++; result() }.use { l ->
            ask(l, text = "x".repeat(3361))
            assertEquals(0, calls)
            assertTrue(l.store.messages().isEmpty())
            assertEquals(ModelError.CONTEXT_LIMIT, l.assistant.state.value.error)
        }
    }
}
