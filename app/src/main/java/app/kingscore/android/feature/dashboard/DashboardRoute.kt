package app.kingscore.android.feature.dashboard

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
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.feature.plays.*
import app.kingscore.android.ui.modelFactory
import app.kingscore.android.ui.GameCover
import java.util.UUID

@Composable
fun DashboardRoute(account: Account, repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit,
    onOpen: (UUID) -> Unit, onHistory: () -> Unit, onRatings: () -> Unit) {
    val model: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure) })
    val state by model.history.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.show() }
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Ciao, ${account.name}", style = MaterialTheme.typography.headlineLarge)
            Text("Le partite del tuo gruppo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("Storico") }
                OutlinedButton(onClick = onRatings, modifier = Modifier.weight(1f)) { Text("Classifiche ELO") }
            }
        }
        item { Text("Ultime partite", style = MaterialTheme.typography.titleLarge) }
        if (state.loading) item { CircularProgressIndicator(); Text("Caricamento…") }
        state.problem?.let { item {
            Text("Non è stato possibile leggere le partite.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { model.refresh() }) { Text("Riprova") }
        } }
        if (!state.loading && state.problem == null && state.items.isEmpty()) item { Text("Nessuna partita registrata.") }
        items(state.items.take(5), key = { it.play.id.toString() }) { detail ->
            Card(onClick = { onOpen(detail.play.id) }, modifier = Modifier.fillMaxWidth().testTag("recent-${detail.play.id}")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile", 56.dp)
                        Text(detail.game?.name ?: "Gioco non disponibile", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    }
                    Text(playDateFormat.format(detail.play.played_at), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(resultLabel(detail), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (!state.loading) item { TextButton(onClick = { model.refresh() }) { Text("Aggiorna Home") } }
    }
}
