package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FilterList
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import app.kingscore.android.ui.OnlinePullRefresh
import app.kingscore.android.ui.WebPanelShape
import app.kingscore.android.ui.WebBadge
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
import app.kingscore.android.feature.navigation.CommentDataRefresh
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.*

@Composable
fun HistoryRoute(repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit,
    onCreate: (() -> Unit)? = null, onOpenComments: ((UUID) -> Unit)? = null, onOpen: (UUID) -> Unit) {
    val model: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val state by model.history.collectAsStateWithLifecycle()
    var applied by rememberSaveable(stateSaver = historyFiltersSaver) { mutableStateOf(HistoryFilters()) }
    var query by rememberSaveable { mutableStateOf(applied.query) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    PlayDataRefresh(model, onShow = { model.show(applied) }, onRefresh = { model.refresh(applied) })
    CommentDataRefresh(model)
    val apply: (HistoryFilters) -> Unit = { applied = it.copy(query = it.query.trim()); model.refresh(applied) }
    val validQuery = query.length <= MAX_HISTORY_QUERY && query.none { it.isISOControl() }
    val search = { if (validQuery) { focus.clearFocus(); keyboard?.hide(); query = query.trim(); apply(applied.copy(query = query)) }; Unit }
    HistoryScreen(state, query, { query = it }, validQuery, search,
        { apply(applied.copy(source = it)) }, { focus.clearFocus(); keyboard?.hide(); filtersOpen = true },
        { query = ""; apply(HistoryFilters()) }, { model.refresh() }, model::loadMore, model::retry, onOpen, onCreate, onOpenComments)
    if (filtersOpen) HistoryFiltersDialog(state, model::loadOptions,
        { filtersOpen = false; focus.clearFocus(); keyboard?.hide(); apply(it) }, { filtersOpen = false })
}

@Composable
fun DetailRoute(repository: PlaysRepository, accountId: UUID, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenGame: (UUID) -> Unit, onOpenPlayer: (UUID) -> Unit, onManage: (() -> Unit)? = null,
    openComments: Boolean = false, onCommentsShown: () -> Unit = {}, onCommentsChanged: () -> Unit = {}, onBack: () -> Unit) {
    val model: PlayDetailViewModel = viewModel(factory = modelFactory { PlayDetailViewModel(repository, id, onAccessFailure, accountId, onCommentsChanged) })
    val state by model.detail.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = { if (model.detail.value.loading) model.refresh() }, onRefresh = model::refresh)
    DetailScreen(state, accountId, model::refresh, model::moreComments, model::retryComments, onBack, onOpenGame, onOpenPlayer,
        if (repository.commentWrites == null) null else CommentActions(model::composeComment, model::changeComment,
            model::saveComment, model::requestCommentDeletion, model::deleteComment, model::dismissComment, model::recheckComments, model::acknowledgeComments), onManage, openComments, onCommentsShown)
}

@Composable
fun HistoryScreen(state: HistoryState, query: String, onQuery: (String) -> Unit, validQuery: Boolean, onSearch: () -> Unit,
    onSource: (PlaySource?) -> Unit, onFilters: () -> Unit, onReset: () -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (UUID) -> Unit,
    onCreate: (() -> Unit)? = null, onOpenComments: ((UUID) -> Unit)? = null) {
    OnlinePullRefresh(state.loading, onRefresh, Modifier.fillMaxSize().testTag("plays-history")) {
        key(state.filters) {
            LazyColumn(Modifier.fillMaxSize().testTag("history-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Partite", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        onCreate?.let { create -> TextButton(onClick = create, modifier = Modifier.testTag("create-play")) { Text("Nuova partita") } }
                    }
                    Text("Lo storico del gruppo", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true, isError = !validQuery,
                        label = { Text("Cerca gioco o note") }, modifier = Modifier.fillMaxWidth().testTag("history-query"),
                        supportingText = { if (!validQuery) Text("Usa una riga di massimo $MAX_HISTORY_QUERY caratteri.") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSearch, enabled = validQuery) { Text("Cerca") }
                        OutlinedButton(onClick = onFilters) {
                            Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            val count = listOf(state.filters.gameId, state.filters.playerId, state.filters.from, state.filters.to).count { it != null }
                            Text(if (count == 0) "Filtri" else "Filtri ($count)")
                        }
                    }
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
                if (state.loading && state.items.isEmpty()) item { Loading("Caricamento partite…") }
                state.updatedAt?.let { instant -> item {
                    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)
                    Text("${state.items.size} partite caricate · Aggiornato alle $time", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (!state.loading && state.items.isEmpty() && state.problem == null) item {
                    Text("Nessuna partita", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.filters.active) "Nessuna partita corrisponde alla ricerca e ai filtri." else "Non ci sono partite registrate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.items, key = { it.play.id.toString() }) { detail ->
                    PlayHistoryCard(detail, onOpen, state.commentCounts[detail.play.id],
                        onComments = { (onOpenComments ?: onOpen)(detail.play.id) })
                }
                state.problem?.let { item { DataError(it, onRetry) } }
                if (state.commentCountsProblem != null) item {
                    Text("Conteggi commenti non disponibili. Puoi leggere i commenti aprendo una partita.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    onRetryComments: () -> Unit, onBack: () -> Unit, onOpenGame: ((UUID) -> Unit)? = null,
    onOpenPlayer: ((UUID) -> Unit)? = null, commentActions: CommentActions? = null, onManage: (() -> Unit)? = null, openComments: Boolean = false, onCommentsShown: () -> Unit = {}) {
    PlayDetailContent(state, accountId, onRefresh, onMoreComments, onRetryComments, onBack,
        onOpenGame, onOpenPlayer, commentActions, onManage, openComments, onCommentsShown)
}

@Composable
internal fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null, refreshing: Boolean = false,
    backLabel: String = "Torna allo storico") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel) } }
        Text(title, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.headlineSmall)
        onRefresh?.let { refresh -> IconButton(onClick = refresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Aggiorna") } }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayHistoryCard(detail: PlayDetail, onOpen: (UUID) -> Unit,
    commentCount: Int? = null, onComments: (() -> Unit)? = null) {
    Card(onClick = { onOpen(detail.play.id) }, shape = WebPanelShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("play-${detail.play.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile", 58.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(playDateFormat.format(detail.play.played_at), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail.play.duration_minutes?.let { Text("$it min", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (commentCount != null && commentCount > 0) {
                    TextButton(onClick = onComments ?: { onOpen(detail.play.id) }, contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("play-comments-${detail.play.id}")
                            .semantics { contentDescription = "$commentCount commenti, apri Commenti" }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp), tint = Color(0xFF7DD3FC))
                            Text(commentCount.toString(), color = Color(0xFF7DD3FC), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.scores.forEach { row ->
                    val winner = row.score.winner == true
                    Surface(color = if (winner) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("history-score-${row.score.id}")) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (winner) Icon(Icons.Outlined.EmojiEvents, "Vincitore", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("${playerName(row.player)}: ${row.score.score?.toPlainString()?.replace('.', ',') ?: "punti non registrati"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (winner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (detail.play.is_cooperative == true || detail.scores.none { it.score.winner == true }) {
                Text(resultLabel(detail), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            if (detail.scores.isEmpty()) Text("Nessun partecipante registrato", style = MaterialTheme.typography.bodySmall)
            detail.play.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WebBadge(sourceLabel(detail.play.source), color = if (detail.play.source == "online") Color(0xFF93C5FD) else MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail.play.is_cooperative == true) WebBadge("Cooperativa")
            }
        }
    }
}
