package app.kingscore.android.feature.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel

internal val LocalPlayDataRevision = staticCompositionLocalOf { 0 }

/** Each retained screen refreshes once after a confirmed mutation, preserving its filters. */
@Composable
internal fun PlayDataRefresh(model: ViewModel, selection: Any? = null,
    onShow: () -> Unit, onRefresh: () -> Unit) {
    val revision = LocalPlayDataRevision.current
    var observed by rememberSaveable { mutableIntStateOf(revision) }
    LaunchedEffect(model, revision, selection) {
        if (observed != revision) { observed = revision; onRefresh() } else onShow()
    }
}
