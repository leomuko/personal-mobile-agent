package dev.edgecompanion.core.sources.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import java.nio.file.Files
import kotlin.test.*

class PassageBackfillTest {
    private val unlimitedClock = PassageControl(monotonicMillis = { 0 })

    @Test fun migrationPreservesChatAttachmentsDraftChunksAndSourceSearchThenBackfills() = PassageTestDatabase(legacy = true).use { f ->
        val text = "contract needle " + "x ".repeat(1000) + "😀"
        val legacy = f.legacy(text)
        val chat = SqlConversationStore(f.db)
        val user = chat.submitUserMessage("saved message", 42)
        chat.saveDraft("unsent"); chat.reserve(Attachment("file", "synthetic.txt", "text/plain"))
        val chunks = f.repo.text(legacy.id)
        f.migrate()
        assertEquals(6, CompanionDatabase.Schema.version)
        assertEquals(listOf(legacy), f.repo.list("needle").items)
        assertEquals(chunks, f.repo.text(legacy.id))
        assertEquals(user, chat.messages().single()); assertEquals("unsent", chat.draft())
        assertEquals("file", chat.attachments().single().id)
        assertEquals(PassageCoverage(0, 1), f.repo.passageCoverage())
        val result = f.repo.backfillPassages(control = unlimitedClock)
        assertEquals(1, result.publishedRevisions); assertNull(result.reason)
        assertEquals(PassageCoverage(1, 1), result.coverage)
        val expected = PassageRules.split(text).map { Triple(it.startUtf16.toLong(), it.endUtf16.toLong(), it.text) }
        assertEquals(expected, f.spans())
        assertEquals(expected.size.toLong(), f.count("source_passage_search"))
        val rows = f.spans()
        assertEquals(0, f.repo.backfillPassages().publishedRevisions)
        assertEquals(rows, f.spans())
        assertEquals(chunks, f.repo.text(legacy.id))
        assertEquals(listOf(legacy), f.repo.list("needle").items)
        PassageTestDatabase().use { fresh -> fresh.add(text); assertEquals(fresh.spans(), rows) }
    }
    @Test fun backfillIsBoundedToTwoRevisionsAndCoverageRemainsVisible() = PassageTestDatabase(legacy = true).use { f ->
        repeat(5) { f.legacy("needle $it") }; f.migrate()
        val pending = f.repo.retrievePassages("needle")
        assertEquals(PassageCoverage(0, 5), pending.coverage); assertTrue(pending.passages.isEmpty())
        assertEquals(5, f.repo.list("needle").items.size)
        val first = f.repo.backfillPassages(Int.MAX_VALUE)
        assertEquals(2, first.publishedRevisions); assertEquals(PassageCoverage(2, 5), first.coverage)
        assertEquals(PassageStopReason.REVISION_LIMIT, first.reason)
        assertEquals(2, f.repo.retrievePassages("needle").passages.size)
        assertEquals(2, f.repo.backfillPassages().publishedRevisions)
        assertEquals(1, f.repo.backfillPassages().publishedRevisions)
        assertTrue(f.repo.passageCoverage().complete)
    }
    @Test fun interruptionAtEveryCheckpointRollsBackOrKeepsOnlyAWholePublishedRevision() {
        val text = "needle " + "x".repeat(1000)
        var totalChecks = 0
        PassageTestDatabase(legacy = true).use { f ->
            f.legacy(text); f.migrate()
            f.repo.backfillPassages(control = PassageControl({ totalChecks++; true }, { 0 }))
        }
        assertTrue(totalChecks > 8)
        for (interruption in 1..totalChecks) PassageTestDatabase(legacy = true).use { f ->
            f.legacy(text); f.migrate()
            var checks = 0
            val result = f.repo.backfillPassages(control = PassageControl({ ++checks != interruption }, { 0 }))
            assertEquals(PassageStopReason.CANCELLED, result.reason)
            val indexed = result.coverage.indexedRevisions
            val expectedCount = if (indexed == 0L) 0 else PassageRules.split(text).size.toLong()
            assertEquals(expectedCount, f.count("source_passage"), "checkpoint $interruption")
            assertEquals(expectedCount, f.count("source_passage_search"))
            if (indexed == 0L) assertTrue(f.repo.retrievePassages("needle").passages.isEmpty())
            assertTrue(f.repo.backfillPassages(control = unlimitedClock).coverage.complete)
            assertEquals(PassageRules.split(text).map { Triple(it.startUtf16.toLong(), it.endUtf16.toLong(), it.text) }, f.spans())
        }
    }
    @Test fun expiredBackfillAndZeroBudgetRemainResumable() = PassageTestDatabase(legacy = true).use { f ->
        f.legacy("needle " + "x".repeat(2000)); f.migrate()
        var ticks = 0
        val expired = f.repo.backfillPassages(control = PassageControl(monotonicMillis = { if (++ticks > 4) 5000 else 0 }))
        assertEquals(PassageStopReason.DEADLINE, expired.reason)
        assertEquals(PassageCoverage(0, 1), expired.coverage); assertEquals(0, f.count("source_passage"))
        val zero = f.repo.backfillPassages(0)
        assertEquals(0, zero.publishedRevisions); assertEquals(PassageStopReason.REVISION_LIMIT, zero.reason)
        assertTrue(f.repo.backfillPassages().coverage.complete)
    }
    @Test fun fileReopenAfterInterruptedRevisionResumesWithoutDuplicates() {
        val directory = Files.createTempDirectory("s3-synthetic-").toFile()
        val path = directory.resolve("library.db")
        try {
            PassageTestDatabase(legacy = true, raw = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")).use { f ->
                f.legacy("needle " + "x".repeat(3000)); f.migrate()
                var checks = 0
                assertEquals(PassageStopReason.CANCELLED, f.repo.backfillPassages(control = PassageControl({ ++checks < 6 }, { 0 })).reason)
                assertEquals(0, f.count("source_passage"))
            }
            PassageTestDatabase(raw = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}"), initialize = false).use { reopened ->
                assertEquals(PassageCoverage(0, 1), reopened.repo.passageCoverage())
                assertTrue(reopened.repo.backfillPassages().coverage.complete)
                val count = reopened.count("source_passage")
                assertEquals(0, reopened.repo.backfillPassages().publishedRevisions)
                assertEquals(count, reopened.count("source_passage"))
                assertTrue(reopened.repo.retrievePassages("needle").passages.isNotEmpty())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun capacityFailureInImportRollsBackAllNewDataAndIndexRows() = PassageTestDatabase().use { f ->
        val keep = f.add("keep")
        val chunks = f.repo.text(keep.id)
        f.failAt = f.measurements + 4
        assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> { f.add("needle " + "x".repeat(1000)) }.error)
        assertEquals(listOf(keep), f.repo.list().items); assertEquals(chunks, f.repo.text(keep.id))
        assertEquals(1, f.count("source_passage")); assertEquals(1, f.count("source_passage_search"))
        assertTrue(f.repo.retrievePassages("needle").passages.isEmpty())
    }
    @Test fun backfillCapacityFailurePreservesSourcesAndAlreadyPublishedPassages() = PassageTestDatabase().use { f ->
        val keep = f.add("keep"); val pending = f.legacy("needle " + "x".repeat(1000))
        val chunks = f.repo.text(pending.id)
        f.failAt = f.measurements + 2
        val failed = f.repo.backfillPassages()
        assertEquals(PassageStopReason.QUOTA, failed.reason); assertEquals(PassageCoverage(1, 2), failed.coverage)
        assertEquals(1, f.count("source_passage")); assertEquals(1, f.count("source_passage_search"))
        assertEquals(chunks, f.repo.text(pending.id)); assertEquals(keep.id, f.repo.retrievePassages("keep").passages.single().sourceId)
        assertEquals(pending, f.repo.list("needle").items.single())
        f.failAt = null
        assertTrue(f.repo.backfillPassages().coverage.complete)
    }
    @Test fun existingCapacityCeilingsApplyToBothPathsAtTheirExactBoundary() = PassageTestDatabase().use { f ->
        f.legacy("pending")
        for (usage in listOf(LibraryFootprint(-1, Long.MAX_VALUE), LibraryFootprint(0, -1),
            LibraryFootprint(SourceLimits.LIBRARY_BYTES - SourceLimits.TRANSACTION_RESERVE + 1, Long.MAX_VALUE),
            LibraryFootprint(0, SourceLimits.DISK_RESERVE + SourceLimits.TRANSACTION_RESERVE - 1))) {
            f.footprint = usage
            assertEquals(PassageStopReason.QUOTA, f.repo.backfillPassages().reason)
            assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> { f.add("new") }.error)
            assertEquals(PassageCoverage(0, 1), f.repo.passageCoverage())
            assertEquals(0, f.count("source_passage"))
        }
        f.footprint = LibraryFootprint(SourceLimits.LIBRARY_BYTES - SourceLimits.TRANSACTION_RESERVE,
            SourceLimits.DISK_RESERVE + SourceLimits.TRANSACTION_RESERVE)
        assertTrue(f.repo.backfillPassages().coverage.complete)
        f.add("new"); assertEquals(PassageCoverage(2, 2), f.repo.passageCoverage())
    }
    @Test fun passageQuotaIsSeparateFromDisplayChunksAndEnforcedAtImportBoundary() = PassageTestDatabase().use { f ->
        val keep = f.add("keep")
        f.fillPassageQuota(keep.revisionId, PassageLimits.COUNT - 2)
        f.add("last slot")
        assertEquals(PassageLimits.COUNT, f.count("source_passage"))
        assertEquals(2, f.sql.chunkCount().executeAsOne())
        assertEquals(SourceError.QUOTA, assertFailsWith<SourceException> { f.add("over quota") }.error)
        assertEquals(2, f.sql.sourceCount().executeAsOne()); assertEquals(PassageLimits.COUNT, f.count("source_passage"))
        assertEquals(2, f.count("source_passage_search"))
    }
    @Test fun passageQuotaBackfillFailureIsAtomicAndResumesAtBoundary() = PassageTestDatabase().use { f ->
        val keep = f.add("keep"); f.legacy("pending")
        f.fillPassageQuota(keep.revisionId, PassageLimits.COUNT - 1)
        assertEquals(PassageStopReason.QUOTA, f.repo.backfillPassages().reason)
        assertEquals(PassageCoverage(1, 2), f.repo.passageCoverage())
        assertEquals(1, f.count("source_passage_search"))
        f.driver.execute(null, "DELETE FROM source_passage WHERE id = (SELECT max(id) FROM source_passage)", 0)
        assertTrue(f.repo.backfillPassages().coverage.complete)
        assertEquals(PassageLimits.COUNT, f.count("source_passage")); assertEquals(2, f.count("source_passage_search"))
    }
}
