package dev.edgecompanion.core.inference

import kotlinx.coroutines.flow.Flow

enum class ChatRole { USER, ASSISTANT }
data class ChatMessage(val role: ChatRole, val text: String)
data class ChatRequest(val id: String, val messages: List<ChatMessage>,
    val backend: ModelBackend = ModelBackend.GPU, val contextTokens: Int = 2048, val outputTokens: Int = 512)
enum class ChatOutcome { COMPLETED, CANCELLED, INTERRUPTED, REJECTED, FAILED }
sealed interface ChatEvent {
    val id: String
    data class Text(override val id: String, val delta: String) : ChatEvent
    data class Terminal(override val id: String, val outcome: ChatOutcome, val error: ModelError? = null) : ChatEvent
}
interface ChatRuntime {
    val enabled: Boolean
    fun generateChat(request: ChatRequest): Flow<ChatEvent>
    suspend fun cancelChat(id: String, interrupted: Boolean = false)
}

/** Bound for the pinned identity-normalized, byte-fallback BPE artifact; not a token estimate. */
object GemmaChatBudget {
    const val VERSION = "gemma4-e2b-byte-bound-v2"
    fun inputBound(messages: List<ChatMessage>): Long {
        require(messages.isNotEmpty() && messages.last().role == ChatRole.USER)
        require(messages.size % 2 == 1 && messages.withIndex().all {
            it.value.role == if (it.index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT
        })
        var bound = 128L // BOS, generation prefix and tokenizer boundary allowance.
        for (message in messages) {
            val text = message.text
            if (text.contains("<|") || text.contains("<start_of_") || text.contains("<turn|>"))
                throw ModelException(ModelError.CONTEXT_LIMIT)
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (c.isHighSurrogate()) {
                    if (i == text.length || !text[i++].isLowSurrogate()) throw ModelException(ModelError.CONTEXT_LIMIT)
                } else if (c.isLowSurrogate()) throw ModelException(ModelError.CONTEXT_LIMIT)
            }
            // The pinned tokenizer has a NORMAL one-token metaspace, so replacing an
            // ASCII space does not increase seed tokens. Other scalars cost at most
            // their UTF-8 bytes; BPE merging/resegmentation cannot exceed those seeds.
            bound += 96L + text.encodeToByteArray().size
        }
        return bound
    }
    fun validate(request: ChatRequest): Long {
        if (request.id.isBlank() || request.contextTokens != 2048 || request.outputTokens !in 1..512)
            throw ModelException(ModelError.CONTEXT_LIMIT)
        val bound = try { inputBound(request.messages) }
            catch (_: IllegalArgumentException) { throw ModelException(ModelError.CONTEXT_LIMIT) }
        if (bound + request.outputTokens > request.contextTokens) throw ModelException(ModelError.CONTEXT_LIMIT)
        return bound
    }
    fun build(current: String, completeTurns: List<Pair<String, String>>, id: String): ChatRequest {
        val messages = mutableListOf(ChatMessage(ChatRole.USER, current))
        validate(ChatRequest(id, messages.toList()))
        for ((user, assistant) in completeTurns.asReversed()) {
            val older = listOf(ChatMessage(ChatRole.USER, user), ChatMessage(ChatRole.ASSISTANT, assistant))
            val candidate = ChatRequest(id, older + messages)
            try { validate(candidate) } catch (_: ModelException) { break }
            messages.addAll(0, older)
        }
        return ChatRequest(id, messages.toList())
    }
}
