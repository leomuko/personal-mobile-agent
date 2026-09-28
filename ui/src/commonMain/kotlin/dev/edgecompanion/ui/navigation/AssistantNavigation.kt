package dev.edgecompanion.ui.navigation

import dev.edgecompanion.ui.designsystem.theme.Forest
import dev.edgecompanion.ui.designsystem.theme.Mint
import dev.edgecompanion.ui.designsystem.components.Mascot
import dev.edgecompanion.ui.designsystem.components.ToolButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import dev.edgecompanion.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AssistantNavigation(
    floating: Boolean,
    conversationRequest: Int,
    content: @Composable (AssistantDestination, () -> Unit, LazyListState) -> Unit,
) {
    val chatList = rememberLazyListState()
    if (floating) {
        content(AssistantDestination.CHAT, {}, chatList)
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun select(destination: AssistantDestination) {
        nav.navigate(destination.route) {
            popUpTo(AssistantDestination.CHAT.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        scope.launch { drawer.close() }
    }
    LaunchedEffect(conversationRequest) {
        if (conversationRequest > 0) {
            nav.popBackStack(AssistantDestination.CHAT.route, false)
            drawer.close()
        }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(drawerState = drawer, modifier = Modifier.widthIn(max = 320.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
            drawerShape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp), windowInsets = WindowInsets(0)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Mascot(Modifier.size(40.dp))
                Text(stringResource(Res.string.assistant), Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                ToolButton(Icons.Outlined.Close, stringResource(Res.string.close_navigation), { scope.launch { drawer.close() } })
            }
            AssistantDestination.entries.forEach { destination ->
                if (destination == AssistantDestination.SETTINGS) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    shape = RoundedCornerShape(8.dp),
                    colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Mint,
                        selectedIconColor = Forest, selectedTextColor = Forest),
                    label = { Text(stringResource(destination.label)) },
                    selected = (entry?.destination?.route ?: AssistantDestination.CHAT.route) == destination.route,
                    onClick = { select(destination) },
                    icon = { Icon(when (destination) {
                        AssistantDestination.CHAT -> Icons.AutoMirrored.Outlined.Chat
                        AssistantDestination.MEMORY -> Icons.Outlined.Bookmarks
                        AssistantDestination.SOURCES -> Icons.Outlined.Link
                        AssistantDestination.SETTINGS -> Icons.Outlined.Settings
                    }, null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            }
        }
    }) {
        NavHost(nav, startDestination = AssistantDestination.CHAT.route, modifier = Modifier.fillMaxSize()) {
            AssistantDestination.entries.forEach { destination ->
                composable(destination.route) {
                    content(destination, { scope.launch { drawer.open() } }, chatList)
                }
            }
        }
    }
}
