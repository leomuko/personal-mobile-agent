package dev.edgecompanion.core.sources.data

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase

/** Synthetic JDBC-only fixture; does not establish SQLCipher compatibility. */
internal class PassageTestDatabase(legacy: Boolean = false,
    raw: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), initialize: Boolean = true) : AutoCloseable {
    val queries = mutableListOf<String>()
    var openCursors = 0
    var peakCursors = 0
    var postingRowsRead = 0
    var afterQuery: (String) -> Unit = {}
    val driver = object : SqlDriver by raw {
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            queries += sql
            return raw.executeQuery(identifier, sql, { cursor ->
                openCursors++; peakCursors = maxOf(peakCursors, openCursors)
                val observed = object : SqlCursor by cursor {
                    override fun next(): QueryResult<Boolean> = cursor.next().also {
                        if (it.value && !sql.startsWith("EXPLAIN") && "MATCH ? AND source_passage.id > ?" in sql) postingRowsRead++
                    }
                }
                try { mapper(observed) } finally { openCursors--; afterQuery(sql) }
            }, parameters, binders)
        }
    }
    val db = CompanionDatabase(driver)
    val sql get() = db.libraryQueries
    var measurements = 0
    var failAt: Int? = null
    var footprint = LibraryFootprint(0, Long.MAX_VALUE)
    val repo = SourcesRepository(db, LibraryCapacity {
        measurements++
        if (measurements == failAt) LibraryFootprint(SourceLimits.LIBRARY_BYTES, Long.MAX_VALUE) else footprint
    }) { 42 }
    init {
        if (initialize) {
            if (legacy) CompanionDatabase.Schema.migrate(driver, 0, 5)
            else CompanionDatabase.Schema.create(driver)
        }
    }
    fun migrate() { CompanionDatabase.Schema.migrate(driver, 5, CompanionDatabase.Schema.version) }
    fun add(text: String, title: String = "Synthetic") = repo.importText(title, text, SourceKind.PASTED_TEXT)
    fun legacy(text: String, title: String = "Legacy"): SourceSummary = db.transactionWithResult {
        sql.insertSource(title, SourceKind.PASTED_TEXT.name, 42)
        val id = sql.lastId().executeAsOne()
        sql.insertRevision(id, text, text.encodeToByteArray().size.toLong())
        val revision = sql.lastId().executeAsOne()
        SourceTextRules.chunks(text).forEach { sql.insertChunk(revision, it.start, it.end, it.text) }
        sql.indexSource(id, title, text)
        SourceSummary(id, title, SourceKind.PASTED_TEXT, 42, revision, text.encodeToByteArray().size.toLong())
    }
    fun count(table: String): Long = driver.executeQuery(null, "SELECT count(*) FROM $table", {
        it.next(); QueryResult.Value(it.getLong(0)!!)
    }, 0).value
    fun spans(): List<Triple<Long, Long, String>> = driver.executeQuery(null,
        "SELECT start_utf16, end_utf16, text FROM source_passage ORDER BY id", { cursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(Triple(cursor.getLong(0)!!, cursor.getLong(1)!!, cursor.getString(2)!!)) })
        }, 0).value
    /** Count-only quota fault injection. These unindexed rows are not source/retrieval fixtures. */
    fun fillPassageQuota(revision: Long, count: Long) {
        if (count == 0L) return
        driver.execute(null, """WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n < $count)
            INSERT INTO source_passage(revision_id,start_utf16,end_utf16,text)
            SELECT $revision, 100000+n,100001+n,'quota counter fixture' FROM rows""", 0)
    }
    override fun close() = driver.close()
}
