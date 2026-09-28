package dev.edgecompanion.ui.navigation

import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.StringResource

enum class AssistantDestination(val route: String, val label: StringResource) {
    CHAT("chat", Res.string.chat), MEMORY("memory", Res.string.memory),
    SOURCES("sources", Res.string.sources), SETTINGS("settings", Res.string.settings),
}
