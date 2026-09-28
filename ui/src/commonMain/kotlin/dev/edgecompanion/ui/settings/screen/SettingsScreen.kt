package dev.edgecompanion.ui.settings.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun SettingsScreen(overlayEnabled: Boolean, notificationsAvailable: Boolean, deviceSummary: String, ready: Boolean, onOverlay: (Boolean) -> Unit, onNotificationSettings: () -> Unit, onClearRequested: () -> Unit, modifier: Modifier = Modifier) {
                Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
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
                    Text(stringResource(Res.string.models), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.models_missing), Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    TextButton(onClick = onClearRequested, enabled = ready, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Text(stringResource(Res.string.clear_conversation), Modifier.padding(start = 8.dp))
                    }
                }
}
