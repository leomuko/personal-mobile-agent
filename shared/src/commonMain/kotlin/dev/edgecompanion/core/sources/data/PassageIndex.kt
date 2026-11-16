package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase

/** Part of the synchronous repository owner, not a second scheduler or data authority. */
internal class PassageIndex(private val db: CompanionDatabase, private val checkCapacity: () -> Unit) {
    private val sql get() = db.libraryQueries
    private fun epoch(): Long = sql.readContext().executeAsOneOrNull()?.epoch ?: 0L
    fun coverage(): PassageCoverage = db.transactionWithResult {
        sql.passageCoverage { total, indexed -> PassageCoverage(indexed, total) }.executeAsOne()
    }

    /** Nested under import, or the backfill revision transaction; never publishes a partial revision. */
    fun publish(revisionId: Long, text: String, checkpoint: () -> Unit = {}) {
        val spans = PassageRules.split(text)
        db.transaction {
            val current = sql.passageRevision(revisionId).executeAsOneOrNull() ?: throw SourceException(SourceError.MISSING)
            if (current.status == "PASSAGE_READY_V1") return@transaction
            if (current.text != text) throw SourceException(SourceError.MISSING)
            val retained = sql.passageCount().executeAsOne() - sql.revisionPassageCount(revisionId).executeAsOne()
            if (retained > PassageLimits.COUNT - spans.size) throw SourceException(SourceError.QUOTA)
            checkCapacity(); checkpoint()
            sql.deletePassageRevisionSearch(revisionId); sql.deletePassageRevision(revisionId)
            checkpoint()
            spans.forEach { span ->
                sql.insertPassage(revisionId, span.startUtf16.toLong(), span.endUtf16.toLong(), span.text)
                val id = sql.lastId().executeAsOne()
                checkpoint()
                sql.indexPassage(id, span.text)
                checkCapacity(); checkpoint()
            }
            checkpoint()
            sql.markPassageReady(revisionId)
            checkCapacity(); checkpoint()
        }
    }

    fun deleteSource(sourceId: Long) {
        sql.deletePassageSourceSearch(sourceId); sql.deletePassageSource(sourceId)
    }

    fun backfill(maxRevisions: Int, control: PassageControl): PassageBackfill {
        if (maxRevisions < 0) throw SourceException(SourceError.LIMIT)
        val work = Work(control)
        val capturedEpoch = epoch()
        fun stop() = work.stop() ?: if (epoch() != capturedEpoch) PassageStopReason.CONTEXT_CHANGED else null
        var published = 0
        var reason: PassageStopReason? = null
        while (published < minOf(maxRevisions, PassageLimits.BACKFILL_REVISIONS)) {
            reason = stop()
            if (reason != null) break
            val pending = sql.nextPendingPassageRevision().executeAsOneOrNull() ?: break
            try {
                publish(pending.id, pending.text) { stop()?.let { throw Stopped(it) } }
                published++
            } catch (stopped: Stopped) {
                reason = stopped.reason; break
            } catch (failure: SourceException) {
                if (failure.error != SourceError.QUOTA) throw failure
                reason = PassageStopReason.QUOTA; break
            }
        }
        reason = reason ?: stop()
        val coverage = coverage()
        return PassageBackfill(published, coverage, reason ?: if (coverage.complete) null else PassageStopReason.REVISION_LIMIT)
    }

