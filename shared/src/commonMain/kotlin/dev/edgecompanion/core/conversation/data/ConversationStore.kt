package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.AssistantAttempt
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.conversation.model.TurnGrounding
import dev.edgecompanion.core.inference.ModelError

interface ConversationStore {
    fun attachments(): List<Attachment> = emptyList()
    fun epoch(): Long = 0
    fun contextEpoch(): Long = 0
    fun messageFence(): Long = 0
    fun reserve(item: Attachment) { error("Attachments unavailable") }
    fun complete(id: String, size: Long) { error("Attachments unavailable") }
    fun fail(id: String) { error("Attachments unavailable") }
    fun remove(id: String) { error("Attachments unavailable") }
    fun interruptImports() = Unit
    fun messages(): List<Message>
    fun draft(): String
    fun saveDraft(text: String)
    fun draftScope(): RequestScope = RequestScope.ORDINARY
    fun saveDraftScope(scope: RequestScope) { error("Draft scope unavailable") }
    fun append(text: String, timestamp: Long)
    fun submitUserMessage(text: String, timestamp: Long): Message { append(text, timestamp); return messages().last() }
    /** Persistent stores override this to keep the scope with the turn; simple fakes ignore it. */
    fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope): Message = submitUserMessage(text, timestamp)
    /**
     * Saves the user message and, when [attemptId] is set, its PENDING attempt in one transaction, so a
     * process loss before generation still leaves an attempt that restore reconciles to INTERRUPTED.
     * Persistent stores override this; the default (for simple fakes) is not atomic.
     */
    fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope, attemptId: String?): Message =
        submitUserMessage(text, timestamp, scope).also { if (attemptId != null) startAssistantTurn(attemptId, it.id, epoch()) }
    fun attempts(): List<AssistantAttempt> = emptyList()
    fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean = false
    /** Starts the attempt and writes its grounding record in one transaction (persistent stores override). */
    fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long, grounding: TurnGrounding): Boolean =
        startAssistantTurn(id, userId, expectedEpoch)
    /**
     * Begins generation for an existing PENDING attempt: re-checks the draft and context epochs and writes the
     * grounding and references in one transaction. Returns false (writing nothing) when the attempt is not
     * PENDING or an epoch moved on.
     */
    fun beginAssistantTurn(id: String, grounding: TurnGrounding): Boolean =
        attempts().any { it.id == id && it.status == TurnStatus.PENDING }
    /** Completes an existing PENDING attempt with a SOURCES no-answer result and no assistant message. */
    fun recordSourcesResult(id: String, grounding: TurnGrounding): Boolean = false
    /** Removes a PENDING attempt that never began (SOURCES with usable passages and no runtime). */
    fun discardPendingAssistantTurn(id: String) = Unit
    fun completeAssistantTurn(id: String, text: String, timestamp: Long): Boolean = false
    fun finishAssistantTurn(id: String, status: TurnStatus, error: ModelError? = null) = Unit
    fun reconcileAssistantTurns() = Unit
    fun clear()
}
