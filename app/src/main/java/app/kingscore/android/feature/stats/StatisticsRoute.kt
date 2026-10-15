package app.kingscore.android.feature.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.navigation.LocalPlayDataRevision
import app.kingscore.android.feature.navigation.PlayDataRefresh
import app.kingscore.android.ui.*
import java.math.BigDecimal
import java.util.UUID

@Composable
fun StatisticsRoute(repository: StatisticsRepository, onAccessFailure: (AuthProblem) -> Unit) {
    val model: StatisticsDetailsViewModel = viewModel(factory = modelFactory { StatisticsDetailsViewModel(repository, onAccessFailure) })
    val state by model.statistics.collectAsStateWithLifecycle()
    val revision = LocalPlayDataRevision.current
    PlayDataRefresh(model, onShow = { model.show(revision) }, onRefresh = { model.show(revision) })
    StatisticsScreen(state, model::refresh, model::moreGames, model::toggleGame,
        { model.loadGame(it) }, { model.loadGame(it, true) })
}

@Composable
internal fun StatisticsScreen(state: StatisticsDetailsState, onRefresh: () -> Unit,
    onMoreGames: () -> Unit = {}, onToggle: (UUID) -> Unit = {}, onRetryGame: (UUID) -> Unit = {},
    onMorePlayers: (UUID) -> Unit = {},
) {
    OnlinePullRefresh(state.loading, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("statistics-content"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Statistiche", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Analisi dettagliata delle partite", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            state.problem?.let { problem -> item { StatisticsError(problem, onRefresh) } }
            if (state.loading && state.overview == null) item { KingScorePanel {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Caricamento statistiche…")
            } }
            item { SectionTitle("Statistiche per Gioco", Icons.Outlined.SportsEsports, MaterialTheme.colorScheme.primary) }
            state.overview?.let { overview ->
                if (overview.games.isEmpty()) item { Text("Nessuna partita registrata.") }
                items(overview.games, key = { it.id?.toString() ?: "unassigned" }) { game ->
                    StatisticsGameCard(game, game.id in state.expanded,
                        game.id?.let { state.games[it] }, onToggle, onRetryGame, onMorePlayers)
                }
                if (overview.next != null) item {
                    Button(onClick = onMoreGames, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.loading) "Caricamento…" else "Altri giochi (${overview.games.size} di ${overview.totalGames})")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsGameCard(game: GameStatistics, expanded: Boolean, details: GameStatisticsState?,
    onToggle: (UUID) -> Unit, onRetry: (UUID) -> Unit, onMore: (UUID) -> Unit,
) {
    KingScorePanel(Modifier.testTag("statistics-game-${game.id}").border(1.dp, MaterialTheme.colorScheme.outline, WebPanelShape)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game.imageUrl, game.name, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(game.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${game.plays} partite totali", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints {
            @Composable fun Score() { Metric("Punteggio medio", game.scoreAverage.format(),
                "Punteggio disponibile per ${game.scoreSamples} di ${game.participations} partecipazioni", MaterialTheme.colorScheme.primary) }
            @Composable fun Duration() { Metric("Durata media", game.durationAverage?.let { "${it.format()} min" } ?: "Non disponibile",
                "Durata disponibile per ${game.durationSamples} di ${game.plays} partite", Color(0xFF60A5FA)) }
            if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Score() }; Box(Modifier.weight(1f)) { Duration() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Score(); Duration() }
        }
        if (game.unattributed > 0) Text("${game.unattributed} partecipazioni senza giocatore disponibile",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        game.id?.let { id ->
            OutlinedButton(onClick = { onToggle(id) }, modifier = Modifier.fillMaxWidth().testTag("expand-statistics-$id")
                .semantics { stateDescription = if (expanded) "Espanso" else "Compresso" }) {
                Text("Statistiche giocatori", Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (expanded) {
                HorizontalDivider()
                Text("Punteggio medio per giocatore", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (details?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                details?.problem?.let { StatisticsError(it) { onRetry(id) } }
                details?.data?.let { data ->
                    if (data.players.isEmpty()) Text("Nessuna partecipazione attribuita a un giocatore.")
                    data.players.forEach { player -> Surface(shape = WebPanelShape, color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().testTag("statistics-player-${player.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerAvatar(player.avatar, player.name, 40.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(player.name, fontWeight = FontWeight.Medium)
                                    Text("${player.participations} partite", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(player.average.format(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text("Media su ${player.samples} di ${player.participations} partecipazioni", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    if (data.next != null) TextButton(onClick = { onMore(id) }, enabled = !details.loading) {
                        Text("Altri giocatori (${data.players.size} di ${data.totalPlayers})")
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, coverage: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(coverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatisticsError(problem: DataProblem, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (problem) {
            DataProblem.FORBIDDEN -> "Non hai il permesso di leggere questi dati."
            DataProblem.REVISION_CHANGED -> "I dati sono cambiati durante il caricamento. Trascina dalla cima per ricaricare."
            else -> "Non è stato possibile completare la lettura. Nessun dato parziale viene presentato come totale."
        }, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text("Riprova") }
    }
}

internal fun BigDecimal?.format(): String = this?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "Non disponibile"
