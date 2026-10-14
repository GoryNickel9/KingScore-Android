package app.kingscore.android.feature.stats

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
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.feature.plays.playerName
import app.kingscore.android.ui.modelFactory
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun StatisticsRoute(repository: StatisticsRepository, onAccessFailure: (AuthProblem) -> Unit) {
    val model: StatisticsViewModel = viewModel(factory = modelFactory { StatisticsViewModel(repository, onAccessFailure) })
    val state by model.statistics.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = model::show, onRefresh = model::refresh)
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Statistiche", style = MaterialTheme.typography.headlineLarge)
            Text("Partite condivise del gruppo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) item {
            CircularProgressIndicator()
            Text("Lettura archivio: ${state.processed} partite…")
        }
        state.problem?.let { item {
            Text("Non è stato possibile completare la lettura. I totali non sono disponibili.", color = MaterialTheme.colorScheme.error)
            Button(onClick = model::refresh) { Text("Riprova") }
        } }
        state.snapshot?.let { stats ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${stats.totalPlays} partite", style = MaterialTheme.typography.headlineMedium)
                        Text("Nel mese corrente: ${stats.monthPlays}")
                        Text("Giochi identificati: ${stats.uniqueGames}")
                        Text("Giocatori identificati: ${stats.uniquePlayers}")
                    }
                }
            }
            item {
                Text("Tempo di gioco", style = MaterialTheme.typography.titleLarge)
                Text("${stats.recordedMinutes} minuti registrati")
                Text("Durata disponibile per ${stats.durationSamples} di ${stats.totalPlays} partite", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val average = if (stats.durationSamples == 0) null else BigDecimal.valueOf(stats.recordedMinutes)
                    .divide(BigDecimal(stats.durationSamples), 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
                Text(average?.let { "Media delle durate registrate: $it min" } ?: "Durata media non disponibile")
            }
            item {
                Text("Le tue partite", style = MaterialTheme.typography.titleLarge)
                if (stats.linkedPlayer == null) Text("Profilo giocatore non collegato.") else {
                    Text(playerName(stats.linkedPlayer))
                    Text("Partecipazioni: ${stats.personalPlays}")
                    Text("Vittorie registrate: ${stats.personalWins}")
                }
            }
            item { Text("Partite per gioco", style = MaterialTheme.typography.titleLarge) }
            if (stats.games.isEmpty()) item { Text("Nessun gioco registrato.") }
            items(stats.games, key = { it.gameId.toString() }) { game ->
                ListItem(headlineContent = { Text(game.name) }, supportingContent = { Text("${game.plays} partite") })
            }
            item { Text("Totali calcolati dopo la lettura completa. Le modifiche più recenti compaiono al prossimo aggiornamento.", style = MaterialTheme.typography.bodySmall) }
        }
        state.updatedAt?.let { instant -> item {
            val time = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.ITALIAN)
                .withZone(java.time.ZoneId.systemDefault()).format(instant)
            Text("Ultima lettura: $time", style = MaterialTheme.typography.bodySmall)
        } }
        if (!state.loading) item { TextButton(onClick = model::refresh) { Text("Aggiorna statistiche") } }
    }
}
