package dev.edgecompanion.app

import android.content.Context
import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.model.LibraryCapacity
import dev.edgecompanion.core.sources.model.LibraryFootprint
import dev.edgecompanion.core.sources.model.PassageControl
import dev.edgecompanion.core.sources.model.PassageCoverage
import dev.edgecompanion.core.sources.model.PassageLimits
import dev.edgecompanion.core.sources.model.PassageStatus
import dev.edgecompanion.core.sources.model.PassageStopReason
import dev.edgecompanion.core.sources.model.SourceKind
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.db.CompanionDatabase
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic, randomly named SQLCipher fixtures only; never resolves the app's database or session.
 * Requires secureQa and the explicit instrumentation argument p2PassageSqlCipher=true.
 * The normal Application still starts before JUnit's guard; this guard covers test-owned access.
 */
@RunWith(AndroidJUnit4::class)
class PassageSqlCipherTest {
    @Before fun requireSecureQaOptInBeforeContextAccess() {
        assumeTrue("Passage gate requires the separate secureQa application", BuildConfig.DEBUG &&
            BuildConfig.BUILD_TYPE == "secureQa" &&
            BuildConfig.APPLICATION_ID == "dev.edgecompanion.app.secureqa")
        assumeTrue("Passage gate requires p2PassageSqlCipher=true",
            InstrumentationRegistry.getArguments().getString("p2PassageSqlCipher") == "true")
    }

    @Test fun freshEncryptedImportIndexesPassagesRanksQuestionAndSupportsMatchinfo() = fixture().use { f ->
        val repo = f.repo
        val earlier = repo.importText("Earlier discussion", discussion, SourceKind.PASTED_TEXT)
        val answer = repo.importText("Synthetic agreement", agreement, SourceKind.PASTED_TEXT)
        assertEquals(PassageCoverage(2, 2), repo.passageCoverage())
        assertTrue(f.scalar("SELECT count(*) FROM source_passage") > 2)
        assertEquals(f.scalar("SELECT count(*) FROM source_passage"),
            f.scalar("SELECT count(*) FROM source_passage_search"))

        val result = repo.retrievePassages(question)
        assertEquals(PassageStatus.COMPLETE, result.status)
        assertNull(result.reason)
        assertTrue(result.coverage.complete)
        val best = result.passages.first()
        assertEquals(answer.id, best.sourceId)
        assertTrue(best.text.contains("The contract deadline is Friday."))
        assertTrue("The answer must come from a later passage", best.startUtf16 > 0)
        assertEquals(agreement.substring(best.startUtf16.toInt(), best.endUtf16.toInt()), best.text)
        assertTrue(best.score > result.passages.first { it.sourceId == earlier.id }.score)

        // Production ranks bounded term matches, not matchinfo. Probe FTS4 compatibility separately.
        f.assertContractDeadlineMatchinfo(best.passageId)
        f.reopen()
        assertEquals(result, f.repo.retrievePassages(question))
        f.assertEncryptedOnDisk()
    }

