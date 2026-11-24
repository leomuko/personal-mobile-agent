package dev.edgecompanion.app.support

import android.content.Context
import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.conversation.session.SourcesPromptText
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal fun qualitySha(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun qualitySha(text: String): String = qualitySha(text.encodeToByteArray())

internal class AnswerQualityFixture(private val assets: AssetManager, private val set: String, expectedLockSha: String) {
    private val development = set == "dev"
    private val name = if (development) "answer-quality-dev-v1" else "answer-quality-v1"
    private val prefix = if (development) "$name/" else ""
    private fun read(file: String) = assets.open(prefix + file).use { it.readBytes() }
    private val lockBytes = read("$name.lock.json")
    val lockSha = qualitySha(lockBytes).also { check(it == expectedLockSha) { "fixture lock mismatch" } }
    private val lock = JSONObject(lockBytes.decodeToString(throwOnInvalidSequence = true))
    val librarySha = lock.getString("librarySha256").also { check(lock.getString("fixture") == name) }
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
        check(library.size in (if (development) 8..14 else 12..20) && qualitySha(library.keys.joinToString("") { "${hashes.getString(it)}  $it\n" }) == librarySha)
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

    fun schedule(): List<Item> {
        val document = JSONObject(verified("items.json"))
        check(lock.getInt("schema") == 1 && document.getInt("schema") == 1)
        val items = document.getJSONArray("items").let { rows -> List(rows.length()) { rows.getJSONObject(it) } }
        check(items.size == (if (development) 40 else 92) && items.map { it.getString("id") }.toSet().size == items.size)
        if (development) {
            val counts = mapOf("answer-one" to 10, "answer-two" to 4, "unanswerable-off-topic" to 2,
                "unanswerable-missing-detail" to 10, "unanswerable-near-miss" to 8, "diagnostic-contradiction" to 3,
                "diagnostic-bait" to 3)
            check(items.groupingBy { it.getString("set") }.eachCount() == counts && items.none { it.getBoolean("ordinarySubset") })
        }
        val chosen = items.filter { (it.getString("set") == "tuning") == (set == "tuning") }
        val ordinary = if (set == "heldout") chosen.filter { it.getBoolean("ordinarySubset") } else emptyList()
        check(when (set) {
            "dev" -> chosen.size == 40
            "tuning" -> chosen.size == 10
            "heldout" -> chosen.size == 82 && ordinary.size == 20
            else -> false
        })
        fun map(rows: List<JSONObject>, scope: RequestScope) = rows.map {
            Item(it.getString("id"), it.getString("question"), scope)
        }
        return map(chosen, RequestScope.SOURCES) + map(ordinary, RequestScope.ORDINARY)
    }
}

internal data class AnswerQualityCandidate(val id: String, val prompt: SourcesPromptText) {
    val textSha = qualitySha(prompt.instruction + "\n" + prompt.reminder.orEmpty())

    companion object {
        val V1 = AnswerQualityCandidate("v1", SourcesPromptText.V1)
        private const val FROZEN_R1_LOCK = "8b2c6f64c283051692b139b666d77d00b94277ade80ed505c9025151d5c0cf28"
        private val slug = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private data class Entry(val candidate: AnswerQualityCandidate, val rationale: String)

        fun load(assets: AssetManager, expectedLock: String): List<AnswerQualityCandidate> =
            load(expectedLock) { file -> assets.open(file).use { it.readBytes() } }

        internal fun load(expectedLock: String, read: (String) -> ByteArray): List<AnswerQualityCandidate> {
            fun parse(bytes: ByteArray) = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            fun verifiedDocument(prefix: String, expected: String): JSONObject {
                val lockBytes = read(prefix + "candidates.lock.json")
                check(qualitySha(lockBytes) == expected) { "candidate lock mismatch" }
                val lock = parse(lockBytes)
                val candidateBytes = read(prefix + "candidates.json")
                check(lock.keys().asSequence().toSet() == setOf("schema", "sha256") &&
                    lock.getInt("schema") == 1 && lock.getString("sha256") == qualitySha(candidateBytes)) {
                    "candidate file hash mismatch"
                }
                return parse(candidateBytes)
            }

            fun entries(document: JSONObject, pair: Boolean): List<Entry> {
                val keys = if (pair) setOf("schema", "candidates", "purpose", "sourceRound") else setOf("schema", "candidates")
                check(document.keys().asSequence().toSet() == keys && document.getInt("schema") == 1)
                val rows = document.getJSONArray("candidates")
                check(if (pair) rows.length() == 2 else rows.length() in 4..6) { "invalid candidate count" }
                return List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    check(row.keys().asSequence().toSet() == setOf("id", "version", "instruction", "reminder", "rationale"))
                    fun identifier(field: String) = (row.get(field) as String).also {
                        check(it.length <= 80 && it.matches(slug))
                    }
                    val rationale = row.get("rationale") as String
                    check(rationale.isNotBlank() && rationale.none { it in "\n\r\u000B\u000C\u0085\u2028\u2029" })
                    Entry(AnswerQualityCandidate(identifier("id"), SourcesPromptText(identifier("version"),
                        row.get("instruction") as String, row.get("reminder").takeUnless { it == JSONObject.NULL } as String?)),
                        rationale)
                }.also { entries ->
                    check(entries.first().candidate == V1 &&
                        entries.map { it.candidate.id }.toSet().size == entries.size &&
                        entries.map { it.candidate.prompt.version }.toSet().size == entries.size) {
                        "invalid candidate baseline or duplicate"
                    }
                }
            }

            val document = verifiedDocument("", expectedLock)
            val pair = document.opt("purpose") == "verification-pair" && document.opt("sourceRound") == "r1"
            val selected = entries(document, pair)
            if (pair) {
                val source = entries(verifiedDocument("source-r1/", FROZEN_R1_LOCK), pair = false)
                check(selected.all { it in source }) { "verification pair entries must match frozen r1" }
            }
            return selected.map { it.candidate }
        }
    }
}

