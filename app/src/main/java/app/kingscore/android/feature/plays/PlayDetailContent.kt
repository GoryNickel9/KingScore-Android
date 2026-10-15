package app.kingscore.android.feature.plays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kingscore.android.ui.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kingscore.contracts.v1.*

@Composable
internal fun PlayDetailContent(state: DetailState, accountId: UUID, onRefresh: () -> Unit, onMoreComments: () -> Unit,
    onRetryComments: () -> Unit, onBack: () -> Unit, onOpenGame: ((UUID) -> Unit)?,
    onOpenPlayer: ((UUID) -> Unit)?, commentActions: CommentActions?, onManage: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().testTag("play-detail")) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Torna allo storico") }
                Text("Dettaglio partita", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (onManage != null && state.detail != null && !state.loading && state.problem == null && !state.missing) {
                    TextButton(onClick = onManage, enabled = !state.commentSaving, modifier = Modifier.testTag("manage-play")) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gestisci")
                    }
                } else IconButton(onClick = onRefresh, enabled = !state.loading && !state.commentSaving) { Icon(Icons.Outlined.Refresh, "Aggiorna") }
            }
        }
        when {
            state.loading -> Loading("Caricamento partita…")
            state.problem != null -> Box(Modifier.padding(16.dp)) { DataError(state.problem, onRefresh) }
            state.missing -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Partita non trovata", style = MaterialTheme.typography.titleLarge)
                Text("Potrebbe essere stata eliminata o non essere più accessibile.")
            }
            state.detail != null -> {
                val detail = state.detail
                LazyColumn(Modifier.weight(1f).testTag("detail-content"), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { KingScorePanel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile", 76.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (detail.play.game_id != null && onOpenGame != null) {
                                    TextButton(onClick = { onOpenGame(detail.play.game_id) }, contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.testTag("open-game")) {
                                        Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                } else Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                Text(playDateFormat.format(detail.play.played_at), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                WebBadge(sourceLabel(detail.play.source))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PlayMetadata(detail) }
                        else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { PlayMetadata(detail) }
                        detail.play.location?.takeIf { it.isNotBlank() }?.let { Text("Luogo: $it", style = MaterialTheme.typography.bodyMedium) }
                        if (detail.play.is_cooperative == true) Text(resultLabel(detail), color = MaterialTheme.colorScheme.primary)
                    } }
                    item { KingScorePanel {
                        Text("Punteggi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (detail.scores.isEmpty()) Text("Nessun partecipante registrato.")
                        else {
                            Row(Modifier.fillMaxWidth()) {
                                Text("Giocatore", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Punti", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            detail.scores.sortedWith(compareBy<ScoreWithPlayer> { it.score.position ?: Int.MAX_VALUE }
                                .thenBy { playerName(it.player) }.thenBy { it.score.id.toString() }).forEach { row ->
                                DetailScore(row, accountId, onOpenPlayer)
                            }
                        }
                    } }
                    item { KingScorePanel {
                        Text("Note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(detail.play.notes?.takeIf { it.isNotBlank() } ?: "Nessuna nota.", style = MaterialTheme.typography.bodyMedium)
                    } }
                    item { KingScorePanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Commenti", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (!state.commentsLoading && state.commentsProblem == null) Text(
                                "${state.comments.size}${if (state.commentCursor != null) "+" else ""}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (commentActions != null) CommentControls(state, commentActions)
                    } }
                    items(state.comments, key = { it.id.toString() }) { comment ->
                        KingScorePanel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PlayerAvatar(null, comment.author_name, 30.dp)
                                Text(comment.author_name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (comment.user_id == accountId) WebBadge("Tu")
                            }
                            Text(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(comment.created_at),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                            if (commentActions != null && comment.user_id == accountId) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { commentActions.compose(comment.id) }, enabled = !state.commentSaving &&
                                        !state.commentsLoading && !state.commentWritesBlocked,
                                        modifier = Modifier.testTag("edit-comment-${comment.id}")) { Text("Modifica") }
                                    TextButton(onClick = { commentActions.requestDelete(comment.id) }, enabled = !state.commentSaving &&
                                        !state.commentsLoading && !state.commentWritesBlocked,
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.testTag("delete-comment-${comment.id}")) { Text("Elimina") }
                                }
                            }
                        }
                    }
                    if (state.commentsLoading) item { Loading("Caricamento commenti…") }
                    else if (state.commentsProblem != null) item { DataError(state.commentsProblem, onRetryComments) }
                    else if (state.comments.isEmpty()) item { Text("Nessun commento.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!state.commentsLoading && state.commentsProblem == null && state.commentCursor != null) item {
                        OutlinedButton(onClick = onMoreComments, modifier = Modifier.fillMaxWidth()) { Text("Carica altri commenti") }
                    }
                    if (onManage != null) item {
                        TextButton(onClick = onRefresh, enabled = !state.commentSaving) {
                            Icon(Icons.Outlined.Refresh, "Aggiorna")
                            Spacer(Modifier.width(8.dp))
                            Text("Rileggi partita")
                        }
                    }
                }
            }
        }
    }
    if (commentActions != null) CommentDialogs(state, commentActions)
}

@Composable
private fun PlayMetadata(detail: PlayDetail) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PeopleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("${detail.scores.size} giocatori", style = MaterialTheme.typography.bodySmall)
    }
    detail.play.duration_minutes?.let { minutes ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("$minutes minuti", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailScore(row: ScoreWithPlayer, accountId: UUID, onOpenPlayer: ((UUID) -> Unit)?) {
    val winner = row.score.winner == true
    val points = row.score.score?.toPlainString()?.replace('.', ',')
    val expanded = LocalDensity.current.fontScale > 1.3f || (points?.length ?: 0) > 8
    Column(Modifier.fillMaxWidth().background(if (winner) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 4.dp, horizontal = 6.dp)
        .testTag("score-${row.score.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(if (LocalDensity.current.fontScale > 1.3f) 40.dp else 26.dp).background(if (winner) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .semantics { contentDescription = "Posizione: ${row.score.position ?: "—"}" }, contentAlignment = Alignment.Center) {
                Text(row.score.position?.toString() ?: "—", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.weight(1f)) {
                if (row.score.player_id != null && onOpenPlayer != null) TextButton(
                    onClick = { onOpenPlayer(row.score.player_id) }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("open-player-${row.score.id}").semantics {
                        contentDescription = "Apri giocatore: ${playerName(row.player)}"
                    }) { Text(playerName(row.player), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) }
                else Text(playerName(row.player), style = MaterialTheme.typography.bodyMedium)
                if (row.player?.account_user_id == accountId) WebBadge("Sei tu")
                if (winner) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Vincitore", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!expanded) ScoreValue(points)
        }
        if (expanded) ScoreValue(points)
        row.score.role?.takeIf { it.isNotBlank() }?.let { Text("Ruolo: $it", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ScoreValue(points: String?) {
    Text(points ?: "Punteggio non registrato", style = if (points == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleLarge,
        fontWeight = if (points == null) FontWeight.Normal else FontWeight.Bold,
        modifier = Modifier.widthIn(max = if (points == null) 110.dp else 600.dp).semantics {
            contentDescription = points?.let { "Punteggio: $it" } ?: "Punteggio non registrato"
        })
}
