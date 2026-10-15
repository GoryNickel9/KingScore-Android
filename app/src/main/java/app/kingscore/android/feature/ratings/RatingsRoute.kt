package app.kingscore.android.feature.ratings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
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
import app.kingscore.android.ui.KingScoreHeader
import app.kingscore.android.ui.WebPanelShape
import app.kingscore.android.ui.WebBadge
import app.kingscore.android.ui.OnlinePullRefresh
import app.kingscore.android.ui.CatalogOption
import app.kingscore.android.ui.CatalogSelectionSheet
import app.kingscore.android.feature.navigation.PlayDataRefresh
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
fun RatingsRoute(account: Account, repository: RatingsRepository, onAccessFailure: (AuthProblem) -> Unit,
    onProfile: () -> Unit = {}) {
    val model: RatingsViewModel = viewModel(factory = modelFactory { RatingsViewModel(repository, onAccessFailure) })
    val state by model.ratings.collectAsStateWithLifecycle()
    var game by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseGame by remember { mutableStateOf(false) }
    val visibleRanking = state.ranking.takeIf { state.gameId?.toString() == game }
    PlayDataRefresh(model, selection = game, onShow = { model.show(game?.let(UUID::fromString)) },
        onRefresh = { model.refresh(game?.let(UUID::fromString)) })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, state.problem, state.gameId) {
        if (state.problem == DataProblem.PUBLICATION_STALE) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(5_000)
            model.refresh()
        }
    }
    if (chooseGame) CatalogSelectionSheet(
        title = "Scegli gioco", options = state.games.map { CatalogOption(it.id, it.name, imageUrl = it.image_url) },
        selectedId = game?.let(UUID::fromString), searchLabel = "Cerca gioco", tagPrefix = "rating", modifier = Modifier.testTag("rating-game-picker"),
        onDismiss = { chooseGame = false }, onChoose = { id -> game = id?.toString(); chooseGame = false })
    Column(Modifier.fillMaxSize()) {
    KingScoreHeader(account.name, onProfile)
    OnlinePullRefresh(state.loading, { model.refresh() }, Modifier.weight(1f), enabled = !chooseGame) {
    LazyColumn(Modifier.fillMaxSize().testTag("ratings-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.EmojiEvents, null, tint = MaterialTheme.colorScheme.primary)
                Text("Rating ELO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("Classifica globale del gruppo", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, WebPanelShape).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { game = null }, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (game == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(8.dp))
                            .semantics { selected = game == null }) { Text("Generale") }
                    TextButton(onClick = { chooseGame = true }, enabled = state.games.isNotEmpty(),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (game != null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(8.dp))
                            .semantics { selected = game != null }) { Text("Per gioco") }
                }
                state.games.firstOrNull { it.id.toString() == game }?.let { selected ->
                    Surface(onClick = { chooseGame = true }, shape = WebPanelShape, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().testTag("rating-selected-game")) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameCover(selected.image_url, selected.name, 44.dp)
                            Text(selected.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Outlined.KeyboardArrowDown, "Scegli gioco")
                        }
                    }
                }
            }
        }
        if ((state.loading || state.gameId?.toString() != game) && visibleRanking == null) item { CircularProgressIndicator(); Text("Caricamento classifica…") }
        state.problem?.let { item {
            Text(when (state.problem) {
                DataProblem.PUBLICATION_STALE -> "La classifica è in attesa di aggiornamento. Riprova dopo la pubblicazione dei nuovi risultati."
                DataProblem.PUBLICATION_MISSING -> "La classifica non è ancora stata pubblicata."
                else -> "Non è stato possibile leggere la classifica."
            }, color = MaterialTheme.colorScheme.error)
            Button(onClick = { model.refresh() }) { Text("Riprova") }
        } }
        visibleRanking?.let { ranking ->
            if (ranking.profile == null) item { Text("Profilo giocatore non collegato.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ranking.entries.isEmpty()) item { Text("Nessun rating pubblicato per questa classifica.") }
            if (ranking.entries.isNotEmpty()) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.StarOutline, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(if (game == null) "ELO generale" else "Classifica per gioco", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            val positions = competitionRanks(ranking.entries)
            items(ranking.entries, key = { it.id.toString() }) { rating ->
                RatingCard(rating, positions[rating.id], rating.player?.account_user_id == account.id, game == null)
            }
            if (ranking.entries.isNotEmpty()) item {
                Text("V = vittorie · P = pareggi · S = sconfitte", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.readAt?.takeIf { visibleRanking != null }?.let { item { Text("Classifica letta: ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) } }
    }
    }
    }
}

@Composable
private fun RatingCard(rating: PublishedRating, position: Int?, mine: Boolean, general: Boolean) {
    val highlight = if (general) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Card(shape = WebPanelShape,
        border = if (mine) BorderStroke(1.dp, highlight.copy(alpha = 0.6f)) else null,
        colors = CardDefaults.cardColors(containerColor = if (mine) highlight.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("rating-${rating.id}").semantics(mergeDescendants = true) {}) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val expanded = LocalDensity.current.fontScale > 1.3f || maxWidth < 270.dp || (rating.rating?.toString()?.length ?: 0) > 5
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(if (expanded) 40.dp else 26.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    .semantics { contentDescription = "Posizione: ${position ?: "—"}" }, contentAlignment = Alignment.Center) {
                    Text(position?.toString() ?: "—", style = MaterialTheme.typography.labelMedium)
                }
                if (!expanded) PlayerAvatar(rating.player?.avatar_url, playerName(rating.player), 34.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(playerName(rating.player), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (mine) WebBadge("Tu", color = highlight)
                    }
                    val value = rating.rating
                    if (value != null) Text(when {
                        value >= 1500 -> "Maestro"; value >= 1100 -> "Esperto"; value >= 1050 -> "Intermedio"; else -> "Principiante"
                    }, style = MaterialTheme.typography.bodySmall, color = if (value >= 1050) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${rating.gamesPlayed ?: "—"} partite · ${rating.wins ?: "—"}V ${rating.draws ?: "—"}P ${rating.losses ?: "—"}S",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded) RatingValue(rating)
                }
                if (!expanded) RatingValue(rating)
            }
        }
    }
}

@Composable
private fun RatingValue(rating: PublishedRating) {
    Text(rating.rating?.toString() ?: "Rating non disponibile",
        style = if (rating.rating == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(max = if (rating.rating == null) 92.dp else 160.dp),
        color = if ((rating.rating ?: 0) >= 1050) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
