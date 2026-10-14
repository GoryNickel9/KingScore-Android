package app.kingscore.android.feature.players

import androidx.compose.foundation.layout.*
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
import app.kingscore.android.ui.PlayerAvatar
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun PlayerRoute(repository: PlaysRepository, accountId: UUID, id: UUID, onAccessFailure: (AuthProblem) -> Unit,
    onOpenPlay: (UUID) -> Unit, onBack: () -> Unit) {
    val history: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val model: PlayerDetailViewModel = viewModel(factory = modelFactory {
        PlayerDetailViewModel(repository, id) { history.clearMemory(); onAccessFailure(it) }
    })
    val player by model.detail.collectAsStateWithLifecycle()
    val plays by history.history.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = model::show, onRefresh = { history.clearMemory(); model.refresh() })
    LaunchedEffect(player.player, player.revision) { if (player.player != null) history.show(HistoryFilters(playerId = id)) }
    PlayerScreen(player, plays, accountId, { history.clearMemory(); model.refresh() }, history::loadMore,
        history::retry, onOpenPlay, onBack)
}

@Composable
fun PlayerScreen(state: PlayerState, history: HistoryState, accountId: UUID, onRefresh: () -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit, onOpenPlay: (UUID) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("player-detail")) {
        ScreenHeader("Dettaglio giocatore", onBack, onRefresh, state.loading, backLabel = "Torna indietro")
        when {
            state.loading -> Loading("Caricamento giocatore…")
            state.problem != null -> Box(Modifier.padding(24.dp)) { DataError(state.problem, onRefresh) }
            state.missing -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Giocatore non disponibile", style = MaterialTheme.typography.titleLarge)
                Text("Potrebbe essere stato eliminato o non essere più accessibile.")
            }
            state.player != null -> LazyColumn(Modifier.weight(1f).testTag("player-content"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    val player = state.player
                    val displayName = playerName(player)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        PlayerAvatar(player.avatar_url, displayName, 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(displayName, style = MaterialTheme.typography.headlineMedium)
                            if (displayName != player.name) Text(player.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (player.account_user_id == accountId) Text("Sei tu", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Profilo aggiornato: ${DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ITALIAN)
                        .withZone(ZoneId.systemDefault()).format(player.updated_at)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Text("Partite del giocatore", style = MaterialTheme.typography.titleLarge) }
                detailHistory(history, "Nessuna partita registrata per questo giocatore.", "more-player-plays",
                    onMore, onRetry, onOpenPlay)
            }
        }
    }
}
