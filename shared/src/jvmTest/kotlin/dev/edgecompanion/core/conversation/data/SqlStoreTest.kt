package dev.edgecompanion.core.conversation.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.db.CompanionDatabase
import kotlin.test.*

class SqlStoreTest {
    @Test fun saveSearchAndDeleteShareTheSameTransactionBackedSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CompanionDatabase.Schema.create(driver)
            val db = CompanionDatabase(driver); val store = SqlConversationStore(db)
            store.saveDraft("Meeting draft"); store.append("Tuesday meeting about the contract", 42)
            assertEquals("", store.draft())
            assertEquals("Tuesday meeting about the contract", db.conversationQueries.search("contract").executeAsOne().text)
            store.clear()
            assertTrue(store.messages().isEmpty())
            assertTrue(db.conversationQueries.search("contract").executeAsList().isEmpty())
        } finally { driver.close() }
    }
    @Test fun migrationPreservesAnExistingConversation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE message (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)", 0)
            driver.execute(null, "INSERT INTO message(text, created_at) VALUES ('Keep this', 42)", 0)
            CompanionDatabase.Schema.migrate(driver, 1, 2)
            assertEquals("Keep this", CompanionDatabase(driver).conversationQueries.allMessages().executeAsOne().text)
        } finally { driver.close() }
    }
}