    @Test fun encryptedPrePassageSchemaFiveMigratesAndBackfillsLikeFreshImport() {
        fixture().use { legacy ->
            fixture().use { fresh ->
                val oldRepo = legacy.repo
                val sources = listOf(
                    oldRepo.importText("Earlier discussion", discussion, SourceKind.PASTED_TEXT),
                    oldRepo.importText("Synthetic agreement", agreement, SourceKind.PASTED_TEXT),
                )
                val chunks = sources.map { oldRepo.text(it.id) }
                val search = oldRepo.list("contract")
                val epoch = oldRepo.retrievePassages(question).contextEpoch
                val freshRepo = fresh.repo
                freshRepo.importText("Earlier discussion", discussion, SourceKind.PASTED_TEXT)
                freshRepo.importText("Synthetic agreement", agreement, SourceKind.PASTED_TEXT)

                // SourcesLibraryTest's synthetic downgrade/reopen approach, with actual versions:
                // 4.sqm introduces sources (schema 5); 5.sqm introduces passages (schema 6).
                // Schema 4 cannot contain existing sources. Keep sources/chunks/search intact,
                // remove only passage structures and restore the pre-passage revision status.
                assertEquals(8L, CompanionDatabase.Schema.version)
                legacy.db.transaction {
                    legacy.execute("DROP TABLE draft_scope")
                    legacy.execute("DROP TABLE source_passage_search")
                    legacy.execute("DROP TABLE source_passage")
                    legacy.execute("UPDATE source_revision SET status = 'KEYWORD_READY'")
                    legacy.execute("PRAGMA user_version = 5")
                }
                assertEquals(5L, legacy.scalar("PRAGMA user_version"))
                assertEquals(0L, legacy.scalar("SELECT count(*) FROM sqlite_master WHERE name IN " +
                    "('source_passage', 'source_passage_search')"))
                assertEquals(sources, oldRepo.list().items)
                assertEquals(chunks, sources.map { oldRepo.text(it.id) })
                assertEquals(search, oldRepo.list("contract"))

                // Reopening through the real encrypted factory executes migration 5 (5 -> 6).
                legacy.reopen()
                val migrated = legacy.repo
                assertEquals(8L, legacy.scalar("PRAGMA user_version"))
                assertEquals(sources, migrated.list().items)
                assertEquals(chunks, sources.map { migrated.text(it.id) })
                assertEquals(search, migrated.list("contract"))
                assertEquals(PassageCoverage(0, 2), migrated.passageCoverage())
                val pending = migrated.retrievePassages(question)
                assertFalse(pending.coverage.complete)
                assertTrue(pending.passages.isEmpty())
                assertEquals(epoch, pending.contextEpoch)

                val backfill = migrated.backfillPassages()
                assertEquals(2, backfill.publishedRevisions)
                assertEquals(PassageStatus.COMPLETE, backfill.status)
                assertNull(backfill.reason)
                assertEquals(PassageCoverage(2, 2), backfill.coverage)
                assertTrue(backfill.coverage.complete)
                assertEquals(backfill.coverage, migrated.passageCoverage())
                assertEquals(fresh.passageSpans(), legacy.passageSpans())
                assertEquals(legacy.scalar("SELECT count(*) FROM source_passage"),
                    legacy.scalar("SELECT count(*) FROM source_passage_search"))

                val expected = freshRepo.retrievePassages(question)
                val actual = migrated.retrievePassages(question)
                assertEquals(PassageStatus.COMPLETE, actual.status)
                assertEquals(expected.coverage, actual.coverage)
                assertEquals(epoch, actual.contextEpoch)
                // Identity counters belong to each fixture; compare evidence, offsets and scores.
                assertEquals(expected.passages.map { it.copy(sourceId = 0, revisionId = 0, passageId = 0) },
                    actual.passages.map { it.copy(sourceId = 0, revisionId = 0, passageId = 0) })
                legacy.assertContractDeadlineMatchinfo(actual.passages.first().passageId)
                assertEquals(chunks, sources.map { migrated.text(it.id) })
                assertEquals(search, migrated.list("contract"))
                val spans = legacy.passageSpans()
                val again = migrated.backfillPassages()
                assertEquals(0, again.publishedRevisions)
                assertNull(again.reason)
                assertEquals(backfill.coverage, again.coverage)
                assertEquals(spans, legacy.passageSpans())

                legacy.reopen()
                assertEquals(actual, legacy.repo.retrievePassages(question))
                legacy.assertEncryptedOnDisk()
            }
        }
    }

