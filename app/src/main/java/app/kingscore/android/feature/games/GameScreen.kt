package app.kingscore.android.feature.games

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.HistoryFilters
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.feature.plays.*
import app.kingscore.android.ui.modelFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.GameRow

@Composable
fun GameRoute(repository: PlaysRepository, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenPlay: (UUID) -> Unit, onBack: () -> Unit) {
    val history: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val model: GameDetailViewModel = viewModel(factory = modelFactory {
        GameDetailViewModel(repository, id) { history.clearMemory(); onAccessFailure(it) }
    })
    val game by model.detail.collectAsStateWithLifecycle()
    val plays by history.history.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.show() }
    LaunchedEffect(game.game, game.revision) { if (game.game != null) history.show(HistoryFilters(gameId = id)) }
    GameScreen(game, plays, { history.clearMemory(); model.refresh() }, history::loadMore,
        history::retry, onOpenPlay, onBack)
}

@Composable
fun GameScreen(state: GameState, history: HistoryState, onRefresh: () -> Unit, onMore: () -> Unit,
    onRetry: () -> Unit, onOpenPlay: (UUID) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("game-detail")) {
        ScreenHeader("Dettaglio gioco", onBack, onRefresh, state.loading, backLabel = "Torna indietro")
        when {
            state.loading -> Loading("Caricamento gioco…")
            state.problem != null -> Box(Modifier.padding(24.dp)) { DataError(state.problem, onRefresh) }
            state.missing -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gioco non disponibile", style = MaterialTheme.typography.titleLarge)
                Text("Potrebbe essere stato eliminato o non essere più accessibile.")
            }
            state.game != null -> LazyColumn(Modifier.weight(1f).testTag("game-content"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(state.game.name, style = MaterialTheme.typography.headlineMedium)
                    Text(playerCountLabel(state.game), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.game.bga_id?.takeIf(String::isNotBlank)?.let { Text("Riferimento BGA: $it") }
                }
                item { Text("Partite del gioco", style = MaterialTheme.typography.titleLarge) }
                if (history.loading) item { Loading("Caricamento partite…") }
                else {
                    history.updatedAt?.let { instant -> item {
                        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)
                        Text("${history.items.size} partite caricate · Aggiornato alle $time",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    if (history.items.isEmpty() && history.problem == null) item {
                        Text("Nessuna partita registrata per questo gioco.")
                    }
                    items(history.items, key = { it.play.id.toString() }) { PlayHistoryCard(it, onOpenPlay) }
                    history.problem?.let { item { DataError(it, onRetry) } }
                    if (history.loadingMore) item { Loading("Caricamento altre partite…") }
                    else if (history.next != null && history.problem == null) item {
                        OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth().testTag("more-game-plays")) {
                            Text("Carica altre partite")
                        }
                    }
                }
            }
        }
    }
}

internal fun playerCountLabel(game: GameRow): String = when {
    game.min_players != null && game.max_players == game.min_players -> "${game.min_players} giocatori"
    game.min_players != null && game.max_players != null -> "${game.min_players}–${game.max_players} giocatori"
    game.min_players != null -> "Da ${game.min_players} giocatori"
    game.max_players != null -> "Fino a ${game.max_players} giocatori"
    else -> "Numero di giocatori non indicato"
}
