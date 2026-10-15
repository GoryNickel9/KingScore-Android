package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.data.repository.HistoryFilters
import app.kingscore.android.feature.plays.HistoryFiltersDialog
import app.kingscore.android.feature.plays.HistoryState
import java.io.File
import java.util.UUID
import kingscore.contracts.v1.PlaySource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CatalogSelectionSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchStaysVisibleAfterScrollingAndMatchesTheOriginalNameBehindAnAlias() {
        val shown = mutableStateOf(true)
        val target = CatalogOption(UUID(0, 120), "Alias visibile", subtitle = "Originale ricercabile",
            searchText = "Originale ricercabile Alias visibile", player = true)
        val options = (1L..119L).map { CatalogOption(UUID(0, it), "Partecipante $it", player = true) } + target
        var selected: UUID? = null
        compose.setContent { KingScoreTheme {
            if (shown.value) CatalogSelectionSheet("Scegli partecipante", options,
                onDismiss = { shown.value = false }, onChoose = { selected = it; shown.value = false },
                selectedId = target.id, tagPrefix = "test", modifier = Modifier.testTag("test-sheet"))
        } }
        capture("participant", "test-sheet")
        compose.onNodeWithTag("test-catalog").performScrollToNode(hasTestTag("test-option-${target.id}"))
        compose.onNodeWithTag("test-catalog-search").assertIsDisplayed().performTextReplacement("Originale ricercabile")
        compose.onNodeWithTag("test-option-${target.id}").assertIsSelected().performClick()
        compose.onNodeWithTag("test-sheet").assertDoesNotExist()
        assertEquals(target.id, selected)
    }

    @Test fun duplicatedNamesKeepTheirIdentityAndCloseRemainsReachableWithLargeText() {
        val options = listOf(CatalogOption(UUID(0, 1), "Alex", player = true), CatalogOption(UUID(0, 2), "Alex", player = true))
        val shown = mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            KingScoreTheme { if (shown.value) CatalogSelectionSheet("Scegli partecipante", options,
                onDismiss = { shown.value = false }, onChoose = {}, tagPrefix = "test", modifier = Modifier.testTag("test-sheet")) }
        } }
        compose.onNodeWithTag("test-catalog").performScrollToNode(hasTestTag("test-option-${options.last().id}"))
        compose.onNodeWithTag("test-option-${options.last().id}").assertIsDisplayed()
        compose.onNodeWithText("Identificativo: ${options.last().id}").assertExists()
        capture("participant-font2", "test-sheet")
        compose.onNodeWithContentDescription("Chiudi Scegli partecipante").assertIsDisplayed().performClick()
        compose.onNodeWithTag("test-catalog-search").assertDoesNotExist()
    }

    @Test fun noMatchExplainsHowToRecoverWithoutShowingUnrelatedOptions() {
        compose.setContent { KingScoreTheme {
            CatalogSelectionSheet("Scegli gioco", listOf(CatalogOption(UUID(0, 1), "Azul")),
                onDismiss = {}, onChoose = {}, tagPrefix = "test", modifier = Modifier.testTag("test-sheet"))
        } }
        capture("game", "test-sheet")
        compose.onNodeWithTag("test-catalog-search").performTextReplacement("Nessuno")
        compose.onNodeWithText("Nessun risultato. Prova un altro nome o alias.").assertIsDisplayed()
        compose.onNodeWithTag("test-option-${UUID(0, 1)}").assertDoesNotExist()
        capture("game-empty", "test-sheet")
    }

    @Test fun filterSourceChangesAreOnlyAppliedByTheExplicitConfirmation() {
        val initial = HistoryFilters(query = "finale", source = PlaySource.IRL, gameId = UUID(0, 8))
        val shown = mutableStateOf(true)
        var applied = initial
        var applyCalls = 0
        compose.setContent { KingScoreTheme { if (shown.value) HistoryFiltersDialog(HistoryState(filters = initial), {},
            { applied = it; applyCalls++; shown.value = false }, { shown.value = false }) } }
        compose.onNodeWithText("Filtri").assertIsDisplayed()
        capture("filters", "history-filters-dialog")
        compose.onNodeWithTag("filter-source-ONLINE").performScrollTo().performClick()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(0, applyCalls)
        assertEquals(initial, applied)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("filter-source-IRL").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("filter-source-ONLINE").performClick()
        compose.onNodeWithText("Applica filtri").performClick()
        assertEquals(1, applyCalls)
        assertEquals(initial.copy(source = PlaySource.ONLINE), applied)
    }

    private fun capture(name: String, tag: String) {
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(target.filesDir, "ui-052-menu-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
