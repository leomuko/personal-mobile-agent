package dev.edgecompanion.ui.conversation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S5-F3 composer rules in px at density 1 (1 px = 1 dp), so the numbers read as dp. Inner widths are
 * the composer's Layout width: screen width minus 24 dp outer and 16 dp inner padding.
 */
class ComposerPlacementTest {
    private val button = 48
    private val floor = 72

    @Test fun idleBarKeepsOneRowOnlyWhileTheFieldShowsItsContentOnOneLine() {
        // Widths measured from 0.9-scale S24 pixels (round 1): chip off ~96, on ~116; content ~113.
        assertTrue(composerUsesOneRow(false, 371, button, 96, 113, floor))
        assertFalse(composerUsesOneRow(false, 371, button, 116, 113, floor), "chip on would wrap the placeholder")
        // 360 dp (inner 320): above the old 72 dp floor, but the placeholder would still wrap.
        assertFalse(composerUsesOneRow(false, 320, button, 96, 113, floor))
        // 320 dp (inner 280): below the floor even for a short draft.
        assertFalse(composerUsesOneRow(false, 280, button, 96, 20, floor))
        // The floor still applies to short content.
        assertTrue(composerUsesOneRow(false, 371, button, 96 + 59, 20, floor))
        assertFalse(composerUsesOneRow(false, 371, button, 96 + 60, 20, floor))
    }

    @Test fun chipIsIconOnlyExactlyWhileIdleAndOff() {
        assertFalse(sourcesChipShowsLabel(expanded = false, checked = false))
        assertTrue(sourcesChipShowsLabel(expanded = false, checked = true))
        assertTrue(sourcesChipShowsLabel(expanded = true, checked = false))
        assertTrue(sourcesChipShowsLabel(expanded = true, checked = true))
    }

    /**
     * S24 run 2026-11-23T03:09Z: at 411 dp and font scale 1.0 the labelled chip-off bar stacked (about
     * 103 dp of chip left about 124 dp, and the field's one-line content needs roughly 131-141 dp). The
     * icon-only chip (its 48 dp target) leaves 179 dp at any font scale.
     */
    @Test fun iconOnlyChipKeepsTheS24IdleBarOnOneRow() {
        assertFalse(composerUsesOneRow(false, 371, button, 103, 141, floor), "R1: labelled chip off at 1.0")
        assertTrue(composerUsesOneRow(false, 371, button, 48, 141, floor), "icon only at 1.0")
        assertTrue(composerUsesOneRow(false, 371, button, 48, 129, floor), "icon only at 0.9")
    }

    @Test fun expandedNeverUsesOneRowWhateverTheWidth() {
        assertFalse(composerUsesOneRow(true, 2_000, button, 96, 20, floor))
    }

    @Test fun stackedFieldTakesTheFullWidthAboveTheControlsRow() {
        for ((width, chip) in listOf(280 to 136, 320 to 156, 371 to 116, 371 to 176)) {
            val at = composerPlacement(false, width, button, stripHeight = 0, chipWidth = chip, chipHeight = 48, fieldHeight = 50)
            assertEquals(0, at.fieldX)
            assertEquals(width, at.fieldWidth, "full width at $width")
            assertEquals(0, at.fieldY)
            assertTrue(at.chipY >= 50 && at.actionY >= 50, "controls below the field at $width")
            assertEquals(0, at.addX)
            assertEquals(button, at.chipX)
            assertEquals(width - 2 * button, at.micX)
            assertEquals(width - button, at.sendX)
            assertEquals(50 + 48, at.height)
        }
    }

    @Test fun attachmentStripComesBeforeTheFieldRow() {
        val at = composerPlacement(false, 371, button, stripHeight = 80, chipWidth = 116, chipHeight = 48, fieldHeight = 50)
        assertEquals(80, at.fieldY)
        assertEquals(80 + 50 + (48 - button) / 2, at.actionY)
        assertEquals(80 + 50 + 48, at.height)
    }

    @Test fun controlsRowCentresTallerLargeFontChipWithTheButtons() {
        val at = composerPlacement(false, 280, button, stripHeight = 0, chipWidth = 136, chipHeight = 64, fieldHeight = 90)
        assertEquals(90, at.chipY)
        assertEquals(90 + 8, at.actionY)
        assertEquals(90 + 64, at.height)
    }

    @Test fun oneRowPutsTheFieldBetweenChipAndMicWithMicBesideSend() {
        val at = composerPlacement(true, 371, button, stripHeight = 0, chipWidth = 96, chipHeight = 48, fieldHeight = 48)
        assertEquals(button + 96, at.fieldX)
        assertEquals(371 - 3 * button - 96, at.fieldWidth)
        assertEquals(at.fieldX + at.fieldWidth, at.micX)
        assertEquals(at.micX + button, at.sendX)
        assertEquals(0, at.actionY)
        assertEquals(48, at.height)
    }

    @Test fun chipShrinksOnlyIntoTheControlsRowAndKeepsItsTouchTarget() {
        assertEquals(280 - 3 * button, composerChipMaxWidth(280, button))
        assertEquals(button, composerChipMaxWidth(150, button))
        assertEquals(280, composerFieldWidth(false, 280, button, 136))
        assertEquals(1, composerFieldWidth(true, 100, button, 96))
    }
}
