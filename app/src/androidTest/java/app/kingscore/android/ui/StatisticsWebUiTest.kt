package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.stats.*
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatisticsWebUiTest {
    @get:Rule val compose=createComposeRule()
    private val value=mutableStateOf(ready())
    private var refreshes=0
    private fun show(large:Boolean=false) {
        compose.setContent { KingScoreTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(large)2f else density.fontScale)) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    StatisticsScreen(value.value,{ refreshes++ },onToggle={ id -> value.value=value.value.copy(
                        expanded=if(id in value.value.expanded)value.value.expanded-id else value.value.expanded+id) })
                }
            }
        } }
    }
    @Test fun numericGameCardsReplaceWinRatesAndAllStatisticsCharts() {
        show()
        compose.onNodeWithText("Analisi dettagliata delle partite").assertIsDisplayed()
        compose.onNodeWithText("Win Rate per Gioco (Top 10)").assertDoesNotExist()
        compose.onNodeWithText("ELO Generale").assertDoesNotExist()
        compose.onNodeWithText("50%").assertDoesNotExist()
        compose.onNodeWithText("2 vittorie su 4 partecipazioni").assertDoesNotExist()
        compose.onNodeWithText("Partite per mese").assertDoesNotExist()
        capture("statistics-top")
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasTestTag("statistics-game-$game"))
        compose.onNodeWithTag("statistics-game-$game").assert(hasAnyDescendant(hasText("Skull King")))
        compose.onNodeWithText("Durata disponibile per 3 di 10 partite").assertExists()
        capture("statistics-game")
    }
    @Test fun playerExpansionKeepsAveragesAndCoverageWithoutCharts() {
        show()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasTestTag("expand-statistics-$game"))
        compose.onNodeWithTag("expand-statistics-$game").performClick()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasTestTag("statistics-player-$silvia"))
        compose.onNodeWithTag("statistics-player-$silvia").assert(hasAnyDescendant(hasText("Silvia")))
        compose.onNodeWithText("Media su 3 di 4 partecipazioni").assertExists()
        capture("statistics-player")
        compose.onNodeWithTag("statistics-elo-plot-$game").assertDoesNotExist()
        compose.onNodeWithText("Evoluzione ELO nel tempo").assertDoesNotExist()
        compose.onNodeWithText("Scegli giocatori").assertDoesNotExist()
    }
    @Test fun numericOverviewErrorOffersRetryAndThenShowsTheGameCards() {
        value.value=StatisticsDetailsState(loading=false,problem=DataProblem.INVALID_RESPONSE)
        show()
        compose.onNodeWithText("Riprova").performClick();assertEquals(1,refreshes)
        compose.runOnIdle { value.value=ready() }
        compose.onNodeWithTag("statistics-game-$game").assertIsDisplayed()
        compose.onNodeWithText("ELO Generale").assertDoesNotExist()
        compose.onNodeWithTag("statistics-win-rates").assertDoesNotExist()
    }
    @Test fun fontTwoKeepsGameCoverageAndExpansionReachable() {
        show(large=true)
        compose.onNodeWithText("50%").assertDoesNotExist()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasTestTag("statistics-game-$game"))
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasText("Durata disponibile per 3 di 10 partite"))
        compose.onNodeWithText("Durata disponibile per 3 di 10 partite").performScrollTo().assertIsDisplayed()
        capture("statistics-font2")
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasTestTag("expand-statistics-$game"))
        compose.onNodeWithTag("expand-statistics-$game").performClick()
        compose.onNodeWithTag("statistics-content").performScrollToNode(hasText("Media su 3 di 4 partecipazioni"))
        compose.onNodeWithText("Media su 3 di 4 partecipazioni").performScrollTo().assertIsDisplayed()
        capture("statistics-player-font2")
    }
    @Test fun unlinkedPlayerStillSeesNumericGamesWithoutPersonalWinRateOrCharts() {
        val ready=ready()
        value.value=ready.copy(overview=ready.overview!!.copy(linked=false,winRates=emptyList()))
        show()
        compose.onNodeWithTag("statistics-game-$game").assertIsDisplayed()
        compose.onNodeWithTag("statistics-win-rates").assertDoesNotExist()
        compose.onNodeWithTag("statistics-elo-plot-null").assertDoesNotExist()
        compose.onNodeWithText("Profilo giocatore non collegato.").assertDoesNotExist()
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.filesDir,"ui-062-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    companion object {
        private val game=UUID(0,10);private val silvia=UUID(0,20);private val ale=UUID(0,21);private val luca=UUID(0,22)
        private fun ready():StatisticsDetailsState {
            val stats=GameStatistics(game,"Skull King",null,10,30,BigDecimal(3000),27,BigDecimal("111.11"),BigDecimal(90),3,BigDecimal(30),0)
            val detail=GameStatisticsPage("7",stats,listOf(PlayerStatistic(silvia,"Silvia",null,4,BigDecimal(330),3,BigDecimal(110)),
                PlayerStatistic(ale,"Ale",null,4,BigDecimal(120),4,BigDecimal(30)),PlayerStatistic(luca,"Luca",null,3,BigDecimal(-60),3,BigDecimal(-20))),3,null)
            return StatisticsDetailsState(StatisticsOverview("7",true,listOf(WinRateStatistic(game,"Skull King",4,2,50)),listOf(stats),1,null),
                loading=false,games=mapOf(game to GameStatisticsState(detail)))
        }
    }
}
