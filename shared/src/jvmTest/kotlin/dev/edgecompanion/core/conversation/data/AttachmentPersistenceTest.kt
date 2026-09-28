package dev.edgecompanion.core.conversation.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.session.ConversationController
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AttachmentPersistenceTest {
    private fun database(test: (CompanionDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try { CompanionDatabase.Schema.create(driver); test(CompanionDatabase(driver)) }
        finally { driver.close() }
    }

    @Test fun pendingAndSentAttachmentsRestoreWithPromptAndStableOrder() = database { db -> runTest {
        val store = SqlConversationStore(db)
        val first = ConversationController(store) { 42 }
        first.restore(); first.editDraft("Compare these")
        listOf("gallery", "camera", "document").forEach {
            assertTrue(first.reserve(Attachment(it, "$it.dat", "application/octet-stream"), 0))
            assertTrue(first.complete(it, 12))
        }
        val reopened = ConversationController(SqlConversationStore(db)) { 50 }
        reopened.restore()
        assertEquals(first.state.value, reopened.state.value)
        reopened.submit(); reopened.submit()
        val next = ConversationController(SqlConversationStore(db)) { 60 }
        next.restore()
        assertEquals(1, next.state.value.messages.size)
        assertEquals("Compare these", next.state.value.messages.single().text)
        assertEquals(listOf("gallery", "camera", "document"), next.state.value.attachments.map { it.id })
        assertTrue(next.state.value.attachments.all { it.messageId == next.state.value.messages.single().id })
        assertEquals("", next.state.value.draft)
    } }

    @Test fun failedOrPendingImportsBlockSendAndRestartMarksInterrupted() = database { db -> runTest {
        val first = ConversationController(SqlConversationStore(db)) { 1 }
        first.restore(); first.editDraft("Keep me")
        first.reserve(Attachment("pending", "a.pdf", "application/pdf"), 0)
        first.submit()
        assertTrue(first.state.value.messages.isEmpty())
        val restored = ConversationController(SqlConversationStore(db)) { 2 }
        restored.restore()
        assertEquals(AttachmentStatus.FAILED, restored.state.value.attachments.single().status)
        assertEquals("Keep me", restored.state.value.draft)
        restored.remove("pending"); restored.submit()
        assertEquals(1, restored.state.value.messages.size)
    } }

    @Test fun retryAfterCommittedSendAndFailedRefreshDoesNotDuplicateMessage() = database { db -> runTest {
        val persisted = SqlConversationStore(db)
        var failRefresh = false
        val store = object : ConversationStore by persisted {
            override fun messages() = if (failRefresh) {
                failRefresh = false
                error("Synthetic read failure")
            } else persisted.messages()
        }
        val controller = ConversationController(store) { 42 }
        controller.restore()
        controller.editDraft("Send once")
        controller.reserve(Attachment("photo", "photo.png", "image/png"), 0)
        controller.complete("photo", 12)
        failRefresh = true
        controller.submit()
        assertEquals(1, persisted.messages().size)
        assertNotNull(controller.state.value.error)
        controller.submit()
        assertEquals(1, controller.state.value.messages.size)
        assertEquals("", controller.state.value.draft)
        assertNotNull(controller.state.value.attachments.single().messageId)
        assertNull(controller.state.value.error)
    } }

    @Test fun removeAndClearRejectLateImportsAndStalePickerResults() = database { db -> runTest {
        val controller = ConversationController(SqlConversationStore(db)) { 1 }
        controller.restore()
        controller.reserve(Attachment("removed", "x", "text/plain"), 0)
        controller.remove("removed")
        assertFalse(controller.complete("removed", 4))
        controller.clear()
        assertFalse(controller.reserve(Attachment("late", "x", "text/plain"), 0))
        assertTrue(controller.state.value.attachments.isEmpty())
    } }

    @Test fun attachmentOnlySendAndLimitsAreValidated() = database { db -> runTest {
        val controller = ConversationController(SqlConversationStore(db)) { 1 }
        controller.restore()
        repeat(10) { assertTrue(controller.reserve(Attachment("item-$it", "x", "image/png"), 0)) }
        assertFalse(controller.reserve(Attachment("overflow", "x", "image/png"), 0))
        assertFalse(controller.complete("item-0", AttachmentLimits.ITEM_BYTES + 1))
        repeat(10) { controller.remove("item-$it") }
        assertTrue(controller.reserve(Attachment("only", "x", "image/png"), 0))
        assertTrue(controller.complete("only", 5))
        controller.submit()
        assertEquals("", controller.state.value.messages.single().text)
        assertNotNull(controller.state.value.attachments.single().messageId)
    } }

    @Test fun schemaUpgradePreservesTextDraftAndSearch() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.migrate(driver, 0, 2)
            driver.execute(null, "INSERT INTO message(text, created_at) VALUES ('Preserved', 42)", 0)
            driver.execute(null, "INSERT INTO draft(id, text) VALUES (1, 'Unsent')", 0)
            CompanionDatabase.Schema.migrate(driver, 2, CompanionDatabase.Schema.version)
            val store = SqlConversationStore(CompanionDatabase(driver))
            assertEquals("Preserved", store.messages().single().text)
            assertEquals("Unsent", store.draft()); assertTrue(store.attachments().isEmpty())
        } finally { driver.close() }
    }
}
