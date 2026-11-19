package dev.edgecompanion.core.conversation.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.db.CompanionDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Conversation migrations on temporary copies of schema snapshots using JDBC. */
class GroundingMigrationTest {
    private fun withSnapshot(version: Int, block: (JdbcSqliteDriver) -> Unit) {
        val temporary = Files.createTempDirectory("grounding-migration-").toFile()
        try {
            val project = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
            val copy = File(temporary, "synthetic.db")
            Files.copy(File(project, "shared/src/commonMain/sqldelight/dev/edgecompanion/db/$version.db").toPath(), copy.toPath())
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try { block(driver) } finally { driver.close() }
        } finally { temporary.deleteRecursively() }
    }
    private fun JdbcSqliteDriver.exec(vararg sql: String) = sql.forEach { execute(null, it, 0) }
    private fun JdbcSqliteDriver.tables(): List<String> = executeQuery(null,
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", { cursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)!!) }) }, 0).value

    @Test fun schemaSixConversationsMigrateAsOrdinaryWithoutReferences() {
        for (version in listOf(5, 6)) withSnapshot(version) { driver ->
            driver.exec(
                "INSERT INTO message(id, text, created_at, role, turn_id) VALUES (1, 'Synthetic question', 10, 'USER', 1)",
                "INSERT INTO message(id, text, created_at, role, turn_id, generation_id) VALUES (2, 'Synthetic answer', 11, 'ASSISTANT', 1, 'done')",
                "INSERT INTO message(id, text, created_at, role, turn_id) VALUES (3, 'Second question', 12, 'USER', 3)",
                "INSERT INTO assistant_attempt(id, user_id, epoch, status) VALUES ('done', 1, 0, 'COMPLETED')",
                "INSERT INTO assistant_attempt(id, user_id, epoch, status) VALUES ('open', 3, 0, 'PENDING')",
                "INSERT INTO draft VALUES (1, 'Synthetic draft')",
            )
            CompanionDatabase.Schema.migrate(driver, version.toLong(), CompanionDatabase.Schema.version)
            assertEquals(8L, CompanionDatabase.Schema.version)
            assertTrue(driver.tables().containsAll(listOf("message_scope", "attempt_grounding", "consulted_passage")))
            val store = SqlConversationStore(CompanionDatabase(driver))
            assertEquals(listOf(RequestScope.ORDINARY, RequestScope.ORDINARY, RequestScope.ORDINARY), store.messages().map { it.scope })
            assertEquals(listOf("Synthetic question", "Synthetic answer", "Second question"), store.messages().map { it.text })
            assertEquals("Synthetic draft", store.draft())
            store.reconcileAssistantTurns()
            val attempts = store.attempts()
            assertEquals(listOf(TurnStatus.COMPLETED, TurnStatus.INTERRUPTED), attempts.map { it.status })
            assertTrue(attempts.all { it.consulted.isEmpty() && it.retrieval == null && it.sourcesResult == null })
            // New turns record scope and references on the migrated database.
            val user = store.submitUserMessage("Scoped", 13, RequestScope.SOURCES)
            assertEquals(RequestScope.SOURCES, user.scope)
            assertTrue(store.startAssistantTurn("new", user.id, store.epoch(), TurnGrounding(0,
                RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1), listOf(ConsultedPassage(1, 4, 5, 6, 0, 10)))))
            assertEquals(listOf(ConsultedPassage(1, 4, 5, 6, 0, 10)), store.attempts().last().consulted)
        }
    }

