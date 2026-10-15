package app.kingscore.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.kingscore.android.core.auth.Account
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kingscore.contracts.v1.GameRow
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlayerRow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionSummaryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeAndStatisticsSharePendingAndCompleteSummaryAndExplicitRefreshUpdatesBoth() {
        val calls = AtomicInteger()
        val initial = CompletableDeferred<StatisticsSnapshot>()
        val statistics = object : StatisticsRepository {
            override suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot {
                val call = calls.incrementAndGet()
                onProgress(50)
                return if (call == 1) initial.await() else snapshot(1052)
            }
        }
        val account = Account(UUID(0, 1), "Marta", "test@example.com", false)
        val access = mutableStateOf<AccessState>(AccessState.Connected(account))
        val plays = object : PlaysRepository {
            override suspend fun player(id: UUID): PlayerRow? = error("Unexpected player detail")
            override suspend fun game(id: UUID): GameRow? = error("Unexpected game detail")
            override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(emptyList(), null)
            override suspend fun filterOptions() = HistoryOptions()
            override suspend fun detail(id: UUID): PlayDetail? = error("Unexpected play detail")
            override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        compose.setContent { KingScoreTheme {
            AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { plays }, statisticsFactory = { statistics })
        } }
        compose.onNodeWithTag("dashboard-total").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls.get()) }

        compose.onNodeWithText("Statistiche", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("statistics-content").assertExists()
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, calls.get()); initial.complete(snapshot(1051)) }
        compose.onNodeWithTag("dashboard-total", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("1051")))

        compose.onNodeWithText("Statistiche", useUnmergedTree = true).performClick()
        compose.onNodeWithText("1051").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.onNodeWithText("Aggiorna statistiche").assertDoesNotExist()
        val action = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Aggiorna" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.onNodeWithText("1052").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls.get()) }
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("dashboard-total", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("1052")))
        compose.onNodeWithText("Aggiorna Home").assertDoesNotExist()
        compose.onNodeWithText("Classifiche ELO").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls.get()) }
    }

    private fun snapshot(total: Int) = StatisticsSnapshot(total, 1, 0, 0, 0, 0, emptyList(), null, 0, 0)
}
