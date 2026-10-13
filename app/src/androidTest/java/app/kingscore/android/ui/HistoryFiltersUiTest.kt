package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.plays.HistoryFiltersDialog
import app.kingscore.android.feature.plays.HistoryState
import app.kingscore.android.feature.plays.HistoryRoute
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryFiltersUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val repo = FakePlays()

    @Test fun searchCombinedFiltersPaginationDetailBackAndResetKeepTheAppliedScope() {
        showApp()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("history-query").performTextReplacement("Finale")
        compose.onNodeWithTag("history-query").performImeAction()
        assertEquals("Finale", repo.requests.last().first.query)
        action("Dal vivo")
        action("Filtri")
        compose.onNodeWithText("Gioco: Tutti").performClick()
        compose.onNodeWithTag("history-catalog").performScrollToNode(hasTestTag("history-option-${game.id}"))
        compose.onNodeWithTag("history-option-${game.id}").performClick()
        compose.onNodeWithText("Partecipante: Tutti").performClick()
        compose.onNodeWithTag("history-catalog").performScrollToNode(hasTestTag("history-option-${player.id}"))
        compose.onNodeWithTag("history-option-${player.id}").performClick()
        compose.onNodeWithTag("history-from").performScrollTo().performTextReplacement("01/10/2026")
        compose.onNodeWithTag("history-to").performScrollTo().performTextReplacement("13/10/2026")
        compose.onNodeWithTag("history-to").performImeAction()
        capture("phase32-filter-dialog", "history-filters-dialog")
        compose.onNodeWithText("Applica filtri").performClick()
        val filters = repo.requests.last().first
        assertEquals(HistoryFilters("Finale", PlaySource.IRL, game.id, player.id,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-13")), filters)
        compose.onNodeWithTag("history-content").performScrollToNode(hasText("2 partecipanti"))
        compose.onNodeWithText("2 partecipanti").assertIsDisplayed()
        capture("phase32-filtered-history")
        action("Carica altre partite")
        assertEquals(filters, repo.requests.last().first)
        assertNotNull(repo.requests.last().second)
        val beforeDetail = repo.requests.size
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${first.play.id}"))
        compose.onNodeWithTag("play-${first.play.id}").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Torna allo storico").performClick()
        compose.onNodeWithTag("plays-history").assertIsDisplayed()
        assertEquals(beforeDetail, repo.requests.size)
        action("Rimuovi filtri")
        assertEquals(HistoryFilters(), repo.requests.last().first)
        compose.onNodeWithTag("active-history-filters").assertDoesNotExist()
        assertEquals(1, repo.optionsCalls)
    }

    @Test fun invalidDatesAndCancellingDraftNeverSendAHistoryRequest() {
        showApp()
        compose.onNodeWithText("Partite").performClick()
        compose.waitForIdle()
        val before = repo.requests.size
        action("Filtri")
        compose.onNodeWithTag("history-from").performScrollTo().performTextReplacement("31/02/2026")
        compose.onNodeWithText("Applica filtri").assertIsNotEnabled()
        compose.onNodeWithTag("history-from").performTextReplacement("13/10/2026")
        compose.onNodeWithTag("history-to").performScrollTo().performTextReplacement("01/10/2026")
        compose.onNodeWithText("Applica filtri").assertIsNotEnabled()
        compose.onNodeWithTag("history-to").performImeAction()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(before, repo.requests.size)
        assertEquals(0, repo.optionsCalls)
        compose.onNodeWithTag("history-query").performTextReplacement("x".repeat(MAX_HISTORY_QUERY + 1))
        compose.onNodeWithText("Cerca").assertIsNotEnabled()
        assertEquals(before, repo.requests.size)
    }

    @Test fun offlineClosesFilterCatalogAndReconnectOrAccountSwitchStartsUnfiltered() {
        showApp()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("history-query").performTextReplacement("Finale")
        compose.onNodeWithTag("history-query").performImeAction()
        action("Filtri")
        compose.onNodeWithText("Partecipante: Tutti").performClick()
        compose.onNodeWithTag("history-catalog").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("history-filters-dialog").assertDoesNotExist()
        compose.onNodeWithTag("history-query").assertDoesNotExist()
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("active-history-filters").assertDoesNotExist()
        assertEquals(HistoryFilters(), repo.requests.last().first)
        compose.runOnIdle { access.value = AccessState.Connected(account.copy(id = id(999))) }
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("active-history-filters").assertDoesNotExist()
    }

    @Test fun restoredOpenSelectorReloadsCatalogWhenViewModelOptionsWereCleared() {
        val restoration = StateRestorationTester(compose)
        val state = mutableStateOf(HistoryState())
        var calls = 0
        restoration.setContent { KingScoreTheme { HistoryFiltersDialog(state.value,
            { calls++; state.value = state.value.copy(options = HistoryOptions(listOf(game), listOf(player))) }, {}, {}) } }
        compose.onNodeWithText("Gioco: Tutti").performClick()
        compose.onNodeWithTag("history-option-${game.id}").assertIsDisplayed()
        assertEquals(1, calls)
        compose.runOnIdle { state.value = state.value.copy(options = null) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-option-${game.id}").assertIsDisplayed()
        assertEquals(2, calls)
    }

    @Test fun restoredAppliedFiltersRefetchWithSameScopeAfterViewModelStoreIsCleared() {
        val restoration = StateRestorationTester(compose)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        restoration.setContent { KingScoreTheme { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            HistoryRoute(repo, {}, {})
        } } }
        compose.onNodeWithTag("history-query").performTextReplacement("Finale")
        compose.onNodeWithTag("history-query").performImeAction()
        action("Dal vivo")
        action("Filtri")
        compose.onNodeWithTag("history-to").performScrollTo().performTextReplacement("13/10/2026")
        compose.onNodeWithTag("history-to").performImeAction()
        compose.onNodeWithText("Applica filtri").performClick()
        compose.waitForIdle()
        val expected = repo.requests.last().first
        assertEquals("Finale", expected.query)
        assertEquals(PlaySource.IRL, expected.source)
        assertEquals(LocalDate.parse("2026-10-13"), expected.to)
        val before = repo.requests.size
        compose.runOnIdle { owner.viewModelStore.clear() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("active-history-filters").assertExists()
        assertTrue(repo.requests.size > before)
        assertTrue(repo.requests.drop(before).all { it.first == expected && it.second == null })
        compose.runOnIdle { owner.viewModelStore.clear() }
    }

    private fun action(text: String) {
        compose.onNodeWithTag("history-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
    private fun showApp() { compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
        playsFactory = { repo }, onAccessFailure = { access.value = AccessState.Blocked(it) }) } } }
    private fun capture(name: String, tag: String? = null) {
        compose.waitForIdle()
        val bitmap = (tag?.let { compose.onNodeWithTag(it) } ?: compose.onRoot()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png").outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private class FakePlays : PlaysRepository {
        override suspend fun player(id: UUID) = player
        override suspend fun game(id: UUID) = game
        val requests = mutableListOf<Pair<HistoryFilters, PlayCursor?>>()
        var optionsCalls = 0
        override suspend fun filterOptions(): HistoryOptions { optionsCalls++; return HistoryOptions(listOf(game), listOf(player)) }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            requests += filters to after
            val found = listOf(first, second, third).filter { item ->
                (filters.query.isBlank() || item.game?.name?.contains(filters.query, true) == true || item.play.notes?.contains(filters.query, true) == true) &&
                (filters.source == null || filters.source.wireValue == item.play.source) &&
                (filters.gameId == null || filters.gameId == item.play.game_id) &&
                (filters.playerId == null || item.scores.any { it.score.player_id == filters.playerId }) &&
                (filters.from == null || item.play.played_at >= filters.from) && (filters.to == null || item.play.played_at <= filters.to) &&
                (after == null || item.play.played_at < after.date || item.play.played_at == after.date && item.play.id.toString() < after.id.toString())
            }
            return PlayPage(found.take(1), found.firstOrNull()?.takeIf { found.size > 1 }?.let { PlayCursor(it.play.played_at, it.play.id) })
        }
        override suspend fun detail(id: UUID) = first
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = Account(id(99), "Marta", "test@example.com", false)
        private val game = GameRow(id(10), "Azul", null, null, 2, 4, null)
        private val player = PlayerRow(id(12), null, "Marta", false, null, null, null, Instant.EPOCH, null, account.id)
        private val first = PlayDetail(PlayRow(id(3), null, game.id, LocalDate.parse("2026-10-13"), null, "Finale combattuto", null,
            "offline", null, null, false, null), game, listOf(
            ScoreWithPlayer(PlayScoreRow(id(11), id(3), player.id, null, true, null, null, 1), player),
            ScoreWithPlayer(PlayScoreRow(id(13), id(3), id(14), null, false, null, null, 2), null)))
        private val second = first.copy(play = first.play.copy(id = id(2), played_at = LocalDate.parse("2026-10-01")))
        private val third = first.copy(play = first.play.copy(id = id(1), played_at = LocalDate.parse("2026-09-01"), source = "online"))
    }
}
