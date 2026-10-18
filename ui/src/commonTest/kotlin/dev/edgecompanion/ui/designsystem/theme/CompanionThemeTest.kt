package dev.edgecompanion.ui.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class CompanionThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    @Test fun bothPalettesProvideReadableFeatureAndControlColors() {
        listOf(false, true).forEach { dark ->
            val colors = companionColorScheme(dark)
            listOf(
                colors.onSurface to colors.surface,
                colors.onBackground to colors.background,
                colors.onSurface to colors.surfaceContainer,
                colors.onSurfaceVariant to colors.surfaceContainerHighest,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.error to colors.surface,
            ).forEach { (foreground, background) ->
                assertTrue(contrast(foreground, background) >= 4.5f, "Unreadable pair in dark=$dark: $foreground / $background")
            }
            assertTrue(contrast(colors.outline, colors.surfaceContainerHighest) >= 3f, "Control outline dark=$dark")
        }
    }
}
