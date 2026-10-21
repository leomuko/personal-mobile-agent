package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One active request per durable session; native lifetime remains owned by ChatRuntime. */
internal class ConversationAssistant(private val scope: CoroutineScope,
    private val controller: suspend () -> ConversationController?, private val runtime: ChatRuntime?) {
    private val mutable = MutableStateFlow(AssistantState(enabled = runtime?.enabled == true))
    val state = mutable.asStateFlow()
    private val admission = Mutex()
    private val mutations = Mutex()
    private var worker: Job? = null
    private var validId: String? = null
    private var userCancelled = false
    private var clearing = false
    private var cleanupFailed = false

    fun submit(retryUserId: Long? = null) {
        scope.launch {
            if (clearing) return@launch
            if (cleanupFailed) {
                mutable.value = AssistantState(runtime?.enabled == true, AssistantPhase.ERROR, error = ModelError.RESTART_REQUIRED)
                return@launch
            }
            if (!admission.tryLock()) {
                mutable.value = mutable.value.copy(error = ModelError.BUSY)
                return@launch
            }
            userCancelled = false
            worker = currentCoroutineContext().job
            var id: String? = null
            var target: ConversationController? = null
            var pending = false
            try {
                target = controller() ?: return@launch
                if (runtime?.enabled != true) { if (retryUserId == null) target.submit(); return@launch }
                val snapshot = target.state.value
                if (retryUserId != null && retryUserId <= snapshot.messageFence) return@launch
                val current = if (retryUserId == null) snapshot.draft.trim() else
                    snapshot.messages.firstOrNull { it.id == retryUserId && it.role == MessageRole.USER }?.text ?: return@launch
                if (current.isEmpty()) { if (retryUserId == null) target.submit(); return@launch }
                if (retryUserId != null && snapshot.attempts.any { it.userId == retryUserId && it.status == TurnStatus.COMPLETED }) return@launch
                id = Random.nextLong().toString(16) + Random.nextLong().toString(16)
                val older = snapshot.messages.filter { it.id > snapshot.messageFence && (retryUserId == null || it.id < retryUserId) }
                val complete = older.filter { it.role == MessageRole.USER }.mapNotNull { user ->
                    older.lastOrNull { it.role == MessageRole.ASSISTANT && it.turnId == user.id }?.let { user.text to it.text }
                }
                val request = GemmaChatBudget.build(current, complete, id)
                val user = if (retryUserId == null) target.submit() else snapshot.messages.first { it.id == retryUserId }
                if (user == null || user.text != current) return@launch
                validId = id
                mutable.value = AssistantState(true, AssistantPhase.LOADING, id, user.id)
                if (!target.startAssistant(id, user.id, target.state.value.draftEpoch, snapshot.contextEpoch)) return@launch
                pending = true
                var terminalSeen = false
                runtime.generateChat(request).collect { event ->
                    if (event.id != id || validId != id || terminalSeen) return@collect
                    when (event) {
                        is ChatEvent.Text -> mutable.value = mutable.value.copy(phase = AssistantPhase.STREAMING,
                            text = mutable.value.text + event.delta)
                        is ChatEvent.Terminal -> {
                            terminalSeen = true
                            if (event.outcome == ChatOutcome.COMPLETED && mutable.value.text.isNotBlank()) {
                                if (!target.completeAssistant(id, mutable.value.text)) {
                                    mutable.value = mutable.value.copy(phase = AssistantPhase.ERROR, saveFailed = true)
                                } else mutable.value = AssistantState(enabled = true)
                            } else {
                                val status = when (event.outcome) {
                                    ChatOutcome.CANCELLED -> TurnStatus.CANCELLED
                                    ChatOutcome.INTERRUPTED -> TurnStatus.INTERRUPTED
                                    else -> TurnStatus.FAILED
                                }
                                target.finishAssistant(id, status, event.error)
                                mutable.value = AssistantState(true, AssistantPhase.ERROR, id, user.id, error = event.error)
                            }
                            pending = false
                        }
                    }
                }
                if (!terminalSeen && validId == id) {
                    target.finishAssistant(id, TurnStatus.FAILED, ModelError.GENERATION_FAILED)
                    pending = false
                    mutable.value = AssistantState(true, AssistantPhase.ERROR, id, user.id, error = ModelError.GENERATION_FAILED)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    id?.let { cancelRequest(it, interrupted = !userCancelled) }
                    if (pending && id != null) target?.finishAssistant(id,
                        if (userCancelled) TurnStatus.CANCELLED else TurnStatus.INTERRUPTED, null)
                }
                if (validId == id && !cleanupFailed) mutable.value = AssistantState(enabled = runtime?.enabled == true)
                throw cancelled
            } catch (failure: Exception) {
                if (id == null || validId == id || !pending) mutable.value = AssistantState(runtime?.enabled == true,
                    AssistantPhase.ERROR, id, mutable.value.userId,
                    error = (failure as? ModelException)?.error, saveFailed = failure !is ModelException)
                if (pending && id != null) runCatching { target?.finishAssistant(id, TurnStatus.FAILED, (failure as? ModelException)?.error) }
            } finally {
                if (validId == id) validId = null
                worker = null
                if (mutable.value.active) mutable.value = mutable.value.copy(phase = AssistantPhase.ERROR,
                    error = ModelError.GENERATION_FAILED)
                admission.unlock()
            }
        }
    }
    fun cancel() { scope.launch {
        userCancelled = true
        validId?.let { cancelRequest(it, interrupted = false) }
        worker?.cancel()
    } }
    suspend fun invalidate() {
        val id = validId
        validId = null
        mutable.value = AssistantState(enabled = runtime?.enabled == true)
        if (id != null) cancelRequest(id, interrupted = true)
        worker?.cancelAndJoin()
    }
    suspend fun clear(block: suspend () -> Unit) = mutations.withLock {
        clearing = true
        try { invalidate(); block() } finally { clearing = false }
    }

    /** Fence first, then cancel. No callback can publish after the durable mutation begins. */
    suspend fun <T> mutateContext(block: suspend () -> T): T = mutations.withLock {
        clearing = true
        val id = validId
        validId = null
        mutable.value = AssistantState(enabled = runtime?.enabled == true)
        try { block() }
        finally {
            withContext(NonCancellable) {
                try {
                    if (id != null) cancelRequest(id, interrupted = true)
                    worker?.cancelAndJoin()
                    if (id != null) controller()?.finishAssistant(id, TurnStatus.INTERRUPTED, null)
                    if (cleanupFailed) throw ModelException(ModelError.RESTART_REQUIRED)
                } finally { clearing = false }
            }
        }
    }
    private suspend fun cancelRequest(id: String, interrupted: Boolean) = withContext(NonCancellable) {
        try { runtime?.cancelChat(id, interrupted) }
        catch (_: Exception) {
            cleanupFailed = true
            mutable.value = AssistantState(runtime?.enabled == true, AssistantPhase.ERROR, error = ModelError.RESTART_REQUIRED)
        }
    }
}
