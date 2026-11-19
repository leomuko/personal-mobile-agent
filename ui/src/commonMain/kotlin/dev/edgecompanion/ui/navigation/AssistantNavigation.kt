package dev.edgecompanion.ui.navigation

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.edgecompanion.core.sources.model.SourceSpan
import dev.edgecompanion.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val SOURCE_SPAN_ROUTE = "sources/passage/{span}"

private fun SourceSpan.navigationArgument() =
    listOf(sourceId, revisionId, passageId, startUtf16, endUtf16).joinToString("_")

private fun String.sourceSpan(): SourceSpan? {
    val parts = split('_')
    if (parts.size != 5) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    return SourceSpan(values[0], values[1], values[2], values[3], values[4])
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AssistantNavigation(
    floating: Boolean,
    conversationRequest: Int,
    content: @Composable (
        destination: AssistantDestination, openMenu: () -> Unit, chatList: LazyListState,
        openPassage: (SourceSpan) -> Unit, span: SourceSpan?, backToChat: () -> Unit,
    ) -> Unit,
) {
    val chatList = rememberLazyListState()
    if (floating) {
        var selection by rememberSaveable(conversationRequest) { mutableStateOf<String?>(null) }
        val span = selection?.sourceSpan()
        val close: () -> Unit = { selection = null }
        BackHandler(enabled = span != null, onBack = close)
        content(if (span == null) AssistantDestination.CHAT else AssistantDestination.SOURCES, {}, chatList,
            { selection = it.navigationArgument() }, span, close)
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
                    colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer),
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
                    content(destination, { scope.launch { drawer.open() } }, chatList, { span ->
                        nav.navigate("sources/passage/${span.navigationArgument()}") { launchSingleTop = true }
                    }, null, {})
                }
            }
            composable(SOURCE_SPAN_ROUTE, arguments = listOf(navArgument("span") { type = NavType.StringType })) { backStack ->
                val span = backStack.arguments?.read { getString("span") }?.sourceSpan()
                content(AssistantDestination.SOURCES, {}, chatList, {}, span, { nav.popBackStack() })
            }
        }
    }
}
