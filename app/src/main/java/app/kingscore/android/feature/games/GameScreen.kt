package app.kingscore.android.feature.games

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import app.kingscore.android.ui.GameCover
import app.kingscore.android.ui.OnlinePullRefresh
import app.kingscore.android.feature.navigation.CommentDataRefresh
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.util.UUID
import kingscore.contracts.v1.GameRow

@Composable
fun GameRoute(repository: PlaysRepository, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenPlay: (UUID) -> Unit, onOpenComments: ((UUID) -> Unit)? = null, onBack: () -> Unit) {
    val history: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val model: GameDetailViewModel = viewModel(factory = modelFactory {
        GameDetailViewModel(repository, id) { history.clearMemory(); onAccessFailure(it) }
    })
    val game by model.detail.collectAsStateWithLifecycle()
    val plays by history.history.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = model::show, onRefresh = { history.clearMemory(); model.refresh() })
    CommentDataRefresh(history)
    LaunchedEffect(game.game, game.revision) { if (game.game != null) history.show(HistoryFilters(gameId = id)) }
    GameScreen(game, plays, { history.clearMemory(); model.refresh() }, history::loadMore,
        history::retry, onOpenPlay, onBack, onOpenComments)
}

@Composable
fun GameScreen(state: GameState, history: HistoryState, onRefresh: () -> Unit, onMore: () -> Unit,
    onRetry: () -> Unit, onOpenPlay: (UUID) -> Unit, onBack: () -> Unit, onOpenComments: ((UUID) -> Unit)? = null) {
    Column(Modifier.fillMaxSize().testTag("game-detail")) {
        ScreenHeader("Dettaglio gioco", onBack = onBack, backLabel = "Torna indietro")
        OnlinePullRefresh(state.loading || (state.game != null && history.loading), onRefresh, Modifier.weight(1f)) {
        when {
            state.loading -> Loading("Caricamento gioco…")
            state.problem != null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { DataError(state.problem, onRefresh) }
            state.missing -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gioco non disponibile", style = MaterialTheme.typography.titleLarge)
                Text("Potrebbe essere stato eliminato o non essere più accessibile.")
            }
            state.game != null -> LazyColumn(Modifier.fillMaxSize().testTag("game-content"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        GameCover(state.game.image_url, state.game.name, 80.dp)
                        Text(state.game.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(playerCountLabel(state.game), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.game.bga_id?.takeIf(String::isNotBlank)?.let { Text("Riferimento BGA: $it") }
                }
                item { Text("Partite del gioco", style = MaterialTheme.typography.titleLarge) }
                detailHistory(history, "Nessuna partita registrata per questo gioco.", "more-game-plays",
                    onMore, onRetry, onOpenPlay, onOpenComments)
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
