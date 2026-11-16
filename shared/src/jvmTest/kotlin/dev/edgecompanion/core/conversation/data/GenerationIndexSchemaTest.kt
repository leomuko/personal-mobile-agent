package dev.edgecompanion.core.conversation.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.db.CompanionDatabase
import java.io.File
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.*

class GenerationIndexSchemaTest {
    private class Fixture(migrated: Boolean) : AutoCloseable {
        private val temporary = if (migrated) Files.createTempDirectory("generation-index-schema-").toFile() else null
        val driver = if (temporary == null) JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) else {
            val project = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
            val baseline = File(project, "shared/src/commonMain/sqldelight/dev/edgecompanion/db/5.db")
            val copy = File(temporary, "synthetic.db")
            Files.copy(baseline.toPath(), copy.toPath())
            JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
        }
        val db = CompanionDatabase(driver)
        init {
            if (migrated) CompanionDatabase.Schema.migrate(driver, 5, CompanionDatabase.Schema.version)
            else CompanionDatabase.Schema.create(driver)
        }
        override fun close() {
            try { driver.close() } finally { temporary?.deleteRecursively() }
        }
    }

    private fun <T> rows(driver: SqlDriver, sql: String, read: (SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { cursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(read(cursor)) })
        }, 0).value

    private data class Column(val name: String, val type: String, val required: Long, val default: String?, val primary: Long)
    private data class Index(val name: String, val unique: Long, val origin: String, val partial: Long, val columns: List<String>)
    private fun columns(driver: SqlDriver) = rows(driver, "PRAGMA table_info(message)") {
        Column(it.getString(1)!!, it.getString(2)!!, it.getLong(3)!!, it.getString(4), it.getLong(5)!!)
    }
    private fun indexes(driver: SqlDriver): List<Index> {
        val definitions = rows(driver, "PRAGMA index_list(message)") {
            Index(it.getString(1)!!, it.getLong(2)!!, it.getString(3)!!, it.getLong(4)!!, emptyList())
        }
        return definitions.map { definition ->
            val quoted = definition.name.replace("\"", "\"\"")
            definition.copy(columns = rows(driver, "PRAGMA index_info(\"$quoted\")") { it.getString(2)!! })
        }.sortedBy { it.name }
    }

    @Test fun freshAndMigratedMessageColumnsAndNamedIndexesMatch() {
        Fixture(false).use { fresh -> Fixture(true).use { migrated ->
            assertEquals(columns(migrated.driver), columns(fresh.driver))
            assertEquals(indexes(migrated.driver), indexes(fresh.driver))
            assertEquals(Index("message_generation_id", 1, "c", 0, listOf("generation_id")),
                indexes(fresh.driver).single { it.name == "message_generation_id" })
        } }
    }

    @Test fun bothPathsRejectDuplicateNonNullIdsAndPermitMultipleNullIds() {
        for (migrated in listOf(false, true)) Fixture(migrated).use { fixture ->
            val queries = fixture.db.conversationQueries
            queries.insertMessage("Synthetic first user", 1)
            queries.insertMessage("Synthetic second user", 2)
            queries.insertAssistant("Synthetic first response", 3, 1, "generation-one")
            val rejected = assertFailsWith<SQLException> {
                queries.insertAssistant("Synthetic duplicate", 4, 1, "generation-one")
            }
            assertTrue(rejected.message.orEmpty().contains("UNIQUE", ignoreCase = true))
            assertTrue(rejected.message.orEmpty().contains("message.generation_id"))
            queries.insertAssistant("Synthetic distinct response", 5, 2, "generation-two")
            queries.insertMessage("Synthetic third user", 6)
            val saved = queries.allMessages().executeAsList()
            assertEquals(5, saved.size)
            assertEquals(3, saved.count { it.generation_id == null })
            assertEquals(listOf("generation-one", "generation-two"), saved.mapNotNull { it.generation_id })
        }
    }
}
