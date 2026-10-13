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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.*

@Composable
fun HistoryRoute(repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit, onOpen: (UUID) -> Unit) {
    val model: HistoryViewModel = viewModel(factory = factory { HistoryViewModel(repository, onAccessFailure) })
    val state by model.history.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("ALL") }
    val source = PlaySource.entries.firstOrNull { it.name == filter }
    LaunchedEffect(model, filter) { model.show(source) }
    HistoryScreen(state, source, { filter = it?.name ?: "ALL" }, { model.refresh(source) }, model::loadMore, model::retry, onOpen)
}

@Composable
fun DetailRoute(repository: PlaysRepository, accountId: UUID, id: UUID, onAccessFailure: (AuthProblem) -> Unit, onBack: () -> Unit) {
    val model: PlayDetailViewModel = viewModel(factory = factory { PlayDetailViewModel(repository, id, onAccessFailure) })
    val state by model.detail.collectAsStateWithLifecycle()
    LaunchedEffect(model) { if (model.detail.value.loading) model.refresh() }
    DetailScreen(state, accountId, model::refresh, model::moreComments, model::retryComments, onBack)
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        @Suppress("UNCHECKED_CAST")
        return create() as V
    }
}

@Composable
fun HistoryScreen(state: HistoryState, source: PlaySource?, onSource: (PlaySource?) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (UUID) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("plays-history")) {
        ScreenHeader("Partite", onRefresh = onRefresh, refreshing = state.loading)
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(null, PlaySource.IRL, PlaySource.ONLINE)) { option ->
                FilterChip(selected = source == option, onClick = { onSource(option) },
                    label = { Text(when (option) { null -> "Tutte"; PlaySource.IRL -> "Dal vivo"; PlaySource.ONLINE -> "Online" }) })
            }
        }
        if (state.loading) {
            Loading("Caricamento partite…")
        } else {
            LazyColumn(Modifier.weight(1f).testTag("history-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.updatedAt?.let { instant -> item {
                    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)
                    Text("${state.items.size} partite caricate · Aggiornato alle $time", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (state.items.isEmpty() && state.problem == null) item {
                    Text("Nessuna partita", style = MaterialTheme.typography.titleLarge)
                    Text("Non ci sono partite per questa modalità.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.items, key = { it.play.id.toString() }) { detail ->
                    Card(onClick = { onOpen(detail.play.id) }, modifier = Modifier.fillMaxWidth().testTag("play-${detail.play.id}")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.titleLarge)
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
                state.problem?.let { item { DataError(it, onRetry) } }
                if (state.loadingMore) item { Loading("Caricamento altre partite…") }
                else if (state.next != null && state.problem == null) item {
                    OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth().testTag("more-plays")) { Text("Carica altre partite") }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(state: DetailState, accountId: UUID, onRefresh: () -> Unit, onMoreComments: () -> Unit,
    onRetryComments: () -> Unit, onBack: () -> Unit) {
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
                        Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.headlineMedium)
                        Text(playDateFormat.format(detail.play.played_at), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(playerName(row.player), style = MaterialTheme.typography.titleMedium)
                            if (row.player?.account_user_id == accountId) Text("Sei tu", color = MaterialTheme.colorScheme.primary)
                            Text("Posizione: ${row.score.position ?: "—"}")
                            Text(row.score.score?.let { "Punteggio: ${it.toPlainString().replace('.', ',')}" } ?: "Punteggio non registrato")
                            if (row.score.winner == true) Text("Vincitore", color = MaterialTheme.colorScheme.primary)
                            row.score.role?.takeIf { it.isNotBlank() }?.let { Text("Ruolo: $it") }
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
private fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, onRefresh: () -> Unit, refreshing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Torna allo storico") } }
        Text(title, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.headlineSmall)
        IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Aggiorna") }
    }
}

@Composable
private fun Loading(label: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text(label) }
}

@Composable
private fun DataError(problem: DataProblem, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (problem) {
            DataProblem.FORBIDDEN -> "Non hai il permesso di leggere questi dati."
            DataProblem.INVALID_RESPONSE -> "Non è stato possibile leggere i risultati. Riprova."
        }, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Riprova") }
    }
}
