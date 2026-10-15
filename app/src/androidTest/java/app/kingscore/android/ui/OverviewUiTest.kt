package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class OverviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private var historyCalls = 0
    private var rankingCalls = 0

    @Test fun dashboardStatisticsRankingUsePublishedDataWithoutRatingHistory() {
        showApp()
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${play.play.id}"))
        compose.onNodeWithText("Ultime partite").assertExists()
        compose.onNodeWithTag("recent-${play.play.id}").assertTextContains("Azul")
        capture("phase3-dashboard")
        compose.onNodeWithText("Statistiche").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasText("Durata disponibile per 2 di 1051 partite"))
        compose.onNodeWithText("Durata disponibile per 2 di 1051 partite").assertIsDisplayed()
        capture("phase3-statistics")
        compose.onNodeWithText("ELO").performClick()
        compose.onNodeWithTag("rating-${id(30)}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Marta")))
        compose.onAllNodesWithContentDescription("Posizione: 1").assertCountEquals(2)
        compose.onNodeWithTag("rating-${id(31)}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Luca")))
        compose.onAllNodesWithText("6 partite · 1V 3P 2S")[0].assertExists()
        capture("phase3-ratings")
        compose.onNodeWithTag("rating-${id(30)}").assertHasNoClickAction()
        compose.onNodeWithTag("rating-history").assertDoesNotExist()
        compose.onNodeWithText("Tocca un giocatore per vedere lo storico").assertDoesNotExist()
        val beforeRefresh = compose.runOnIdle { rankingCalls }
        refreshFromAccessibilityAction()
        compose.runOnIdle { assertEquals(beforeRefresh + 1, rankingCalls); assertEquals(0, historyCalls) }
        compose.onNodeWithTag("ratings-content").performScrollToNode(hasText("Rating non disponibile"))
        compose.onNodeWithText("Rating non disponibile").assertIsDisplayed()
        compose.onNodeWithTag("ratings-content").performScrollToNode(hasText("Per gioco"))
        compose.onNodeWithText("Per gioco").performClick()
        compose.onNodeWithTag("rating-option-${game.id}").performClick()
        compose.onNodeWithText("Azul").assertIsDisplayed()
        compose.onNodeWithText("Ricalcola ELO").assertDoesNotExist()
    }

    @Test fun offlineRemovesLoadedStatisticsAndStaticRanking() {
        showApp()
        compose.onNodeWithText("ELO").performClick()
        compose.onNodeWithTag("rating-${id(30)}").assertHasNoClickAction()
        compose.onNodeWithTag("rating-${id(30)}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("1100")))
        compose.runOnIdle { assertEquals(0, historyCalls) }
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("rating-history").assertDoesNotExist()
        compose.onNodeWithTag("ratings-content").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
    }

    @Test fun missingProfileAndEmptyPublishedRatingsRemainExplicit() {
        showApp(unlinked = true)
        compose.onNodeWithText("Statistiche").performClick()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasText("Profilo giocatore non collegato."))
        compose.onNodeWithText("Profilo giocatore non collegato.").assertIsDisplayed()
        compose.onNodeWithText("ELO").performClick()
        compose.onNodeWithText("Profilo giocatore non collegato.").assertIsDisplayed()
        compose.onNodeWithText("Nessun rating pubblicato per questa classifica.").assertIsDisplayed()
    }
    private fun showApp(unlinked: Boolean = false) {
        val ratings = object : RatingsRepository {
            override suspend fun games() = listOf(game)
            override suspend fun ranking(gameId: UUID?): Ranking {
                rankingCalls++
                return Ranking(if (unlinked) null else player,
                    if (unlinked) emptyList() else listOf(rating(30, player, 1100), rating(31, player.copy(id = id(51), name = "Luca", account_user_id = null), 1100), rating(32, null, null)))
            }
            override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?): RatingHistoryPage {
                historyCalls++
                error("Rating history must not be requested by the app UI")
            }
        }
        val statistics = object : StatisticsRepository {
            override suspend fun load(onProgress: (Int) -> Unit) = StatisticsSnapshot(1051, 1, 1, 2, 60, 2,
                listOf(GameStatistic(game.id, game.name, 1051)), if (unlinked) null else player, 1, 1)
            override suspend fun overview(revision: String?, cursor: String?) = StatisticsOverview("1", !unlinked,
                if (unlinked) emptyList() else listOf(WinRateStatistic(game.id, game.name, 1, 1, 100)),
                listOf(GameStatistics(game.id,game.name,null,1051,2102,java.math.BigDecimal.ZERO,0,null,
                    java.math.BigDecimal(60),2,java.math.BigDecimal(30),0)),1,null)
            override suspend fun eloChart(gameId: UUID?, publication: UUID?, cursor: String?, players: Set<UUID>?) =
                StatisticsChart("1",null,gameId,"missing",emptyList(),emptyMap(),emptyList(),0,null)
        }
        val plays = object : PlaysRepository {
            override suspend fun player(id: UUID) = player
            override suspend fun game(id: UUID) = game
            override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(play), null)
            override suspend fun filterOptions() = HistoryOptions()
            override suspend fun detail(id: UUID) = play
            override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
            playsFactory = { plays }, onAccessFailure = { access.value = AccessState.Blocked(it) },
            statisticsFactory = { statistics }, ratingsFactory = { ratings }) } }
    }
    private fun refreshFromAccessibilityAction() {
        val action = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Aggiorna" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = Account(id(1), "Marta", "test@example.com", false)
        private val player = PlayerRow(id(50), id(99), "Marta", false, null, null, null, Instant.EPOCH, null, account.id)
        private val game = GameRow(id(10), "Azul", null, null, 2, 4, null)
        private val play = PlayDetail(PlayRow(id(20), id(99), game.id, LocalDate.parse("2026-10-13"), null, null, 60, "offline", null, null, false, null), game, emptyList())
        private fun rating(n: Int, player: PlayerRow?, value: Int?) = PublishedRating(id(n), player, player?.id, null, id(99), value, 6, 1, 2, 3, Instant.EPOCH)
    }
}
