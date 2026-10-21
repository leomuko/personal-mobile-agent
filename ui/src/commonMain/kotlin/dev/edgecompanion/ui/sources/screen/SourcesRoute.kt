package dev.edgecompanion.ui.sources.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.ui.designsystem.components.ToolButton
import dev.edgecompanion.ui.resources.*
import dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel
import org.jetbrains.compose.resources.stringResource

@Composable fun SourcesRoute(model: SourcesViewModel, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxWidth().testTag("sources-library")) {
        if (state.selected == null) {
            OutlinedTextField(state.query, model::query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(Res.string.source_search)) }, singleLine = true,
                trailingIcon = { ToolButton(Icons.Outlined.Search, stringResource(Res.string.source_search), { model.refresh() }) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ToolButton(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(Res.string.source_add_text), { model.edit() }, !state.writing)
                ToolButton(Icons.Outlined.UploadFile, stringResource(Res.string.source_import_utf8), onImport, !state.writing)
                Spacer(Modifier.weight(1f))
                ToolButton(Icons.Outlined.Refresh, stringResource(Res.string.source_refresh), { model.refresh() }, !state.loading)
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.sources), model::closeDetail)
                Text(state.selected!!.title, Modifier.weight(1f).padding(top = 12.dp), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                ToolButton(Icons.Outlined.Edit, stringResource(Res.string.source_rename), { model.edit(state.selected) }, !state.writing)
                ToolButton(Icons.Outlined.Delete, stringResource(Res.string.source_delete), { deleting = state.selected!!.id }, !state.writing)
            }
            Text(stringResource(Res.string.source_revision, state.selected!!.revisionId), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium)
        }
        if (state.loading || state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { SourceFailure(it, Modifier.padding(16.dp)) }
        state.importError?.let { SourceFailure(it, Modifier.padding(16.dp).testTag("source-import-error")) }
        if (state.selected == null) {
            LazyColumn(Modifier.weight(1f)) {
                if (!state.loading && state.page.items.isEmpty()) item {
                    Text(stringResource(if (state.query.isBlank()) Res.string.no_sources else Res.string.source_no_results), Modifier.padding(16.dp))
                }
                items(state.page.items, key = { it.id }) { source ->
                    ListItem(modifier = Modifier.clickable(enabled = !state.loading) { model.inspect(source) },
                        leadingContent = { Icon(Icons.Outlined.Description, null) },
                        headlineContent = { Text(source.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(Res.string.source_keyword_ready)) })
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolButton(Icons.Outlined.ChevronLeft, stringResource(Res.string.source_previous), model::previous, state.canGoBack && !state.loading)
                ToolButton(Icons.Outlined.ChevronRight, stringResource(Res.string.source_next), model::next, state.page.next != null && !state.loading)
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(state.text.items, key = { it.id }) { part ->
                    SelectionContainer { Text(part.text, Modifier.padding(vertical = 8.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolButton(Icons.Outlined.FirstPage, stringResource(Res.string.source_first_page), { model.firstText() }, !state.loading)
                ToolButton(Icons.Outlined.ChevronRight, stringResource(Res.string.source_next), model::nextText, state.text.next != null && !state.loading)
            }
        }
    }
    if (state.editorOpen) AlertDialog(onDismissRequest = model::dismissEditor,
        title = { Text(stringResource(if (state.renameId == null) Res.string.source_add_text else Res.string.source_rename)) },
        text = { Column {
            OutlinedTextField(state.editTitle, model::editTitle, label = { Text(stringResource(Res.string.source_title)) },
                singleLine = true, enabled = !state.writing, modifier = Modifier.testTag("source-title"))
            if (state.renameId == null) OutlinedTextField(state.editText, model::editText,
                label = { Text(stringResource(Res.string.source_text)) }, minLines = 3, maxLines = 8,
                enabled = !state.writing, modifier = Modifier.testTag("source-body"))
            state.error?.let { Text(stringResource(if (it == SourceError.LIMIT) Res.string.source_limit else Res.string.source_save_error),
                color = MaterialTheme.colorScheme.error) }
            state.importError?.let { SourceFailure(it) }
        } },
        confirmButton = { TextButton(model::save, enabled = !state.writing && state.editTitle.isNotBlank() &&
            (state.renameId != null || state.editText.isNotBlank())) { Text(stringResource(Res.string.source_save)) } },
        dismissButton = { TextButton(model::dismissEditor, enabled = !state.writing) { Text(stringResource(Res.string.cancel)) } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(Res.string.source_delete_title)) },
        text = { Text(stringResource(Res.string.source_delete_context)) },
        confirmButton = { TextButton({ deleting = null; model.delete(id) }) { Text(stringResource(Res.string.source_delete)) } },
        dismissButton = { TextButton({ deleting = null }) { Text(stringResource(Res.string.cancel)) } }) }
}

@Composable private fun SourceFailure(error: SourceError, modifier: Modifier = Modifier) {
    Text(stringResource(when (error) {
        SourceError.INVALID_TEXT -> Res.string.source_invalid
        SourceError.LIMIT -> Res.string.source_limit
        SourceError.QUOTA -> Res.string.source_quota
        SourceError.MISSING -> Res.string.source_missing
        SourceError.BUSY -> Res.string.source_import_busy
        SourceError.UNAVAILABLE, SourceError.STORAGE -> Res.string.source_storage
    }), modifier, color = MaterialTheme.colorScheme.error)
}
