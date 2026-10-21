package dev.edgecompanion.core.sources.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlin.test.*

class SourcesRepositoryTest {
    private class Fixture : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = CompanionDatabase(driver)
        var footprint = LibraryFootprint(0, 2L * 1024 * 1024 * 1024)
        var measurements = 0
        var failAfter: Int? = null
        val repo = SourcesRepository(db, LibraryCapacity {
            measurements++
            if (failAfter == measurements) footprint = LibraryFootprint(SourceLimits.LIBRARY_BYTES, footprint.freeBytes)
            footprint
        }) { 42 }
        init { CompanionDatabase.Schema.create(driver) }
        override fun close() = driver.close()
    }
    @Test fun importRenameInspectSearchAndDeleteAreDurableAndTextOnly() = Fixture().use { f ->
        val source = f.repo.importText("Contract", "Call John about the contract.", SourceKind.PASTED_TEXT)
        assertEquals(source, f.repo.list().items.single())
        assertEquals("Call John about the contract.", f.repo.text(source.id).items.single().text)
        assertEquals(source, f.repo.list("contract").items.single())
        assertTrue(f.repo.list("\"*():").items.isEmpty())
        assertTrue(f.repo.list("contract OR coffee").items.isEmpty())
        f.repo.rename(source.id, "Work")
        assertEquals("Work", f.repo.list().items.single().title)
        assertTrue(f.repo.delete(source.id)); assertFalse(f.repo.delete(source.id))
        assertTrue(f.repo.list("contract").items.isEmpty())
        assertEquals(0, f.db.libraryQueries.chunkCount().executeAsOne())
        assertEquals(SourceError.MISSING, assertFailsWith<SourceException> { f.repo.text(source.id) }.error)
    }
    @Test fun pagesAreKeysetBoundedAndTextFetchNeverExceedsEightChunks() = Fixture().use { f ->
        repeat(55) { f.repo.importText("Source $it", "needle " + "x".repeat(20_000), SourceKind.PASTED_TEXT) }
        val first = f.repo.list("needle")
        assertEquals(50, first.items.size)
        val second = f.repo.list("needle", first.next!!)
        assertEquals(5, second.items.size); assertNull(second.next)
        assertTrue(first.items.none { a -> second.items.any { it.id == a.id } })
        val text = f.repo.text(first.items.first().id)
        assertEquals(8, text.items.size)
        assertTrue(text.items.sumOf { it.text.encodeToByteArray().size } <= 64 * 1024)
        assertTrue(f.repo.text(first.items.first().id, text.next!!).items.size <= 8)
    }
    @Test fun keywordAtChunkBoundaryIsNotLost() = Fixture().use { f ->
        val text = "x ".repeat(1020) + "boundarykeyword"
        f.repo.importText("Boundary", text, SourceKind.PASTED_TEXT)
        assertEquals(1, f.repo.list("boundarykeyword").items.size)
    }
    @Test fun quotaFailureAtAdmissionOrMidWriteRollsBackWithoutDamagingPriorSource() = Fixture().use { f ->
        val keep = f.repo.importText("Keep", "prior", SourceKind.PASTED_TEXT)
        f.failAfter = f.measurements + 3
        assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> {
            f.repo.importText("Rejected", "x".repeat(6000), SourceKind.PASTED_TEXT) }.error)
        assertEquals(listOf(keep), f.repo.list().items)
        assertTrue(f.repo.list("Rejected").items.isEmpty())
        assertFailsWith<SourceException> { f.repo.rename(keep.id, "No space") }
        assertEquals("Keep", f.repo.list().items.single().title)
        // No admission reservation is needed to delete, even if the DB remains physically large.
        assertTrue(f.repo.delete(keep.id))
    }
    @Test fun freeSpaceUnknownCapacityAndRowCeilingsFailClosed() = Fixture().use { f ->
        listOf(LibraryFootprint(0, -1), LibraryFootprint(-1, Long.MAX_VALUE),
            LibraryFootprint(0, SourceLimits.DISK_RESERVE)).forEach {
            f.footprint = it
            assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> {
                f.repo.importText("No", "text", SourceKind.PASTED_TEXT) }.error)
        }
        f.footprint = LibraryFootprint(0, Long.MAX_VALUE)
        repeat(500) { f.db.libraryQueries.insertSource("Existing", SourceKind.PASTED_TEXT.name, 0) }
        assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> {
            f.repo.importText("No", "text", SourceKind.PASTED_TEXT) }.error)
    }
    @Test fun deleteFencesOldChatAndRetriesButPreservesVisibleHistoryDraftAttachmentsAndSearch() = Fixture().use { f ->
        val chat = SqlConversationStore(f.db)
        val source = f.repo.importText("Personal", "old fact", SourceKind.PASTED_TEXT)
        val user = chat.submitUserMessage("old fact", 1)
        assertTrue(chat.startAssistantTurn("old", user.id, chat.epoch()))
        chat.saveDraft("Unsent"); chat.reserve(Attachment("draft", "a.txt", "text/plain"))
        val draftEpoch = chat.epoch()
        f.repo.delete(source.id)
        assertEquals(1, chat.contextEpoch()); assertEquals(user.id, chat.messageFence())
        assertFalse(chat.completeAssistantTurn("old", "late old fact", 2))
        assertFalse(chat.startAssistantTurn("retry", user.id, chat.epoch()))
        assertEquals("old fact", chat.messages().single().text)
        assertEquals("old fact", f.db.conversationQueries.search("fact").executeAsOne().text)
        assertEquals("Unsent", chat.draft()); assertEquals(draftEpoch, chat.epoch())
        assertNull(chat.attachments().single().messageId)
        val fresh = chat.submitUserMessage("explicit new fact", 3)
        assertTrue(chat.startAssistantTurn("new", fresh.id, chat.epoch()))
        assertTrue(chat.completeAssistantTurn("new", "new response", 4))
        chat.clear()
        assertEquals(1, chat.contextEpoch())
    }
    @Test fun contextAndLibrarySurviveReopenAndInterruptedAttemptReconciles() = Fixture().use { f ->
        val chat = SqlConversationStore(f.db)
        val source = f.repo.importUtf8("UTF8", "caf\u00E9".encodeToByteArray())
        val user = chat.submitUserMessage("before", 1)
        assertTrue(chat.startAssistantTurn("pending", user.id, chat.epoch()))
        f.repo.delete(source.id)
        val reopened = SqlConversationStore(CompanionDatabase(f.driver))
        reopened.reconcileAssistantTurns()
        assertEquals(1, reopened.contextEpoch()); assertEquals(user.id, reopened.messageFence())
        assertEquals(TurnStatus.INTERRUPTED, reopened.attempts().single().status)
        assertFalse(reopened.completeAssistantTurn("pending", "stale", 2))
    }
    @Test fun schemaFourMigrationPreservesExistingChatStateAndStartsWithNoFence() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val db = CompanionDatabase(driver); val chat = SqlConversationStore(db)
            val user = chat.submitUserMessage("legacy", 1)
            chat.saveDraft("draft")
            assertTrue(chat.startAssistantTurn("pending", user.id, chat.epoch()))
            // Reconstruct the immediately previous schema, without changing any legacy rows.
            listOf("DROP TABLE source_search", "DROP TABLE source_chunk", "DROP TABLE source_revision",
                "DROP TABLE source", "DROP TABLE context_eligibility", "ALTER TABLE assistant_attempt DROP COLUMN context_epoch")
                .forEach { driver.execute(null, it, 0) }
            CompanionDatabase.Schema.migrate(driver, 4, CompanionDatabase.Schema.version)
            val migrated = SqlConversationStore(CompanionDatabase(driver))
            assertEquals(user, migrated.messages().single()); assertEquals("draft", migrated.draft())
            assertEquals(0, migrated.contextEpoch()); assertEquals(0, migrated.messageFence())
            assertTrue(migrated.completeAssistantTurn("pending", "reply", 2))
        } finally { driver.close() }
    }
}
