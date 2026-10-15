package app.kingscore.android.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.plays.*
import app.kingscore.android.feature.stats.StatisticsViewModel
import app.kingscore.android.ui.*
import app.kingscore.android.feature.navigation.PlayDataRefresh
import app.kingscore.android.feature.navigation.LocalPlayDataRevision
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kingscore.contracts.v1.PlayDetail

@Composable
fun DashboardRoute(account: Account, repository: PlaysRepository, onAccessFailure: (AuthProblem) -> Unit,
    onOpen: (UUID) -> Unit, onHistory: () -> Unit, onRatings: () -> Unit,
    statisticsRepository: StatisticsRepository? = null, onProfile: () -> Unit = {},
    sharedModel: StatisticsViewModel? = null) {
    val model: HistoryViewModel = viewModel(factory = modelFactory { HistoryViewModel(repository, onAccessFailure, includeCommentCounts = false) })
    val state by model.history.collectAsStateWithLifecycle()
    PlayDataRefresh(model, onShow = model::show, onRefresh = { model.refresh() })
    val summaryModel: StatisticsViewModel? = sharedModel ?: statisticsRepository?.let {
        viewModel(key = "dashboard-statistics", factory = modelFactory { StatisticsViewModel(it, onAccessFailure) })
    }
    val revision = LocalPlayDataRevision.current
    summaryModel?.let { PlayDataRefresh(it, onShow = { it.show(revision) }, onRefresh = { it.refreshForRevision(revision) }) }
    val summary = summaryModel?.statistics?.collectAsStateWithLifecycle()?.value
    Column(Modifier.fillMaxSize()) {
        KingScoreHeader(account.name, onProfile)
        OnlinePullRefresh(state.loading || summary?.loading == true, { model.refresh(); summaryModel?.refresh() }, Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().testTag("dashboard-content"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Benvenuto, ${account.name}!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Ecco il riepilogo del gruppo", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            if (summary != null) {
                when {
                    summary.loading && summary.snapshot == null -> item { KingScorePanel {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Caricamento riepilogo…", style = MaterialTheme.typography.bodySmall)
                    } }
                    summary.problem != null -> item { KingScorePanel {
                        Text("Il riepilogo non è disponibile. Le ultime partite restano consultabili.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { summaryModel?.refresh() }) { Text("Riprova riepilogo") }
                    } }
                    summary.snapshot != null -> {
                        item { DashboardTotals(summary.snapshot) }
                        if (summary.snapshot.months.isNotEmpty()) item { MonthlyChart(summary.snapshot.months) }
                    }
                }
            }
            summary?.snapshot?.games?.take(3)?.takeIf { it.isNotEmpty() }?.let { games ->
                item { KingScorePanel {
                    Text("Giochi preferiti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    games.forEach { game ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GameCover(game.imageUrl, game.name, 44.dp)
                            Text(game.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(game.plays.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("partite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ultime partite", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onHistory) { Text("Vedi tutte") }
                }
            }
            if (state.loading) item { Loading("Caricamento…") }
            state.problem?.let { item { DataError(it) { model.refresh() } } }
            if (!state.loading && state.problem == null && state.items.isEmpty()) item { Text("Nessuna partita registrata.") }
            items(state.items.take(5), key = { it.play.id.toString() }) { RecentPlay(it, onOpen) }

        }
        }
    }
}

@Composable
private fun DashboardTotals(stats: StatisticsSnapshot) {
    @Composable fun Total() { KingScorePanel(Modifier.heightIn(min = 108.dp).testTag("dashboard-total")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SportsEsports, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Partite totali", style = MaterialTheme.typography.bodySmall)
        }
        Text(stats.totalPlays.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    } }
    @Composable fun Favorite() { KingScorePanel(Modifier.heightIn(min = 108.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text("Gioco preferito", style = MaterialTheme.typography.bodySmall)
        }
        Text(stats.games.firstOrNull()?.name ?: "Nessun gioco", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
    } }
    BoxWithConstraints {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Total(); Favorite() }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(0.9f)) { Total() }
            Box(Modifier.weight(1.1f)) { Favorite() }
        }
    }
}

@Composable
internal fun MonthlyChart(months: List<MonthStatistic>) {
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMM", Locale.ITALIAN) }
    val summary = months.joinToString("; ") { "${it.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN))}: ${it.plays} partite" }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    KingScorePanel(Modifier.testTag("dashboard-months")) {
        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Partite per mese", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Ultimi ${months.size} mesi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Partite per mese", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Ultimi ${months.size} mesi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val peak = months.maxOf { it.plays }.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(92.dp).semantics { contentDescription = summary }) {
            val inset = 4.dp.toPx()
            val height = size.height - 2 * inset
            val width = size.width - 2 * inset
            repeat(3) { i -> drawLine(grid, Offset(inset, inset + height * i / 2), Offset(size.width - inset, inset + height * i / 2)) }
            val points = months.mapIndexed { i, value -> Offset(inset + width * i / (months.size - 1).coerceAtLeast(1),
                inset + height * (1 - value.plays.toFloat() / peak)) }
            val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            points.forEach { drawCircle(color, 3.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            months.forEach { Text(it.month.format(monthFormat).replace(".", ""), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun RecentPlay(detail: PlayDetail, onOpen: (UUID) -> Unit) {
    Card(onClick = { onOpen(detail.play.id) }, shape = WebPanelShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("recent-${detail.play.id}")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(detail.game?.image_url, detail.game?.name ?: "Gioco non disponibile", 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail.game?.name ?: "Gioco non disponibile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${playDateFormat.format(detail.play.played_at)} · ${sourceLabel(detail.play.source)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(resultLabel(detail), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
