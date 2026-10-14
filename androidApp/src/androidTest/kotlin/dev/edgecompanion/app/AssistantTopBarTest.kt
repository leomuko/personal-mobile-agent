package dev.edgecompanion.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.components.AssistantTopBarDefaults
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantTopBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerRhythmIsSharedAcrossDestinationsAndHosts() {
        val floating = mutableStateOf(false)
        var density = 1f
        compose.setContent { CompanionTheme {
            density = LocalDensity.current.density
            Fixture(floating.value)
        } }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun checkHeader() {
            val body = bounds("assistant-top-bar-body")
            val mascot = bounds("assistant-top-bar-mascot")
            val title = bounds("assistant-top-bar-title")
            val status = bounds("assistant-top-bar-status")
            assertTrue(body.height >= AssistantTopBarDefaults.MinHeight.value * density - 1f)
            assertEquals(AssistantTopBarDefaults.MascotSize.value * density, mascot.width, 1f)
            assertEquals(mascot.width, mascot.height, 1f)
            assertEquals(body.center.y, mascot.center.y, 1f)
            assertEquals(title.left, status.left, 1f)
            assertTrue(title.bottom <= status.top)
            assertTrue(title.left >= mascot.right)
            assertTrue(status.bottom <= body.bottom)
        }
        checkHeader()
        val initialTitle = bounds("assistant-top-bar-title")
        val initialStatus = bounds("assistant-top-bar-status")
        for (destination in listOf("Settings", "Memory", "Sources", "Chat")) {
            compose.onNodeWithContentDescription("Open navigation menu").performClick()
            compose.onNode(hasText(destination) and hasClickAction()).performClick()
            compose.waitForIdle()
            checkHeader()
            assertEquals(initialTitle.top, bounds("assistant-top-bar-title").top, 1f)
            assertEquals(initialStatus.top, bounds("assistant-top-bar-status").top, 1f)
        }
        compose.runOnIdle { floating.value = true }
        checkHeader()
        compose.onNodeWithContentDescription("Open navigation menu").assertDoesNotExist()
        for (label in listOf("Open full app", "Close conversation")) {
            val control = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertEquals(AssistantTopBarDefaults.ControlSize.value * density, control.width, 1f)
            assertEquals(bounds("assistant-top-bar-mascot").center.y, control.center.y, 1f)
        }
    }

    @Test fun compactLargeTextHeaderUsesThemeColorsWithoutOverlappingActions() {
        val surface = Color(0xFFE0FBFC)
        val text = Color(0xFF172E96)
        val icons = Color(0xFFE00080)
        val status = Color(0xFF009946)
        val divider = Color(0xFFBA2200)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = surface, onSurface = text,
                onSurfaceVariant = icons, primary = status, outlineVariant = divider)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) { Fixture(true) }
                }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = bounds("assistant-top-bar-title")
        val actions = bounds("assistant-top-bar-actions")
        assertTrue(title.right <= actions.left)
        assertTrue(title.bottom <= bounds("assistant-top-bar-status").top)
        compose.onNodeWithContentDescription("Open full app").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close conversation").assertIsDisplayed()
        val pixels = compose.onNodeWithTag("assistant-top-bar").captureToImage().toPixelMap()
        fun count(color: Color): Int {
            var matches = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == color.toArgb()) matches++
            }
            return matches
        }
        assertTrue("Surface token missing", count(surface) > pixels.width * pixels.height / 3)
        assertTrue("Text token missing", count(text) > 20)
        assertTrue("Icon token missing", count(icons) > 20)
        assertTrue("Status token missing", count(status) > 20)
        assertTrue("Divider token missing", count(divider) > pixels.width / 2)
    }

    @Composable private fun Fixture(drawer: Boolean) {
        CompanionScreen(ConversationState(ready = true), drawer, false, "Synthetic", {}, {}, {}, {}, {}, {}, {}, {})
    }
}
