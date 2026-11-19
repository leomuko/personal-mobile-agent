package dev.edgecompanion.ui.conversation.presentation

import dev.edgecompanion.core.conversation.model.ConsultedPassage
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.MessageRole
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.conversation.model.SourcesNoAnswer
import dev.edgecompanion.core.sources.model.SourceSpanPreview
import dev.edgecompanion.ui.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesPresentationTest {
    @Test fun chipUsesPersistedDraftScopeAndEnablesTogglingWithSources() {
        val off = ConversationState(ready = true)
        val on = off.copy(draftScope = RequestScope.SOURCES)
        assertEquals(SourcesChipState(false, true, false), off.sourcesChip(true))
        assertEquals(SourcesChipState(true, true, false), on.sourcesChip(true))
        assertEquals(SourcesChipState(false, false, false), ConversationState().sourcesChip(true))
    }

    @Test fun checkedChipCanTurnOffWhenTheLibraryIsEmpty() {
        val on = ConversationState(ready = true, draftScope = RequestScope.SOURCES)
        assertEquals(SourcesChipState(true, true, true), on.sourcesChip(false))
        assertFalse(on.copy(ready = false).sourcesChip(false).enabled)
    }

    @Test fun uncheckedChipCannotTurnOnWhenTheLibraryIsEmpty() {
        val off = ConversationState(ready = true)
        assertEquals(SourcesChipState(false, false, true), off.sourcesChip(false))
    }

    @Test fun unknownLibraryAvailabilityBehavesLikeAnEmptyLibrary() {
        for (scope in RequestScope.entries) {
            val state = ConversationState(ready = true, draftScope = scope)
            assertEquals(state.sourcesChip(false), state.sourcesChip(null))
        }
    }

    @Test fun fromMySourcesLabelBelongsOnlyToScopedUserMessages() {
        val scoped = Message(1, "Synthetic question", 0, scope = RequestScope.SOURCES)
        assertEquals(Res.string.from_my_sources, scoped.sourceScopeLabel)
        assertNull(scoped.copy(scope = RequestScope.ORDINARY).sourceScopeLabel)
        assertNull(scoped.copy(role = MessageRole.ASSISTANT).sourceScopeLabel)
    }

    @Test fun allFourNoAnswersUseDistinctResourcesAndOnlyTemporaryReasonsOfferRetry() {
        val state = ConversationState(messageFence = 10)
        val expected = mapOf(
            SourcesNoAnswer.NO_MATCH to Res.string.sources_no_match,
            SourcesNoAnswer.NOT_INDEXED to Res.string.sources_not_indexed,
            SourcesNoAnswer.RETRIEVAL_INCOMPLETE to Res.string.sources_retrieval_incomplete,
            SourcesNoAnswer.PASSAGES_UNUSABLE to Res.string.sources_passages_unusable,
        )
        assertEquals(expected, SourcesNoAnswer.entries.associateWith { it.messageResource })
        assertEquals(setOf(SourcesNoAnswer.NOT_INDEXED, SourcesNoAnswer.RETRIEVAL_INCOMPLETE),
            SourcesNoAnswer.entries.filter { state.offersSourcesRetry(11, it) }.toSet())
        assertFalse(state.offersSourcesRetry(10, SourcesNoAnswer.NOT_INDEXED))
    }

    @Test fun consultedRowsPreserveOrderAndOpenOnlyLiveSpans() {
        val first = ConsultedPassage(1, 2, 3, 4, 20, 25)
        val second = ConsultedPassage(2, 7, 8, 9, 40, 60)
        val renamed = SourceSpanPreview("Synthetic renamed source", "Span preview")
        val read = ConsultedRead.Loaded(listOf(null, renamed))
        assertEquals(listOf(ConsultedItem.Deleted, ConsultedItem.Available(second.span, renamed)),
            listOf(first, second).consultedItems(read))
        assertTrue(emptyList<ConsultedPassage>().consultedItems(read).isEmpty())
    }
}
