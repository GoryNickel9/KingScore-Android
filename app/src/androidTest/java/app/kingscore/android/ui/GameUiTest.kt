package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.games.GameRoute
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val repo = FakePlays()

    @Test fun gamePaginationPlayBackAndRefreshPreserveThenResetTheScope() {
        showApp()
        openGame()
        compose.onNodeWithText("2–4 giocatori").assertIsDisplayed()
        compose.onNodeWithText("Riferimento BGA: azul").assertIsDisplayed()
        compose.waitForIdle()
        val beforePage = repo.gameRequests.size
        compose.onNodeWithTag("game-content").performScrollToNode(hasTestTag("more-game-plays"))
        compose.onNodeWithTag("more-game-plays").performClick()
        compose.waitUntil { repo.gameRequests.size == beforePage + 1 }
        assertEquals(HistoryFilters(gameId = game.id), repo.gameRequests.last().first)
        assertNotNull(repo.gameRequests.last().second)
        compose.onNodeWithTag("game-content").performScrollToNode(hasText("2 partite caricate", substring = true))
        compose.onNodeWithText("2 partite caricate", substring = true).assertIsDisplayed()
        capture("phase33-game")
        compose.onNodeWithTag("game-content").performScrollToNode(hasTestTag("play-${second.play.id}"))
        compose.onNodeWithTag("play-${second.play.id}").performClick()
        compose.onNodeWithTag("open-game").performClick()
        compose.onNodeWithTag("game-detail").assertIsDisplayed()
        assertEquals(beforePage + 1, repo.gameRequests.size)
        assertEquals(1, repo.gameCalls)
        compose.onNodeWithContentDescription("Aggiorna").performClick()
        compose.waitUntil { repo.gameRequests.size == beforePage + 2 }
        compose.onNodeWithTag("game-content").performScrollToNode(hasText("1 partite caricate", substring = true))
        compose.onNodeWithText("1 partite caricate", substring = true).assertIsDisplayed()
        assertNull(repo.gameRequests.last().second)
        assertEquals(2, repo.gameCalls)
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
    }

    @Test fun missingAndForbiddenMetadataNeverReadGameHistoryAndRetryIsAvailable() {
        repo.gameRead = { null }
        showApp(); openGame()
        compose.onNodeWithText("Gioco non disponibile").assertIsDisplayed()
        assertTrue(repo.gameRequests.isEmpty())
        repo.gameRead = { throw DataFailure(DataProblem.FORBIDDEN) }
        compose.onNodeWithContentDescription("Aggiorna").performClick()
        compose.onNodeWithText("Non hai il permesso di leggere questi dati.").assertIsDisplayed()
        compose.onNodeWithText("Gioco non disponibile").assertDoesNotExist()
        assertTrue(repo.gameRequests.isEmpty())
        repo.gameRead = { game.copy(min_players = null, max_players = null, bga_id = null) }
        compose.onNodeWithText("Riprova").performClick()
        compose.onNodeWithText("Numero di giocatori non indicato").assertIsDisplayed()
        compose.onNodeWithText("Riferimento BGA:", substring = true).assertDoesNotExist()
        compose.waitUntil { repo.gameRequests.size == 1 }
    }

    @Test fun failedNextPageRetainsCursorAndRowsUntilRetry() {
        showApp(); openGame()
        repo.failMore = true
        compose.onNodeWithTag("game-content").performScrollToNode(hasTestTag("more-game-plays"))
        compose.onNodeWithTag("more-game-plays").performClick()
        compose.onNodeWithTag("game-content").performScrollToNode(hasText("Riprova"))
        compose.onNodeWithText("Riprova").assertIsDisplayed()
        val cursor = repo.gameRequests.last().second
        repo.failMore = false
        compose.onNodeWithText("Riprova").performClick()
        compose.waitForIdle()
        assertEquals(cursor, repo.gameRequests.last().second)
        compose.onNodeWithTag("game-content").performScrollToNode(hasText("2 partite caricate", substring = true))
        compose.onNodeWithText("2 partite caricate", substring = true).assertIsDisplayed()
    }

    @Test fun disconnectAndLogoutRemoveGameAndReconnectReadsFreshMetadata() {
        showApp(); openGame()
        compose.onNodeWithText("2–4 giocatori").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("game-detail").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        val pending = CompletableDeferred<GameRow?>()
        repo.gameRead = { pending.await() }
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        openGame()
        compose.onNodeWithText("Caricamento gioco…").assertIsDisplayed()
        compose.onNodeWithText("2–4 giocatori").assertDoesNotExist()
        pending.complete(game.copy(name = "Azul aggiornato"))
        compose.onNodeWithText("Azul aggiornato").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("game-detail").assertDoesNotExist()
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
    }

    @Test fun existingGameWithEmptyHistoryIsDistinctFromMissingGame() {
        repo.empty = true
        compose.setContent { KingScoreTheme { GameRoute(repo, game.id, {}, {}, {}) } }
        compose.onNodeWithTag("game-content").performScrollToNode(hasText("Nessuna partita registrata per questo gioco."))
        compose.onNodeWithText("Nessuna partita registrata per questo gioco.").assertIsDisplayed()
        compose.onNodeWithText("Gioco non disponibile").assertDoesNotExist()
        assertEquals(HistoryFilters(gameId = game.id), repo.gameRequests.single().first)
    }

    private fun showApp() {
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
            playsFactory = { repo }, onAccessFailure = { access.value = AccessState.Blocked(it) }) } }
    }
    private fun openGame() {
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${first.play.id}"))
        compose.onNodeWithTag("recent-${first.play.id}").performClick()
        compose.onNodeWithTag("open-game").performClick()
        compose.onNodeWithTag("game-detail").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private class FakePlays : PlaysRepository {
        override suspend fun player(id: UUID): PlayerRow? = error("Unused player detail")
        var gameRead: suspend () -> GameRow? = { game }
        var gameCalls = 0
        var failMore = false
        var empty = false
        val gameRequests = mutableListOf<Pair<HistoryFilters, PlayCursor?>>()
        override suspend fun game(id: UUID): GameRow? { assertEquals(game.id, id); gameCalls++; return gameRead() }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            if (filters.gameId == null) return PlayPage(listOf(first), null)
            assertEquals(game.id, filters.gameId)
            gameRequests += filters to after
            if (empty) return PlayPage(emptyList(), null)
            if (after != null && failMore) throw DataFailure(DataProblem.INVALID_RESPONSE)
            return if (after == null) PlayPage(listOf(first), PlayCursor(first.play.played_at, first.play.id))
                else PlayPage(listOf(second), null)
        }
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun detail(id: UUID) = if (id == first.play.id) first else second
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    companion object {
        private val account = Account(UUID(0, 99), "Marta", "test@example.com", false)
        private val game = GameRow(UUID(0, 10), "Azul", "azul", null, 2, 4, null)
        private val first = PlayDetail(PlayRow(UUID(0, 1), null, game.id, LocalDate.parse("2026-10-13"),
            null, "Finale deciso all'ultimo turno.", 45, "offline", null, null, true, true), game, emptyList())
        private val second = first.copy(play = first.play.copy(id = UUID(0, 2), played_at = LocalDate.parse("2026-10-12"), source = "online"))
    }
}
