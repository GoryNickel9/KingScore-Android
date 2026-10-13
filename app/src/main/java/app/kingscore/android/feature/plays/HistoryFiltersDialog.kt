package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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

@Composable
fun HistoryFiltersDialog(state: HistoryState, onOptions: () -> Unit, onApply: (HistoryFilters) -> Unit, onDismiss: () -> Unit) {
    val initial = state.filters
    var game by rememberSaveable { mutableStateOf(initial.gameId?.toString()) }
    var player by rememberSaveable { mutableStateOf(initial.playerId?.toString()) }
    var from by rememberSaveable { mutableStateOf(initial.from?.let(filterDateFormat::format) ?: "") }
    var to by rememberSaveable { mutableStateOf(initial.to?.let(filterDateFormat::format) ?: "") }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(choosing) { if (choosing != null) onOptions() }
    val fromDate = runCatching { historyFilterDate(from) }
    val toDate = runCatching { historyFilterDate(to) }
    val reversed = fromDate.getOrNull()?.let { start -> toDate.getOrNull()?.let { start > it } } == true
    val valid = fromDate.isSuccess && toDate.isSuccess && !reversed
    val window = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { window.height.toDp() * 0.55f }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val done = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() })
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("history-filters-dialog"),
        title = { Text(when (choosing) { "game" -> "Scegli gioco"; "player" -> "Scegli partecipante"; else -> "Filtri dello storico" }) },
        text = {
            if (choosing == null) Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { search = ""; choosing = "game" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gioco: ${state.options?.games?.firstOrNull { it.id.toString() == game }?.name ?: if (game == null) "Tutti" else "selezionato"}")
                }
                OutlinedButton(onClick = { search = ""; choosing = "player" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Partecipante: ${state.options?.players?.firstOrNull { it.id.toString() == player }?.let(::playerName) ?: if (player == null) "Tutti" else "selezionato"}")
                }
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
                TextButton(onClick = { game = null; player = null; from = ""; to = "" }) { Text("Azzera questi filtri") }
            } else Column(Modifier.heightIn(max = height), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Cerca nel catalogo") }, modifier = Modifier.fillMaxWidth())
                when {
                    state.optionsLoading -> { CircularProgressIndicator(); Text("Caricamento cataloghi…") }
                    state.optionsProblem != null -> {
                        Text(if (state.optionsProblem == DataProblem.FORBIDDEN) "Non hai il permesso di leggere i cataloghi." else "Non è stato possibile leggere i cataloghi.")
                        TextButton(onClick = onOptions) { Text("Riprova cataloghi") }
                    }
                    state.options != null -> {
                        val options = if (choosing == "game") state.options.games.map { Triple(it.id.toString(), it.name, it.name) }
                            else state.options.players.map { Triple(it.id.toString(), playerName(it), "${it.name} ${it.alias.orEmpty()}") }
                        val matching = options.filter { it.third.contains(search.trim(), ignoreCase = true) }
                        val duplicates = options.groupingBy { it.second }.eachCount()
                        LazyColumn(Modifier.weight(1f, fill = false).testTag("history-catalog")) {
                            item {
                                TextButton(onClick = { if (choosing == "game") game = null else player = null; choosing = null }) {
                                    Text(if (choosing == "game") "Tutti i giochi" else "Tutti i partecipanti")
                                }
                            }
                            if (matching.isEmpty()) item { Text("Nessun risultato nel catalogo.") }
                            items(matching, key = { it.first }) { (id, name, _) ->
                                TextButton(onClick = { if (choosing == "game") game = id else player = id; choosing = null },
                                    modifier = Modifier.fillMaxWidth().testTag("history-option-$id")) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(name)
                                        if ((duplicates[name] ?: 0) > 1) Text("Identificativo: $id", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (choosing != null) TextButton(onClick = { choosing = null }) { Text("Indietro") }
            else Button(onClick = { onApply(initial.copy(gameId = game?.let(UUID::fromString), playerId = player?.let(UUID::fromString),
                from = fromDate.getOrNull(), to = toDate.getOrNull())) }, enabled = valid) { Text("Applica filtri") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
