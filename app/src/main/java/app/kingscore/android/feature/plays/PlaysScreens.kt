package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import app.kingscore.android.ui.modelFactory
import app.kingscore.android.ui.GameCover
import app.kingscore.android.ui.PlayerAvatar
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.*

@Composable
fun HistoryRoute(repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit, onOpen: (UUID) -> Unit) {
    val model: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val state by model.history.collectAsStateWithLifecycle()
    var applied by rememberSaveable(stateSaver = historyFiltersSaver) { mutableStateOf(HistoryFilters()) }
    var query by rememberSaveable { mutableStateOf(applied.query) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(model) { model.show(applied) }
    val apply: (HistoryFilters) -> Unit = { applied = it.copy(query = it.query.trim()); model.refresh(applied) }
    val validQuery = query.length <= MAX_HISTORY_QUERY && query.none { it.isISOControl() }
    val search = { if (validQuery) { focus.clearFocus(); keyboard?.hide(); query = query.trim(); apply(applied.copy(query = query)) }; Unit }
    HistoryScreen(state, query, { query = it }, validQuery, search,
        { apply(applied.copy(source = it)) }, { focus.clearFocus(); keyboard?.hide(); filtersOpen = true },
        { query = ""; apply(HistoryFilters()) }, { model.refresh() }, model::loadMore, model::retry, onOpen)
    if (filtersOpen) HistoryFiltersDialog(state, model::loadOptions,
        { filtersOpen = false; focus.clearFocus(); keyboard?.hide(); apply(it) }, { filtersOpen = false })
}

@Composable
fun DetailRoute(repository: PlaysRepository, accountId: UUID, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenGame: (UUID) -> Unit, onOpenPlayer: (UUID) -> Unit, onBack: () -> Unit) {
    val model: PlayDetailViewModel = viewModel(factory = modelFactory { PlayDetailViewModel(repository, id, onAccessFailure) })
    val state by model.detail.collectAsStateWithLifecycle()
    LaunchedEffect(model) { if (model.detail.value.loading) model.refresh() }
    DetailScreen(state, accountId, model::refresh, model::moreComments, model::retryComments, onBack, onOpenGame, onOpenPlayer)
}

@Composable
fun HistoryScreen(state: HistoryState, query: String, onQuery: (String) -> Unit, validQuery: Boolean, onSearch: () -> Unit,
    onSource: (PlaySource?) -> Unit, onFilters: () -> Unit, onReset: () -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (UUID) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("plays-history")) {
        ScreenHeader("Partite", onRefresh = onRefresh, refreshing = state.loading)
        key(state.filters) {
            LazyColumn(Modifier.weight(1f).testTag("history-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true, isError = !validQuery,
                        label = { Text("Cerca gioco o note") }, modifier = Modifier.fillMaxWidth().testTag("history-query"),
                        supportingText = { if (!validQuery) Text("Usa una riga di massimo $MAX_HISTORY_QUERY caratteri.") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSearch, enabled = validQuery) { Text("Cerca") }
                        OutlinedButton(onClick = onFilters) { Text("Filtri") }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(null, PlaySource.IRL, PlaySource.ONLINE)) { option ->
                            FilterChip(selected = state.filters.source == option, onClick = { onSource(option) },
                                label = { Text(when (option) { null -> "Tutte"; PlaySource.IRL -> "Dal vivo"; PlaySource.ONLINE -> "Online" }) })
                        }
                    }
                    if (state.filters.active) {
                        Text(historyFilterSummary(state), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("active-history-filters"))
                        TextButton(onClick = onReset) { Text("Rimuovi filtri") }
                    }
                }
                if (state.loading) item { Loading("Caricamento partite…") }
                else {
                    state.updatedAt?.let { instant -> item {
                        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)
                        Text("${state.items.size} partite caricate · Aggiornato alle $time", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    if (state.items.isEmpty() && state.problem == null) item {
                        Text("Nessuna partita", style = MaterialTheme.typography.titleLarge)
                        Text(if (state.filters.active) "Nessuna partita corrisponde alla ricerca e ai filtri." else "Non ci sono partite registrate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(state.items, key = { it.play.id.toString() }) { detail ->
                        PlayHistoryCard(detail, onOpen)
                    }
                    state.problem?.let { item { DataError(it, onRetry) } }
                    if (state.loadingMore) item { Loading("Caricamento altre partite…") }
                    else if (state.next != null && state.problem == null) item {
                        OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth().testTag("more-plays")) { Text("Carica altre partite") }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(state: DetailState, accountId: UUID, onRefresh: () -> Unit, onMoreComments: () -> Unit,
    onRetryComments: () -> Unit, onBack: () -> Unit, onOpenGame: ((UUID) -> Unit)? = null,
    onOpenPlayer: ((UUID) -> Unit)? = null) {
    Column(Modifier.fillMaxSize().testTag("play-detail")) {
        ScreenHeader("Dettaglio partita", onBack, onRefresh, state.loading)
        when {
            state.loading -> Loading("Caricamento partita…")
            state.problem != null -> Box(Modifier.padding(24.dp)) { DataError(state.problem, onRefresh) }
            state.missing -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Partita non trovata", style = MaterialTheme.typography.titleLarge)
                Text("Potrebbe essere stata eliminata o non essere più accessibile.")
            }
            state.detail != null -> {
                val detail = state.detail
                LazyColumn(Modifier.weight(1f).testTag("detail-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile")
                            Text(detail.game?.name ?: "Gioco non disponibile", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                        }
                        Text(playDateFormat.format(detail.play.played_at), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        detail.play.game_id?.let { gameId ->
                            if (onOpenGame != null) OutlinedButton(onClick = { onOpenGame(gameId) },
                                modifier = Modifier.testTag("open-game")) { Text("Apri gioco") }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(resultLabel(detail), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(sourceLabel(detail.play.source))
                                Text(detail.play.duration_minutes?.let { "Durata: $it min" } ?: "Durata non indicata")
                                detail.play.location?.takeIf { it.isNotBlank() }?.let { Text("Luogo: $it") }
                            }
                        }
                    }
                    item { Text("Partecipanti", style = MaterialTheme.typography.titleLarge) }
                    if (detail.scores.isEmpty()) item { Text("Nessun partecipante registrato.") }
                    items(detail.scores.sortedWith(compareBy<ScoreWithPlayer> { it.score.position ?: Int.MAX_VALUE }
                        .thenBy { playerName(it.player) }.thenBy { it.score.id.toString() }), key = { it.score.id.toString() }) { row ->
                        Column(Modifier.fillMaxWidth().testTag("score-${row.score.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerAvatar(row.player?.avatar_url, playerName(row.player), 32.dp)
                                Text(playerName(row.player), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            }
                            if (row.player?.account_user_id == accountId) Text("Sei tu", color = MaterialTheme.colorScheme.primary)
                            Text("Posizione: ${row.score.position ?: "—"}")
                            Text(row.score.score?.let { "Punteggio: ${it.toPlainString().replace('.', ',')}" } ?: "Punteggio non registrato")
                            if (row.score.winner == true) Text("Vincitore", color = MaterialTheme.colorScheme.primary)
                            row.score.role?.takeIf { it.isNotBlank() }?.let { Text("Ruolo: $it") }
                            row.score.player_id?.let { playerId ->
                                if (onOpenPlayer != null) TextButton(onClick = { onOpenPlayer(playerId) },
                                    modifier = Modifier.testTag("open-player-${row.score.id}").semantics {
                                        contentDescription = "Apri giocatore: ${playerName(row.player)}"
                                    }) { Text("Apri giocatore") }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp))
                        }
                    }
                    item {
                        Text("Note", style = MaterialTheme.typography.titleLarge)
                        Text(detail.play.notes?.takeIf { it.isNotBlank() } ?: "Nessuna nota.", modifier = Modifier.padding(top = 8.dp))
                    }
                    item { Text("Commenti", style = MaterialTheme.typography.titleLarge) }
                    items(state.comments, key = { it.id.toString() }) { comment ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(comment.author_name, style = MaterialTheme.typography.titleMedium)
                                Text(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(comment.created_at),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(comment.content)
                            }
                        }
                    }
                    if (state.commentsLoading) item { Loading("Caricamento commenti…") }
                    else if (state.commentsProblem != null) item { DataError(state.commentsProblem, onRetryComments) }
                    else if (state.comments.isEmpty()) item { Text("Nessun commento.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!state.commentsLoading && state.commentsProblem == null && state.commentCursor != null) item {
                        OutlinedButton(onClick = onMoreComments, modifier = Modifier.fillMaxWidth()) { Text("Carica altri commenti") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, onRefresh: () -> Unit, refreshing: Boolean,
    backLabel: String = "Torna allo storico") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel) } }
        Text(title, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.headlineSmall)
        IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Aggiorna") }
    }
}

@Composable
internal fun Loading(label: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text(label) }
}

@Composable
internal fun DataError(problem: DataProblem, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (problem) {
            DataProblem.FORBIDDEN -> "Non hai il permesso di leggere questi dati."
            DataProblem.INVALID_RESPONSE -> "Non è stato possibile leggere i risultati. Riprova."
        }, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Riprova") }
    }
}

@Composable
internal fun PlayHistoryCard(detail: PlayDetail, onOpen: (UUID) -> Unit) {
    Card(onClick = { onOpen(detail.play.id) }, modifier = Modifier.fillMaxWidth().testTag("play-${detail.play.id}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile", 56.dp)
                Text(detail.game?.name ?: "Gioco non disponibile", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            Text("${playDateFormat.format(detail.play.played_at)} · ${sourceLabel(detail.play.source)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(resultLabel(detail), color = MaterialTheme.colorScheme.primary)
            Text("${detail.scores.size} partecipanti", style = MaterialTheme.typography.bodySmall)
            detail.play.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
