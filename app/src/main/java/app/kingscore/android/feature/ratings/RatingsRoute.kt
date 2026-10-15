package app.kingscore.android.feature.ratings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.plays.playerName
import app.kingscore.android.ui.modelFactory
import app.kingscore.android.ui.PlayerAvatar
import app.kingscore.android.ui.GameCover
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
fun RatingsRoute(account: Account, repository: RatingsRepository, onAccessFailure: (AuthProblem) -> Unit) {
    val model: RatingsViewModel = viewModel(factory = modelFactory { RatingsViewModel(repository, onAccessFailure) })
    val state by model.ratings.collectAsStateWithLifecycle()
    var game by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseGame by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    PlayDataRefresh(model, selection = game, onShow = { model.show(game?.let(UUID::fromString)) },
        onRefresh = { model.refresh(game?.let(UUID::fromString)) })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, state.problem, state.gameId) {
        if (state.problem == DataProblem.PUBLICATION_STALE) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(5_000)
            model.refresh()
        }
    }
    if (chooseGame) AlertDialog(onDismissRequest = { chooseGame = false }, title = { Text("Scegli gioco") },
        confirmButton = { TextButton(onClick = { chooseGame = false }) { Text("Chiudi") } }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Cerca gioco") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.games.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id.toString() }) { item ->
                        TextButton(onClick = { game = item.id.toString(); chooseGame = false }, modifier = Modifier.fillMaxWidth()) { Text(item.name) }
                    }
                }
            }
        })
    LazyColumn(Modifier.fillMaxSize().testTag("ratings-content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("ELO", style = MaterialTheme.typography.headlineLarge)
            Text("Classifiche globali pubblicate dal server", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = game == null, onClick = { game = null }, label = { Text("Generale") })
                    OutlinedButton(onClick = { query = ""; chooseGame = true }, enabled = state.games.isNotEmpty()) { Text("Per gioco") }
                }
                state.games.firstOrNull { it.id.toString() == game }?.let { selected ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        GameCover(selected.image_url, selected.name, 56.dp)
                        Text(selected.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        if (state.loading) item { CircularProgressIndicator(); Text("Caricamento classifica…") }
        state.problem?.let { item {
            Text(when (state.problem) {
                DataProblem.PUBLICATION_STALE -> "La classifica è in attesa di aggiornamento. Riprova dopo la pubblicazione dei nuovi risultati."
                DataProblem.PUBLICATION_MISSING -> "La classifica non è ancora stata pubblicata."
                else -> "Non è stato possibile leggere la classifica."
            }, color = MaterialTheme.colorScheme.error)
            Button(onClick = { model.refresh() }) { Text("Riprova") }
        } }
        state.ranking?.let { ranking ->
            if (ranking.profile == null) item { Text("Profilo giocatore non collegato.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ranking.entries.isEmpty()) item { Text("Nessun rating pubblicato per questa classifica.") }
            val positions = competitionRanks(ranking.entries)
            items(ranking.entries, key = { it.id.toString() }) { rating ->
                Card(onClick = { model.select(rating) }, enabled = rating.playerId != null, modifier = Modifier.fillMaxWidth().testTag("rating-${rating.id}")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            PlayerAvatar(rating.player?.avatar_url, playerName(rating.player))
                            Text("${positions[rating.id]?.let { "$it. " } ?: ""}${playerName(rating.player)}",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        }
                        if (rating.player?.account_user_id == account.id) Text("Sei tu", color = MaterialTheme.colorScheme.primary)
                        Text(rating.rating?.toString() ?: "Rating non disponibile", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Partite: ${rating.gamesPlayed ?: "—"} · V/P/S: ${rating.wins ?: "—"}/${rating.draws ?: "—"}/${rating.losses ?: "—"}")
                        Text(rating.lastPlayedAt?.let { "Ultima partita: $it" } ?:
                            rating.updatedAt?.let { "Rating aggiornato: ${formatTime(it)}" } ?: "Data del rating non disponibile",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (rating.playerId != null) Text("Apri storico", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        state.readAt?.let { item { Text("Classifica letta: ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) } }
        if (!state.loading) item { TextButton(onClick = { model.refresh() }) { Text("Aggiorna classifica") } }
    }
    state.selected?.let { selected ->
        AlertDialog(onDismissRequest = model::closeHistory, title = { Text("Storico di ${playerName(selected.player)}") },
            confirmButton = { TextButton(onClick = model::closeHistory) { Text("Chiudi") } }, text = {
                LazyColumn(Modifier.heightIn(max = 420.dp).testTag("rating-history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.history, key = { it.id.toString() }) { item ->
                        Column {
                            Text(if (selected.publicationId != null) DateTimeFormatter.ofPattern("d MMM yyyy")
                                .withZone(java.time.ZoneOffset.UTC).format(item.date) else formatTime(item.date))
                            Text("${item.rating} · ${if (item.rating_change > 0) "+" else ""}${item.rating_change}")
                        }
                    }
                    if (state.historyLoading) item { CircularProgressIndicator() }
                    else if (state.historyProblem != null) item {
                        Text("Storico non disponibile. Riprova.", color = MaterialTheme.colorScheme.error)
                        Button(onClick = model::retryHistory) { Text("Riprova") }
                    } else if (state.history.isEmpty()) item { Text("Nessun dato storico pubblicato.") }
                    if (!state.historyLoading && state.historyProblem == null && state.nextHistory != null) item {
                        OutlinedButton(onClick = model::moreHistory) { Text("Carica altri risultati") }
                    }
                }
            })
    }
}

fun competitionRanks(entries: List<PublishedRating>): Map<UUID, Int?> {
    var previous: Int? = null
    var rank = 0
    return entries.mapIndexed { index, entry ->
        if (entry.rating == null) entry.id to null else {
            if (previous != entry.rating) rank = index + 1
            previous = entry.rating
            entry.id to rank
        }
    }.toMap()
}
private fun formatTime(time: java.time.Instant): String = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(time)
