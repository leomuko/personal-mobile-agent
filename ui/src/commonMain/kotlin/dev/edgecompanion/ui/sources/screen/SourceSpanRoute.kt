package dev.edgecompanion.ui.sources.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.edgecompanion.core.sources.model.SourceSpan
import dev.edgecompanion.ui.resources.*
import dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel
import dev.edgecompanion.ui.sources.viewmodel.SpanRead
import org.jetbrains.compose.resources.stringResource

@Composable fun SourceSpanRoute(model: SourcesViewModel, span: SourceSpan, modifier: Modifier = Modifier) {
    val read by remember(model, span) { model.observeSpan(span) }.collectAsStateWithLifecycle(initialValue = SpanRead.Loading)
    Column(modifier.fillMaxWidth().testTag("source-span-detail")) {
        when (val result = read) {
            SpanRead.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            SpanRead.Deleted -> Text(stringResource(Res.string.source_deleted), Modifier.padding(16.dp))
            SpanRead.Unavailable -> Text(stringResource(Res.string.source_passage_unavailable), Modifier.padding(16.dp))
            is SpanRead.Found -> {
                val detail = result.detail
                Text(detail.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.source_revision, span.revisionId), Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium)
                val list = rememberLazyListState(initialFirstVisibleItemIndex = detail.before.size)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    state = list, contentPadding = PaddingValues(16.dp)) {
                    items(detail.before) { text ->
                        SelectionContainer { Text(text, Modifier.padding(vertical = 8.dp)) }
                    }
                    item(key = "highlight") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("source-highlight"),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            SelectionContainer { Text(detail.highlight, Modifier.padding(12.dp)) }
                        }
                    }
                    items(detail.after) { text ->
                        SelectionContainer { Text(text, Modifier.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
    }
}
