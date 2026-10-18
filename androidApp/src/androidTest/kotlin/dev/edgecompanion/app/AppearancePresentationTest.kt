package dev.edgecompanion.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import dev.edgecompanion.core.appearance.AppearanceState
import dev.edgecompanion.core.appearance.ThemeMode
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.conversation.components.RecordingState
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearancePresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appearanceSelectionRetainsNavigationDraftAndStreamingControls() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        val state = ConversationState(ready = true, draft = "Unsent synthetic draft",
            messages = listOf(Message(1, "Synthetic question", 1)),
            assistant = AssistantState(true, AssistantPhase.STREAMING, "synthetic", 1, "Synthetic streamed reply"))
        var sends = 0
        compose.setContent { CompanionTheme(dark = mode == ThemeMode.DARK) {
            CompanionScreen(state, false, false, "Synthetic", {}, { sends++ }, {}, {}, {}, {}, {}, {},
                appearance = AppearanceState(mode = mode), onThemeMode = { mode = it })
        } }
        screenshot("light-streaming")
        navigate("Settings")
        compose.onNodeWithTag("theme-mode-dark").performClick()
        compose.onNodeWithTag("theme-mode-dark").assertIsSelected()
        compose.onNodeWithTag("assistant-top-bar-title", useUnmergedTree = true).assertTextEquals("Settings")
        assertTextColor("Appearance", Color(0xFFE3E9E5))
        screenshot("dark-settings")
        navigate("Chat")
        compose.onNodeWithText("Unsent synthetic draft").assertIsDisplayed()
        compose.onNodeWithTag("assistant-stream").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop response").assertIsEnabled()
        screenshot("dark-streaming")
        compose.runOnIdle { assertEquals(0, sends) }
        navigate("Settings")
        compose.onNodeWithTag("theme-mode-system").performClick()
        compose.onNodeWithTag("theme-mode-system").assertIsSelected()
    }

    @Test fun compactLargeTextBothThemesKeepNavigationAndRecordingAccessible() {
        var dark by mutableStateOf(false)
        var recording by mutableStateOf(false)
        var stops = 0
        compose.setContent { CompanionTheme(dark = dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    CompanionScreen(ConversationState(ready = true), false, false, "Synthetic", {}, {}, {}, {}, {}, {}, {}, {},
                        recording = RecordingState(active = recording, seconds = 37, levels = List(32) { 0.5f }),
                        onStopRecording = { stops++ })
                }
            }
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            for (destination in listOf("Memory", "Sources", "Settings", "Chat")) {
                navigate(destination)
                compose.onNodeWithTag("assistant-top-bar-title", useUnmergedTree = true)
                    .assertTextEquals(if (destination == "Chat") "Assistant" else destination)
                val image = compose.onNodeWithTag("assistant-top-bar").captureToImage()
                val pixels = image.toPixelMap()
                val expected = if (night) 0xFF141816.toInt() else 0xFFFFFFFF.toInt()
                assertEquals(expected, pixels[0, pixels.height / 2].toArgb())
                screenshot("compact-large-${if (night) "dark" else "light"}-${destination.lowercase()}")
            }
            compose.runOnIdle { recording = true }
            compose.onNodeWithText("00:37").assertIsDisplayed()
            compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Cancel recording").assertIsDisplayed()
            screenshot("compact-large-${if (night) "dark" else "light"}-recording")
            compose.runOnIdle { recording = false }
        }
        compose.runOnIdle { assertEquals(2, stops) }
    }

    private fun navigate(destination: String) {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNode(hasText(destination) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("appearanceScreenshots") != "true") return
        // Only this test's synthetic Compose root is exported, never a real assistant window.
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "appearance-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertTextColor(text: String, color: Color) {
        val pixels = compose.onNodeWithText(text).captureToImage().toPixelMap()
        var matches = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].toArgb() == color.toArgb()) matches++
        }
        assertTrue("Text must inherit the theme's readable content color", matches > 10)
    }
}