/** Candidate manifest checks do not start a session or load a model. */
class AnswerQualityCandidateTest {
    @Test fun verificationPairRequiresTwoFrozenEntriesAndTheApprovedSource() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open(name).use { it.readBytes() }
        val packaged = JSONObject(read("candidates.json").decodeToString())
        val source = if (packaged.opt("purpose") == "verification-pair") "source-r1/" else ""
        val sourceBytes = read(source + "candidates.json")
        val sourceLock = read(source + "candidates.lock.json")
        val originals = JSONObject(sourceBytes.decodeToString()).getJSONArray("candidates")
        fun original(id: String): JSONObject =
            (0 until originals.length()).map { originals.getJSONObject(it) }.single { it.getString("id") == id }

        fun pair() = JSONObject().put("schema", 1).put("purpose", "verification-pair").put("sourceRound", "r1")
            .put("candidates", JSONArray(listOf(JSONObject(original("v1").toString()), JSONObject(original("exact-fixed").toString()))))
        fun load(document: JSONObject, frozenBytes: ByteArray = sourceBytes, frozenLock: ByteArray = sourceLock): List<String> {
            val bytes = document.toString().encodeToByteArray()
            val lock = JSONObject().put("schema", 1).put("sha256", qualitySha(bytes)).toString().encodeToByteArray()
            val files = mapOf("candidates.json" to bytes, "candidates.lock.json" to lock,
                "source-r1/candidates.json" to frozenBytes, "source-r1/candidates.lock.json" to frozenLock)
            return AnswerQualityCandidate.load(qualitySha(lock), files::getValue).map { it.id }
        }

        assertEquals(listOf("v1", "exact-fixed"), load(pair()))
        if (source.isNotEmpty()) {
            assertEquals(listOf("v1", "exact-fixed"), AnswerQualityCandidate.load(assets,
                qualitySha(read("candidates.lock.json"))).map { it.id })
        }
        val edited = pair()
        edited.getJSONArray("candidates").getJSONObject(1).put("instruction", "Use the passages.")
        val reversed = pair().put("candidates", JSONArray(listOf(original("exact-fixed"), original("v1"))))
        val three = pair().also { it.getJSONArray("candidates").put(original("exact-match")) }
        val missingPurpose = pair().also { it.remove("purpose") }
        val wrongSource = pair().put("sourceRound", "r2")
        for (invalid in listOf(edited, reversed, three, missingPurpose, wrongSource)) {
            assertThrows(IllegalStateException::class.java) { load(invalid) }
        }
        assertThrows(IllegalStateException::class.java) { load(pair(), frozenBytes = byteArrayOf()) }
        assertThrows(IllegalStateException::class.java) { load(pair(), frozenLock = byteArrayOf()) }
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
