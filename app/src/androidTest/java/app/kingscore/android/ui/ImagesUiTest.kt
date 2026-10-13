package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.core.images.AccountImages
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import coil3.request.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kingscore.contracts.v1.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImagesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val sessions = Collections.synchronizedList(mutableListOf<AccountImages>())
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val verified = AtomicInteger()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val pink = png(0xFFD946EF.toInt())
    private val blue = png(0xFF38BDF8.toInt())
    @After fun cleanup() { sessions.forEach(AccountImages::close) }

    @Test fun coversAndStorageAvatarsDecodeWithoutCredentialsOrPersistentCaches() {
        val images = session()
        compose.setContent { KingScoreTheme { CompositionLocalProvider(LocalAccountImages provides images) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Azul"); GameCover("https://cdn.example/cover.png", "Azul", 80.dp)
                Text("Marta Rossi"); PlayerAvatar("owner/avatars/marta.png", "Marta Rossi", 72.dp)
            }
        } } }
        ready("Azul"); ready("Marta Rossi")
        assertPink("Azul")
        assertTrue(requests.any { it.url.toString() == "https://project.example/storage/v1/object/public/player-images/owner/avatars/marta.png" })
        assertTrue(requests.all { it.header("Authorization") == null && it.header("apikey") == null && it.header("Cookie") == null })
        assertEquals(requests.size, verified.get())
        assertNull(images.loader.memoryCache)
        assertNull(images.loader.diskCache)
        capture("phase35-images")
    }

    @Test fun failedImageRetriesWithoutActivatingTheContainingCard() {
        var opens = 0
        val calls = AtomicInteger()
        val images = session(transport = Interceptor { chain ->
            response(chain.request(), if (calls.incrementAndGet() == 1) 404 else 200, pink)
        })
        compose.setContent { KingScoreTheme { CompositionLocalProvider(LocalAccountImages provides images) {
            Card(onClick = { opens++ }) { Column(Modifier.padding(24.dp)) { Text("Azul"); GameCover("https://cdn.example/cover.png", "Azul") } }
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry-image-Azul", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry-image-Azul", useUnmergedTree = true).performClick()
        ready("Azul")
        assertEquals(2, calls.get())
        assertEquals(0, opens)
    }

    @Test fun missingAndUnsafeImagesRemainPlaceholdersWithoutAnyFetch() {
        val images = session()
        compose.setContent { KingScoreTheme { CompositionLocalProvider(LocalAccountImages provides images) {
            Column { GameCover("file:///private/image", "Gioco"); PlayerAvatar(null, "Marta Rossi") }
        } } }
        compose.onNodeWithTag("image-placeholder-Gioco").assertIsDisplayed()
        compose.onNodeWithTag("image-placeholder-Marta Rossi").assertIsDisplayed()
        assertTrue(requests.isEmpty())
        assertEquals(0, verified.get())
    }

    @Test fun revokedAccountBlocksShellBeforeAnyImageTransport() {
        val imagesFactory: (UUID, (AuthProblem) -> Unit) -> AccountImages = { _, failure ->
            session(verify = { throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }, failure = failure)
        }
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { repo },
            onAccessFailure = { access.value = AccessState.Blocked(it) }, imagesFactory = imagesFactory) } }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test fun offlineLogoutAndAccountSwitchDisposeImagesAndRejectLateOldPixels() {
        val pending = CountDownLatch(1)
        val calls = AtomicInteger()
        val factory: (UUID, (AuthProblem) -> Unit) -> AccountImages = { _, failure ->
            session(failure = failure, transport = Interceptor { chain ->
                val first = calls.incrementAndGet() == 1
                if (first) pending.await(10, TimeUnit.SECONDS)
                response(chain.request(), 200, if (first) pink else blue)
            })
        }
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { repo },
            onAccessFailure = { access.value = AccessState.Blocked(it) }, imagesFactory = factory) } }
        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.runOnIdle { network.connected.value = false; access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.onNodeWithTag("image-ready-Azul", useUnmergedTree = true).assertDoesNotExist()
        pending.countDown()
        compose.runOnIdle { network.connected.value = true; access.value = AccessState.Connected(account.copy(id = UUID(0, 98))) }
        ready("Azul")
        val pixel = compose.onNodeWithTag("image-ready-Azul", useUnmergedTree = true).captureToImage().toPixelMap()
        val center = pixel[pixel.width / 2, pixel.height / 2]
        assertTrue(center.blue > center.red)
        assertEquals(2, sessions.size)
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
        compose.onNodeWithTag("image-ready-Azul", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun corruptAndOversizedImagesAreOptionalErrorsWhileOnline() = runBlocking<Unit> {
        val tooLarge = session(transport = Interceptor { chain ->
            response(chain.request(), 200, pink).newBuilder().body(object : ResponseBody() {
                override fun contentType() = "image/png".toMediaType()
                override fun contentLength() = 8L * 1024 * 1024 + 1
                override fun source() = Buffer()
            }).build()
        })
        val corrupt = session(transport = Interceptor { chain -> response(chain.request(), 200, "bad image".toByteArray()) })
        for (images in listOf(tooLarge, corrupt)) {
            assertTrue(images.loader.execute(ImageRequest.Builder(context).data("https://cdn.example/image.png").size(64).build()) is ErrorResult)
            assertTrue(access.value is AccessState.Connected)
        }
    }

    private fun ready(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-ready-$name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-ready-$name", useUnmergedTree = true).assertIsDisplayed()
    }
    private fun assertPink(name: String) {
        val pixels = compose.onNodeWithTag("image-ready-$name", useUnmergedTree = true).captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(center.red > 0.7f && center.blue > 0.7f && center.green < 0.5f)
    }
    private fun session(verify: suspend () -> Unit = { verified.incrementAndGet(); Unit },
        failure: (AuthProblem) -> Unit = { access.value = AccessState.Blocked(it) }, transport: Interceptor? = null): AccountImages {
        return AccountImages(context, "https://project.example", network, verify, failure, transport ?: Interceptor { chain ->
            requests += chain.request(); response(chain.request(), 200, pink)
        }).also(sessions::add)
    }
    private fun response(request: Request, status: Int, bytes: ByteArray) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(bytes.toResponseBody("image/png".toMediaType())).build()
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    companion object {
        private val account = Account(UUID(0, 99), "Marta", "test@example.com", false)
        private val game = GameRow(UUID(0, 10), "Azul", null, "https://cdn.example/cover.png", 2, 4, null)
        private val play = PlayDetail(PlayRow(UUID(0, 1), null, game.id, LocalDate.parse("2026-10-13"),
            null, null, 45, "offline", null, null, false, null), game, emptyList())
        private val repo = object : PlaysRepository {
            override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(play), null)
            override suspend fun player(id: UUID): PlayerRow? = null
            override suspend fun game(id: UUID) = game
            override suspend fun filterOptions() = HistoryOptions()
            override suspend fun detail(id: UUID) = play
            override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
    }
}
