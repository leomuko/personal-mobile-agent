package dev.edgecompanion.app

import android.content.Context
import android.os.StatFs
import androidx.test.core.app.ApplicationProvider
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.db.CompanionDatabase
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Random synthetic SQLCipher database only. No production sources, chat, keys or model are modified. */
class SourcesLibraryTest {
    @Test fun encryptedSourcesReopenSearchAndFenceLegacyChatWithoutLosingDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sources-test-${UUID.randomUUID()}.db"
        var driver: app.cash.sqldelight.db.SqlDriver? = null
        try {
            driver = EncryptedDatabaseFactory.open(context, name)
            var db = CompanionDatabase(driver)
            var repo = SourcesRepository(db, capacity(context, name)) { 42 }
            val source = repo.importText("Contract", "Synthetic contract for John", SourceKind.PASTED_TEXT)
            val chat = SqlConversationStore(db)
            val user = chat.submitUserMessage("Synthetic old context", 1)
            chat.saveDraft("Synthetic unsent draft")
            assertTrue(chat.startAssistantTurn("pending", user.id, chat.epoch()))
            driver.close(); driver = EncryptedDatabaseFactory.open(context, name)
            db = CompanionDatabase(driver); repo = SourcesRepository(db, capacity(context, name)) { 43 }
            assertEquals(source, repo.list("contract").items.single())
            assertEquals("Synthetic contract for John", repo.text(source.id).items.single().text)
            repo.rename(source.id, "Work")
            assertEquals("Work", repo.list("Work").items.single().title)
            assertTrue(repo.delete(source.id))
            driver.close(); driver = EncryptedDatabaseFactory.open(context, name)
            val reopened = SqlConversationStore(CompanionDatabase(driver))
            reopened.reconcileAssistantTurns()
            assertEquals(user.id, reopened.messageFence())
            assertEquals(1, reopened.contextEpoch())
            assertEquals("Synthetic unsent draft", reopened.draft())
            assertEquals(user, reopened.messages().single())
            assertFalse(reopened.completeAssistantTurn("pending", "stale", 2))
            // SQLCipher on-disk bytes must not contain the synthetic cleartext.
            assertFalse(File(context.getDatabasePath(name).path).readBytes().decodeToString().contains("Synthetic"))
        } finally { driver?.close(); cleanup(context, name) }
    }

    @Test fun encryptedVersionFourMigrationPreservesChatSearchAttachmentsAndDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sources-test-${UUID.randomUUID()}.db"
        var driver: app.cash.sqldelight.db.SqlDriver? = null
        try {
            driver = EncryptedDatabaseFactory.open(context, name)
            val chat = SqlConversationStore(CompanionDatabase(driver))
            val user = chat.submitUserMessage("Synthetic migration contract", 1)
            chat.saveDraft("Synthetic preserved draft")
            chat.reserve(dev.edgecompanion.core.attachments.model.Attachment("synthetic", "fixture.txt", "text/plain"))
            val epoch = chat.epoch()
            assertTrue(chat.startAssistantTurn("pending", user.id, epoch))
            listOf("DROP TABLE source_search", "DROP TABLE source_chunk", "DROP TABLE source_revision",
                "DROP TABLE source", "DROP TABLE context_eligibility", "ALTER TABLE assistant_attempt DROP COLUMN context_epoch",
                "PRAGMA user_version = 4").forEach { driver.execute(null, it, 0) }
            driver.close(); driver = EncryptedDatabaseFactory.open(context, name)
            val db = CompanionDatabase(driver)
            val migrated = SqlConversationStore(db)
            assertEquals(user, migrated.messages().single())
            assertEquals("Synthetic preserved draft", migrated.draft())
            assertEquals("synthetic", migrated.attachments().single().id)
            assertEquals(epoch, migrated.epoch())
            assertEquals(user.id, db.conversationQueries.search("contract").executeAsOne().id)
            assertEquals(0, migrated.contextEpoch()); assertEquals(0, migrated.messageFence())
            assertTrue(migrated.completeAssistantTurn("pending", "reply", 2))
        } finally { driver?.close(); cleanup(context, name) }
    }

    @Test fun maximumPilotPayloadsStayInsideReservedPhysicalHeadroom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sources-test-${UUID.randomUUID()}.db"
        var driver: app.cash.sqldelight.db.SqlDriver? = null
        try {
            driver = EncryptedDatabaseFactory.open(context, name)
            val disk = capacity(context, name)
            var peak = 0L
            val repo = SourcesRepository(CompanionDatabase(driver), LibraryCapacity {
                disk.measure().also { peak = maxOf(peak, it.usedBytes) }
            }) { 1 }
            val profiles = listOf("x".repeat(SourceLimits.TEXT_BYTES),
                (0..10000).joinToString(" ") { "word$it" }.take(SourceLimits.TEXT_BYTES),
                "\u4E2D".repeat(SourceLimits.TEXT_BYTES / 3))
            profiles.forEachIndexed { i, text ->
                val before = disk.measure().usedBytes; peak = before
                repo.importText("Capacity $i", text, SourceKind.PASTED_TEXT)
                val after = disk.measure().usedBytes
                assertTrue("Pilot growth exceeded reservation", maxOf(peak, after) - before < SourceLimits.TRANSACTION_RESERVE)
            }
        } finally { driver?.close(); cleanup(context, name) }
    }

    private fun capacity(context: Context, name: String) = LibraryCapacity {
        val path = context.getDatabasePath(name).path
        LibraryFootprint(listOf("", "-wal", "-shm", "-journal").sumOf { File(path + it).length() },
            StatFs(context.noBackupFilesDir.path).availableBytes)
    }
    private fun cleanup(context: Context, name: String) {
        context.deleteDatabase(name)
        File(context.noBackupFilesDir, "$name.key").delete()
        File(context.noBackupFilesDir, "$name.key.bak").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("dev.edgecompanion.database.$name") }
    }
}
