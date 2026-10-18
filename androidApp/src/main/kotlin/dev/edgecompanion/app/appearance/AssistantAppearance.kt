package dev.edgecompanion.app.appearance

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.edgecompanion.core.appearance.AppearancePreferences
import dev.edgecompanion.core.appearance.AppearanceState
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme

@Composable internal fun ComponentActivity.AssistantAppearance(
    preferences: AppearancePreferences,
    floating: Boolean = false,
    content: @Composable (AppearanceState) -> Unit,
) {
    val appearance by preferences.state.collectAsStateWithLifecycle()
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    CompanionTheme(dark = dark) {
        val background = MaterialTheme.colorScheme.background
        SideEffect {
            window.setBackgroundDrawable(ColorDrawable(if (floating) Color.Transparent.toArgb() else background.toArgb()))
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (floating) window.setDimAmount(if (dark) 0.36f else 0.2f)
        }
        content(appearance)
    }
}
