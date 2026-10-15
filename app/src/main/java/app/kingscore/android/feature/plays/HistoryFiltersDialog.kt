package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Alignment
import app.kingscore.android.ui.CatalogOption
import app.kingscore.android.ui.CatalogSelectionSheet
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kingscore.android.data.repository.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kingscore.contracts.v1.PlaySource

internal val historyFiltersSaver = listSaver<HistoryFilters, String>(
    save = { listOf(it.query, it.source?.name.orEmpty(), it.gameId?.toString().orEmpty(), it.playerId?.toString().orEmpty(),
        it.from?.toString().orEmpty(), it.to?.toString().orEmpty()) },
    restore = { values -> runCatching {
        require(values.size == 6)
        HistoryFilters(values[0], values[1].takeIf(String::isNotEmpty)?.let(PlaySource::valueOf),
            values[2].takeIf(String::isNotEmpty)?.let(UUID::fromString), values[3].takeIf(String::isNotEmpty)?.let(UUID::fromString),
            values[4].takeIf(String::isNotEmpty)?.let(LocalDate::parse), values[5].takeIf(String::isNotEmpty)?.let(LocalDate::parse))
    }.getOrElse { HistoryFilters() } },
)

private val filterDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
internal fun historyFilterDate(value: String): LocalDate? = value.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it, filterDateFormat) }

internal fun historyFilterSummary(state: HistoryState): String = buildList {
    val filters = state.filters
    if (filters.query.isNotBlank()) add("Ricerca: ${filters.query}")
    filters.gameId?.let { id -> add("Gioco: ${state.options?.games?.firstOrNull { it.id == id }?.name ?: "selezionato"}") }
    filters.playerId?.let { id -> add("Partecipante: ${state.options?.players?.firstOrNull { it.id == id }?.let(::playerName) ?: "selezionato"}") }
    filters.from?.let { add("Dal ${filterDateFormat.format(it)}") }
    filters.to?.let { add("Al ${filterDateFormat.format(it)}") }
    if (filters.source != null) add(sourceLabel(filters.source.wireValue))
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryFiltersDialog(state: HistoryState, onOptions: () -> Unit, onApply: (HistoryFilters) -> Unit, onDismiss: () -> Unit) {
    val initial = state.filters
    var game by rememberSaveable { mutableStateOf(initial.gameId?.toString()) }
    var player by rememberSaveable { mutableStateOf(initial.playerId?.toString()) }
    var from by rememberSaveable { mutableStateOf(initial.from?.let(filterDateFormat::format) ?: "") }
    var to by rememberSaveable { mutableStateOf(initial.to?.let(filterDateFormat::format) ?: "") }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf(initial.source?.name) }
    LaunchedEffect(choosing) { if (choosing != null) onOptions() }
    val fromDate = runCatching { historyFilterDate(from) }
    val toDate = runCatching { historyFilterDate(to) }
    val reversed = fromDate.getOrNull()?.let { start -> toDate.getOrNull()?.let { start > it } } == true
    val valid = fromDate.isSuccess && toDate.isSuccess && !reversed
    val window = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { window.height.toDp() * 0.86f }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val done = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() })
    if (choosing != null) {
        val games = choosing == "game"
        val options = if (games) state.options?.games.orEmpty().map { CatalogOption(it.id, it.name, imageUrl = it.image_url) }
            else state.options?.players.orEmpty().map {
                CatalogOption(it.id, playerName(it), imageUrl = it.avatar_url,
                    subtitle = it.name.takeIf { original -> original != playerName(it) },
                    searchText = "${it.name} ${it.alias.orEmpty()}", player = true)
            }
        CatalogSelectionSheet(title = if (games) "Scegli gioco" else "Scegli partecipante",
            options = listOf(CatalogOption(null, if (games) "Tutti i giochi" else "Tutti i partecipanti", player = !games)) + options,
            selectedId = (if (games) game else player)?.let(UUID::fromString),
            searchLabel = if (games) "Cerca gioco" else "Cerca nome o alias", tagPrefix = "history",
            modifier = Modifier.testTag("history-filters-dialog"), loading = state.optionsLoading,
            problem = state.optionsProblem?.let { if (it == DataProblem.FORBIDDEN) "Non hai il permesso di leggere i cataloghi."
                else "Non è stato possibile leggere i cataloghi." }, onRetry = onOptions,
            onDismiss = { choosing = null }, onChoose = { id ->
                if (games) game = id?.toString() else player = id?.toString()
                choosing = null
            })
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).imePadding().padding(horizontal = 16.dp)
            .testTag("history-filters-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtri", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Chiudi Filtri") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); choosing = "game" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gioco: ${state.options?.games?.firstOrNull { it.id.toString() == game }?.name ?: if (game == null) "Tutti" else "selezionato"}")
                }
                OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); choosing = "player" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Partecipante: ${state.options?.players?.firstOrNull { it.id.toString() == player }?.let(::playerName) ?: if (player == null) "Tutti" else "selezionato"}")
                }
                Text("Periodo", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(from, { from = it }, singleLine = true, isError = fromDate.isFailure,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = done,
                    label = { Text("Dal (gg/mm/aaaa)") }, modifier = Modifier.fillMaxWidth().testTag("history-from"),
                    supportingText = { if (fromDate.isFailure) Text("Inserisci una data valida, per esempio 01/10/2026.") })
                OutlinedTextField(to, { to = it }, singleLine = true, isError = toDate.isFailure || reversed,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = done,
                    label = { Text("Al (gg/mm/aaaa)") }, modifier = Modifier.fillMaxWidth().testTag("history-to"),
                    supportingText = {
                        if (toDate.isFailure) Text("Inserisci una data valida, per esempio 31/10/2026.")
                        else if (reversed) Text("La data finale deve essere uguale o successiva a quella iniziale.")
                    })
                Text("Estremi inclusi. Lascia una data vuota per non limitarla.", style = MaterialTheme.typography.bodySmall)
                Text("Fonte", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Tutte", PlaySource.IRL.name to "Dal vivo", PlaySource.ONLINE.name to "Online").forEach { (value, label) ->
                        FilterChip(selected = source == value, onClick = { source = value }, label = { Text(label) },
                            modifier = Modifier.testTag("filter-source-${value ?: "all"}"))
                    }
                }
                Text("Chiudere senza applicare mantiene i filtri precedenti.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { game = null; player = null; from = ""; to = ""; source = null }) { Text("Azzera questi filtri") }
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text("Annulla") }
                Button(onClick = { focus.clearFocus(); keyboard?.hide()
                    onApply(initial.copy(gameId = game?.let(UUID::fromString), playerId = player?.let(UUID::fromString),
                        from = fromDate.getOrNull(), to = toDate.getOrNull(), source = source?.let(PlaySource::valueOf)))
                }, enabled = valid) { Text("Applica filtri") }
            }
        }
    }
}
