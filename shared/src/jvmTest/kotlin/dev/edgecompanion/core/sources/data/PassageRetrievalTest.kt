package dev.edgecompanion.core.sources.data

import app.cash.sqldelight.db.QueryResult
import dev.edgecompanion.core.sources.model.*
import kotlin.test.*

class PassageRetrievalTest {
    @Test fun laterPageCanBeatTheFirstFiftyCandidates() = PassageTestDatabase().use { f ->
        f.add("contract ".repeat(5000), "Earlier matches")
        val answer = f.add("contract deadline Friday", "Later answer")
        val result = f.repo.retrievePassages("contract deadline")
        assertEquals(PassageStatus.COMPLETE, result.status)
        assertTrue(result.scannedMatches > PassageLimits.SCAN_PAGE_MATCHES)
        assertEquals(answer.id, result.passages.first().sourceId)
        assertTrue(result.passages.first().score > result.passages.last().score)
    }
    @Test fun jdbcIndexPagingUsesNativeDocidOrderingWithoutWholeResultTemporarySort() = PassageTestDatabase().use { f ->
        f.add("needle ".repeat(1000)); f.queries.clear()
        f.repo.retrievePassages("needle")
        val queries = f.queries.filter { "FROM source_passage_search" in it }.distinct()
        assertTrue(queries.size >= 2)
        for (query in queries) {
            val parameters = query.count { it == '?' }
            val plan = f.driver.executeQuery(null, "EXPLAIN QUERY PLAN $query", { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(3)!!) })
            }, parameters) {
                bindString(0, "\"needle\"")
                if (parameters == 4) { bindLong(1, 0); bindLong(2, 0); bindLong(3, 64) }
                else {
                    val ids = (parameters - 6) / 2
                    assertEquals(0, (parameters - 6) % 2)
                    for (i in 0 until ids * 2) bindLong(i + 1, (i % ids + 1).toLong())
                    bindLong(2 * ids + 1, 1); bindLong(2 * ids + 2, 64)
                    bindLong(2 * ids + 3, 1); bindLong(2 * ids + 4, 64); bindLong(2 * ids + 5, 64)
                }
            }.value
            assertTrue(plan.any { "VIRTUAL TABLE INDEX" in it })
            assertFalse(plan.any { "TEMP B-TREE" in it }, plan.joinToString())
        }
    }
    @Test fun ranksAnyTermCoverageAcrossSourcesWithDeterministicIdsAndCurrentTitles() = PassageTestDatabase().use { f ->
        val unrelated = f.add("The contract was discussed.", "Earlier")
        val best = f.add("The contract deadline is Friday.", "Answer")
        f.add("The contract deadline is Monday.", "Another")
        val result = f.repo.retrievePassages("What is the contract deadline?")
        assertEquals(PassageStatus.COMPLETE, result.status)
        assertEquals(best.id, result.passages.first().sourceId)
        assertTrue(result.passages.first().score > result.passages.first { it.sourceId == unrelated.id }.score)
        assertEquals(result.passages.take(2).map { it.passageId }.sorted(), result.passages.take(2).map { it.passageId })
        result.passages.forEach { assertEquals(it.text.length.toLong(), it.endUtf16 - it.startUtf16) }
        f.repo.rename(best.id, "Renamed")
        assertEquals("Renamed", f.repo.retrievePassages("contract deadline").passages.first().sourceTitle)
        assertTrue(f.repo.list("contract OR deadline").items.isEmpty()) // Existing AND search unchanged.
    }
    @Test fun fetchesOnlyFinalCandidatesWithCountBytesAndCursorBounds() = PassageTestDatabase().use { f ->
        f.add("needle ".repeat(4000))
        f.queries.clear()
        val result = f.repo.retrievePassages("needle", maxPassages = Int.MAX_VALUE, maxTextUtf8Bytes = Int.MAX_VALUE)
        assertEquals(8, result.passages.size); assertEquals(8, result.fetchedPassages)
        assertEquals(result.passages.sumOf { it.text.encodeToByteArray().size }, result.textUtf8Bytes)
        assertTrue(result.textUtf8Bytes <= PassageLimits.RESULT_TEXT_UTF8_BYTES)
        assertEquals(1, f.peakCursors); assertEquals(0, f.openCursors)
        val indexed = f.queries.withIndex().filter { "FROM source_passage_search" in it.value }
        assertTrue(indexed.isNotEmpty())
        indexed.forEach { assertFalse("text" in it.value.substringBefore("FROM")) }
        val textReads = f.queries.withIndex().filter { "source_passage.text" in it.value }
        assertEquals(8, textReads.size)
        assertTrue(textReads.first().index > indexed.last().index)
        assertEquals(1, f.repo.retrievePassages("needle", maxPassages = 1).passages.size)
    }
    @Test fun utf8BudgetNeverConfusesOffsetsWithBytesOrSilentlyClipsText() = PassageTestDatabase().use { f ->
        val original = ("needle " + "終".repeat(90) + " ").repeat(8)
        f.add(original)
        val first = f.repo.retrievePassages("needle", maxPassages = 1).passages.single()
        val budget = first.text.encodeToByteArray().size
        assertTrue(budget > first.text.length)
        val limited = f.repo.retrievePassages("needle", maxTextUtf8Bytes = budget)
        assertEquals(listOf(first), limited.passages)
        assertEquals(PassageStopReason.TEXT_BUDGET, limited.reason)
        assertEquals(original.substring(first.startUtf16.toInt(), first.endUtf16.toInt()), first.text)
        val zero = f.repo.retrievePassages("needle", maxTextUtf8Bytes = 0)
        assertEquals(0, zero.fetchedPassages); assertTrue(zero.passages.isEmpty())
        assertEquals(PassageStopReason.TEXT_BUDGET, zero.reason)
    }
    @Test fun hostileQueriesAreLiteralBoundedAndEmptyTermsAreNotErrors() = PassageTestDatabase().use { f ->
        f.add("contract coffee NEAR OR title deadline")
        for (query in listOf("contract OR coffee", "title:contract*", "NEAR(contract coffee)", "\"unclosed", "-contract +coffee", "contract\u0000coffee")) {
            val result = f.repo.retrievePassages(query)
            assertTrue(result.passages.size <= 8)
        }
        val empty = f.repo.retrievePassages("\"*():😀")
        assertEquals(PassageStatus.COMPLETE, empty.status); assertTrue(empty.passages.isEmpty())
        val limited = f.repo.retrievePassages("contract coffee near or title deadline seven eight nine ten")
        assertTrue(limited.queryTermsTruncated); assertEquals(PassageStopReason.QUERY_LIMIT, limited.reason)
        assertEquals(SourceError.LIMIT, assertFailsWith<SourceException> { f.repo.retrievePassages("x".repeat(1025)) }.error)
        for (count in listOf(0, -1)) assertEquals(SourceError.LIMIT,
            assertFailsWith<SourceException> { f.repo.retrievePassages("coffee", maxPassages = count) }.error)
        assertEquals(SourceError.LIMIT, assertFailsWith<SourceException> { f.repo.retrievePassages("coffee", maxTextUtf8Bytes = -1) }.error)
    }
    @Test fun scanCapReportsPartialRankingWhileUnderCapIsComplete() = PassageTestDatabase().use { f ->
        repeat(25) { f.add("needle ".repeat(9000), "Source $it") }
        val late = f.add("needle rareanswer", "Beyond scan cap")
        val result = f.repo.retrievePassages("needle rareanswer", control = PassageControl(monotonicMillis = { 0 }))
        assertEquals(PassageLimits.SCAN_MATCHES, result.scannedMatches)
        assertEquals(result.scannedMatches, f.postingRowsRead) // Probes/overlaps share the cap.
        assertEquals(PassageStatus.INCOMPLETE, result.status); assertEquals(PassageStopReason.SCAN_LIMIT, result.reason)
        assertTrue(result.passages.isNotEmpty()); assertTrue(result.fetchedPassages <= 8)
        assertEquals(late.id, result.passages.first().sourceId)
        assertEquals(result.passages.size, result.passages.map { it.passageId }.distinct().size)
        val common = f.repo.retrievePassages("needle", control = PassageControl(monotonicMillis = { 0 }))
        assertEquals(PassageLimits.SCAN_MATCHES, common.scannedMatches)
        assertEquals(PassageStopReason.SCAN_LIMIT, common.reason)
        assertEquals(PassageStatus.INCOMPLETE, common.status)
        assertEquals(common.passages.map { it.passageId }.sorted(), common.passages.map { it.passageId })
    }
    @Test fun lateConjunctionIsSeededEvenWhenEveryIndividualTermExceedsTheScanCap() = PassageTestDatabase().use { f ->
        repeat(25) { f.add("needle ".repeat(9000), "Needle $it") }
        repeat(25) { f.add("frequent ".repeat(7000), "Frequent $it") }
        val answer = f.add("needle frequent", "Late intersection")
        val result = f.repo.retrievePassages("needle frequent", control = PassageControl(monotonicMillis = { 0 }))
        assertEquals(answer.id, result.passages.first().sourceId)
        assertEquals(PassageStopReason.SCAN_LIMIT, result.reason)
        assertEquals(PassageLimits.SCAN_MATCHES, result.scannedMatches)
        assertEquals(result.scannedMatches, f.postingRowsRead)
        assertEquals(PassageStatus.INCOMPLETE, result.status)
        assertTrue(result.passages.size <= PassageLimits.RESULT_PASSAGES)
    }
    @Test fun longQuestionKeepsItsTrailingEntityAndAttribute() = PassageTestDatabase().use { f ->
        f.add("Please tell me what is the name of the valve.", "Filler")
        val answer = f.add("Zircon valve pressure rating: 12 megapascals.", "Rating")
        val result = f.repo.retrievePassages("Please tell me what is the name of the pressure rating for the Zircon valve in megapascals?")
        assertEquals(answer.id, result.passages.first().sourceId)
        assertTrue(result.queryTermsTruncated) // Omitted words are still disclosed.
        assertEquals(PassageStopReason.QUERY_LIMIT, result.reason)
    }
    @Test fun sparseRareTermPagesScoreOnlyTheirExactCandidatesAndDeduplicateOverlaps() = PassageTestDatabase().use { f ->
        val first = f.add("needle rareanswer", "First rare")
        f.add("needle ".repeat(9000), "Common gap")
        val last = f.add("needle rareanswer", "Last rare")
        val result = f.repo.retrievePassages("needle rareanswer")
        assertEquals(PassageStatus.COMPLETE, result.status)
        assertEquals(listOf(first.id, last.id), result.passages.take(2).map { it.sourceId })
        assertEquals(result.passages.size, result.passages.map { it.passageId }.distinct().size)
        assertEquals(result.passages[0].score, result.passages[1].score)
        assertTrue(result.passages[1].score > result.passages[2].score)
        assertEquals(1, f.peakCursors)
    }
    @Test fun rareSingleTermEvidenceOutranksSeveralCommonTerms() = PassageTestDatabase().use { f ->
        repeat(2) { f.add("common frequent ".repeat(4000), "Common $it") }
        val rare = f.add("specific", "Specific detail")
        val result = f.repo.retrievePassages("common frequent specific")
        assertEquals(rare.id, result.passages.first().sourceId)
        assertEquals(PassageStatus.COMPLETE, result.status)
    }
    @Test fun rareBoilerplateCannotOutrankEvenTheMostCommonContentTerms() = PassageTestDatabase().use { f ->
        repeat(64) { f.add("contract", "Contract $it"); f.add("deadline", "Deadline $it") }
        val distraction = f.add("What happened at lunch?", "Lunch")
        val answer = f.add("contract deadline Friday", "Answer")
        val result = f.repo.retrievePassages("What is the contract deadline?", maxPassages = 1)
        assertEquals(PassageStatus.COMPLETE, result.status)
        assertEquals(answer.id, result.passages.single().sourceId)
        val fallback = f.repo.retrievePassages("What is?", maxPassages = 1)
        assertEquals(PassageStatus.COMPLETE, fallback.status)
        assertEquals(distraction.id, fallback.passages.single().sourceId)
    }
    @Test fun cancellationDuringProbesDoesNotContinueToRankingOrText() = PassageTestDatabase().use { f ->
        f.add("needle rareanswer")
        var probes = 0
        f.afterQuery = { if ("MATCH" in it) probes++ }
        val result = f.repo.retrievePassages("needle rareanswer", control = PassageControl(continueWork = { probes == 0 }))
        assertEquals(PassageStopReason.CANCELLED, result.reason)
        assertEquals(1, probes)
        assertEquals(1, result.scannedMatches)
        assertEquals(0, result.fetchedPassages)
        assertTrue(result.passages.isEmpty())
    }
    @Test fun cancellationAfterAContinuationPageDiscardsEarlierCandidates() = PassageTestDatabase().use { f ->
        f.add("needle ".repeat(9000))
        var pages = 0
        f.afterQuery = { if ("MATCH ? AND source_passage.id > ?" in it) pages++ }
        val result = f.repo.retrievePassages("needle", control = PassageControl(continueWork = { pages < 2 }))
        assertEquals(2, pages)
        assertEquals(128, result.scannedMatches)
        assertEquals(result.scannedMatches, f.postingRowsRead)
        assertEquals(PassageStopReason.CANCELLED, result.reason)
        assertEquals(0, result.fetchedPassages)
        assertTrue(result.passages.isEmpty())
    }
    @Test fun cancellationDeadlineAndEpochChangeStopBetweenPagesWithoutFetchingText() {
        for (mode in listOf("cancel", "deadline", "delete", "backwards")) PassageTestDatabase().use { f ->
            val source = f.add("needle ".repeat(5000))
            var checks = 0; var ticks = 0
            val control = PassageControl(continueWork = {
                checks++
                if (mode == "delete" && checks == 4) f.repo.delete(source.id)
                !(mode == "cancel" && checks >= 4)
            }, monotonicMillis = {
                ticks++
                when { mode == "deadline" && ticks >= 5 -> 5000L; mode == "backwards" && ticks >= 5 -> -1L; else -> 0L }
            })
            val result = f.repo.retrievePassages("needle", control = control)
            assertEquals(if (mode == "cancel") PassageStopReason.CANCELLED else if (mode == "delete") PassageStopReason.CONTEXT_CHANGED else PassageStopReason.DEADLINE, result.reason)
            assertEquals(64, result.scannedMatches); assertEquals(0, result.fetchedPassages); assertTrue(result.passages.isEmpty())
            if (mode == "delete") {
                assertEquals(0, f.count("source_passage")); assertEquals(0, f.count("source_passage_search"))
                val fresh = f.repo.retrievePassages("needle")
                assertEquals(result.contextEpoch + 1, fresh.contextEpoch); assertTrue(fresh.passages.isEmpty())
            }
        }
    }
    @Test fun finalStopSignalDiscardsAlreadyFetchedText() = PassageTestDatabase().use { f ->
        f.add("needle")
        var fetched = false
        f.afterQuery = { if ("source_passage.text" in it) fetched = true }
        val result = f.repo.retrievePassages("needle", control = PassageControl(continueWork = { !fetched }))
        assertEquals(PassageStopReason.CANCELLED, result.reason)
        assertTrue(result.passages.isEmpty()); assertEquals(0, result.textUtf8Bytes); assertEquals(1, result.fetchedPassages)
    }
    @Test fun emptyIndexAndNoHitHaveDifferentCoverage() = PassageTestDatabase(legacy = true).use { f ->
        f.legacy("needle"); f.migrate()
        val pending = f.repo.retrievePassages("needle")
        assertTrue(pending.passages.isEmpty()); assertEquals(PassageCoverage(0, 1), pending.coverage)
        assertFalse(pending.coverage.complete)
        assertTrue(f.repo.backfillPassages().coverage.complete)
        val noHit = f.repo.retrievePassages("unrelated")
        assertTrue(noHit.passages.isEmpty()); assertTrue(noHit.coverage.complete)
        assertEquals(PassageStatus.COMPLETE, noHit.status)
    }
}
