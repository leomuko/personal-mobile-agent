package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Real JDBC store and retriever, fake runtime, synthetic text only. Does not establish SQLCipher behaviour. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroundedSessionTest {
    private val profile = ChatContextProfile.EXTENDED_4096
    private class Fixture(capacity: LibraryCapacity = unlimited, migrateLegacy: (CompanionDatabase.() -> Unit)? = null) : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: CompanionDatabase
        init {
            if (migrateLegacy == null) CompanionDatabase.Schema.create(driver) else CompanionDatabase.Schema.migrate(driver, 0, 5)
            db = CompanionDatabase(driver)
            migrateLegacy?.let { db.it(); CompanionDatabase.Schema.migrate(driver, 5, CompanionDatabase.Schema.version) }
        }
        val store = SqlConversationStore(db)
        val repo = SourcesRepository(db, capacity) { 1 }
        val runtime = RecordingRuntime()
        override fun close() = driver.close()
    }
    private fun TestScope.session(f: Fixture, runtime: ChatRuntime? = f.runtime) = ConversationSession(
        StandardTestDispatcher(testScheduler), { ConversationController(f.store) { 1 } }, {},
        chatRuntime = runtime, sourcesProvider = { f.repo }, chatProfile = profile).also { it.start(); runCurrent() }
    private suspend fun TestScope.send(session: ConversationSession, text: String, scope: RequestScope = RequestScope.ORDINARY) {
        session.editDraft(text); runCurrent(); session.submit(scope); runCurrent()
    }
    private val garden = "The synthetic greenhouse vent opens at twenty degrees on weekdays."
    private val question = "When does the greenhouse vent open?"

    @Test fun ordinaryHitPacksEvidenceAndReferencesSurviveRestart() = runTest {
        Fixture().use { f ->
            var session = session(f)
            try {
                val source = session.addTextSource("Garden", garden)
                session.addTextSource("Boats", "Unrelated synthetic content about harbour ropes.")
                send(session, question)
                val text = f.runtime.requests.single().messages.single().text
                assertTrue(text.startsWith(GroundedPrompt.ORDINARY_INSTRUCTION))
                assertTrue(text.contains("${GroundedPrompt.label(1)}\n$garden\n"))
                assertTrue(text.endsWith("\n\n$question"))
                val attempt = f.store.attempts().single()
                assertEquals(TurnStatus.PENDING, attempt.status)
                assertEquals(listOf(ConsultedPassage(1, source.id, source.revisionId, attempt.consulted.single().passageId,
                    0, garden.length.toLong())), attempt.consulted)
                assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 2, 2), attempt.retrieval)
                f.runtime.finish("Synthetic reply"); runCurrent()
                val completed = f.store.attempts().single()
                assertEquals(TurnStatus.COMPLETED, completed.status)
                // References only: the passage text is not copied into the conversation tables.
                assertEquals(0L, f.driver.executeQuery(null, "SELECT count(*) FROM message WHERE text LIKE '%twenty degrees%'",
                    { it.next(); app.cash.sqldelight.db.QueryResult.Value(it.getLong(0)!!) }, 0).value)
                session.shutdown(); session = session(f)
                assertEquals(listOf(completed), session.state.value.attempts)
                assertEquals(RequestScope.ORDINARY, session.state.value.messages.first().scope)
            } finally { session.shutdown() }
        }
    }

    @Test fun ordinaryWithoutAHitIsByteIdenticalToPlainChat() = runTest {
        Fixture().use { f ->
            val session = session(f)
            try {
                session.addTextSource("Boats", "Unrelated synthetic content about harbour ropes.")
                send(session, "Hello there"); f.runtime.finish("Synthetic hello"); runCurrent()
                send(session, question)
                val request = f.runtime.requests.last()
                assertEquals(GemmaChatBudget.build(question, listOf("Hello there" to "Synthetic hello"), request.id, profile), request)
                val attempt = f.store.attempts().last()
                assertTrue(attempt.consulted.isEmpty())
                assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1), attempt.retrieval)
            } finally { session.shutdown() }
        }
    }

    @Test fun sourcesNoMatchPersistsTypedResultWithoutTheRuntimeAndSurvivesRestart() = runTest {
        Fixture().use { f ->
            var session = session(f)
            try {
                session.addTextSource("Boats", "Unrelated synthetic content about harbour ropes.")
                send(session, question, RequestScope.SOURCES)
                assertTrue(f.runtime.requests.isEmpty())
                val attempt = f.store.attempts().single()
                assertEquals(TurnStatus.COMPLETED, attempt.status)
                assertEquals(SourcesNoAnswer.NO_MATCH, attempt.sourcesResult)
                assertTrue(attempt.consulted.isEmpty())
                assertEquals(listOf(MessageRole.USER), f.store.messages().map { it.role })
                assertEquals(RequestScope.SOURCES, f.store.messages().single().scope)
                assertFalse(session.state.value.assistant.active)
                assertNull(session.state.value.assistant.error)
                session.retryAssistant(f.store.messages().single().id); runCurrent()
                assertEquals(1, f.store.attempts().size)
                session.shutdown(); session = session(f)
                assertEquals(listOf(attempt), session.state.value.attempts)
                assertEquals(RequestScope.SOURCES, session.state.value.messages.single().scope)
            } finally { session.shutdown() }
        }
    }

    @Test fun sourcesNotYetIndexedIsTypedWhenBackfillCannotRun() = runTest {
        // Capacity refuses writes, so backfill stops on quota and coverage stays partial.
        Fixture(capacity = LibraryCapacity { LibraryFootprint(SourceLimits.LIBRARY_BYTES, Long.MAX_VALUE) }).use { f ->
            f.db.legacyRevision("Legacy", garden)
            val session = session(f)
            try {
                assertEquals(PassageCoverage(0, 1), f.repo.passageCoverage())
                send(session, question, RequestScope.SOURCES)
                assertTrue(f.runtime.requests.isEmpty())
                val attempt = f.store.attempts().single()
                assertEquals(SourcesNoAnswer.NOT_INDEXED, attempt.sourcesResult)
                assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 0, 1), attempt.retrieval)
            } finally { session.shutdown() }
        }
    }

    @Test fun retryIsDeterministicKeepsScopeAndRecordsItsOwnReferences() = runTest {
        Fixture().use { f ->
            val session = session(f)
            try {
                session.addTextSource("Garden", garden)
                send(session, question, RequestScope.SOURCES)
                val first = f.runtime.requests.single()
                assertTrue(first.messages.single().text.startsWith(GroundedPrompt.SOURCES_INSTRUCTION))
                session.cancelAssistant(); runCurrent()
                assertEquals(TurnStatus.CANCELLED, f.store.attempts().single().status)
                val user = f.store.messages().single()
                session.retryAssistant(user.id); runCurrent()
                val second = f.runtime.requests.last()
                assertNotEquals(first.id, second.id)
                assertEquals(first.messages, second.messages)
                assertEquals(first.contextTokens to first.outputTokens, second.contextTokens to second.outputTokens)
                val (cancelled, retried) = f.store.attempts()
                assertEquals(cancelled.consulted, retried.consulted)
                assertEquals(1, retried.consulted.size)
                assertEquals(RequestScope.SOURCES, f.store.messages().single().scope)
                assertEquals(2L, f.driver.count("consulted_passage"))
            } finally { session.shutdown() }
        }
    }

    @Test fun deletionDuringGenerationInterruptsAdvancesEpochAndLeavesNoStaleEvidence() = runTest {
        Fixture().use { f ->
            val session = session(f)
            try {
                val source = session.addTextSource("Garden", garden)
                send(session, question)
                assertEquals(1, f.store.attempts().single().consulted.size)
                assertTrue(session.deleteSource(source.id)); runCurrent()
                val interrupted = f.store.attempts().single()
                assertEquals(TurnStatus.INTERRUPTED, interrupted.status)
                assertEquals(1L, f.store.contextEpoch())
                // The reference was written before the fence; it stays a reference and resolves as deleted.
                assertEquals(source.id, interrupted.consulted.single().sourceId)
                send(session, question)
                val after = f.runtime.requests.last()
                assertEquals(GemmaChatBudget.build(question, emptyList(), after.id, profile), after)
                assertFalse(after.messages.any { it.text.contains("twenty degrees") })
                assertTrue(f.store.attempts().last().consulted.isEmpty())
            } finally { session.shutdown() }
        }
    }

    @Test fun clearRemovesScopesGroundingAndReferences() = runTest {
        Fixture().use { f ->
            val session = session(f)
            try {
                session.addTextSource("Garden", garden)
                send(session, question); f.runtime.finish("Synthetic reply"); runCurrent()
                send(session, "Anything about harbour ropes?", RequestScope.SOURCES)
                listOf("message_scope", "attempt_grounding", "consulted_passage").forEach { assertTrue(f.driver.count(it) > 0) }
                session.clear(); runCurrent()
                listOf("message", "assistant_attempt", "message_scope", "attempt_grounding", "consulted_passage")
                    .forEach { assertEquals(0L, f.driver.count(it), it) }
                assertEquals(1, session.listSources().items.size)
            } finally { session.shutdown() }
        }
    }

    @Test fun backfillConvergesAndDelaysUserWorkByAtMostOneBudgetPlusOneRevision() = runTest {
        lateinit var session: ConversationSession
        lateinit var f: Fixture
        // Synthetic clock: every capacity check inside a publish costs 40 ms (3 checks per one-passage revision).
        val revisionMillis = 120L
        var now = 0L
        var queuedAt = -1L
        var lastUnsaved = -1L
        f = Fixture(capacity = LibraryCapacity {
            now += 40
            if (queuedAt < 0) { queuedAt = now; session.editDraft("typed during backfill") }
            else if (f.store.draft().isEmpty()) lastUnsaved = now
            LibraryFootprint(0, Long.MAX_VALUE)
        }) { repeat(12) { legacyRevision("Legacy $it", "Synthetic legacy note $it about garden vents.") } }
        f.use {
            session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(f.store) { 1 } }, {},
                chatRuntime = f.runtime, sourcesProvider = { f.repo }, chatProfile = profile, monotonicMillis = { now })
            try {
                assertEquals(PassageCoverage(0, 12), f.repo.passageCoverage())
                session.start(); runCurrent()
                assertEquals(PassageCoverage(12, 12), f.repo.passageCoverage())
                assertEquals("typed during backfill", session.state.value.draft)
                // The draft save waited behind the current batch only, not the whole backfill.
                assertTrue(lastUnsaved >= queuedAt)
                assertTrue(lastUnsaved - queuedAt <= ConversationSession.BACKFILL_BATCH_MILLIS + revisionMillis,
                    "delay ${lastUnsaved - queuedAt} ms")
                assertTrue(now - queuedAt > ConversationSession.BACKFILL_BATCH_MILLIS + revisionMillis)
                send(session, "Which note mentions garden vents?", RequestScope.SOURCES)
                assertTrue(f.runtime.requests.single().messages.single().text.contains("garden vents"))
            } finally { session.shutdown() }
        }
    }

    @Test fun emptyLibrarySkipsRetrievalForBothScopes() = runTest {
        Fixture().use { f ->
            val session = session(f)
            try {
                send(session, question)
                val request = f.runtime.requests.single()
                assertEquals(GemmaChatBudget.build(question, emptyList(), request.id, profile), request)
                assertEquals(RetrievalSummary(RetrievalStatus.SKIPPED, null, 0, 0), f.store.attempts().single().retrieval)
                f.runtime.finish("Synthetic reply"); runCurrent()
                send(session, "Anything in my sources?", RequestScope.SOURCES)
                assertEquals(1, f.runtime.requests.size)
                val result = f.store.attempts().last()
                assertEquals(TurnStatus.COMPLETED, result.status)
                assertEquals(SourcesNoAnswer.NO_MATCH, result.sourcesResult)
                assertEquals(RetrievalSummary(RetrievalStatus.SKIPPED, null, 0, 0), result.retrieval)
            } finally { session.shutdown() }
        }
    }

    /** S4-F2: the SOURCES no-answer path does not depend on generation capability. */
    @Test fun sourcesNoAnswerIsRecordedWithAnAbsentOrDisabledRuntime() = runTest {
        for (disabled in listOf(null, DisabledRuntime())) Fixture().use { f ->
            val session = session(f, runtime = disabled)
            try {
                send(session, question, RequestScope.SOURCES)
                val result = f.store.attempts().single()
                assertEquals(TurnStatus.COMPLETED, result.status)
                assertEquals(SourcesNoAnswer.NO_MATCH, result.sourcesResult)
                assertEquals(RetrievalSummary(RetrievalStatus.SKIPPED, null, 0, 0), result.retrieval)
                assertEquals(RequestScope.SOURCES, f.store.messages().single().scope)
                assertEquals(0, disabled?.generateCalls ?: 0)
                assertEquals(AssistantState(enabled = false), session.state.value.assistant)
                // A library without a match: retrieval runs and the typed result is recorded.
                session.addTextSource("Boats", "Unrelated synthetic content about harbour ropes.")
                send(session, question, RequestScope.SOURCES)
                val noMatch = f.store.attempts().last()
                assertEquals(SourcesNoAnswer.NO_MATCH, noMatch.sourcesResult)
                assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1), noMatch.retrieval)
                assertEquals(0, disabled?.generateCalls ?: 0)
            } finally { session.shutdown() }
        }
    }

    @Test fun backfillStopsWithTheSessionAndResumesOnTheNextRestore() = runTest {
        lateinit var session: ConversationSession
        lateinit var f: Fixture
        var stopOnMeasure = true
        f = Fixture(capacity = LibraryCapacity {
            if (stopOnMeasure) { stopOnMeasure = false; session.stop() }
            LibraryFootprint(0, Long.MAX_VALUE)
        }) { repeat(5) { legacyRevision("Legacy $it", "Synthetic legacy note $it.") } }
        f.use {
            session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(f.store) { 1 } }, {},
                sourcesProvider = { f.repo })
            session.start(); runCurrent()
            assertEquals(PassageCoverage(0, 5), f.repo.passageCoverage())
            session.shutdown()
            val next = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(f.store) { 1 } }, {},
                sourcesProvider = { f.repo })
            try {
                next.start(); runCurrent()
                assertEquals(PassageCoverage(5, 5), f.repo.passageCoverage())
            } finally { next.shutdown() }
        }
    }
}