    @Test fun startTransactionRejectsAStaleContextEpochAndWritesNoReferences() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val db = CompanionDatabase(driver)
            val store = SqlConversationStore(db)
            val user = store.submitUserMessage("Synthetic", 1)
            db.libraryQueries.advanceContext()
            val newer = store.submitUserMessage("Synthetic again", 2)
            val stale = TurnGrounding(0, null, listOf(ConsultedPassage(1, 1, 1, 1, 0, 5)))
            assertFalse(store.startAssistantTurn("stale", newer.id, store.epoch(), stale))
            assertTrue(store.attempts().isEmpty())
            assertTrue(store.startAssistantTurn("fresh", newer.id, store.epoch(), stale.copy(contextEpoch = 1)))
            assertEquals(1, store.attempts().single().consulted.size)
            assertEquals(TurnStatus.PENDING, store.attempts().single().status)
            assertTrue(user.id < newer.id)
        } finally { driver.close() }
    }

    @Test fun schemaSevenDraftMigratesWithOffDefaultAndStoresScopeSeparatelyFromDraftText() = withSnapshot(7) { driver ->
        driver.exec("INSERT INTO draft VALUES (1, 'Synthetic existing draft')")
        CompanionDatabase.Schema.migrate(driver, 7, CompanionDatabase.Schema.version)
        val store = SqlConversationStore(CompanionDatabase(driver))
        assertEquals("Synthetic existing draft", store.draft())
        assertEquals(RequestScope.ORDINARY, store.draftScope())
        store.saveDraftScope(RequestScope.SOURCES)
        assertEquals("Synthetic existing draft", store.draft())
        store.saveDraft("Synthetic changed draft")
        val restored = SqlConversationStore(CompanionDatabase(driver))
        assertEquals("Synthetic changed draft", restored.draft())
        assertEquals(RequestScope.SOURCES, restored.draftScope())
    }

    /** S4-F5: the PENDING attempt is committed with the message; begin and the SOURCES result finish that attempt. */
    @Test fun submitCreatesThePendingAttemptAtomicallyAndBeginRechecksTheEpoch() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val db = CompanionDatabase(driver)
            val store = SqlConversationStore(db)
            val refs = listOf(ConsultedPassage(1, 4, 5, 6, 0, 10))
            val first = store.submitUserMessage("Synthetic", 1, RequestScope.SOURCES, "a")
            assertEquals(listOf(AssistantAttempt("a", first.id, store.epoch(), TurnStatus.PENDING)), store.attempts())
            assertEquals(RequestScope.SOURCES, store.messages().single().scope)
            // A deletion advances the context: the prepared attempt can never begin or record a result.
            db.libraryQueries.advanceContext()
            assertFalse(store.beginAssistantTurn("a", TurnGrounding(0, null, refs)))
            assertFalse(store.beginAssistantTurn("a", TurnGrounding(1, null, refs)))
            assertFalse(store.recordSourcesResult("a", TurnGrounding(1, sourcesResult = SourcesNoAnswer.NO_MATCH)))
            assertEquals(0L, driver.count("attempt_grounding") + driver.count("consulted_passage"))
            // The stopped turn finishes the same attempt; no second attempt is created.
            store.finishAssistantTurn("a", TurnStatus.INTERRUPTED)
            assertEquals(listOf(TurnStatus.INTERRUPTED), store.attempts().map { it.status })

            val second = store.submitUserMessage("Synthetic again", 2, RequestScope.ORDINARY, "b")
            val summary = RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1)
            assertTrue(store.beginAssistantTurn("b", TurnGrounding(1, summary, refs)))
            assertTrue(store.beginAssistantTurn("b", TurnGrounding(1, summary, refs)))
            assertEquals(AssistantAttempt("b", second.id, store.epoch(), TurnStatus.PENDING, null, summary, refs), store.attempts().last())
            // An attempt that already began generating cannot also become a SOURCES result.
            assertFalse(store.recordSourcesResult("b", TurnGrounding(1, sourcesResult = SourcesNoAnswer.NO_MATCH)))
            store.finishAssistantTurn("b", TurnStatus.CANCELLED)

            val third = store.submitUserMessage("Scoped", 3, RequestScope.SOURCES, "c")
            assertTrue(store.recordSourcesResult("c", TurnGrounding(1, summary, sourcesResult = SourcesNoAnswer.NO_MATCH)))
            assertEquals(AssistantAttempt("c", third.id, store.epoch(), TurnStatus.COMPLETED, null, summary, emptyList(),
                SourcesNoAnswer.NO_MATCH), store.attempts().last())
            assertFalse(store.beginAssistantTurn("c", TurnGrounding(1, summary, refs)))

            // A failed attempt insert rolls the whole submission back: no message, scope or draft change.
            store.saveDraft("Kept draft")
            assertFails { store.submitUserMessage("Duplicate", 4, RequestScope.SOURCES, "c") }
            assertEquals(3, store.messages().size)
            assertEquals(3L, driver.count("message_scope"))
            assertEquals("Kept draft", store.draft())

            // Discard removes only a PENDING attempt that never began.
            val fourth = store.submitUserMessage("Disabled", 5, RequestScope.SOURCES, "d")
            store.discardPendingAssistantTurn("d")
            store.discardPendingAssistantTurn("c")
            assertEquals(listOf("a", "b", "c"), store.attempts().map { it.id })
            assertEquals(RequestScope.SOURCES, store.messages().last { it.id == fourth.id }.scope)
        } finally { driver.close() }
    }

    private fun JdbcSqliteDriver.count(table: String): Long = executeQuery(null, "SELECT count(*) FROM $table", {
        it.next(); QueryResult.Value(it.getLong(0)!!)
    }, 0).value
}
