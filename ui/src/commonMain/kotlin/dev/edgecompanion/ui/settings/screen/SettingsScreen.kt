package dev.edgecompanion.ui.settings.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.appearance.AppearanceError
import dev.edgecompanion.core.appearance.AppearanceState
import dev.edgecompanion.core.appearance.ThemeMode
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun SettingsScreen(overlayEnabled: Boolean, notificationsAvailable: Boolean, deviceSummary: String, ready: Boolean, onOverlay: (Boolean) -> Unit, onNotificationSettings: () -> Unit, onClearRequested: () -> Unit, modifier: Modifier = Modifier, modelsContent: (@Composable () -> Unit)? = null,
    appearance: AppearanceState = AppearanceState(), onThemeMode: (ThemeMode) -> Unit = {}) {
                Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AppearanceSettings(appearance, onThemeMode)
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text(stringResource(Res.string.presence), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.floating_mascot), Modifier.weight(1f))
                        Switch(checked = overlayEnabled, onCheckedChange = onOverlay)
                    }
                    if (!notificationsAvailable) {
                        Text(stringResource(Res.string.notifications_unavailable))
                        TextButton(onClick = onNotificationSettings) { Text(stringResource(Res.string.notification_settings)) }
                    }
                    HorizontalDivider()
                    Text(stringResource(Res.string.device), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                    Text(deviceSummary, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    if (modelsContent != null) modelsContent() else {
                    Text(stringResource(Res.string.models), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.models_missing), Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                    TextButton(onClick = onClearRequested, enabled = ready, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Text(stringResource(Res.string.clear_conversation), Modifier.padding(start = 8.dp))
                    }
                }
}

@Composable private fun AppearanceSettings(state: AppearanceState, onSelect: (ThemeMode) -> Unit) {
    Text(stringResource(Res.string.appearance), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-mode-${mode.storedValue}")
                .selectable(selected = state.mode == mode, enabled = !state.saving, role = Role.RadioButton,
                    onClick = { onSelect(mode) }).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.mode == mode, enabled = !state.saving, onClick = null)
                Text(stringResource(when (mode) {
                    ThemeMode.SYSTEM -> Res.string.theme_system
                    ThemeMode.LIGHT -> Res.string.theme_light
                    ThemeMode.DARK -> Res.string.theme_dark
                }), Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.error?.let { error ->
        Text(stringResource(if (error == AppearanceError.LOAD_FAILED) Res.string.appearance_load_failed else Res.string.appearance_save_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        state.retryMode?.let { mode ->
            TextButton(onClick = { onSelect(mode) }, enabled = !state.saving) {
                Icon(Icons.Outlined.Refresh, null)
                Text(stringResource(Res.string.appearance_retry), Modifier.padding(start = 8.dp))
            }
        }
    }
}
