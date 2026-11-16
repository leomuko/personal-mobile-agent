package dev.edgecompanion.core.sources.model

import kotlin.time.TimeSource

/** Offsets/sizes in this API deliberately name their units. No model-token claim. */
object PassageLimits {
    const val TARGET_UTF16_UNITS = 384
    const val MIN_NONFINAL_UTF16_UNITS = 256
    const val OVERLAP_UTF16_UNITS = 0
    // UTF-16 length <= admitted UTF-8 bytes. 500 * ceil(65,536 / 256) = 128,000.
    const val PER_SOURCE = 256
    const val COUNT = 128_000L
    const val QUERY_UTF16_UNITS = 1024
    const val QUERY_TERMS = 8
    const val SCAN_PAGE_MATCHES = 64
    const val INDEX_QUERIES_PER_PAGE = 1 + QUERY_TERMS
    // One first-page probe per term plus a full-term conjunction seed.
    // All returned rows count against SCAN_MATCHES, including overlapping IDs.
    const val PROBE_MATCHES = SCAN_PAGE_MATCHES
    const val SCAN_MATCHES = 4096
    const val CANDIDATES = 50
    const val RESULT_PASSAGES = 8
    const val FETCH_BATCH_PASSAGES = 8
    const val RESULT_TEXT_UTF8_BYTES = 64 * 1024
    const val OPEN_CURSORS = 1
    const val OPERATION_MILLIS = 5000L
    const val BACKFILL_REVISIONS = 2
}

private val passageClockOrigin = TimeSource.Monotonic.markNow()
/** Callbacks must be quick, synchronous signals; invoked on the repository's serial lane. */
data class PassageControl(
    val continueWork: () -> Boolean = { true },
    val monotonicMillis: () -> Long = { passageClockOrigin.elapsedNow().inWholeMilliseconds },
)

enum class PassageStatus { COMPLETE, INCOMPLETE }
enum class PassageStopReason { SCAN_LIMIT, DEADLINE, CANCELLED, CONTEXT_CHANGED, QUERY_LIMIT, TEXT_BUDGET, REVISION_LIMIT, QUOTA }
data class PassageCoverage(val indexedRevisions: Long, val totalRevisions: Long) {
    val complete: Boolean get() = indexedRevisions == totalRevisions
}
data class RetrievedPassage(
    val sourceId: Long, val sourceTitle: String, val revisionId: Long, val passageId: Long,
    val startUtf16: Long, val endUtf16: Long, val text: String, val score: Double,
)
data class PassageRetrieval(
    val contextEpoch: Long, val coverage: PassageCoverage, val passages: List<RetrievedPassage>,
    // Posting rows read, including probes and overlaps between terms; <= SCAN_MATCHES.
    val reason: PassageStopReason?, val scannedMatches: Int, val fetchedPassages: Int,
    val textUtf8Bytes: Int, val queryTermsTruncated: Boolean = false,
) {
    val status: PassageStatus get() = if (reason == null) PassageStatus.COMPLETE else PassageStatus.INCOMPLETE
}
data class PassageBackfill(val publishedRevisions: Int, val coverage: PassageCoverage, val reason: PassageStopReason?) {
    val status: PassageStatus get() = if (reason == null) PassageStatus.COMPLETE else PassageStatus.INCOMPLETE
}
