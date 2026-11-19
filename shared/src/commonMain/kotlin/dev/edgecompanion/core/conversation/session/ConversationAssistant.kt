package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.edgecompanion.core.sources.model.PassageControl
import dev.edgecompanion.core.sources.model.PassageRetrieval
import dev.edgecompanion.core.sources.model.PassageStopReason
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** Retrieval on the session's serial storage lane: (query, UTF-8 text budget, control) -> result. */
/** Returns null when retrieval is skipped because the library has no source revisions (D3). */
internal typealias PassageRetriever = suspend (String, Int, PassageControl) -> PassageRetrieval?

/** One active request per durable session; native lifetime remains owned by ChatRuntime. */
internal class ConversationAssistant(private val scope: CoroutineScope,
    private val controller: suspend () -> ConversationController?, private val runtime: ChatRuntime?,
    private val chatProfile: ChatContextProfile = ChatContextProfile.STANDARD,
    private val retriever: PassageRetriever? = null) {
    /** Set synchronously by [cancel], so a blocking retrieval on the lane can stop at its next checkpoint. */
    private class RetrievalStop { @Volatile var requested = false }
    @Volatile private var retrievalStop: RetrievalStop? = null
    private val mutable = MutableStateFlow(AssistantState(enabled = runtime?.enabled == true))
    val state = mutable.asStateFlow()
    private val admission = Mutex()
    private val mutations = Mutex()
    private var worker: Job? = null
    private var validId: String? = null
    private var userCancelled = false
    private var clearing = false
    private var cleanupFailed = false

    fun submit(retryUserId: Long? = null, requestScope: RequestScope? = RequestScope.ORDINARY) {
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
            // True while this turn owns a durable PENDING attempt; every exit finishes it exactly once.
            var pending = false
            val generator = runtime?.takeIf { it.enabled }
            try {
                target = controller() ?: return@launch
                val snapshot = target.state.value
                if (retryUserId != null && retryUserId <= snapshot.messageFence) return@launch
                val retried = if (retryUserId == null) null else
                    snapshot.messages.firstOrNull { it.id == retryUserId && it.role == MessageRole.USER } ?: return@launch
                val turnScope = retried?.scope ?: requestScope ?: snapshot.draftScope
                // Without a runtime, only SOURCES has work to do: its no-answer result needs no model (S4-F2).
                if (generator == null && turnScope != RequestScope.SOURCES) {
                    if (retryUserId == null) target.submit(turnScope); return@launch
                }
                val current = retried?.text ?: snapshot.draft.trim()
                if (current.isEmpty()) { if (retryUserId == null) target.submit(turnScope); return@launch }
                if (retryUserId != null && snapshot.attempts.any { it.userId == retryUserId && it.blocksRetry }) return@launch
                val turnId = Random.nextLong().toString(16) + Random.nextLong().toString(16)
                id = turnId
                val older = snapshot.messages.filter { it.id > snapshot.messageFence && (retryUserId == null || it.id < retryUserId) }
                val complete = older.filter { it.role == MessageRole.USER }.mapNotNull { user ->
                    older.lastOrNull { it.role == MessageRole.ASSISTANT && it.turnId == user.id }?.let { user.text to it.text }
                }
                // Validates the plain request first, so an over-long message is still rejected before it is saved.
                GemmaChatBudget.build(current, complete, turnId, chatProfile)
                // S4-F5: the PENDING attempt is durable before retrieval starts (with the message for a new turn),
                // so a process loss at any later point restores as an INTERRUPTED attempt with Retry.
                val user = if (retried == null) target.submit(turnScope, turnId, current) ?: return@launch else {
                    if (!target.startAssistant(turnId, retried.id, target.state.value.draftEpoch, snapshot.contextEpoch)) return@launch
                    retried
                }
                if (target.state.value.attempts.none { it.id == turnId && it.status == TurnStatus.PENDING }) return@launch
                pending = true
                validId = turnId
                mutable.value = AssistantState(generator != null, AssistantPhase.LOADING, turnId, user.id)
                suspend fun stopBeforeGeneration(status: TurnStatus) {
                    target.finishAssistant(turnId, status, null)
                    pending = false
                    if (validId == turnId) mutable.value = AssistantState(enabled = generator != null)
                }
                var retrieval: PassageRetrieval? = null
                var retrievalFailed = false
                var retrievalSkipped = false
                if (retriever != null) {
                    val stop = RetrievalStop()
                    retrievalStop = stop
                    val job = currentCoroutineContext().job
                    try {
                        retrieval = retriever.invoke(current, GroundedPrompt.retrievalTextBudget(chatProfile),
                            PassageControl(continueWork = { !stop.requested && job.isActive && validId == turnId }))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { retrievalFailed = true }
                    finally { retrievalStop = null }
                    retrievalSkipped = retrieval == null && !retrievalFailed
                    currentCoroutineContext().ensureActive()
                    if (validId != turnId || retrieval?.reason == PassageStopReason.CONTEXT_CHANGED) {
                        stopBeforeGeneration(TurnStatus.INTERRUPTED); return@launch
                    }
                    if (stop.requested || retrieval?.reason == PassageStopReason.CANCELLED) {
                        stopBeforeGeneration(TurnStatus.CANCELLED); return@launch
                    }
                }
                // The retrieval's epoch must be the one the attempt starts under; otherwise a source changed.
                val contextEpoch = retrieval?.contextEpoch ?: snapshot.contextEpoch
                if (contextEpoch != snapshot.contextEpoch) { stopBeforeGeneration(TurnStatus.INTERRUPTED); return@launch }
                val assembly = GroundedPrompt.assemble(current, complete, turnId, chatProfile, turnScope,
                    retrieval?.passages.orEmpty())
                val summary = retrieval?.let {
                    RetrievalSummary(if (it.reason == null) RetrievalStatus.COMPLETE else RetrievalStatus.INCOMPLETE,
                        it.reason, it.coverage.indexedRevisions, it.coverage.totalRevisions)
                } ?: when {
                    retrievalFailed -> RetrievalSummary(RetrievalStatus.FAILED, null, 0, 0)
                    retriever != null && retrievalSkipped -> RetrievalSummary(RetrievalStatus.SKIPPED, null, 0, 0)
                    else -> null
                }
                val grounding = TurnGrounding(retrieval = summary, consulted = assembly.consulted)
                if (turnScope == RequestScope.SOURCES && assembly.consulted.isEmpty()) {
                    // No model load and no generation: a typed result that S5 renders from resources.
                    val result = GroundedPrompt.noAnswer(retrieval, retrievalFailed)
                    if (target.recordSourcesResult(turnId, contextEpoch, grounding.copy(sourcesResult = result))) {
                        pending = false
                        mutable.value = AssistantState(enabled = generator != null)
                    } else stopBeforeGeneration(TurnStatus.INTERRUPTED)
                    return@launch
                }
                if (generator == null) {
                    // S4-F2: usable passages but no runtime. End as a disabled turn always has: the message stays
                    // with its scope, no attempt and no references remain, and the assistant is idle and disabled.
                    target.discardAssistant(turnId)
                    pending = false
                    mutable.value = AssistantState(enabled = false)
                    return@launch
                }
                // Begin generation: grounding and references are written for this same attempt, under its epoch.
                if (!target.beginAssistant(turnId, contextEpoch, grounding)) {
                    stopBeforeGeneration(TurnStatus.INTERRUPTED); return@launch
                }
                val request = assembly.request
                var terminalSeen = false
                generator.generateChat(request).collect { event ->
                    if (event.id != turnId || validId != turnId || terminalSeen) return@collect
                    when (event) {
                        is ChatEvent.Text -> mutable.value = mutable.value.copy(phase = AssistantPhase.STREAMING,
                            text = mutable.value.text + event.delta)
                        is ChatEvent.Terminal -> {
                            terminalSeen = true
                            if (event.outcome == ChatOutcome.COMPLETED && mutable.value.text.isNotBlank()) {
                                if (!target.completeAssistant(turnId, mutable.value.text)) {
                                    mutable.value = mutable.value.copy(phase = AssistantPhase.ERROR, saveFailed = true)
                                } else mutable.value = AssistantState(enabled = true)
                            } else {
                                val status = when (event.outcome) {
                                    ChatOutcome.CANCELLED -> TurnStatus.CANCELLED
                                    ChatOutcome.INTERRUPTED -> TurnStatus.INTERRUPTED
                                    else -> TurnStatus.FAILED
                                }
                                target.finishAssistant(turnId, status, event.error)
                                mutable.value = AssistantState(true, AssistantPhase.ERROR, turnId, user.id, error = event.error)
                            }
                            pending = false
                        }
                    }
                }
                if (!terminalSeen && validId == turnId) {
                    target.finishAssistant(turnId, TurnStatus.FAILED, ModelError.GENERATION_FAILED)
                    pending = false
                    mutable.value = AssistantState(true, AssistantPhase.ERROR, turnId, user.id, error = ModelError.GENERATION_FAILED)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    id?.let { if (generator != null) cancelRequest(it, interrupted = !userCancelled) }
                    // Covers a stop during retrieval too: the same PENDING attempt is finished, never a new one.
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
    fun cancel() {
        retrievalStop?.requested = true
        scope.launch {
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
