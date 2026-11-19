package dev.edgecompanion.ui.conversation.presentation

import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.sources.model.SourceSpan
import dev.edgecompanion.core.sources.model.SourceSpanPreview
import dev.edgecompanion.ui.resources.*

internal data class SourcesChipState(val checked: Boolean, val enabled: Boolean, val showEmptyHint: Boolean)

internal fun ConversationState.sourcesChip(hasSources: Boolean?): SourcesChipState {
    val checked = draftScope == RequestScope.SOURCES
    return SourcesChipState(checked, ready && (hasSources == true || checked), ready && hasSources != true)
}

internal val Message.sourceScopeLabel get() =
    if (role == MessageRole.USER && scope == RequestScope.SOURCES) Res.string.from_my_sources else null

internal val SourcesNoAnswer.messageResource get() = when (this) {
    SourcesNoAnswer.NO_MATCH -> Res.string.sources_no_match
    SourcesNoAnswer.NOT_INDEXED -> Res.string.sources_not_indexed
    SourcesNoAnswer.RETRIEVAL_INCOMPLETE -> Res.string.sources_retrieval_incomplete
    SourcesNoAnswer.PASSAGES_UNUSABLE -> Res.string.sources_passages_unusable
}

internal fun ConversationState.offersSourcesRetry(userId: Long, reason: SourcesNoAnswer) =
    userId > messageFence && reason.canRetry

sealed interface ConsultedRead {
    data object Loading : ConsultedRead
    data object Unavailable : ConsultedRead
    data class Loaded(val previews: List<SourceSpanPreview?>) : ConsultedRead
}

internal sealed interface ConsultedItem {
    data object Deleted : ConsultedItem
    data class Available(val span: SourceSpan, val preview: SourceSpanPreview) : ConsultedItem
}

internal fun List<ConsultedPassage>.consultedItems(read: ConsultedRead.Loaded) =
    mapIndexed { index, reference ->
        read.previews.getOrNull(index)?.let { ConsultedItem.Available(reference.span, it) } ?: ConsultedItem.Deleted
    }
