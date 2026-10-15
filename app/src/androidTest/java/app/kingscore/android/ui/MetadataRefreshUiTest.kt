package app.kingscore.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import app.kingscore.android.data.repository.DataProblem
import app.kingscore.android.feature.games.GameScreen
import app.kingscore.android.feature.games.GameState
import app.kingscore.android.feature.plays.HistoryState
import app.kingscore.android.feature.players.PlayerScreen
import app.kingscore.android.feature.players.PlayerState
import java.time.Instant
import java.util.UUID
import kingscore.contracts.v1.GameRow
import kingscore.contracts.v1.PlayerRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MetadataRefreshUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingOrForbiddenGameCanRefreshWithoutWaitingForUnstartedHistory() {
        val state = mutableStateOf(GameState(loading = false, missing = true))
        var calls = 0
        compose.setContent { KingScoreTheme {
            GameScreen(state.value, HistoryState(), { calls++ }, {}, {}, {}, {})
        } }
        refresh()
        compose.runOnIdle {
            assertEquals(1, calls)
            state.value = GameState(loading = false, problem = DataProblem.FORBIDDEN)
        }
        refresh()
        compose.runOnIdle {
            assertEquals(2, calls)
            state.value = GameState(game = GameRow(UUID(0, 1), "Azul", null, null, 2, 4, null), loading = false)
        }
        assertRefreshBusy()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun missingOrForbiddenPlayerCanRefreshWithoutWaitingForUnstartedHistory() {
        val state = mutableStateOf(PlayerState(loading = false, missing = true))
        var calls = 0
        val account = UUID(0, 1)
        compose.setContent { KingScoreTheme {
            PlayerScreen(state.value, HistoryState(), account, { calls++ }, {}, {}, {}, {})
        } }
        refresh()
        compose.runOnIdle {
            assertEquals(1, calls)
            state.value = PlayerState(loading = false, problem = DataProblem.FORBIDDEN)
        }
        refresh()
        compose.runOnIdle {
            assertEquals(2, calls)
            state.value = PlayerState(player = PlayerRow(UUID(0, 2), null, "Marta", false, null,
                null, null, Instant.EPOCH, null, account), loading = false)
        }
        assertRefreshBusy()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    private fun refresh() {
        val actions = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Aggiorna"), actions.map { it.label })
        compose.runOnIdle { assertTrue(actions.single().action()) }
    }

    private fun assertRefreshBusy() {
        val actions = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.isEmpty())
    }
}
