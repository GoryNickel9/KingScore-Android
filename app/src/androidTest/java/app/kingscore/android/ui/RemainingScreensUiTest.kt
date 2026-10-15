package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.players.PlayerScreen
import app.kingscore.android.feature.players.PlayerState
import app.kingscore.android.feature.plays.HistoryScreen
import app.kingscore.android.feature.plays.HistoryState
import app.kingscore.android.feature.plays.PlayHistoryCard
import app.kingscore.android.feature.stats.StatisticsScreen
import app.kingscore.android.feature.stats.StatisticsDetailsState
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kingscore.contracts.v1.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemainingScreensUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historyShowsEveryParticipantAndExactScoresAtLargeFont() {
        val score = "-123456789012345678901234567890.123456789"
        val rows = (1..7).map { n -> ScoreWithPlayer(
            PlayScoreRow(id(30 + n), play.play.id, id(50 + n), if (n == 6) null else BigDecimal(if (n == 7) score else n.toString()),
                n == 1, null, null, n), player.copy(id = id(50 + n), name = "Partecipante $n", account_user_id = null)) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { TestSurface {
                HistoryScreen(HistoryState(items = listOf(play.copy(scores = rows)), loading = false), "", {}, true, {}, {}, {}, {}, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithTag("history-content").performScrollToNode(hasText("Partecipante 7: ${score.replace('.', ',')}", substring = true))
        compose.onNodeWithText("Partecipante 7: ${score.replace('.', ',')}", substring = true).assertExists()
        compose.onNodeWithText("Partecipante 6: punti non registrati", substring = true).assertExists()
        compose.onNodeWithContentDescription("Vincitore", useUnmergedTree = true).assertExists()
        capture("history-font2")
    }

    @Test fun commentBadgeIsAbsentForUnknownAndZeroAndOpensOnlyComments() {
        val count = mutableStateOf<Int?>(null)
        var opened = 0
        var comments = 0
        compose.setContent { TestSurface { Column {
            PlayHistoryCard(play, { opened++ }, count.value, { comments++ })
        } } }
        compose.onNodeWithTag("play-comments-${play.play.id}").assertDoesNotExist()
        compose.runOnIdle { count.value = 0 }
        compose.onNodeWithTag("play-comments-${play.play.id}").assertDoesNotExist()
        compose.runOnIdle { count.value = 1 }
        compose.onNodeWithTag("play-comments-${play.play.id}").performClick()
        assertEquals(1, comments)
        assertEquals(0, opened)
        capture("history-comment-badge")
    }

    @Test fun statisticsNeverShowsPartialTotalsAndKeepsTheCompletedSnapshotDuringRefresh() {
        val state = mutableStateOf(StatisticsDetailsState())
        compose.setContent { TestSurface { StatisticsScreen(state.value, {}) } }
        compose.onNodeWithText("Caricamento statistiche…").assertIsDisplayed()
        compose.onNodeWithText("0 partite").assertDoesNotExist()
        val overview = StatisticsOverview("1",true,listOf(WinRateStatistic(game.id,game.name,4,2,50)),
            listOf(GameStatistics(game.id,game.name,null,483,966,BigDecimal(100),2,BigDecimal(50),BigDecimal(60),2,BigDecimal(30),0)),1,null)
        compose.runOnIdle { state.value = StatisticsDetailsState(overview, loading = false) }
        compose.onNodeWithTag("statistics-game-${game.id}").assertIsDisplayed()
        capture("statistics")
        compose.runOnIdle { state.value = StatisticsDetailsState(overview, loading = true) }
        compose.onNodeWithTag("statistics-game-${game.id}").assertIsDisplayed()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasText("Durata disponibile per 2 di 483 partite"))
        compose.onNodeWithText("Durata disponibile per 2 di 483 partite").assertIsDisplayed()
        compose.onNodeWithText("Aggiorna statistiche").assertDoesNotExist()
    }

    @Test fun playerShowsIdentityAndPaginatedHistoryWithoutInventingGlobalStatistics() {
        compose.setContent { TestSurface {
            PlayerScreen(PlayerState(player, loading = false), HistoryState(items = listOf(play), loading = false,
                commentCounts = mapOf(play.play.id to 1)), player.account_user_id!!, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Silvia").assertIsDisplayed()
        compose.onNodeWithText("Sei tu").assertIsDisplayed()
        compose.onNodeWithText("Partite del giocatore").assertIsDisplayed()
        compose.onNodeWithTag("player-content").performScrollToNode(hasTestTag("play-${play.play.id}"))
        compose.onNodeWithTag("play-comments-${play.play.id}").assertExists()
        compose.onNodeWithText("Win Rate").assertDoesNotExist()
        compose.onNodeWithText("Partite totali").assertDoesNotExist()
        capture("player")
    }

    @Composable
    private fun TestSurface(content: @Composable () -> Unit) {
        KingScoreTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground, content = content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.filesDir, "ui-052-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val player = PlayerRow(id(50), id(99), "Silvia", false, null, null, null, Instant.EPOCH, null, id(1))
        private val game = GameRow(id(10), "Skull King", null, null, 2, 8, null)
        private val play = PlayDetail(PlayRow(id(20), id(99), game.id, LocalDate.parse("2026-10-12"), null, "Altra truffa",
            45, "online", null, null, false, null), game,
            listOf(ScoreWithPlayer(PlayScoreRow(id(70), id(20), player.id, BigDecimal("330"), true, null, null, 1), player)))
        private val snapshot = StatisticsSnapshot(483, 19, 18, 6, 60, 2,
            listOf(GameStatistic(game.id, game.name, 25)), player, 17, 8,
            (5..10).mapIndexed { index, month -> MonthStatistic(YearMonth.of(2026, month), listOf(26, 16, 14, 38, 54, 19)[index]) })
    }
}