    fun retrieve(queryText: String, maxPassages: Int, maxTextUtf8Bytes: Int, control: PassageControl): PassageRetrieval {
        if (maxPassages <= 0 || maxTextUtf8Bytes < 0) throw SourceException(SourceError.LIMIT)
        val work = Work(control)
        val query = PassageRules.query(queryText)
        val snapshot = db.transactionWithResult { epoch() to coverage() }
        val contextEpoch = snapshot.first
        fun stop() = work.stop() ?: if (epoch() != contextEpoch) PassageStopReason.CONTEXT_CHANGED else null
        val best = PassageCandidates()
        var scanned = 0
        var reason: PassageStopReason? = stop()
        // Probe only one bounded ID page per term. A short posting list is exactly
        // counted; a full page is censored at 64, never claimed as corpus IDF.
        // All probe rows count against the same 4096-row scan budget and are reused.
        data class Term(val literal: String, val first: List<Long>, val importance: Double) {
            val weight get() = importance / first.size.coerceAtLeast(1)
        }
        val terms = mutableListOf<Term>()
        if (reason == null) {
            for ((index, literal) in query.terms.withIndex()) {
                reason = stop()
                if (reason != null) break
                val ids = sql.scanPassageMatches(literal, 0, PassageLimits.PROBE_MATCHES.toLong()).executeAsList()
                scanned += ids.size
                // Any content hit has weight >= 1/64. The combined contribution
                // of every fallback word is strictly less than that when content
                // exists (at most seven words, each <= 1/(8*64)). All-boilerplate
                // questions keep ordinary weights, so their fallback still works.
                val importance = if (query.contentTermCount == 0 || index < query.contentTermCount) 1.0
                    else 1.0 / (PassageLimits.QUERY_TERMS * PassageLimits.PROBE_MATCHES)
                terms += Term(literal, ids, importance)
            }
        }
        // IDs only; at most SCAN_MATCHES entries. Overlapping posting lists never
        // duplicate a heap candidate. No passage text is retained during ranking.
        val seen = mutableSetOf<Long>()
        fun score(ids: List<Long>) {
            val unseen = ids.filterNot { it in seen }
            if (unseen.isEmpty()) return
            db.transaction {
                if (epoch() != contextEpoch) { reason = PassageStopReason.CONTEXT_CHANGED; return@transaction }
                val scores = DoubleArray(unseen.size)
                for (term in terms) {
                    val stopped = stop()
                    if (stopped != null) { reason = stopped; return@transaction }
                    // Restrict to these exact candidates: a sparse rare-term page
                    // may span thousands of common-term hits between its IDs.
                    val matching = sql.termPassageMatches(term.literal, unseen, unseen.first(), unseen.last(),
                        unseen.size.toLong()).executeAsList()
                    matching.forEach { id ->
                        val position = unseen.binarySearch(id)
                        if (position < 0) throw SourceException(SourceError.STORAGE)
                        scores[position] += term.weight
                    }
                }
                unseen.forEachIndexed { index, id ->
                    if (scores[index] <= 0) throw SourceException(SourceError.STORAGE)
                    best.add(PassageCandidate(id, scores[index]))
                    seen += id
                }
            }
        }
        if (reason == null) {
            // A late passage containing every selected term must not lose to
            // thousands of older single-term matches, even when all terms are common.
            if (terms.size > 1) {
                reason = stop()
                if (reason == null) {
                    val ids = sql.scanPassageMatches(terms.joinToString(" AND ") { it.literal }, 0,
                        PassageLimits.PROBE_MATCHES.toLong()).executeAsList()
                    scanned += ids.size
                    score(ids)
                }
            }
            // Score every probe before a long posting list can consume the budget.
            val ordered = terms.sortedBy { it.first.size } // Stable input-order tie break.
            for (term in ordered) {
                if (reason != null) break
                reason = stop()
                if (reason != null) break
                score(term.first)
                if (reason != null) break
            }
            if (reason == null) {
                for (term in ordered) {
                    if (term.first.size < PassageLimits.PROBE_MATCHES) continue
                    var after = term.first.last()
                    while (true) {
                        reason = stop()
                        if (reason != null) break
                        val remaining = PassageLimits.SCAN_MATCHES - scanned
                        if (remaining == 0) { reason = PassageStopReason.SCAN_LIMIT; break }
                        val size = minOf(PassageLimits.SCAN_PAGE_MATCHES, remaining)
                        val ids = sql.scanPassageMatches(term.literal, after, size.toLong()).executeAsList()
                        scanned += ids.size
                        score(ids)
                        if (reason != null || ids.size < size) break
                        after = ids.last()
                    }
                    if (reason != null) break
                }
            }
        }
        reason = stop() ?: reason
        val result = mutableListOf<RetrievedPassage>()
        var fetched = 0
        var bytes = 0
        // Stop/deadline/context loss do not initiate more text reads. A scan-cap result
        // may expose the best of the scored subset, explicitly marked incomplete.
        if (reason == null || reason == PassageStopReason.SCAN_LIMIT) {
            val budget = minOf(maxTextUtf8Bytes, PassageLimits.RESULT_TEXT_UTF8_BYTES)
            val candidates = best.best().take(minOf(maxPassages, PassageLimits.RESULT_PASSAGES))
            db.transaction {
                for (candidate in candidates) {
                    val stopped = stop()
                    if (stopped != null) { reason = stopped; break }
                    if (bytes == budget) { if (reason == null) reason = PassageStopReason.TEXT_BUDGET; break }
                    val row = sql.fetchPassage(candidate.id) { source, title, revision, id, start, end, text ->
                        RetrievedPassage(source, title, revision, id, start, end, text, candidate.score)
                    }.executeAsOneOrNull() ?: throw SourceException(SourceError.STORAGE)
                    fetched++
                    val size = PassageRules.utf8Size(row.text)
                    if (size <= budget - bytes) { result += row; bytes += size }
                    else if (reason == null) reason = PassageStopReason.TEXT_BUDGET
                }
            }
        }
        reason = stop() ?: reason
        if (reason in listOf(PassageStopReason.CANCELLED, PassageStopReason.DEADLINE, PassageStopReason.CONTEXT_CHANGED)) {
            result.clear(); bytes = 0
        }
        if (reason == null && query.truncated) reason = PassageStopReason.QUERY_LIMIT
        return PassageRetrieval(contextEpoch, snapshot.second, result.toList(), reason, scanned, fetched, bytes, query.truncated)
    }

    private class Stopped(val reason: PassageStopReason) : Exception()
    private class Work(private val control: PassageControl) {
        private val started = control.monotonicMillis()
        private var last = started
        fun stop(): PassageStopReason? {
            if (!control.continueWork()) return PassageStopReason.CANCELLED
            val current = control.monotonicMillis()
            val elapsed = current - started
            if (current < last || elapsed < 0 || elapsed >= PassageLimits.OPERATION_MILLIS) return PassageStopReason.DEADLINE
            last = current
            return null
        }
    }
}