    @Test fun hostileQueriesStayLiteralAndDeleteRemovesPassagesAndInvalidatesEpoch() = fixture().use { f ->
        val repo = f.repo
        val source = repo.importText("Synthetic target", "contract coffee NEAR OR title deadline", SourceKind.PASTED_TEXT)
        val survivor = repo.importText("Synthetic survivor", "astronomy telescope", SourceKind.PASTED_TEXT)
        val before = repo.retrievePassages("contract deadline")
        assertEquals(source.id, before.passages.single().sourceId)
        val sources = repo.list()
        for (query in listOf("contract OR coffee", "title:contract*", "NEAR(contract coffee)",
            "\"unclosed", "-contract +coffee", "contract\u0000coffee", "\"*():😀",
            "contract'); DROP TABLE source; --")) {
            val result = repo.retrievePassages(query)
            assertEquals(PassageStatus.COMPLETE, result.status)
            assertTrue(result.passages.size <= PassageLimits.RESULT_PASSAGES)
            assertTrue(result.passages.all { it.sourceId == source.id })
            assertEquals(before.contextEpoch, result.contextEpoch)
            assertEquals(PassageCoverage(2, 2), result.coverage)
        }
        // Bare OR must be accepted as text; NOT must not exclude the matching target.
        assertEquals(source.id, repo.retrievePassages("OR").passages.single().sourceId)
        assertEquals(source.id, repo.retrievePassages("coffee NOT contract").passages.single().sourceId)
        assertTrue(repo.retrievePassages("\"*():😀").passages.isEmpty())
        assertEquals(sources, repo.list())
        assertEquals(before, repo.retrievePassages("contract deadline"))

        // Delete at the first synchronous stop checkpoint after retrieval captured its epoch.
        var deleted = false
        val interrupted = repo.retrievePassages("contract deadline", control = PassageControl(continueWork = {
            if (!deleted) {
                assertTrue(repo.delete(source.id))
                deleted = true
            }
            true
        }))
        assertTrue(deleted)
        assertEquals(PassageStopReason.CONTEXT_CHANGED, interrupted.reason)
        assertEquals(PassageStatus.INCOMPLETE, interrupted.status)
        assertEquals(before.contextEpoch, interrupted.contextEpoch)
        assertTrue(interrupted.passages.isEmpty())
        assertEquals(0, interrupted.fetchedPassages)
        assertEquals(0, interrupted.textUtf8Bytes)
        assertEquals(listOf(survivor), repo.list().items)
        assertEquals(1L, f.scalar("SELECT count(*) FROM source_passage"))
        assertEquals(1L, f.scalar("SELECT count(*) FROM source_passage_search"))
        val after = repo.retrievePassages("contract deadline")
        assertEquals(PassageStatus.COMPLETE, after.status)
        assertTrue(after.passages.isEmpty())
        assertEquals(before.contextEpoch + 1, after.contextEpoch)
        assertEquals(PassageCoverage(1, 1), after.coverage)
        assertFalse(repo.delete(source.id))
        assertEquals(after.contextEpoch, repo.retrievePassages("contract").contextEpoch)
        f.reopen()
        assertEquals(after, f.repo.retrievePassages("contract deadline"))
        assertEquals(survivor.id, f.repo.retrievePassages("astronomy").passages.single().sourceId)
    }

    private val discussion = "The contract was discussed in synthetic notes."
    private val agreement = "Synthetic archive filler. ".repeat(40) +
        "The contract deadline is Friday. Synthetic agreement marker 😀."
    private val question = "What is the contract deadline?"

    private fun fixture(): Fixture {
        // No context, path, capacity, key or database access occurs in field initializers/setup.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.edgecompanion.app.secureqa", context.packageName)
        return Fixture(context)
    }

    private class Fixture(private val context: Context) : Closeable {
        private val name = "passage-sqlcipher-test-${UUID.randomUUID()}.db"
        private var driver: SqlDriver? = null
        val db get() = CompanionDatabase(checkNotNull(driver))
        val repo get() = SourcesRepository(db, LibraryCapacity {
            val path = context.getDatabasePath(name).path
            LibraryFootprint(listOf("", "-wal", "-shm", "-journal").sumOf { File(path + it).length() },
                StatFs(context.noBackupFilesDir.path).availableBytes)
        }) { 42 }

