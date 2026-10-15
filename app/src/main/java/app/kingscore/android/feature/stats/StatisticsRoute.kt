package app.kingscore.android.feature.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.data.repository.StatisticsSnapshot
import app.kingscore.android.feature.dashboard.MonthlyChart
import app.kingscore.android.feature.plays.playerName
import app.kingscore.android.feature.navigation.PlayDataRefresh
import app.kingscore.android.feature.navigation.LocalPlayDataRevision
import app.kingscore.android.ui.GameCover
import app.kingscore.android.ui.KingScorePanel
import app.kingscore.android.ui.OnlinePullRefresh
import app.kingscore.android.ui.modelFactory
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun StatisticsRoute(repository: StatisticsRepository, onAccessFailure: (AuthProblem) -> Unit,
    sharedModel: StatisticsViewModel? = null) {
    val model = sharedModel ?: viewModel<StatisticsViewModel>(factory = modelFactory { StatisticsViewModel(repository, onAccessFailure) })
    val state by model.statistics.collectAsStateWithLifecycle()
    val revision = LocalPlayDataRevision.current
    PlayDataRefresh(model, onShow = { model.show(revision) }, onRefresh = { model.refreshForRevision(revision) })
    StatisticsScreen(state, model::refresh)
}

@Composable
internal fun StatisticsScreen(state: StatisticsState, onRefresh: () -> Unit) {
    OnlinePullRefresh(state.loading, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("statistics-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Statistiche", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Le partite del gruppo, in numeri", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            if (state.loading && state.snapshot == null) item {
                CircularProgressIndicator()
                Text("Lettura archivio: ${state.processed} partite…")
            }
            state.problem?.let { item {
                Text(if (state.snapshot == null) "Non è stato possibile completare la lettura. I totali non sono disponibili."
                    else "Non è stato possibile aggiornare. Sono mostrati i dati dell'ultima lettura completa.", color = MaterialTheme.colorScheme.error)
                Button(onClick = onRefresh) { Text("Riprova") }
            } }
            state.snapshot?.let { stats ->
                item { StatisticsMetrics(stats) }
                if (stats.months.isNotEmpty()) item { MonthlyChart(stats.months) }
                item { KingScorePanel {
                    Text("Tempo di gioco", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatisticValue("Minuti registrati", "${stats.recordedMinutes}")
                    val average = if (stats.durationSamples == 0) null else BigDecimal.valueOf(stats.recordedMinutes)
                        .divide(BigDecimal(stats.durationSamples), 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
                    StatisticValue("Media registrata", average?.let { "$it min" } ?: "Non disponibile")
                    Text("Durata disponibile per ${stats.durationSamples} di ${stats.totalPlays} partite", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Le durate mancanti sono escluse dalla media.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { KingScorePanel {
                    Text("Le tue partite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (stats.linkedPlayer == null) Text("Profilo giocatore non collegato.") else {
                        Text(playerName(stats.linkedPlayer), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatisticValue("Partecipazioni", stats.personalPlays.toString())
                        StatisticValue("Vittorie registrate", stats.personalWins.toString())
                    }
                } }
                item { Text("Partite per gioco", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)) }
                if (stats.games.isEmpty()) item { Text("Nessun gioco registrato.") }
                val maximum = stats.games.maxOfOrNull { it.plays }?.coerceAtLeast(1) ?: 1
                items(stats.games, key = { it.gameId.toString() }) { game ->
                    KingScorePanel(Modifier.testTag("statistics-game-${game.gameId}")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameCover(game.imageUrl, game.name, 44.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(game.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("${game.plays} partite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                LinearProgressIndicator(progress = { game.plays.toFloat() / maximum }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                item { Text("Totali calcolati dopo la lettura completa.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.updatedAt?.let { instant -> item {
                val time = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.ITALIAN)
                    .withZone(java.time.ZoneId.systemDefault()).format(instant)
                Text("Ultima lettura: $time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable
private fun StatisticsMetrics(stats: StatisticsSnapshot) {
    val values = listOf("Partite totali" to stats.totalPlays.toString(), "Questo mese" to stats.monthPlays.toString(),
        "Giochi" to stats.uniqueGames.toString(), "Giocatori" to stats.uniquePlayers.toString())
    BoxWithConstraints {
        val columns = if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            values.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) ->
                        KingScorePanel(Modifier.weight(1f).heightIn(min = 106.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticValue(label: String, value: String) {
    if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
