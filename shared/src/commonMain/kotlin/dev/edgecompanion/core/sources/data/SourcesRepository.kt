package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase

/** Internal synchronous DB owner, accessed only on ConversationSession's serial storage lane.
 * Text is stored in the existing encrypted DB; no plaintext original or staging files are created.
 */
class SourcesRepository(private val db: CompanionDatabase, private val capacity: LibraryCapacity,
    private val now: () -> Long) {
    private val sql get() = db.libraryQueries

    fun list(query: String = "", after: Long = 0): SourcePage = guarded {
        require(after >= 0)
        val term = if (query.isBlank()) null else SourceTextRules.query(query)
        if (query.isNotBlank() && term == null) return@guarded SourcePage(emptyList(), null)
        val mapper = { id: Long, title: String, kind: String, created: Long, revision: Long, bytes: Long, _: String ->
            SourceSummary(id, title, SourceKind.valueOf(kind), created, revision, bytes) }
        val rows = if (term == null) sql.listSources(after, SourceLimits.PAGE.toLong(), mapper).executeAsList()
            else sql.searchSources(term, after, SourceLimits.PAGE.toLong(), mapper).executeAsList()
        SourcePage(rows, rows.lastOrNull()?.id?.takeIf { rows.size == SourceLimits.PAGE })
    }

    fun text(sourceId: Long, after: Long = 0): SourceTextPage = guarded {
        if (sql.sourceById(sourceId).executeAsOneOrNull() == null) throw SourceException(SourceError.MISSING)
        val rows = sql.readChunks(sourceId, after, SourceLimits.TEXT_PAGE.toLong()) { id, start, end, text ->
            SourceText(id, start, end, text) }.executeAsList()
        SourceTextPage(rows, rows.lastOrNull()?.id?.takeIf { rows.size == SourceLimits.TEXT_PAGE })
    }

    fun importText(title: String, text: String, kind: SourceKind): SourceSummary = guarded {
        val name = SourceTextRules.title(title)
        SourceTextRules.validate(text)
        val chunks = SourceTextRules.chunks(text)
        val created = now()
        // One bounded transaction is the durable job for this pilot. Failure/death rolls it back.
        db.transactionWithResult {
            if (sql.sourceCount().executeAsOne() >= SourceLimits.SOURCES ||
                sql.revisionCount().executeAsOne() >= SourceLimits.REVISIONS ||
                sql.chunkCount().executeAsOne() + chunks.size > SourceLimits.CHUNKS)
                throw SourceException(SourceError.QUOTA)
            checkCapacity(SourceLimits.TRANSACTION_RESERVE)
            sql.insertSource(name, kind.name, created)
            val id = sql.lastId().executeAsOne()
            val bytes = text.encodeToByteArray().let { encoded -> encoded.size.toLong().also { encoded.fill(0) } }
            sql.insertRevision(id, text, bytes)
            val revision = sql.lastId().executeAsOne()
            chunks.forEach { chunk ->
                sql.insertChunk(revision, chunk.start, chunk.end, chunk.text)
                checkCapacity(SourceLimits.TRANSACTION_RESERVE)
            }
            sql.indexSource(id, name, text)
            checkCapacity(SourceLimits.TRANSACTION_RESERVE)
            SourceSummary(id, name, kind, created, revision, bytes)
        }
    }

    fun importUtf8(title: String, bytes: ByteArray) = importText(title, SourceTextRules.decode(bytes), SourceKind.UTF8_TEXT)

    fun rename(id: Long, title: String) = guarded {
        db.transaction {
            if (sql.sourceById(id).executeAsOneOrNull() == null) throw SourceException(SourceError.MISSING)
            checkCapacity(SourceLimits.TRANSACTION_RESERVE)
            val name = SourceTextRules.title(title)
            sql.renameSource(name, id)
            sql.renameSearch(name, id)
            checkCapacity(SourceLimits.TRANSACTION_RESERVE)
        }
    }

    /** Caller revokes active generation BEFORE this transaction, then cancels native work. */
    fun delete(id: Long): Boolean = guarded {
        db.transactionWithResult {
            if (sql.sourceById(id).executeAsOneOrNull() == null) return@transactionWithResult false
            sql.advanceContext()
            sql.deleteSearch(id); sql.deleteChunks(id); sql.deleteRevisions(id); sql.deleteSource(id)
            true
        }
    }

    private fun checkCapacity(reserve: Long) {
        val usage = capacity.measure()
        if (usage.usedBytes < 0 || usage.freeBytes < 0 ||
            usage.usedBytes > SourceLimits.LIBRARY_BYTES - reserve ||
            usage.freeBytes < SourceLimits.DISK_RESERVE + reserve) throw SourceException(SourceError.QUOTA)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (failure: SourceException) { throw failure }
        catch (failure: Exception) { throw SourceException(SourceError.STORAGE, failure) }
}
