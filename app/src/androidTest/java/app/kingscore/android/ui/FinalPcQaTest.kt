package app.kingscore.android.ui

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.DebugRecreationActivity
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FinalPcQaTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val repo = FixturePlays()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private lateinit var scenario: ActivityScenario<DebugRecreationActivity>
    @Volatile private var keyboardVisible = false
    private var previousFontScale: String? = null

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }

    private fun launch(scale: Float = 1f) {
        if (scale != 1f) {
            previousFontScale = shell("settings get system font_scale")
            check(previousFontScale == "null" || previousFontScale?.toFloatOrNull() != null)
            // Dialogs have a separate window owner: exercise the actual system font scale too.
            shell("settings put system font_scale $scale")
            compose.waitUntil(10_000) {
                InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale == scale
            }
        }
        DebugRecreationActivity.content = { KingScoreTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                val ime = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { keyboardVisible = ime > 0 }
                AppContent(access.value, { _, _ -> }, {}, { access.value = AccessState.SignedOut() }, {},
                    playsFactory = { repo }, onAccessFailure = { access.value = AccessState.Blocked(it) })
            }
        } }
        scenario = ActivityScenario.launch(DebugRecreationActivity::class.java)
        if (scale != 1f) scenario.onActivity { assertEquals(scale, it.resources.configuration.fontScale, 0.01f) }
        compose.onNodeWithTag("authenticated-shell").assertIsDisplayed()
    }
    @After fun close() {
        try { if (::scenario.isInitialized) scenario.close() }
        finally {
            DebugRecreationActivity.content = null
            previousFontScale?.let {
                shell(if (it == "null") "settings delete system font_scale" else "settings put system font_scale ${it.toFloat()}")
                val expected = it.toFloatOrNull() ?: 1f
                compose.waitUntil(10_000) {
                    InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale == expected
                }
            }
        }
    }

    @Test fun largeHistoryMeasuresPaginationMemoryAndScrollWithoutDuplicateFetches() {
        launch()
        compose.onNodeWithText("Partite").performClick()
        val samples = mutableListOf<Double>()
        repeat(5) {
            val start = SystemClock.elapsedRealtimeNanos()
            val refresh = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Aggiorna" }
            compose.runOnIdle { assertTrue(refresh.action()) }
            compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${id(1)}"))
            compose.onNodeWithTag("play-${id(1)}").assertIsDisplayed()
            samples += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        val before = memory()
        repeat(23) { page ->
            // The unified search/filter header and the updated-at row precede the cards.
            compose.onNodeWithTag("history-content").performScrollToIndex((page + 1) * PAGE_SIZE + HISTORY_HEADERS)
            compose.onNodeWithTag("more-plays").assertIsDisplayed()
            compose.onNodeWithTag("more-plays").performClick()
        }
        compose.onNodeWithTag("history-content").performScrollToIndex(1199 + HISTORY_HEADERS)
        compose.onNodeWithTag("play-${id(1200)}").assertIsDisplayed()
        assertEquals(30, repo.historyCalls.size) // Home + initial history + five refreshes + 23 more pages.
        assertTrue(repo.pageSizes.all { it == 50 })
        assertEquals((51..1151 step 50).map { id(it - 1) }, repo.historyCalls.mapNotNull { it?.id })
        compose.onNodeWithTag("history-content").performScrollToIndex(0)
        compose.onNodeWithText("1200 partite caricate", substring = true).assertExists()
        val after = memory()
        val frames = mutableListOf<Long>()
        val worker = HandlerThread("pc-qa-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(frames) { frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
        }
        try {
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
            val bounds = compose.onNodeWithTag("history-content").fetchSemanticsNode().boundsInRoot
            val x = bounds.center.x.toInt(); val start = (bounds.bottom - bounds.height * 0.1f).toInt()
            val end = (bounds.top + bounds.height * 0.2f).toInt()
            repeat(12) {
                val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input swipe $x $start $x $end 350")
                ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
            }
            compose.waitForIdle()
        } finally {
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            worker.quitSafely(); worker.join()
        }
        assertEquals(30, repo.historyCalls.size)
        val measured = synchronized(frames) { frames.toList() }
        assertTrue("No scroll frames collected", measured.isNotEmpty())
        write("history-performance", buildJsonObject {
            put("fixtureRows", 1200); put("pageSize", 50); put("historyRequests", repo.historyCalls.size)
            put("refreshDriverMs", JsonArray(samples.map(::JsonPrimitive)))
            put("pssBeforeKiB", before.first); put("pssAfterKiB", after.first)
            put("javaHeapBeforeBytes", before.second); put("javaHeapAfterBytes", after.second)
            put("scrollFrameNs", JsonArray(measured.map(::JsonPrimitive)))
            put("scrollInput", "Android input swipe, real-time 350 ms, 12 gestures")
            put("syntheticRepository", true); put("releaseBuild", false); put("networkLatencyIncluded", false)
        })
    }

    @Test fun largeFontProfileKeepsLogoutReachableAndAccountControlsReadOnly() {
        launch(1.8f)
        compose.onNodeWithText("Profilo").performClick()
        compose.onNodeWithTag("sign-out").performScrollTo().assertIsDisplayed().assertHasClickAction()
        val bounds = compose.onNodeWithTag("sign-out").fetchSemanticsNode().boundsInRoot
        var density = 1f
        scenario.onActivity { density = it.resources.displayMetrics.density }
        assertTrue(bounds.height / density >= 48f)
        capture("large-font-profile", "authenticated-shell")
        compose.onNodeWithText("Cambia password").assertDoesNotExist()
        compose.onNodeWithText("Cambia email").assertDoesNotExist()
        compose.onNodeWithTag("sign-out").performClick()
        compose.onNodeWithTag("sign-in").assertExists()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
    }

    @Test fun largeFontCommentWithVisibleKeyboardKeepsSaveAndCancelReachable() {
        launch(1.8f)
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${id(1)}"))
        compose.onNodeWithTag("recent-${id(1)}").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("add-comment"))
        compose.onNodeWithTag("add-comment").performClick()
        compose.onNodeWithTag("comment-input").performClick().performTextInput("Commento accessibile 😀")
        compose.waitUntil(10_000) { keyboardVisible }
        compose.onNodeWithTag("comment-input").assertIsFocused()
        assertTrue(compose.onNodeWithTag("comment-input").fetchSemanticsNode().config.contains(SemanticsProperties.EditableText))
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        capture("large-font-keyboard", "comment-dialog")
        compose.onNodeWithTag("cancel-comment").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(0, repo.writes)
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
    }

    private fun memory(): Pair<Int, Long> {
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return info.totalPss to (runtime.totalMemory() - runtime.freeMemory())
    }
    private fun write(name: String, result: JsonObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "pc-qa-$name.json").writeText(result.toString())
    }
    private fun capture(name: String, tag: String) {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "pc-qa-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private class FixturePlays : PlaysRepository {
        val rows = (1..1200).map { n ->
            PlayDetail(PlayRow(id(n), null, game.id, LocalDate.parse("2026-10-13").minusDays(n.toLong()), null,
                "Partita sintetica $n", 45, "offline", null, Instant.EPOCH, false, null), game, emptyList())
        }
        val historyCalls = mutableListOf<PlayCursor?>(); val pageSizes = mutableListOf<Int>(); var writes = 0
        override val commentWrites = object : CommentWrites {
            override suspend fun create(command: CreateComment): PlayCommentRow { writes++; return PlayCommentRow(id(9001), command.play_id, account.id, account.name, command.content, Instant.EPOCH, Instant.EPOCH) }
            override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow = error("unused")
            override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow = error("unused")
        }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            historyCalls += after
            val offset = after?.id?.leastSignificantBits?.toInt() ?: 0
            val page = rows.drop(offset).take(PAGE_SIZE); pageSizes += page.size
            return PlayPage(page, page.lastOrNull()?.takeIf { offset + page.size < rows.size }?.let { PlayCursor(it.play.played_at, it.play.id) })
        }
        override suspend fun detail(id: UUID) = rows.first { it.play.id == id }
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        override suspend fun filterOptions() = HistoryOptions(listOf(game), emptyList())
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID) = game
    }
    companion object {
        private const val HISTORY_HEADERS = 2
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = Account(id(5000), "Nome giocatore lungo per il collaudo", "account.locale@example.invalid", false)
        private val game = GameRow(id(6000), "Azul", null, null, 2, 4, Instant.EPOCH)
    }
}
