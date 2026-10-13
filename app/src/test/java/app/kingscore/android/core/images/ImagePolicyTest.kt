package app.kingscore.android.core.images

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImagePolicyTest {
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val failures = mutableListOf<AuthProblem>()
    private var verified = 0
    private fun guard(verify: suspend () -> Unit = { verified++ }) = ImageAccessGuard(network, verify) { failures += it }

    @Test fun `only public HTTPS URLs without embedded credentials or local schemes are accepted`() {
        assertEquals("https://cdn.example/cover.png?v=2", httpsImageUrl(" https://cdn.example/cover.png?v=2 "))
        for (value in listOf(null, "", "http://cdn.example/x", "file:///private/image", "content://photos/1", "data:image/png;base64,a",
            "https://name:password@cdn.example/x", "https://cdn.example/x#fragment", "https://cdn.example:99999/x", "https://cdn.example/a\nb")) {
            assertNull(value, httpsImageUrl(value))
        }
    }

    @Test fun `avatar storage paths use the player images bucket with encoded path segments`() {
        assertEquals("https://project.example/storage/v1/object/public/player-images/id/avatars/r%C3%A9sum%C3%A9%201.png",
            avatarImageUrl("id/avatars/résumé 1.png", "https://project.example/"))
        assertEquals("https://project.example/storage/v1/object/public/player-images/id/a%3Fv%3D2.png",
            avatarImageUrl("id/a?v=2.png", "https://project.example"))
        assertEquals("https://cdn.example/avatar.webp", avatarImageUrl("https://cdn.example/avatar.webp", ""))
    }

    @Test fun `invalid or traversing avatar paths never become network requests`() {
        for (path in listOf(null, "", "/absolute.png", "//cdn.example/a", "id/../a", "id/./a", "id//a", "id\\a", "file:/a", "id/\na")) {
            assertNull(path, avatarImageUrl(path, "https://project.example"))
        }
        assertNull(avatarImageUrl("id/a.png", "http://project.example"))
    }

    @Test fun `fallback initials handle blank names and Unicode code points`() {
        assertEquals("MR", imageInitials(" Marta   Rossi "))
        assertEquals("?", imageInitials(" "))
        assertEquals("ÉL", imageInitials("Éva Luca Rossi"))
        assertEquals("🧩L", imageInitials("🧩 Luca"))
    }

    @Test fun `every image access verifies account before invoking transport`() = runTest {
        val guard = guard()
        var loads = 0
        repeat(2) { guard.checked { assertEquals(loads + 1, verified); loads++; "image" } }
        assertEquals(2, verified)
        assertEquals(2, loads)
    }

    @Test fun `offline and rejected accounts never invoke transport`() = runTest {
        network.connected.value = false
        try { guard().checked { fail("Must not load offline") } } catch (failure: AuthFailure) {
            assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem)
        }
        assertEquals(0, verified)
        network.connected.value = true
        try { guard { throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }.checked { fail("Must not load signed out") } }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE, AuthProblem.INVALID_CREDENTIALS), failures)
    }

    @Test fun `network lost during image loading rejects completion and blocks access`() = runTest {
        try { guard().checked { network.connected.value = false; "late image" }; fail("Must reject offline result") }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), failures)
    }

    @Test fun `closing image session rejects new and late loads`() = runTest {
        val guard = guard()
        guard.close()
        try { guard.checked { fail("Closed loader must not fetch") }; fail("Must cancel") } catch (_: CancellationException) { }
        assertEquals(0, verified)
        val active = guard()
        try { active.checked { active.close(); "late image" }; fail("Must discard") } catch (_: CancellationException) { }
        assertTrue(failures.isEmpty())
    }

    @Test fun `late account rejection after disposal cannot block a new account`() = runTest {
        val guard = guard()
        try { guard.checked { guard.close(); throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }; fail("Must fail") }
        catch (_: AuthFailure) { }
        assertTrue(failures.isEmpty())
    }

    @Test fun `CDN error while online stays an image error`() = runTest {
        try { guard().checked { throw IOException("Missing image") }; fail("Must fail") } catch (_: IOException) { }
        assertTrue(failures.isEmpty())
    }
}
