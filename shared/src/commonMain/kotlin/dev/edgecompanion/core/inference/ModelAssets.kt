package dev.edgecompanion.core.inference

import kotlinx.coroutines.flow.StateFlow

interface ModelAssets {
    val state: StateFlow<ModelAssetState>
    suspend fun verify(): String?
    suspend fun install(source: ModelSource)
}

fun interface ModelSource { fun open(): ModelReader }
interface ModelReader {
    val size: Long?
    fun read(buffer: ByteArray): Int
    fun close()
}

object ModelTestPrompts {
    const val SHORT = "List three colors, separated by commas."
    const val MEDIUM = """Summarize this fictional project update in three concise sentences. The Cedar team is building a small library kiosk for a community center. On Monday, the team tested the card scanner with twenty sample books and found that two labels were damaged. On Tuesday, they replaced those labels and repeated the scan with no errors. The kiosk display is ready, but the printed instructions still need a final review. Maya will check the instructions on Thursday. Omar will prepare the opening-day checklist on Friday. The team plans a practice session next week, using only sample records. No personal borrower information is part of this update."""
    const val CANCELLATION = "Write a numbered list of one hundred simple household objects, one per line."
    val allowed = setOf(SHORT, MEDIUM, CANCELLATION)
}
