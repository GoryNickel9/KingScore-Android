package app.kingscore.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.UUID

internal data class CatalogOption(
    val id: UUID?, val name: String, val imageUrl: String? = null,
    val subtitle: String? = null, val searchText: String = name, val player: Boolean = false,
)

/** Search and closing controls stay reachable while only the result list scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogSelectionSheet(
    title: String, options: List<CatalogOption>, onDismiss: () -> Unit, onChoose: (UUID?) -> Unit,
    modifier: Modifier = Modifier, selectedId: UUID? = null, searchLabel: String = "Cerca nel catalogo",
    tagPrefix: String = "catalog", helperText: String? = null, loading: Boolean = false,
    problem: String? = null, onRetry: (() -> Unit)? = null,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.86f }
    val matching = options.filter { it.searchText.contains(query.trim(), ignoreCase = true) }
    val duplicates = options.groupingBy { it.name }.eachCount()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier.fillMaxWidth().heightIn(max = height).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Chiudi $title") }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(searchLabel) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-catalog-search"))
            helperText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when {
                loading -> Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Caricamento cataloghi…") }
                problem != null -> {
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    onRetry?.let { TextButton(onClick = it) { Text("Riprova cataloghi") } }
                }
                else -> LazyColumn(Modifier.weight(1f, fill = false).testTag("$tagPrefix-catalog"),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (matching.isEmpty()) item { Text("Nessun risultato. Prova un altro nome o alias.", Modifier.padding(vertical = 16.dp)) }
                    items(matching, key = { it.id?.toString() ?: "all" }) { item ->
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .testTag("$tagPrefix-option-${item.id ?: "all"}")
                                .selectable(item.id == selectedId, role = Role.RadioButton, onClick = { onChoose(item.id) })
                                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (item.player) PlayerAvatar(item.imageUrl, item.name, 42.dp) else GameCover(item.imageUrl, item.name, 42.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if ((duplicates[item.name] ?: 0) > 1) Text("Identificativo: ${item.id}", style = MaterialTheme.typography.bodySmall)
                                    if (item.id == selectedId) Text("Selezionato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (item.id == selectedId) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
