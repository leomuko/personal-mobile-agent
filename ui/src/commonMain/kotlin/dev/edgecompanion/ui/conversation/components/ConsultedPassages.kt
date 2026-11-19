package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.edgecompanion.core.conversation.model.ConsultedPassage
import dev.edgecompanion.core.sources.model.SourceSpan
import dev.edgecompanion.ui.conversation.presentation.ConsultedItem
import dev.edgecompanion.ui.conversation.presentation.ConsultedRead
import dev.edgecompanion.ui.conversation.presentation.consultedItems
import dev.edgecompanion.ui.resources.*
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

@Composable internal fun ConsultedPassages(
    references: List<ConsultedPassage>,
    observe: (List<ConsultedPassage>) -> Flow<ConsultedRead>,
    open: (SourceSpan) -> Unit,
) {
    if (references.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(0.9f).testTag("passages-consulted")) {
        val expansion = stringResource(if (expanded) Res.string.passages_expanded else Res.string.passages_collapsed)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = expansion }) {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.passages_consulted))
        }
        if (expanded) {
            val read by remember(references, observe) { observe(references) }
                .collectAsStateWithLifecycle(initialValue = ConsultedRead.Loading)
            when (val current = read) {
                ConsultedRead.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ConsultedRead.Unavailable -> Text(stringResource(Res.string.source_passage_unavailable),
                    style = MaterialTheme.typography.bodySmall)
                is ConsultedRead.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    references.consultedItems(current).forEach { item ->
                        when (item) {
                            ConsultedItem.Deleted -> Text(stringResource(Res.string.source_deleted),
                                Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                            is ConsultedItem.Available -> OutlinedCard(
                                onClick = { open(item.span) }, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("consulted-source"),
                            ) {
                                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(item.preview.title, style = MaterialTheme.typography.labelLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(item.preview.text, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
