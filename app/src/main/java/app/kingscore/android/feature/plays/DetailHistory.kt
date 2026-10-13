package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun LazyListScope.detailHistory(state: HistoryState, emptyLabel: String, moreTag: String,
    onMore: () -> Unit, onRetry: () -> Unit, onOpen: (UUID) -> Unit) {
    if (state.loading) item { Loading("Caricamento partite…") }
    else {
        state.updatedAt?.let { instant -> item {
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)
            Text("${state.items.size} partite caricate · Aggiornato alle $time",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (state.items.isEmpty() && state.problem == null) item { Text(emptyLabel) }
        items(state.items, key = { it.play.id.toString() }) { PlayHistoryCard(it, onOpen) }
        state.problem?.let { item { DataError(it, onRetry) } }
        if (state.loadingMore) item { Loading("Caricamento altre partite…") }
        else if (state.next != null && state.problem == null) item {
            OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth().testTag(moreTag)) { Text("Carica altre partite") }
        }
    }
}
