package dev.edgecompanion.core.sources.model

/** Text-only pilot ceilings are deliberately below the full P2 import capacity. */
object SourceLimits {
    const val TEXT_BYTES = 64 * 1024
    const val SOURCES = 500L
    const val REVISIONS = 1000L
    const val CHUNKS = 20_000L
    const val CHUNK_CHARS = 2048
    const val PAGE = 50
    const val TEXT_PAGE = 8
    const val LIBRARY_BYTES = 512L * 1024 * 1024
    const val DISK_RESERVE = 512L * 1024 * 1024
    const val TRANSACTION_RESERVE = 64L * 1024 * 1024
}

enum class SourceError { INVALID_TEXT, LIMIT, QUOTA, STORAGE, MISSING, UNAVAILABLE, BUSY }
class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.name, cause)
enum class SourceKind { PASTED_TEXT, UTF8_TEXT }
data class SourceSummary(val id: Long, val title: String, val kind: SourceKind, val createdAt: Long,
    val revisionId: Long, val bytes: Long)
data class SourceText(val id: Long, val start: Long, val end: Long, val text: String)
data class SourcePage(val items: List<SourceSummary>, val next: Long?)
data class SourceTextPage(val items: List<SourceText>, val next: Long?)
data class LibraryFootprint(val usedBytes: Long, val freeBytes: Long)
fun interface LibraryCapacity { fun measure(): LibraryFootprint }
