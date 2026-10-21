package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
import kotlinx.coroutines.flow.StateFlow

interface SourceLibrary {
    val sourceChanges: StateFlow<Long>
    suspend fun listSources(query: String = "", after: Long = 0): SourcePage
    suspend fun sourceText(id: Long, after: Long = 0): SourceTextPage
    suspend fun addTextSource(title: String, text: String): SourceSummary
    suspend fun addUtf8Source(title: String, bytes: ByteArray): SourceSummary
    suspend fun renameSource(id: Long, title: String)
    suspend fun deleteSource(id: Long): Boolean
}
