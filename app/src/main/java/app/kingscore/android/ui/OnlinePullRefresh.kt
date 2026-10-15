package app.kingscore.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** Used inside the verified online shell; the caller also guards critical writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlinePullRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    val refresh = { if (enabled && !refreshing) onRefresh() }
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, state = state,
            modifier = Modifier.fillMaxSize().testTag("pull-refresh").semantics {
                customActions = if (enabled && !refreshing) listOf(CustomAccessibilityAction("Aggiorna") {
                    refresh(); true
                }) else emptyList()
            }, indicator = {
                PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    color = MaterialTheme.colorScheme.primary)
            }) { content() }
    }
}
