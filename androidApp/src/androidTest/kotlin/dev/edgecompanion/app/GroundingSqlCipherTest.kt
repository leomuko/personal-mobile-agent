package dev.edgecompanion.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.AssistantAttempt
import dev.edgecompanion.core.conversation.model.ConsultedPassage
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.conversation.model.RetrievalStatus
import dev.edgecompanion.core.conversation.model.RetrievalSummary
import dev.edgecompanion.core.conversation.model.SourcesNoAnswer
import dev.edgecompanion.core.conversation.model.TurnGrounding
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.conversation.session.ConversationController
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.db.CompanionDatabase
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S4 conversation grounding on SQLCipher: migration 6, request scope, grounding and references, the
 * SOURCES result, stopped turns, S4-F5 recovery, clear and reopen. Synthetic, randomly named fixtures only;
 * never resolves the app's database or session. Requires secureQa and p2GroundingSqlCipher=true.
 */
@RunWith(AndroidJUnit4::class)
class GroundingSqlCipherTest {
    @Before fun requireSecureQaOptInBeforeContextAccess() {
        assumeTrue("Grounding gate requires the separate secureQa application", BuildConfig.DEBUG &&
            BuildConfig.BUILD_TYPE == "secureQa" &&
            BuildConfig.APPLICATION_ID == "dev.edgecompanion.app.secureqa")
        assumeTrue("Grounding gate requires p2GroundingSqlCipher=true",
            InstrumentationRegistry.getArguments().getString("p2GroundingSqlCipher") == "true")
    }

