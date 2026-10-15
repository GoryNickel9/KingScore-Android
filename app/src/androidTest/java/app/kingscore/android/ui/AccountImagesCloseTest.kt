package app.kingscore.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.images.AccountImages
import app.kingscore.android.core.images.ImageAccessGuard
import app.kingscore.android.core.network.NetworkMonitor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the real loader on debug or signed release; no host Activity or network fixture. */
@RunWith(AndroidJUnit4::class)
class AccountImagesCloseTest {
    @Test fun closingOnMainRevokesAccessImmediatelyAndSchedulesPoolCleanupOnlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
        val images = AccountImages(instrumentation.targetContext, "https://project.example", network,
            verifyAccount = {}, onAccessFailure = { fail("No account request expected during disposal") })
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue())
        // Control only the real client's cleanup executor; keep the production loader and guard.
        val clientField = AccountImages::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = (clientField.get(images) as OkHttpClient).newBuilder().dispatcher(Dispatcher(executor)).build()
        clientField.set(images, client)
        val guard = AccountImages::class.java.getDeclaredField("guard").apply { isAccessible = true }
            .get(images) as ImageAccessGuard
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { busy.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(busy.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { images.close(); images.close() }
            assertThrows(CancellationException::class.java) { guard.checkOpen() }
            assertEquals("Pool cleanup must be deferred to the HTTP executor exactly once", 1, executor.queue.size)
            assertFalse(executor.isShutdown)
            release.countDown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(2L, executor.completedTaskCount)
            instrumentation.runOnMainSync { images.close() }
        } finally {
            release.countDown(); images.close(); executor.shutdown()
        }
    }
}