        init {
            try { reopen() }
            catch (failure: Exception) {
                try { close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun reopen() {
            val previous = driver
            driver = null
            previous?.close()
            driver = EncryptedDatabaseFactory.open(context, name)
            // Force the lazy Android open (and any migration) before returning.
            check(scalar("PRAGMA user_version") == CompanionDatabase.Schema.version)
        }

        fun execute(sql: String) { checkNotNull(driver).execute(null, sql, 0) }

        fun scalar(sql: String): Long = checkNotNull(driver).executeQuery(null, sql, { cursor ->
            check(cursor.next().value)
            QueryResult.Value(checkNotNull(cursor.getLong(0)))
        }, 0).value

        fun passageSpans(): List<Triple<Long, Long, String>> = checkNotNull(driver).executeQuery(null,
            "SELECT start_utf16, end_utf16, text FROM source_passage ORDER BY revision_id, start_utf16", { cursor ->
                QueryResult.Value(buildList {
                    while (cursor.next().value) add(Triple(checkNotNull(cursor.getLong(0)),
                        checkNotNull(cursor.getLong(1)), checkNotNull(cursor.getString(2))))
                })
            }, 0).value

        fun assertContractDeadlineMatchinfo(expectedPassageId: Long) {
            val matches = checkNotNull(driver).executeQuery(null,
                "SELECT docid, matchinfo(source_passage_search, 'pcx') FROM source_passage_search " +
                    "WHERE source_passage_search MATCH ? ORDER BY docid", { cursor ->
                    QueryResult.Value(buildList {
                        while (cursor.next().value) add(checkNotNull(cursor.getLong(0)) to
                            checkNotNull(cursor.getBytes(1)))
                    })
                }, 1) { bindString(0, "\"contract\" AND \"deadline\"") }.value
            assertEquals(1, matches.size)
            val (id, blob) = matches.single()
            assertEquals(expectedPassageId, id)
            try {
                // FTS4 pcx: phrase count, column count, then three native uint32s per phrase/column.
                assertEquals(8 * Int.SIZE_BYTES, blob.size)
                val words = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
                assertEquals(2, words.get())
                assertEquals(1, words.get())
                repeat(2) {
                    val localHits = words.get()
                    val corpusHits = words.get()
                    val documents = words.get()
                    assertTrue(localHits > 0)
                    assertTrue(corpusHits >= localHits)
                    assertTrue(documents > 0)
                }
                assertFalse(words.hasRemaining())
            } finally { blob.fill(0) }
        }

        fun assertEncryptedOnDisk() {
            // Close first so the test checks the persisted database rather than only a WAL.
            val previous = driver
            driver = null
            previous?.close()
            val bytes = context.getDatabasePath(name).readBytes()
            try {
                assertTrue(bytes.isNotEmpty())
                val disk = bytes.toString(Charsets.ISO_8859_1)
                assertFalse(disk.startsWith("SQLite format 3"))
                assertFalse(disk.contains("Synthetic agreement marker"))
            } finally { bytes.fill(0) }
        }

        override fun close() {
            // Attempt every cleanup even when closing/deleting a preceding resource fails.
            val cleanup = listOf<() -> Unit>(
                { val previous = driver; driver = null; previous?.close(); Unit },
                {
                    context.deleteDatabase(name)
                    val path = context.getDatabasePath(name).path
                    listOf("", "-wal", "-shm", "-journal").forEach { check(!File(path + it).exists()) }
                },
                { val key = File(context.noBackupFilesDir, "$name.key"); key.delete(); check(!key.exists()) },
                { val backup = File(context.noBackupFilesDir, "$name.key.bak"); backup.delete(); check(!backup.exists()) },
                { val pending = File(context.noBackupFilesDir, "$name.key.new"); pending.delete(); check(!pending.exists()) },
                { KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry("dev.edgecompanion.database.$name")
                    check(!containsAlias("dev.edgecompanion.database.$name"))
                }; Unit },
            )
            var failure: Exception? = null
            cleanup.forEach { action ->
                try { action() }
                catch (error: Exception) {
                    val first = failure
                    if (first == null) failure = error else first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}
