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
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.*

@Composable
fun HistoryRoute(repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit,
    onCreate: (() -> Unit)? = null, onOpen: (UUID) -> Unit) {
    val model: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val state by model.history.collectAsStateWithLifecycle()
    var applied by rememberSaveable(stateSaver = historyFiltersSaver) { mutableStateOf(HistoryFilters()) }
    var query by rememberSaveable { mutableStateOf(applied.query) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    PlayDataRefresh(model, onShow = { model.show(applied) }, onRefresh = { model.refresh(applied) })
    val apply: (HistoryFilters) -> Unit = { applied = it.copy(query = it.query.trim()); model.refresh(applied) }
    val validQuery = query.length <= MAX_HISTORY_QUERY && query.none { it.isISOControl() }
    val search = { if (validQuery) { focus.clearFocus(); keyboard?.hide(); query = query.trim(); apply(applied.copy(query = query)) }; Unit }
    HistoryScreen(state, query, { query = it }, validQuery, search,
        { apply(applied.copy(source = it)) }, { focus.clearFocus(); keyboard?.hide(); filtersOpen = true },
        { query = ""; apply(HistoryFilters()) }, { model.refresh() }, model::loadMore, model::retry, onOpen, onCreate)
    if (filtersOpen) HistoryFiltersDialog(state, model::loadOptions,
        { filtersOpen = false; focus.clearFocus(); keyboard?.hide(); apply(it) }, { filtersOpen = false })
}

@Composable
fun DetailRoute(repository: PlaysRepository, accountId: UUID, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenGame: (UUID) -> Unit, onOpenPlayer: (UUID) -> Unit, onManage: (() -> Unit)? = null, onBack: () -> Unit) {
    val model: PlayDetailViewModel = viewModel(factory = modelFactory { PlayDetailViewModel(repository, id, onAccessFailure, accountId) })
    val state by model.detail.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = { if (model.detail.value.loading) model.refresh() }, onRefresh = model::refresh)
    DetailScreen(state, accountId, model::refresh, model::moreComments, model::retryComments, onBack, onOpenGame, onOpenPlayer,
        if (repository.commentWrites == null) null else CommentActions(model::composeComment, model::changeComment,
            model::saveComment, model::requestCommentDeletion, model::deleteComment, model::dismissComment, model::recheckComments, model::acknowledgeComments), onManage)
}

@Composable
fun HistoryScreen(state: HistoryState, query: String, onQuery: (String) -> Unit, validQuery: Boolean, onSearch: () -> Unit,
    onSource: (PlaySource?) -> Unit, onFilters: () -> Unit, onReset: () -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (UUID) -> Unit, onCreate: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().testTag("plays-history")) {
        ScreenHeader("Partite", onRefresh = onRefresh, refreshing = state.loading)
        onCreate?.let { create ->
            Button(onClick = create, modifier = Modifier.padding(horizontal = 24.dp).testTag("create-play")) { Text("Nuova partita") }
        }
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
    onOpenPlayer: ((UUID) -> Unit)? = null, commentActions: CommentActions? = null, onManage: (() -> Unit)? = null) {
    PlayDetailContent(state, accountId, onRefresh, onMoreComments, onRetryComments, onBack,
        onOpenGame, onOpenPlayer, commentActions, onManage)
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
            else -> "Non è stato possibile leggere i risultati. Riprova."
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
