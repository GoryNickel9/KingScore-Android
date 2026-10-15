package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.plays.*
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebStyleUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))

    @Test fun approvedScreensKeepPublishedValuesAndRemovePrivateContentWhenBlockedOrSignedOut() {
        showApp()
        compose.onNodeWithTag("dashboard-total").assertExists()
        compose.onNodeWithTag("dashboard-months").assertExists()
        capture("home")
        openDetail()
        compose.onNodeWithContentDescription("Punteggio: 330").assertExists()
        capture("detail")
        compose.onNodeWithText("ELO", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("rating-${id(30)}").assertTextContains("1080")
        capture("ranking")
        compose.onNodeWithTag("rating-${id(30)}").performClick()
        compose.onNodeWithTag("rating-history").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("rating-history").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-total").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertExists()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
    }

    @Test fun twiceSizedTextKeepsAllThreeScreensScrollableAndPreservesExactScore() {
        showApp(scale = 2f, shownPlay = play.copy(scores = listOf(ScoreWithPlayer(
            PlayScoreRow(id(70), play.play.id, player.id, BigDecimal("-9007199254740993.125"), true, null, null, 1), player))))
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("dashboard-months"))
        capture("home-font2")
        compose.onNodeWithText("ELO", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ratings-content").performScrollToNode(hasTestTag("rating-${id(30)}"))
        compose.onNodeWithTag("rating-${id(30)}").assertTextContains("1080")
        capture("ranking-font2")
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasContentDescription("Punteggio: -9007199254740993,125"))
        compose.onNodeWithContentDescription("Punteggio: -9007199254740993,125").performScrollTo().assertIsDisplayed()
        capture("detail-font2")
    }

    @Test fun incompleteOrFailedSummaryNeverInventsZeroTotalsOrHidesRecentPlays() {
        val pending = CompletableDeferred<StatisticsSnapshot>()
        val statistics = object : StatisticsRepository {
            override suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot {
                onProgress(50)
                return pending.await()
            }
        }
        showApp(statistics = statistics)
        compose.onNodeWithTag("dashboard-total").assertDoesNotExist()
        compose.onNodeWithText("Lettura riepilogo: 50 partite…").assertExists()
        pending.completeExceptionally(DataFailure(DataProblem.INVALID_RESPONSE))
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasText("Riprova riepilogo"))
        compose.onNodeWithTag("dashboard-total").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${play.play.id}"))
        compose.onNodeWithTag("recent-${play.play.id}").assertIsDisplayed()
    }

    @Test fun redesignedCommentsExposeOnlyOwnActions() {
        var edited: UUID? = null
        val mine = PlayCommentRow(id(80), play.play.id, account.id, "Marco", "Il mio commento", Instant.EPOCH, Instant.EPOCH)
        val other = mine.copy(id = id(81), user_id = id(2), author_name = "Giulia", content = "Un altro commento")
        val actions = CommentActions({ edited = it }, {}, {}, {}, {}, {}, {}, {})
        compose.setContent { KingScoreTheme { DetailScreen(DetailState(detail = play, loading = false,
            comments = listOf(mine, other)), account.id, {}, {}, {}, {}, commentActions = actions) } }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("edit-comment-${mine.id}"))
        compose.onNodeWithTag("edit-comment-${mine.id}").performScrollTo().performClick()
        assertEquals(mine.id, edited)
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Un altro commento"))
        compose.onNodeWithTag("edit-comment-${other.id}").assertDoesNotExist()
        compose.onNodeWithTag("delete-comment-${other.id}").assertDoesNotExist()
        capture("comments")
    }

    private fun openDetail() {
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${play.play.id}"))
        compose.onNodeWithTag("recent-${play.play.id}").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Punteggi"))
    }

    private fun showApp(scale: Float = 1f, shownPlay: PlayDetail = play, statistics: StatisticsRepository = object : StatisticsRepository {
        override suspend fun load(onProgress: (Int) -> Unit) = snapshot
    }) {
        val plays = object : PlaysRepository {
            override suspend fun player(id: UUID) = player
            override suspend fun game(id: UUID) = game
            override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(shownPlay), null)
            override suspend fun filterOptions() = HistoryOptions()
            override suspend fun detail(id: UUID) = shownPlay
            override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val ratings = object : RatingsRepository {
            override suspend fun games() = listOf(game)
            override suspend fun ranking(gameId: UUID?) = Ranking(player, listOf(
                PublishedRating(id(30), player, player.id, gameId, null, 1080, 17, 8, 9, 0, Instant.EPOCH),
                PublishedRating(id(31), player.copy(id = id(51), name = "Giulia", account_user_id = null), id(51), gameId,
                    null, 1053, 42, 21, 21, 0, Instant.EPOCH)))
            override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?) = RatingHistoryPage(listOf(
                EloHistoryRow(id(60), play.play.id, game.id, player.id, "Marco", 1080, 15, Instant.EPOCH, true, 2, id(99), Instant.EPOCH)), null)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { plays },
                    onAccessFailure = { access.value = AccessState.Blocked(it) }, statisticsFactory = { statistics }, ratingsFactory = { ratings }) }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.filesDir, "ui-051-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = Account(id(1), "Marco", "test@example.com", false)
        private val player = PlayerRow(id(50), id(99), "Marco", false, null, null, null, Instant.EPOCH, null, account.id)
        private val game = GameRow(id(10), "Skull King", null, null, 2, 6, null)
        private val play = PlayDetail(PlayRow(id(20), id(99), game.id, LocalDate.parse("2026-10-12"), null, null,
            45, "online", "Ultimo round deciso sul finale.", null, false, null), game,
            listOf(
                ScoreWithPlayer(PlayScoreRow(id(70), id(20), id(51), BigDecimal("330"), true, null, null, 1),
                    player.copy(id = id(51), name = "Giulia", account_user_id = null)),
                ScoreWithPlayer(PlayScoreRow(id(71), id(20), player.id, BigDecimal("30"), false, null, null, 2), player),
                ScoreWithPlayer(PlayScoreRow(id(72), id(20), id(52), BigDecimal("-20"), false, null, null, 3),
                    player.copy(id = id(52), name = "Elena", account_user_id = null))))
        private val snapshot = StatisticsSnapshot(483, 19, 1, 2, 60, 2,
            listOf(GameStatistic(id(11), "Ticket to Ride: Europa", 90), GameStatistic(id(12), "Jaipur", 71),
                GameStatistic(game.id, game.name, 25)), player, 1, 1,
            (5..10).mapIndexed { index, month -> MonthStatistic(YearMonth.of(2026, month), listOf(26, 16, 14, 38, 54, 19)[index]) })
    }
}