    private val summary = RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1)
    private val references = listOf(ConsultedPassage(1, 4, 5, 6, 0, 10), ConsultedPassage(2, 4, 5, 7, 10, 20))

    @Test fun encryptedSchemaSixMigratesToSevenAsOrdinaryWithoutReferences() = fixture().use { f ->
        assertEquals(8L, CompanionDatabase.Schema.version)
        // Recreate the schema-6 shape on the encrypted fixture, then write legacy rows.
        f.db.transaction {
            f.execute("DROP TABLE draft_scope")
            f.execute("DROP TABLE consulted_passage")
            f.execute("DROP TABLE attempt_grounding")
            f.execute("DROP TABLE message_scope")
            f.execute("INSERT INTO message(id, text, created_at, role, turn_id) VALUES (1, 'Synthetic question', 10, 'USER', 1)")
            f.execute("INSERT INTO message(id, text, created_at, role, turn_id, generation_id) VALUES (2, 'Synthetic answer', 11, 'ASSISTANT', 1, 'done')")
            f.execute("INSERT INTO message(id, text, created_at, role, turn_id) VALUES (3, 'Second question', 12, 'USER', 3)")
            f.execute("INSERT INTO assistant_attempt(id, user_id, epoch, status) VALUES ('done', 1, 0, 'COMPLETED')")
            f.execute("INSERT INTO assistant_attempt(id, user_id, epoch, status) VALUES ('open', 3, 0, 'PENDING')")
            f.execute("PRAGMA user_version = 6")
        }
        assertEquals(6L, f.scalar("PRAGMA user_version"))
        assertEquals(0L, f.scalar("SELECT count(*) FROM sqlite_master WHERE name IN " +
            "('message_scope', 'attempt_grounding', 'consulted_passage')"))

        // Reopening through the encrypted factory applies migrations 6 and 7.
        f.reopen()
        assertEquals(8L, f.scalar("PRAGMA user_version"))
        assertEquals(3L, f.scalar("SELECT count(*) FROM sqlite_master WHERE name IN " +
            "('message_scope', 'attempt_grounding', 'consulted_passage')"))
        val store = f.store
        assertEquals(listOf("Synthetic question", "Synthetic answer", "Second question"), store.messages().map { it.text })
        assertTrue(store.messages().all { it.scope == RequestScope.ORDINARY })
        store.reconcileAssistantTurns()
        assertEquals(listOf(AssistantAttempt("done", 1, 0, TurnStatus.COMPLETED), AssistantAttempt("open", 3, 0, TurnStatus.INTERRUPTED)),
            store.attempts())
        assertEquals(0L, f.scalar("SELECT count(*) FROM consulted_passage") + f.scalar("SELECT count(*) FROM attempt_grounding"))
        f.assertEncryptedOnDisk("Second question")
    }

    @Test fun scopeGroundingAndReferencesAreTransactionalAndAStaleEpochRollsBack() = fixture().use { f ->
        val store = f.store
        // Submit commits the message, its scope and its PENDING attempt together.
        val first = store.submitUserMessage("Synthetic scoped question", 1, RequestScope.SOURCES, "first")
        assertEquals(RequestScope.SOURCES, store.messages().single().scope)
        assertEquals(listOf(AssistantAttempt("first", first.id, store.epoch(), TurnStatus.PENDING)), store.attempts())
        assertTrue(store.beginAssistantTurn("first", TurnGrounding(0, summary, references)))
        assertEquals(AssistantAttempt("first", first.id, store.epoch(), TurnStatus.PENDING, null, summary, references),
            store.attempts().single())

        // A failed attempt insert rolls back the whole submission.
        store.saveDraft("Synthetic draft")
        assertThrows(Exception::class.java) { store.submitUserMessage("Duplicate", 2, RequestScope.SOURCES, "first") }
        assertEquals(1, store.messages().size)
        assertEquals(1L, f.scalar("SELECT count(*) FROM message_scope"))
        assertEquals("Synthetic draft", store.draft())

        // A context change between submit and begin: nothing is written for the prepared attempt.
        val second = store.submitUserMessage("Synthetic second question", 3, RequestScope.ORDINARY, "second")
        f.db.libraryQueries.advanceContext()
        assertFalse(store.beginAssistantTurn("second", TurnGrounding(0, summary, references)))
        assertFalse(store.beginAssistantTurn("second", TurnGrounding(1, summary, references)))
        assertEquals(2L, f.scalar("SELECT count(*) FROM consulted_passage"))
        assertEquals(1L, f.scalar("SELECT count(*) FROM attempt_grounding"))
        store.finishAssistantTurn("second", TurnStatus.INTERRUPTED)
        assertEquals(TurnStatus.INTERRUPTED, store.attempts().first { it.userId == second.id }.status)

        val expected = store.attempts()
        val messages = store.messages()
        f.reopen()
        assertEquals(expected, f.store.attempts())
        assertEquals(messages, f.store.messages())
        f.assertEncryptedOnDisk("Synthetic scoped question")
    }

    @Test fun encryptedSchemaSevenDraftMigratesOffAndRestoresStickyScopeAfterReopen() = fixture().use { f ->
        f.store.saveDraft("Synthetic migration draft")
        f.db.transaction {
            f.execute("DROP TABLE draft_scope")
            f.execute("PRAGMA user_version = 7")
        }
        assertEquals(7L, f.scalar("PRAGMA user_version"))
        f.reopen()
        assertEquals(8L, f.scalar("PRAGMA user_version"))
        assertEquals("Synthetic migration draft", f.store.draft())
        assertEquals(RequestScope.ORDINARY, f.store.draftScope())
        f.store.saveDraftScope(RequestScope.SOURCES)
        f.reopen()
        assertEquals("Synthetic migration draft", f.store.draft())
        assertEquals(RequestScope.SOURCES, f.store.draftScope())
        f.assertEncryptedOnDisk("Synthetic migration draft")
    }

    @Test fun sourcesResultAndStoppedTurnsFinishTheirOwnAttempt() = fixture().use { f ->
        val store = f.store
        val answered = store.submitUserMessage("Synthetic sources question", 1, RequestScope.SOURCES, "result")
        assertTrue(store.recordSourcesResult("result", TurnGrounding(0, summary, sourcesResult = SourcesNoAnswer.NOT_INDEXED)))
        val cancelled = store.submitUserMessage("Synthetic cancelled", 2, RequestScope.SOURCES, "cancelled")
        store.finishAssistantTurn("cancelled", TurnStatus.CANCELLED)
        val interrupted = store.submitUserMessage("Synthetic interrupted", 3, RequestScope.ORDINARY, "interrupted")
        store.finishAssistantTurn("interrupted", TurnStatus.INTERRUPTED)
        val discarded = store.submitUserMessage("Synthetic no runtime", 4, RequestScope.SOURCES, "discarded")
        store.discardPendingAssistantTurn("discarded")
        val expected = listOf(
            AssistantAttempt("result", answered.id, store.epoch() - 3, TurnStatus.COMPLETED, null, summary, emptyList(),
                SourcesNoAnswer.NOT_INDEXED),
            AssistantAttempt("cancelled", cancelled.id, store.epoch() - 2, TurnStatus.CANCELLED),
            AssistantAttempt("interrupted", interrupted.id, store.epoch() - 1, TurnStatus.INTERRUPTED),
        )
        assertEquals(expected, store.attempts())
        assertEquals(4, store.messages().size)
        assertEquals(RequestScope.SOURCES, store.messages().last { it.id == discarded.id }.scope)
        assertEquals(0L, f.scalar("SELECT count(*) FROM message WHERE role = 'ASSISTANT'"))
        f.reopen()
        assertEquals(expected, f.store.attempts())
    }

    /** S4-F5: the PENDING attempt is committed with the message, so a loss during retrieval restores INTERRUPTED. */
    @Test fun processLossDuringRetrievalRestoresTheSameTurnAsInterrupted() = fixture().use { f ->
        runBlocking {
            val controller = ConversationController(f.store) { 1 }
            controller.restore()
            controller.editDraft("Synthetic question during retrieval")
            val user = checkNotNull(controller.submit(RequestScope.SOURCES, "lost", "Synthetic question during retrieval"))
            assertEquals(TurnStatus.PENDING, controller.state.value.attempts.single().status)
            // Retrieval would run now. Close the connection without finishing anything, then reopen and restore.
            f.reopen()
            val restored = ConversationController(f.store) { 2 }
            restored.restore()
            val state = restored.state.value
            assertEquals(listOf(user), state.messages)
            assertEquals(RequestScope.SOURCES, state.messages.single().scope)
            assertEquals(listOf(AssistantAttempt("lost", user.id, state.draftEpoch, TurnStatus.INTERRUPTED)), state.attempts)
            // Retry creates its own attempt and record for the same user turn.
            assertTrue(restored.startAssistant("retry", user.id, state.draftEpoch, state.contextEpoch))
            assertTrue(restored.beginAssistant("retry", state.contextEpoch, TurnGrounding(retrieval = summary, consulted = references)))
            assertEquals(listOf("lost" to TurnStatus.INTERRUPTED, "retry" to TurnStatus.PENDING),
                restored.state.value.attempts.map { it.id to it.status })
            assertEquals(references, restored.state.value.attempts.last().consulted)
            assertEquals(RequestScope.SOURCES, restored.state.value.messages.single().scope)
        }
    }

    @Test fun clearRemovesScopesGroundingAndReferences() = fixture().use { f ->
        val store = f.store
        store.submitUserMessage("Synthetic one", 1, RequestScope.SOURCES, "one")
        assertTrue(store.beginAssistantTurn("one", TurnGrounding(0, summary, references)))
        store.submitUserMessage("Synthetic two", 2, RequestScope.SOURCES, "two")
        assertTrue(store.recordSourcesResult("two", TurnGrounding(0, summary, sourcesResult = SourcesNoAnswer.NO_MATCH)))
        val tables = listOf("message", "assistant_attempt", "message_scope", "attempt_grounding", "consulted_passage")
        tables.forEach { assertTrue(it, f.scalar("SELECT count(*) FROM $it") > 0) }
        store.clear()
        tables.forEach { assertEquals(it, 0L, f.scalar("SELECT count(*) FROM $it")) }
        f.reopen()
        tables.forEach { assertEquals(it, 0L, f.scalar("SELECT count(*) FROM $it")) }
    }

    private fun fixture(): Fixture {
        // No context, path, key or database access occurs in field initializers/setup.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.edgecompanion.app.secureqa", context.packageName)
        return Fixture(context)
    }

    private class Fixture(private val context: Context) : Closeable {
        private val name = "grounding-sqlcipher-test-${UUID.randomUUID()}.db"
        private var driver: SqlDriver? = null
        val db get() = CompanionDatabase(checkNotNull(driver))
        val store get() = SqlConversationStore(db)

        init {
            try { reopen() }
            catch (failure: Exception) {
                try { close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun reopen() {
            val previous = driver
            driver = null
            previous?.close()
            driver = EncryptedDatabaseFactory.open(context, name)
            // Force the lazy Android open (and any migration) before returning.
            check(scalar("PRAGMA user_version") == CompanionDatabase.Schema.version)
        }

        fun execute(sql: String) { checkNotNull(driver).execute(null, sql, 0) }

        fun scalar(sql: String): Long = checkNotNull(driver).executeQuery(null, sql, { cursor ->
            check(cursor.next().value)
            QueryResult.Value(checkNotNull(cursor.getLong(0)))
        }, 0).value

        fun assertEncryptedOnDisk(marker: String) {
            // Close first so the test checks the persisted database rather than only a WAL.
            val previous = driver
            driver = null
            previous?.close()
            val bytes = context.getDatabasePath(name).readBytes()
            try {
                assertTrue(bytes.isNotEmpty())
                val disk = bytes.toString(Charsets.ISO_8859_1)
                assertFalse(disk.startsWith("SQLite format 3"))
                assertFalse(disk.contains(marker))
            } finally { bytes.fill(0) }
        }

        override fun close() {
            // Attempt every cleanup even when closing/deleting a preceding resource fails.
            val cleanup = listOf<() -> Unit>(
                { val previous = driver; driver = null; previous?.close(); Unit },
                {
                    context.deleteDatabase(name)
                    val path = context.getDatabasePath(name).path
                    listOf("", "-wal", "-shm", "-journal").forEach { check(!File(path + it).exists()) }
                },
                { val key = File(context.noBackupFilesDir, "$name.key"); key.delete(); check(!key.exists()) },
                { val backup = File(context.noBackupFilesDir, "$name.key.bak"); backup.delete(); check(!backup.exists()) },
                { val pending = File(context.noBackupFilesDir, "$name.key.new"); pending.delete(); check(!pending.exists()) },
                { KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry("dev.edgecompanion.database.$name")
                    check(!containsAlias("dev.edgecompanion.database.$name"))
                }; Unit },
            )
            var failure: Exception? = null
            cleanup.forEach { action ->
                try { action() }
                catch (error: Exception) {
                    val first = failure
                    if (first == null) failure = error else first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}
