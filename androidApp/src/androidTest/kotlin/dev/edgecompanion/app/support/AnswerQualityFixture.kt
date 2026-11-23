package dev.edgecompanion.app.support

import android.content.Context
import android.content.res.AssetManager
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.model.LibraryCapacity
import dev.edgecompanion.core.sources.model.LibraryFootprint
import dev.edgecompanion.core.sources.model.SourceKind
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.db.CompanionDatabase
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun qualitySha(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun qualitySha(text: String): String = qualitySha(text.encodeToByteArray())

internal class AnswerQualityFixture(private val assets: AssetManager, expectedLockSha: String) {
    private fun read(name: String) = assets.open(name).use { it.readBytes() }
    private val lockBytes = read("answer-quality-v1.lock.json")
    val lockSha = qualitySha(lockBytes).also { check(it == expectedLockSha) { "fixture lock mismatch" } }
    private val lock = JSONObject(lockBytes.decodeToString(throwOnInvalidSequence = true))
    val librarySha = lock.getString("librarySha256")
    private val hashes = lock.getJSONObject("files")
    private fun verified(name: String): String {
        val bytes = read(name)
        check(qualitySha(bytes) == hashes.getString(name)) { "fixture file mismatch" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
    val library = hashes.keys().asSequence().filter { it != "items.json" }.sorted().associateWith { name ->
        check(name.matches(Regex("library/[a-z0-9]+(?:-[a-z0-9]+)*\\.txt")))
        verified(name)
    }.also { library ->
        check(library.size in 12..20 && qualitySha(library.keys.joinToString("") { "${hashes.getString(it)}  $it\n" }) == librarySha)
    }
    /** Golden [start, end) UTF-16 passage boundaries per library file, as the host splitter computed them. */
    val boundaries: Map<String, List<Pair<Long, Long>>> = lock.getJSONObject("passageBoundaries").let { all ->
        check(all.keys().asSequence().toSet() == library.keys)
        library.keys.associateWith { name ->
            val rows = all.getJSONArray(name)
            List(rows.length()) { rows.getJSONArray(it).let { pair -> pair.getLong(0) to pair.getLong(1) } }
        }
    }

    data class Item(val id: String, val question: String, val scope: RequestScope)

    fun schedule(set: String): List<Item> {
        val document = JSONObject(verified("items.json"))
        check(lock.getInt("schema") == 1 && document.getInt("schema") == 1)
        val items = document.getJSONArray("items").let { rows -> List(rows.length()) { rows.getJSONObject(it) } }
        check(items.size == 92 && items.map { it.getString("id") }.toSet().size == items.size)
        val chosen = items.filter { (it.getString("set") == "tuning") == (set == "tuning") }
        val ordinary = if (set == "heldout") chosen.filter { it.getBoolean("ordinarySubset") } else emptyList()
        check(if (set == "tuning") chosen.size == 10 else chosen.size == 82 && ordinary.size == 20)
        fun map(rows: List<JSONObject>, scope: RequestScope) = rows.map {
            Item(it.getString("id"), it.getString("question"), scope)
        }
        return map(chosen, RequestScope.SOURCES) + map(ordinary, RequestScope.ORDINARY)
    }
}

/** Uses an isolated SQLCipher filename and alias. Only this harness holds the driver. */
internal class AnswerQualityStorage(private val context: Context, runId: String) : AutoCloseable {
    private val name = "answer-quality-$runId.db"
    private val suffixes = listOf("", "-wal", "-shm", "-journal")
    private val keys = listOf("", ".new", ".bak").map { File(context.noBackupFilesDir, "$name.key$it") }
    private val alias = "dev.edgecompanion.database.$name"
    private val database = context.getDatabasePath(name)
    private var driver: SqlDriver? = null
    private var owned = false
    private val files = mutableMapOf<Long, String>()
    private val texts = mutableMapOf<Long, String>()
    private val spans = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
    lateinit var store: SqlConversationStore
        private set
    lateinit var sources: SourcesRepository
        private set

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun open() {
        check(keys.none(File::exists) && suffixes.none { File(database.path + it).exists() } && !keyStore().containsAlias(alias))
        owned = true
        val opened = EncryptedDatabaseFactory.open(context, name).also { driver = it }
        val db = CompanionDatabase(opened)
        val version = opened.executeQuery(null, "PRAGMA user_version", {
            check(it.next().value)
            QueryResult.Value(it.getLong(0))
        }, 0).value
        check(version == CompanionDatabase.Schema.version)
        store = SqlConversationStore(db)
        sources = SourcesRepository(db, LibraryCapacity {
            LibraryFootprint(suffixes.sumOf { File(database.path + it).length() }, context.filesDir.usableSpace)
        }, System::currentTimeMillis)
    }

    fun importLibrary(fixture: AnswerQualityFixture): JSONArray = JSONArray().apply {
        fixture.library.forEach { (file, body) ->
            val source = sources.importText(body.lineSequence().first().removePrefix("# "), body, SourceKind.PASTED_TEXT)
            files[source.id] = file
            put(JSONObject().put("file", file).put("sourceId", source.id)
                .put("revisionId", source.revisionId).put("sha256", qualitySha(body)))
        }
        val coverage = sources.passageCoverage()
        check(coverage.complete && coverage.totalRevisions == fixture.library.size.toLong()) { "fixture indexing incomplete" }
    }

    fun indexedPassages(): JSONArray = checkNotNull(driver).executeQuery(null,
        "SELECT p.id, r.source_id, p.revision_id, p.start_utf16, p.end_utf16, p.text " +
            "FROM source_passage p JOIN source_revision r ON r.id = p.revision_id " +
            "JOIN source s ON s.id = r.source_id ORDER BY p.id", { cursor ->
            val rows = JSONArray()
            while (cursor.next().value) {
                val id = checkNotNull(cursor.getLong(0))
                val text = checkNotNull(cursor.getString(5))
                val start = checkNotNull(cursor.getLong(3))
                val end = checkNotNull(cursor.getLong(4))
                texts[id] = text
                spans.getOrPut(checkNotNull(files[checkNotNull(cursor.getLong(1))])) { mutableListOf() } += start to end
                rows.put(JSONObject().put("passageId", id).put("sourceId", cursor.getLong(1))
                    .put("revisionId", cursor.getLong(2)).put("startUtf16", start)
                    .put("endUtf16", end).put("textSha256", qualitySha(text)))
            }
            QueryResult.Value(rows)
        }, 0).value

    /** Indexed text of a passage in this run's snapshot, or null when the ID is unknown. */
    fun passageText(passageId: Long): String? = texts[passageId]

    /** Indexed [start, end) boundaries per fixture file, ordered by start. */
    fun boundaries(): Map<String, List<Pair<Long, Long>>> = spans.mapValues { (_, list) -> list.sortedBy { it.first } }

    override fun close() {
        if (!owned) return
        var failure: Exception? = null
        val operations = listOf<() -> Unit>(
            { driver?.close(); driver = null },
            { context.deleteDatabase(name); check(suffixes.none { File(database.path + it).exists() }) },
            { keys.forEach { it.delete() }; check(keys.none(File::exists)) },
            { keyStore().apply { deleteEntry(alias); check(!containsAlias(alias)) } },
        )
        for (operation in operations) {
            try { operation() } catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
        owned = false
    }
}
