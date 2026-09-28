package dev.edgecompanion.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.edgecompanion.core.storage.database.DatabaseKeyVault
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.db.CompanionDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun ciphertextReopensWithKeystoreKeyAndRejectsWrongKey() {
        val name = "test-${System.nanoTime()}.db"
        val secret = "Private contract note unique test fixture"
        val first = EncryptedDatabaseFactory.open(context, name)
        try {
            val store = SqlConversationStore(CompanionDatabase(first))
            store.append(secret, 42); store.saveDraft("Unfinished text")
        } finally { first.close() }
        val disk = context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.startsWith("SQLite format 3")); assertFalse(disk.contains(secret))
        val reopened = EncryptedDatabaseFactory.open(context, name)
        try {
            val db = CompanionDatabase(reopened)
            assertEquals(secret, db.conversationQueries.search("contract").executeAsOne().text)
            assertEquals("Unfinished text", SqlConversationStore(db).draft())
        } finally { reopened.close() }
        val wrong = AndroidSqliteDriver(CompanionDatabase.Schema, context, name,
            factory = SupportOpenHelperFactory(ByteArray(32) { 7 }))
        try {
            var rejected = false
            try { CompanionDatabase(wrong).conversationQueries.allMessages().executeAsList() } catch (_: Exception) { rejected = true }
            assertTrue("A wrong key must never open the database", rejected)
        } finally { wrong.close(); context.deleteDatabase(name) }
    }
    @Test fun missingKeyDoesNotReplaceExistingDatabase() {
        val name = "missing-key-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs(); path.writeText("existing data")
        try {
            var rejected = false
            try { DatabaseKeyVault(context, name).key() } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected); assertEquals("existing data", path.readText())
        } finally { context.deleteDatabase(name) }
    }
}

