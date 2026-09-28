package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.data.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentSessionTest {
    private class Files : AttachmentFiles {
        val values = mutableMapOf<String, ByteArray>()
        var cleanups = 0
        override fun write(id: String, bytes: ByteArray) { values[id] = bytes.copyOf() }
        override fun read(id: String) = values.getValue(id).copyOf()
        override fun remove(id: String) { values.remove(id) }
        override fun contains(id: String) = id in values
        override fun retain(ids: Set<String>) { cleanups++; values.keys.retainAll(ids) }
    }

    @Test fun removedImportCannotReappearAndItsLateFileIsCleaned() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CompanionDatabase.Schema.create(driver)
        val controller = ConversationController(SqlConversationStore(CompanionDatabase(driver))) { 1L }
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = ConversationSession(dispatcher, { controller }, driver::close, files, dispatcher)
        try {
            session.start(); advanceUntilIdle()
            val ready = CompletableDeferred<ByteArray>()
            session.importAttachment(Attachment("late", "late.txt", "text/plain"), 0) { ready.await() }
            runCurrent()
            assertEquals(AttachmentStatus.IMPORTING, session.state.value.attachments.single().status)
            session.removeAttachment("late"); runCurrent()
            ready.complete(byteArrayOf(1, 2, 3)); advanceUntilIdle()
            assertTrue(session.state.value.attachments.isEmpty())
            assertTrue(files.values.isEmpty())
        } finally { session.shutdown() }
    }

    @Test fun failedDatabaseRestoreNeverDeletesProtectedFiles() = runTest {
        val files = Files().apply { values["keep"] = byteArrayOf(1) }
        val broken = object : ConversationStore {
            override fun messages(): List<dev.edgecompanion.core.conversation.model.Message> = error("Unavailable")
            override fun draft() = ""
            override fun saveDraft(text: String) = Unit
            override fun append(text: String, timestamp: Long) = Unit
            override fun clear() = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = ConversationSession(dispatcher, { ConversationController(broken) { 1 } }, {}, files, dispatcher)
        try {
            session.start(); advanceUntilIdle()
            assertFalse(session.state.value.ready)
            assertEquals(0, files.cleanups)
            assertTrue(files.contains("keep"))
        } finally { session.shutdown() }
    }
}
