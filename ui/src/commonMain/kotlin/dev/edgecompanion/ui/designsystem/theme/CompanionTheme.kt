package dev.edgecompanion.ui.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

val Forest = Color(0xFF16634D)

internal fun companionColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFF8BD6B6), onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF234D3E), onPrimaryContainer = Color(0xFFD5F5E5),
    secondary = Color(0xFFF0B5A5), onSecondary = Color(0xFF53271C),
    secondaryContainer = Color(0xFF613A30), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiaryContainer = Color(0xFF514119), onTertiaryContainer = Color(0xFFF5E2AB),
    background = Color(0xFF141816), onBackground = Color(0xFFE3E9E5),
    surface = Color(0xFF141816), onSurface = Color(0xFFE3E9E5),
    surfaceVariant = Color(0xFF343D38), onSurfaceVariant = Color(0xFFC0CBC4),
    surfaceContainerLowest = Color(0xFF101310), surfaceContainerLow = Color(0xFF1B211D),
    surfaceContainer = Color(0xFF222925), surfaceContainerHigh = Color(0xFF2C332F),
    surfaceContainerHighest = Color(0xFF363E39),
    outline = Color(0xFF8B9990), outlineVariant = Color(0xFF414D45),
) else lightColorScheme(
    primary = Forest, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F3ED), onPrimaryContainer = Color(0xFF193C2C),
    secondary = Color(0xFF9B5243), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF3B160E),
    tertiaryContainer = Color(0xFFFFF0CB), onTertiaryContainer = Color(0xFF5D4300),
    background = Color.White, surface = Color.White,
    surfaceVariant = Color(0xFFF2F4F3), onSurfaceVariant = Color(0xFF4C5A53),
    surfaceContainer = Color(0xFFF0F4F2), surfaceContainerHigh = Color(0xFFE2EBE6),
    surfaceContainerLow = Color(0xFFF7F9F8), surfaceContainerLowest = Color.White,
    surfaceContainerHighest = Color(0xFFD8E2DC),
    onSurface = Color(0xFF1D2925), onBackground = Color(0xFF1D2925),
    outline = Color(0xFF6A766F), outlineVariant = Color(0xFFE9EEEB),
)

@Composable fun CompanionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = companionColorScheme(dark)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
