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

    @Test fun unknownLibraryAvailabilityGatesTheChipLikeAnEmptyLibraryWithoutTheHint() {
        for (scope in RequestScope.entries) {
            val state = ConversationState(ready = true, draftScope = scope)
            assertEquals(state.sourcesChip(false).copy(showEmptyHint = false), state.sourcesChip(null))
        }
    }

    @Test fun addSourceHintShowsOnlyWhenTheLibraryIsKnownToBeEmpty() {
        for (scope in RequestScope.entries) {
            val state = ConversationState(ready = true, draftScope = scope)
            assertFalse(state.sourcesChip(null).showEmptyHint, "unknown, $scope")
            assertTrue(state.sourcesChip(false).showEmptyHint, "empty, $scope")
            assertFalse(state.sourcesChip(true).showEmptyHint, "has sources, $scope")
        }
        assertFalse(ConversationState(ready = true).sourcesChip(null).enabled)
    }

    /**
     * S24 ui run 2026-11-20 (PassagesCaptureTest.item2EmptyLibraryChip): once the chip is turned off on
     * an empty library, the visible hint is announced by the disabled chip's state description and is
     * not a separate text node, so it must be located by its tag, not by its text.
     */
    @Test fun disabledChipCarriesTheAddSourceHintOnlyForAKnownEmptyLibrary() {
        val off = ConversationState(ready = true)
        val on = off.copy(draftScope = RequestScope.SOURCES)
        assertTrue(off.sourcesChip(false).hintAnnouncedByChip, "empty, chip off")
        assertFalse(on.sourcesChip(false).hintAnnouncedByChip, "empty, chip on: the hint is its own text")
        assertTrue(on.sourcesChip(false).showEmptyHint, "empty, chip on: the hint is still shown")
        for (scope in RequestScope.entries) {
            val state = ConversationState(ready = true, draftScope = scope)
            assertFalse(state.sourcesChip(null).hintAnnouncedByChip, "unknown, $scope")
            assertFalse(state.sourcesChip(true).hintAnnouncedByChip, "has sources, $scope")
        }
        val notReady = ConversationState(draftScope = RequestScope.SOURCES)
        assertTrue(notReady.sourcesChip(true).let { !it.enabled && !it.hintAnnouncedByChip }, "not ready, has sources")
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
