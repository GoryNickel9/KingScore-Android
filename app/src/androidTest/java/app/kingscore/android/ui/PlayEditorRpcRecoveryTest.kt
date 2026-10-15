package app.kingscore.android.ui

import android.os.Process
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.DebugRecreationActivity
import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.local.EncryptedPendingPlayStore
import app.kingscore.android.data.remote.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kingscore.contracts.v1.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.Assert.*

/** Real clients and encrypted journal, connected only to the PC's synthetic loopback fixture. */
class PlayEditorRpcRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val registry get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private lateinit var base: String
    private lateinit var proof: String
    private lateinit var mode: String
    private lateinit var bootstrap: JsonObject
    private lateinit var store: EncryptedPendingPlayStore
    private lateinit var journals: AccountPlayJournals
    private lateinit var actor: UUID
    private lateinit var second: UUID
    private lateinit var game: UUID
    private lateinit var player: UUID
    private lateinit var access: androidx.compose.runtime.MutableState<AccessState>
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val clients = mutableMapOf<UUID, SupabaseClient>()
    private val auth = mutableMapOf<UUID, SupabaseAuthRepository>()
    private val accounts = mutableMapOf<UUID, Account>()
    private val writers = mutableListOf<SupabasePlayWrites>()
    private val control = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).build()
    private var scenario: ActivityScenario<DebugRecreationActivity>? = null
    private fun fixture(path: String, value: JsonObject? = null): JsonObject {
        val request = Request.Builder().url("$base/__fixture/$path").header("X-Local-Proof", proof).header("Connection", "close")
            .apply { if (value != null) post(value.toString().toRequestBody("application/json".toMediaType())) }.build()
        return control.newCall(request).execute().use {
            check(it.code == 200) { "Local fixture request failed" }
            Json.parseToJsonElement(checkNotNull(it.body).string()).jsonObject
        }
    }
    private fun writer(id: UUID) = SupabasePlayWrites(auth.getValue(id), network, id, enabled = true,
        journal = journals.forAccount(id)).also { writers += it }
    @Before fun start() = runBlocking {
        mode = arguments.getString("mode").orEmpty()
        Assume.assumeTrue("PC integration driver required", mode in setOf("editor-pending", "editor-confirmed", "editor-final"))
        val port = arguments.getString("localPort")?.toIntOrNull()
        require(port != null && port in 1025..65535)
        proof = checkNotNull(arguments.getString("localProof")); require(proof.matches(Regex("[0-9a-f]{48}")))
        base = "http://127.0.0.1:$port"
        bootstrap = fixture("bootstrap")
        fun id(key: String) = UUID.fromString(bootstrap.getValue(key).jsonPrimitive.content)
        actor = id("actor"); second = id("second"); game = id("game"); player = id("player")
        store = EncryptedPendingPlayStore(registry.targetContext); journals = AccountPlayJournals(store)
        for (subject in listOf(actor, second)) {
            val token = bootstrap.getValue("tokens").jsonObject.getValue(subject.toString()).jsonPrimitive.content
            val client = createSupabaseClient(base, "local-test-public-key") {
                httpEngine = OkHttp.create(); defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    autoLoadFromStorage = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false
                    sessionManager = MemorySessionManager(UserSession(token, subject.toString(), expiresIn = 3600, tokenType = "bearer"))
                    codeVerifierCache = MemoryCodeVerifierCache()
                }
            }
            clients[subject] = client
            val repository = SupabaseAuthRepository(client); auth[subject] = repository
            accounts[subject] = checkNotNull(repository.restoreVerifiedAccount()).also { check(it.id == subject && it.isAdmin) }
        }
        if (mode == "editor-pending") { store.save(actor, null); store.save(second, null) }
        access = mutableStateOf(AccessState.Connected(accounts.getValue(actor)))
        DebugRecreationActivity.content = { KingScoreTheme {
            AppContent(access.value, { _, _ -> }, {}, { access.value = AccessState.SignedOut() }, {},
                playsFactory = { id -> SupabasePlaysRepository(SupabaseReader(auth.getValue(id), network), id) },
                onAccessFailure = { access.value = AccessState.Blocked(it) }, playWritesFactory = ::writer, editorNetwork = network)
        } }
        scenario = ActivityScenario.launch(DebugRecreationActivity::class.java)
    }
    @After fun stop() {
        try { scenario?.close() } finally {
            DebugRecreationActivity.content = null
            writers.forEach { it.close() }
            runBlocking { clients.values.forEach { it.close() } }
            control.dispatcher.cancelAll(); control.connectionPool.evictAll(); control.dispatcher.executorService.shutdown()
        }
    }
    private fun newEditor() {
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("create-play").performClick()
        waitEditor()
    }
    private fun waitEditor() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("editor-game").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("editor-read-error").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("editor-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("editor-read-error").assertDoesNotExist()
        compose.onNodeWithTag("editor-error").assertDoesNotExist()
    }
    private fun waitDetail() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("detail-content").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(tag: String) {
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick(); compose.waitForIdle()
    }
    private fun replace(tag: String, text: String) {
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(text); compose.waitForIdle()
    }
    private fun confirmation(message: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("editor-success").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("editor-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(message).assertIsDisplayed()
    }
    private fun signal(stage: String, requestId: UUID? = null) {
        fixture("signal", buildJsonObject {
            put("stage", stage); put("pid", Process.myPid())
            requestId?.let { put("requestId", it.toString()) }
        })
    }
    private fun waitForPcStop() {
        // The PC verifies this live PID and stops it while the encrypted record is present.
        Thread.sleep(60_000)
        fail("The PC driver did not stop the signalled process")
    }
    @Test fun editorUsesHttpAndEncryptedJournalAcrossRealProcessDeaths() = runBlocking {
        when (mode) {
            "editor-pending" -> {
                newEditor()
                click("editor-game"); compose.onNodeWithTag("editor-option-$game").performClick()
                click("editor-add-player"); compose.onNodeWithTag("editor-option-$player").performClick()
                replace("editor-duration", "30"); click("editor-winner-$player")
                replace("editor-score-$player", "9007199254740993.125")
                fixture("fault", buildJsonObject { put("rpc", "create_play_v1") })
                click("editor-save")
                compose.waitUntil(10_000) { fixture("state").getValue("plays").jsonPrimitive.int == 1 }
                val pending = checkNotNull(store.load(actor))
                assertNull(pending.confirmedResponse)
                assertTrue(pending.body.contains("9007199254740993.125"))
                val cipher = File(registry.targetContext.noBackupFilesDir, "pending-plays-v1/$actor.bin").readBytes()
                assertFalse(cipher.toString(Charsets.UTF_8).contains("9007199254740993.125"))
                compose.onNodeWithTag("editor-save").assertIsNotEnabled()
                assertTrue(fixture("state").getValue("awaitingHttp").jsonPrimitive.boolean)
                signal("pending", UUID.fromString(pending.requestId))
                waitForPcStop()
            }
            "editor-confirmed" -> {
                val old = checkNotNull(store.load(actor)); assertNull(old.confirmedResponse)
                val other = writer(second)
                assertNull(other.restorePending())
                try { other.retryPending(); fail("Other account must not replay this request") }
                catch (failure: PlayWriteFailure) { assertEquals(PlayWriteProblem.INVALID_INPUT, failure.problem) }
                newEditor(); compose.onNodeWithTag("editor-pending").assertIsDisplayed()
                assertEquals(old.requestId, writers.last().pendingRequest!!.requestId.toString())
                compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag("editor-save"))
                compose.onNodeWithTag("editor-save").assertIsNotEnabled()
                click("editor-recover")
                confirmation("Operazione precedente recuperata.")
                val confirmed = checkNotNull(store.load(actor))
                assertEquals(old.body, confirmed.body); assertEquals(old.requestId, confirmed.requestId)
                assertNotNull(confirmed.confirmedResponse)
                assertEquals(2, fixture("state").getValue("mutations").jsonPrimitive.int)
                signal("confirmed", UUID.fromString(old.requestId)); waitForPcStop()
            }
            "editor-final" -> {
                val confirmed = checkNotNull(store.load(actor)); assertNotNull(confirmed.confirmedResponse)
                newEditor(); compose.onNodeWithTag("editor-restored").assertIsDisplayed()
                compose.onNodeWithTag("editor-pending").assertDoesNotExist()
                val saved = writer(actor).retryPending() as PlayWriteResult.Saved
                assertTrue(saved.restored); assertEquals(2, fixture("state").getValue("mutations").jsonPrimitive.int)
                compose.onNodeWithContentDescription("Torna indietro").performClick()
                compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${saved.value.play_id}"))
                compose.onNodeWithTag("play-${saved.value.play_id}").performClick()
                waitDetail()
                compose.onNodeWithTag("manage-play").performClick()
                waitEditor()
                replace("editor-notes", "Local RPC update")
                replace("editor-score-$player", "-0.25")
                click("editor-save"); confirmation("Partita salvata."); click("editor-done")
                waitDetail()
                compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Local RPC update"))
                compose.onNodeWithText("Local RPC update").assertIsDisplayed()
                compose.onNodeWithTag("manage-play").performClick()
                waitEditor()
                click("editor-delete"); compose.onNodeWithTag("editor-confirm-delete").performClick()
                confirmation("Partita eliminata."); click("editor-done")
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Partita non trovata").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Partita non trovata").assertIsDisplayed()
                val state = fixture("state"); assertEquals(0, state.getValue("plays").jsonPrimitive.int)
                assertEquals(4, state.getValue("mutations").jsonPrimitive.int)
                signal("final", UUID.fromString(confirmed.requestId))
            }
        }
    }
}
