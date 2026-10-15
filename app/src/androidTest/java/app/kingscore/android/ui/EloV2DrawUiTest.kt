package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import org.junit.Rule
import org.junit.Test

class EloV2DrawUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = Account(id(99), "Anna", "fixture@example.com", false)
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private var match = fixture()

    @Test fun sharedFirstsAreDrawsAcrossHomeHistoryAndDetailAndDisappearWithoutAccess() {
        show()
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasText("Pareggio: Anna, Bruno"))
        compose.onNodeWithText("Pareggio: Anna, Bruno").assertIsDisplayed()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("history-score-${id(10)}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Pareggio")))
        compose.onNodeWithTag("history-score-${id(12)}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Carlo: 6")))
        capture("history-draw")
        openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("score-${id(10)}"))
        compose.onNodeWithTag("score-${id(10)}").assert(hasAnyDescendant(hasText("Pareggio")))
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("score-${id(12)}"))
        compose.onNodeWithTag("score-${id(12)}").assert(hasAnyDescendant(hasText("Carlo")))
        compose.onNodeWithText("Vincitore").assertDoesNotExist()
        capture("detail-draw")
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.onNodeWithText("Pareggio").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
    }

    @Test fun totalTieKeepsEveryPositionAndDrawLabelReachableAtFontTwo() {
        match = fixture().let { d -> d.copy(scores = d.scores.map { it.copy(score = it.score.copy(winner = true, position = 1)) }) }
        show(large = true)
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${match.play.id}"))
        compose.onNodeWithTag("history-score-${id(12)}", useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("Pareggio")))
        capture("history-draw-font2")
        openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("score-${id(12)}"))
        compose.onNodeWithTag("score-${id(12)}").assert(hasAnyDescendant(hasText("Pareggio")))
        compose.onNodeWithTag("score-${id(12)}").assert(hasAnyDescendant(hasContentDescription("Posizione: 1")))
        capture("detail-draw-font2")
    }

    @Test fun cooperativeGroupWinStaysAVictoryEvenWithAStaleIndividualFlag() {
        match = fixture().let { it.copy(play = it.play.copy(is_cooperative = true, team_won = true)) }
        show()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithText("Vittoria della squadra").assertExists()
        compose.onNodeWithText("Pareggio").assertDoesNotExist()
        openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("score-${id(12)}"))
        compose.onNodeWithTag("score-${id(12)}").assert(hasAnyDescendant(hasText("Vincitore")))
        compose.onNodeWithText("Pareggio").assertDoesNotExist()
    }

    @Test fun cooperativeLossNeverShowsDrawOrWinnerBadgesFromStaleIndividualFlags() {
        match = fixture().let { it.copy(play = it.play.copy(is_cooperative = true, team_won = false)) }
        show()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithText("Sconfitta della squadra").assertExists()
        compose.onNodeWithContentDescription("Vincitore", useUnmergedTree = true).assertDoesNotExist()
        openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("score-${id(10)}"))
        compose.onNodeWithText("Vincitore").assertDoesNotExist()
        compose.onNodeWithText("Pareggio").assertDoesNotExist()
    }

    private fun openDetail() {
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${match.play.id}"))
        compose.onNodeWithTag("play-${match.play.id}").performClick()
        compose.onNodeWithTag("play-detail").assertExists()
    }

    private fun show(large: Boolean = false) {
        val repository = object : PlaysRepository {
            override suspend fun player(id: UUID) = match.scores.firstOrNull { it.score.player_id == id }?.player
            override suspend fun game(id: UUID) = match.game
            override suspend fun filterOptions() = HistoryOptions()
            override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(match), null)
            override suspend fun detail(id: UUID) = match
            override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        compose.setContent { KingScoreTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else density.fontScale)) {
                AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { repository },
                    onAccessFailure = { access.value = AccessState.Blocked(it) })
            }
        } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "ui-061-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun fixture(): PlayDetail {
        val play = PlayRow(id(1), id(99), id(3), LocalDate.of(2026,10,15), null, null, 30, "online", null, null, false, null)
        val scores = listOf("Anna", "Bruno", "Carlo").mapIndexed { i, name -> ScoreWithPlayer(
            PlayScoreRow(id(10+i), play.id, id(20+i), BigDecimal(if (i == 2) 6 else 3), i < 2, null, null, if (i < 2) 1 else 3),
            PlayerRow(id(20+i), id(99), name, false, null, null, null, Instant.EPOCH, null, null)) }
        return PlayDetail(play, GameRow(id(3), "Pili Pili", null, null, 2, 8, null), scores)
    }
    private fun id(value: Int) = UUID(0, value.toLong())
}
