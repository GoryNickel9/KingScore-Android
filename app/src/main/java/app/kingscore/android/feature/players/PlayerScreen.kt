package app.kingscore.android.feature.players

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.kingscore.android.ui.KingScorePanel
import app.kingscore.android.ui.OnlinePullRefresh
import app.kingscore.android.ui.WebBadge
import app.kingscore.android.feature.navigation.CommentDataRefresh
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
    onOpenPlay: (UUID) -> Unit, onOpenComments: ((UUID) -> Unit)? = null, onBack: () -> Unit) {
    val history: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val model: PlayerDetailViewModel = viewModel(factory = modelFactory {
        PlayerDetailViewModel(repository, id) { history.clearMemory(); onAccessFailure(it) }
    })
    val player by model.detail.collectAsStateWithLifecycle()
    val plays by history.history.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = model::show, onRefresh = { history.clearMemory(); model.refresh() })
    CommentDataRefresh(history)
    LaunchedEffect(player.player, player.revision) { if (player.player != null) history.show(HistoryFilters(playerId = id)) }
    PlayerScreen(player, plays, accountId, { history.clearMemory(); model.refresh() }, history::loadMore,
        history::retry, onOpenPlay, onBack, onOpenComments)
}

@Composable
fun PlayerScreen(state: PlayerState, history: HistoryState, accountId: UUID, onRefresh: () -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit, onOpenPlay: (UUID) -> Unit, onBack: () -> Unit, onOpenComments: ((UUID) -> Unit)? = null) {
    Column(Modifier.fillMaxSize().testTag("player-detail")) {
        ScreenHeader("Dettaglio giocatore", onBack = onBack, backLabel = "Torna indietro")
        OnlinePullRefresh(state.loading || (state.player != null && history.loading), onRefresh, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("player-content"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.loading -> item { Loading("Caricamento giocatore…") }
                    state.problem != null -> item { DataError(state.problem, onRefresh) }
                    state.missing -> item { KingScorePanel {
                        Text("Giocatore non disponibile", style = MaterialTheme.typography.titleLarge)
                        Text("Potrebbe essere stato eliminato o non essere più accessibile.")
                    } }
                    state.player != null -> {
                        item { KingScorePanel {
                            val player = state.player
                            val displayName = playerName(player)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                PlayerAvatar(player.avatar_url, displayName, 64.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    if (displayName != player.name) Text(player.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (player.account_user_id == accountId) WebBadge("Sei tu")
                                }
                            }
                            Text("Profilo aggiornato: ${DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ITALIAN)
                                .withZone(ZoneId.systemDefault()).format(player.updated_at)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        item { Text("Partite del giocatore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)) }
                        detailHistory(history, "Nessuna partita registrata per questo giocatore.", "more-player-plays",
                            onMore, onRetry, onOpenPlay, onOpenComments)
                    }
                }
            }
        }
    }
}
