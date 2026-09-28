package dev.edgecompanion.ui.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import dev.edgecompanion.ui.resources.*

val Forest = Color(0xFF16634D)
internal val Mint = Color(0xFFE8F3ED)
private val Ink = Color(0xFF1D2925)

@Composable fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Forest, onPrimary = Color.White, secondary = Color(0xFF9B5243),
        background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F4F3),
        surfaceContainer = Color(0xFFF0F4F2), surfaceContainerHigh = Color(0xFFE2EBE6),
        surfaceContainerLow = Color(0xFFF7F9F8), onSurfaceVariant = Color(0xFF4C5A53),
        onSurface = Ink, onBackground = Ink, outline = Color(0xFF7A8580),
    ), content = content)
}
