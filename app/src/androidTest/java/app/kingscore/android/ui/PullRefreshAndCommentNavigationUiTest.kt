package app.kingscore.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PullRefreshAndCommentNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val repo = FakePlays()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))

    @Test fun aRealDownwardPullRefreshesOnceAndPreservesTheAppliedHistoryFilters() {
        showApp()
        applyHistoryScope()
        val expected = HistoryFilters(query = "Skull", source = PlaySource.ONLINE)
        assertEquals(expected, repo.requests.last().first)
        val before = repo.requests.size
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { repo.holdNextHistory = gate }
        try {
            pullFromTheTop()
            compose.waitUntil { repo.requests.size == before + 1 }
            assertEquals(expected to null, repo.requests.last())
            // While the first request is pending a second drag must not submit another refresh.
            pullFromTheTop()
            compose.waitForIdle()
            assertEquals(before + 1, repo.requests.size)
            compose.onNodeWithTag("active-history-filters").assertExists()
        } finally {
            gate.complete(Unit)
        }
        compose.waitForIdle()
        assertEquals(before + 1, repo.requests.size)
        assertEquals(expected to null, repo.requests.last())
        compose.onNodeWithTag("history-query").assertTextContains("Skull")
    }

    @Test fun commentBadgeOpensTheMatchingPlayAtCommentsAndBackKeepsTheHistoryScope() {
        showApp()
        applyHistoryScope()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-comments-${play.play.id}"))
        compose.onNodeWithTag("play-comments-${play.play.id}").assertTextContains("1").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
        // No test scroll is issued in the detail: the badge navigation must position this section itself.
        compose.onNodeWithTag("comments-section").assertIsDisplayed()
        compose.onNodeWithText(comment.content).assertIsDisplayed()
        assertEquals(listOf(play.play.id), repo.detailRequests)
        assertEquals(listOf(play.play.id), repo.commentRequests)
        compose.onNodeWithContentDescription("Torna allo storico").performClick()
        compose.onNodeWithTag("history-query").assertTextContains("Skull")
        compose.onNodeWithTag("active-history-filters").assertExists()
        assertEquals(HistoryFilters(query = "Skull", source = PlaySource.ONLINE), repo.requests.last().first)
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("play-comments-${play.play.id}").assertDoesNotExist()
        compose.onNodeWithTag("comments-section").assertDoesNotExist()
    }

    private fun showApp() {
        compose.setContent { KingScoreTheme {
            AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { repo },
                onAccessFailure = { access.value = AccessState.Blocked(it) })
        } }
    }

    private fun applyHistoryScope() {
        compose.onNode(hasText("Partite") and hasClickAction()).performClick()
        compose.onNodeWithTag("history-query").performTextReplacement("Skull")
        compose.onNodeWithTag("history-query").performImeAction()
        compose.onNodeWithTag("history-content").performScrollToNode(hasText("Filtri"))
        compose.onNodeWithText("Filtri").performClick()
        compose.onNodeWithTag("filter-source-ONLINE").performScrollTo().performClick()
        compose.onNodeWithText("Applica filtri").performClick()
        compose.waitForIdle()
    }

    private fun pullFromTheTop() {
        compose.onNodeWithTag("history-content").performScrollToIndex(0)
        compose.onNodeWithTag("pull-refresh").performTouchInput {
            // Use the list padding, outside text fields and buttons, and cross the native drag threshold.
            swipe(start = Offset(width * 0.97f, height * 0.12f),
                end = Offset(width * 0.97f, height * 0.90f), durationMillis = 600)
        }
        compose.waitForIdle()
    }

    private class FakePlays : PlaysRepository {
        val requests = mutableListOf<Pair<HistoryFilters, PlayCursor?>>()
        val detailRequests = mutableListOf<UUID>()
        val commentRequests = mutableListOf<UUID>()
        var holdNextHistory: CompletableDeferred<Unit>? = null
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            requests += filters to after
            val pending = holdNextHistory
            holdNextHistory = null
            pending?.await()
            return PlayPage(listOf(play), null)
        }
        override suspend fun commentCounts(playIds: Set<UUID>) = playIds.associateWith { if (it == play.play.id) 1 else 0 }
        override suspend fun filterOptions() = HistoryOptions(listOf(game), listOf(player))
        override suspend fun player(id: UUID) = player.takeIf { it.id == id }
        override suspend fun game(id: UUID) = game.takeIf { it.id == id }
        override suspend fun detail(id: UUID): PlayDetail? { detailRequests += id; return play.takeIf { it.play.id == id } }
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage {
            commentRequests += id
            return CommentPage(listOf(comment).filter { it.play_id == id }, null)
        }
    }

    companion object {
        private val account = Account(UUID(8, 99), "Silvia", "test@example.com", false)
        private val game = GameRow(UUID(8, 10), "Skull King", null, null, 2, 6, null)
        private val player = PlayerRow(UUID(8, 11), null, "Silvia", null, null, null, null, Instant.EPOCH, null, account.id)
        private val play = PlayDetail(PlayRow(UUID(8, 12), account.id, game.id, LocalDate.of(2026, 10, 12),
            null, (1..24).joinToString("\n") { "Nota della partita $it" }, 30, "online", null, Instant.EPOCH, false, null),
            game, listOf(ScoreWithPlayer(PlayScoreRow(UUID(8, 13), UUID(8, 12), player.id,
                BigDecimal("330"), true, null, null, 1), player)))
        private val comment = PlayCommentRow(UUID(8, 14), play.play.id, UUID(8, 100), "Ale",
            "La prossima rivincita è mia.", Instant.EPOCH, Instant.EPOCH)
    }
}
