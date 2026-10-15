package app.kingscore.android.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.core.graphics.toColorInt
import app.kingscore.android.data.repository.*
import app.kingscore.android.ui.WebPanelShape
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val fallbackColors = listOf(0xFF10B981, 0xFF60A5FA, 0xFFFBBF24, 0xFFC084FC, 0xFFFB7185, 0xFF22D3EE, 0xFFFDA4AF, 0xFFA3E635)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatisticsEloChart(state: StatisticsChartState, retry: () -> Unit, next: () -> Unit,
    previous: () -> Unit, selectPlayers: () -> Unit) {
    if (state.loading) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant, WebPanelShape)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Caricamento storico ELO…")
            }
        }
        return
    }
    state.problem?.let { problem ->
        if (problem == DataProblem.SELECTION_REQUIRED) {
            Text("Più di 64 serie disponibili. Scegli i giocatori da confrontare.")
            TextButton(onClick = selectPlayers) { Text("Scegli giocatori") }
        } else {
            StatisticsError(problem, retry)
            if (state.selection != null) TextButton(onClick = selectPlayers) { Text("Scegli giocatori") }
        }
        return
    }
    val data = state.data ?: return
    if (data.status != "current") {
        Text(if (data.status == "stale") "ELO in attesa della pubblicazione aggiornata." else "Nessun dato ELO pubblicato.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = retry) { Text("Riprova") }; return
    }
    if (data.plays.isEmpty()) { Text("Nessun dato ELO disponibile per questa selezione.")
        if (state.selection != null) TextButton(onClick = selectPlayers) { Text("Scegli giocatori") }; return }
    var selected by remember(data.publication, data.plays.first().id) { mutableIntStateOf(data.plays.lastIndex) }
    var hidden by remember(data.publication) { mutableStateOf(emptySet<UUID>()) }
    val filled = remember(data) {
        val ratings = data.initial.toMutableMap()
        data.plays.map { play -> ratings.putAll(play.ratings); ratings.toMap() }
    }
    val background = MaterialTheme.colorScheme.surfaceVariant
    val colors = remember(data.players, background) { data.players.mapIndexed { index, player ->
        var color = runCatching { Color((player.color ?: "").toColorInt()) }.getOrNull()
            ?.takeIf { it.alpha == 1f } ?: Color(fallbackColors[index % fallbackColors.size])
        // Preserve the player's hue while keeping meaningful lines visible on the dark plot.
        repeat(8) {
            val a = color.luminance(); val b = background.luminance()
            if ((maxOf(a,b) + 0.05f) / (minOf(a,b) + 0.05f) < 3f) color = lerp(color, Color.White, 0.2f)
        }
        player.id to color
    }.toMap() }
    val active = data.players.filter { it.id !in hidden }
    val allValues = filled.flatMap { row -> row.filterKeys { key -> key !in hidden }.values }
    val low = (allValues.minOrNull() ?: 1000).toDouble() - 10.0
    val high = (allValues.maxOrNull() ?: 1000).toDouble() + 10.0
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val labels = MaterialTheme.colorScheme.onSurfaceVariant
    val current = data.plays[selected]
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, WebPanelShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Partite ${data.plays.first().ordinal}–${data.plays.last().ordinal} di ${data.total}", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("statistics-elo-plot-${data.gameId}")
            .semantics { contentDescription = "Andamento ELO di ${active.size} giocatori. Valori consultabili con i controlli sotto il grafico." }
            .pointerInput(data, hidden) { detectTapGestures { point ->
                val left = 56.dp.toPx(); val width = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
                selected = (((point.x - left) / width) * data.plays.lastIndex).roundToInt().coerceIn(data.plays.indices)
            } }) {
            val left = 56.dp.toPx(); val top = 12.dp.toPx(); val width = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            val height = size.height - top - 28.dp.toPx()
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.argb(255,
                (labels.red * 255).toInt(), (labels.green * 255).toInt(), (labels.blue * 255).toInt()); textSize = 10.sp.toPx(); isAntiAlias = true }
            repeat(4) { row ->
                val y = top + height * row / 3f
                drawLine(grid, Offset(left,y), Offset(left+width,y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f,5f)))
                val value = high - (high-low)*row/3
                val label = if (kotlin.math.abs(value) > 99999) String.format(Locale.ITALIAN,"%.0fk",value/1000) else value.toLong().toString()
                drawContext.canvas.nativeCanvas.drawText(label,0f,y+4.dp.toPx(),paint)
            }
            active.forEachIndexed { lineIndex, player ->
                val path = Path(); var started = false
                filled.forEachIndexed { index, ratings -> ratings[player.id]?.let { rating ->
                    val x = left + width*index/data.plays.lastIndex.coerceAtLeast(1)
                    val y = top+height*(1-(rating.toDouble()-low)/(high-low)).toFloat()
                    if (!started) { path.moveTo(x,y); started=true } else path.lineTo(x,y)
                    if (index == selected || data.plays.size == 1) drawCircle(colors.getValue(player.id),3.dp.toPx(),Offset(x,y))
                } }
                drawPath(path,colors.getValue(player.id),style=Stroke(2.dp.toPx(),pathEffect = when(lineIndex%3) {
                    1 -> PathEffect.dashPathEffect(floatArrayOf(12f,6f)); 2 -> PathEffect.dashPathEffect(floatArrayOf(3f,5f)); else -> null }))
            }
            val x=left+width*selected/data.plays.lastIndex.coerceAtLeast(1)
            drawLine(grid,Offset(x,top),Offset(x,top+height))
            drawContext.canvas.nativeCanvas.drawText(data.plays.first().ordinal.toString(),left,size.height-4.dp.toPx(),paint)
            val end="${data.plays.last().ordinal} · Partita"
            drawContext.canvas.nativeCanvas.drawText(end,(left+width-paint.measureText(end)).coerceAtLeast(left),size.height-4.dp.toPx(),paint)
        }
        if (data.plays.size > 1) Slider(selected.toFloat(), { selected=it.roundToInt() }, valueRange=0f..data.plays.lastIndex.toFloat(),
            steps=(data.plays.size-2).coerceAtLeast(0), modifier=Modifier.semantics { contentDescription="Partita da consultare nel grafico ELO" })
        Text("Partita ${current.ordinal} · ${current.date.format(DateTimeFormatter.ofPattern("d MMM yyyy",Locale.ITALIAN))}",
            style=MaterialTheme.typography.bodySmall, modifier=Modifier.testTag("statistics-chart-selected-${data.gameId}"))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            data.players.forEach { player ->
                FilterChip(selected=player.id !in hidden, onClick={ hidden=if(player.id in hidden) hidden-player.id else hidden+player.id },
                    leadingIcon = { Box(Modifier.size(8.dp).background(colors.getValue(player.id), CircleShape)) },
                    label={ Column {
                        Text(player.name)
                        Text(filled[selected][player.id]?.toString() ?: "Non ancora disponibile",style=MaterialTheme.typography.labelSmall)
                    } })
            }
        }
        if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth()) {
            TextButton(onClick=previous,enabled=state.previous.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("Precedenti") }
            TextButton(onClick=next,enabled=data.next!=null,modifier=Modifier.fillMaxWidth()) { Text("Successive") }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=previous,enabled=state.previous.isNotEmpty()) { Text("Precedenti") }
            TextButton(onClick=next,enabled=data.next!=null) { Text("Successive") }
        }
        if (state.selection != null || data.availableSeries > 64)
            TextButton(onClick=selectPlayers) { Text("Scegli giocatori") }
    }
}
