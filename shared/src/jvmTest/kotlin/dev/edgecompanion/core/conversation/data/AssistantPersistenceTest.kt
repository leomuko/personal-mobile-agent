package dev.edgecompanion.core.conversation.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlin.test.*

class AssistantPersistenceTest {
    @Test fun assistantCompletionPreservesNewDraftAndAttachmentsAndIsIdempotent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val db = CompanionDatabase(driver); val store = SqlConversationStore(db)
            store.saveDraft("Hello")
            val user = store.submitUserMessage("Hello", 1)
            val epoch = store.epoch()
            assertTrue(store.startAssistantTurn("a", user.id, epoch))
            store.saveDraft("Next draft")
            store.reserve(Attachment("pending", "next.txt", "text/plain"))
            assertTrue(store.completeAssistantTurn("a", "Local response", 2))
            assertTrue(store.completeAssistantTurn("a", "Duplicate", 3))
            assertEquals(2, store.messages().size)
            assertEquals(MessageRole.ASSISTANT, store.messages().last().role)
            assertEquals(user.id, store.messages().last().turnId)
            assertEquals("Next draft", store.draft())
            assertEquals(epoch, store.epoch())
            assertNull(store.attachments().single().messageId)
            assertEquals("Local response", db.conversationQueries.search("response").executeAsOne().text)
            assertEquals(TurnStatus.COMPLETED, store.attempts().single().status)
        } finally { driver.close() }
    }
    @Test fun clearAndRestartPreventLateCompletionAndReconcilePendingAttempts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val store = SqlConversationStore(CompanionDatabase(driver))
            val user = store.submitUserMessage("Hi", 1)
            assertTrue(store.startAssistantTurn("a", user.id, store.epoch()))
            store.reconcileAssistantTurns()
            assertEquals(TurnStatus.INTERRUPTED, store.attempts().single().status)
            assertFalse(store.completeAssistantTurn("a", "partial", 2))
            assertTrue(store.startAssistantTurn("retry", user.id, store.epoch()))
            store.clear()
            assertFalse(store.completeAssistantTurn("retry", "late", 3))
            assertTrue(store.messages().isEmpty())
        } finally { driver.close() }
    }
    @Test fun versionThreeMigrationPreservesLegacyData() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            listOf(
                "CREATE TABLE message(id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)",
                "CREATE TABLE draft(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1), text TEXT NOT NULL)",
                "CREATE VIRTUAL TABLE message_search USING fts4(text TEXT)",
                "CREATE TABLE attachment(position INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, name TEXT NOT NULL, mime_type TEXT NOT NULL, size INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, message_id INTEGER REFERENCES message(id))",
                "CREATE TABLE draft_epoch(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1), epoch INTEGER NOT NULL)",
                "INSERT INTO message(id,text,created_at) VALUES(12,'Legacy contract',42)",
                "INSERT INTO message_search(docid,text) VALUES(12,'Legacy contract')",
                "INSERT INTO draft VALUES(1,'Pending draft')",
                "INSERT INTO draft_epoch VALUES(1,9)",
                "INSERT INTO attachment(id,name,mime_type,size,status,message_id) VALUES('file','contract.txt','text/plain',5,'READY',12)"
            ).forEach { driver.execute(null, it, 0) }
            CompanionDatabase.Schema.migrate(driver, 3, CompanionDatabase.Schema.version)
            val db = CompanionDatabase(driver); val store = SqlConversationStore(db)
            assertEquals(Message(12, "Legacy contract", 42), store.messages().single())
            assertEquals(12, store.attachments().single().messageId)
            assertEquals("Pending draft", store.draft())
            assertEquals(9, store.epoch())
            assertEquals(12, db.conversationQueries.search("contract").executeAsOne().id)
        } finally { driver.close() }
    }
}
