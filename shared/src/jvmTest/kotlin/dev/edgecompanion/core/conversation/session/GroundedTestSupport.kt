package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Synthetic-only fake: records requests, never loads anything. */
internal class RecordingRuntime : ChatRuntime {
    override val enabled = true
    val requests = mutableListOf<ChatRequest>()
    val cancels = mutableListOf<Pair<String, Boolean>>()
    private var events = Channel<ChatEvent>(Channel.UNLIMITED)
    override fun generateChat(request: ChatRequest): Flow<ChatEvent> {
        requests += request; events = Channel(Channel.UNLIMITED); return events.receiveAsFlow()
    }
    fun finish(text: String) {
        val id = requests.last().id
        events.trySend(ChatEvent.Text(id, text)); events.trySend(ChatEvent.Terminal(id, ChatOutcome.COMPLETED)); events.close()
    }
    override suspend fun cancelChat(id: String, interrupted: Boolean) {
        cancels += id to interrupted
        if (requests.lastOrNull()?.id == id) {
            events.trySend(ChatEvent.Terminal(id, if (interrupted) ChatOutcome.INTERRUPTED else ChatOutcome.CANCELLED))
            events.close()
        }
    }
}

/** A runtime that is present but disabled (no installed model); any generation call is recorded and fails. */
internal class DisabledRuntime : ChatRuntime {
    override val enabled = false
    var generateCalls = 0
    var cancelCalls = 0
    override fun generateChat(request: ChatRequest): Flow<ChatEvent> { generateCalls++; error("Synthetic runtime is disabled") }
    override suspend fun cancelChat(id: String, interrupted: Boolean) { cancelCalls++ }
}

internal val unlimited = LibraryCapacity { LibraryFootprint(0, Long.MAX_VALUE) }

internal fun SqlDriver.count(table: String): Long = executeQuery(null, "SELECT count(*) FROM $table", {
    it.next(); QueryResult.Value(it.getLong(0)!!)
}, 0).value

/** A revision imported before migration 5: searchable by title/AND search but without passages. */
internal fun CompanionDatabase.legacyRevision(title: String, text: String): Pair<Long, Long> = transactionWithResult {
    libraryQueries.insertSource(title, SourceKind.PASTED_TEXT.name, 42)
    val source = libraryQueries.lastId().executeAsOne()
    libraryQueries.insertRevision(source, text, text.encodeToByteArray().size.toLong())
    source to libraryQueries.lastId().executeAsOne()
}

internal fun retrieved(rank: Int, text: String, source: Long = 7) =
    RetrievedPassage(source, "Synthetic", 70L + rank, 700L + rank, 0, text.length.toLong(), text, 1.0)
