package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SupabasePublishedRatingsRepositoryTest {
    private val f = EloPublicationFixture
    private class Source(var body: String) : EloPublicationSource {
        var reads = 0; var verifications = 0; var closed = false
        var failure: AuthProblem? = null
        var fetch: suspend () -> String = { body }
        override suspend fun read(account: UUID): String { assertEquals(EloPublicationFixture.actor, account); reads++; return fetch() }
        override suspend fun verify(account: UUID) {
            assertEquals(EloPublicationFixture.actor, account); verifications++
            failure?.let { throw AuthFailure(it) }
        }
        override fun close() { closed = true }
    }
    private fun reader() = AuthenticatedReader { account, resource, parameters ->
        assertEquals(f.actor, account)
        assertEquals("id.asc", parameters.toMap()["order"])
        when (resource) {
            "games" -> "[]"
            "players" -> """[${player(f.player, f.actor)},${player(f.other, UUID(0, 999))}]"""
            else -> error("Legacy ratings/history requests are forbidden: $resource")
        }
    }
    private fun player(id: UUID, account: UUID) = """{"id":"$id","user_id":"$account","name":"Catalog name","is_me":false,"created_at":null,"alias":"Catalog alias","avatar_url":null,"updated_at":"2026-10-15T00:00:00Z","color":null,"account_user_id":"$account"}"""
    private suspend fun failure(expected: DataProblem, action: suspend () -> Any?) {
        try { action(); fail("Expected $expected") } catch (failure: DataFailure) { assertEquals(expected, failure.problem) }
    }
    @Test fun `general standings include every account and names come from the verified generation`() = runBlocking {
        val source = Source(f.snapshot().toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        assertTrue(repository.games().isEmpty())
        val ranking = repository.ranking(null)
        assertEquals(listOf(f.player, f.other), ranking.entries.map { it.playerId })
        assertEquals(listOf("Anna", "Bruno"), ranking.entries.map { it.player!!.name })
        assertTrue(ranking.entries.all { it.player!!.alias == null && it.ownerId == null && it.updatedAt == null && it.publicationId == f.publication })
        assertEquals(f.player, ranking.profile!!.id)
        assertEquals(1, source.reads)
    }
    @Test fun `1200 history points are paged locally from one snapshot without rereading or recalculation`() = runBlocking {
        val source = Source(f.snapshot(1200).toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        val ranking = repository.ranking(f.game)
        val player = ranking.entries.first()
        source.body = f.pending("stale").toString()
        var cursor: RatingHistoryCursor? = null
        val rows = mutableListOf<kingscore.contracts.v1.EloHistoryRow>()
        do {
            val page = repository.history(player, cursor)
            assertTrue(page.items.size <= PAGE_SIZE)
            rows += page.items; cursor = page.next
        } while (cursor != null)
        assertEquals(1200, rows.size)
        assertEquals(1200, rows.map { it.id }.toSet().size)
        assertEquals((1200 downTo 1).map { UUID(1, it.toLong()) }, rows.map { it.play_id })
        assertTrue(rows.all { !it.is_general && it.game_id == f.game && it.user_id == null })
        assertEquals(1, source.reads)
        assertEquals(25, source.verifications) // Exact multiples end with an empty local page.
    }
    @Test fun `general history and game history belong to the selected generation`() = runBlocking {
        val source = Source(f.snapshot(2).toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        val general = repository.ranking(null).entries.first()
        val history = repository.history(general).items
        assertEquals(listOf(2, 1), history.map { it.games_played })
        assertTrue(history.all { it.is_general && it.game_id == null })
        source.body = f.snapshot(3, UUID(0, 31)).toString()
        repository.invalidate()
        val current = repository.ranking(null).entries.first()
        failure(DataProblem.INVALID_RESPONSE) { repository.history(general) }
        assertEquals(3, repository.history(current).items.size)
    }
    @Test fun `stale missing and corrupt responses clear old publication and never fall back to legacy tables`() = runBlocking {
        val source = Source(f.snapshot().toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        var old = repository.ranking(null).entries.first()
        for ((body, problem) in listOf(f.pending("stale").toString() to DataProblem.PUBLICATION_STALE,
            f.pending("missing").toString() to DataProblem.PUBLICATION_MISSING, "{}" to DataProblem.INVALID_RESPONSE)) {
            source.body = body
            repository.invalidate()
            failure(problem) { repository.ranking(null) }
            failure(DataProblem.INVALID_RESPONSE) { repository.history(old) }
            source.body = f.snapshot().toString(); old = repository.ranking(null).entries.first()
        }
    }
    @Test fun `offline or revoked identity blocks cached history and close destroys publication state`() = runBlocking {
        val source = Source(f.snapshot().toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        val rating = repository.ranking(null).entries.first()
        for (problem in listOf(AuthProblem.NETWORK_UNAVAILABLE, AuthProblem.INVALID_CREDENTIALS)) {
            source.failure = problem
            try { repository.history(rating); fail("Expected authentication failure") }
            catch (failure: AuthFailure) { assertEquals(problem, failure.problem) }
        }
        source.failure = null
        repository.close()
        assertTrue(source.closed)
        failure(DataProblem.INVALID_RESPONSE) { repository.history(rating) }
    }
    @Test fun `a closed repository cannot accept a late publication or a foreign cursor`() = runBlocking {
        val pending = CompletableDeferred<String>()
        val source = Source(f.snapshot().toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        val rating = repository.ranking(null).entries.first()
        failure(DataProblem.INVALID_RESPONSE) { repository.history(rating, RatingHistoryCursor(java.time.Instant.EPOCH, UUID(0, 777))) }
        source.fetch = { withContext(NonCancellable) { pending.await() } }
        repository.invalidate()
        val job = async { repository.ranking(null) }
        yield()
        repository.close()
        pending.complete(f.snapshot().toString())
        try { job.await(); fail("Late generation accepted") } catch (_: CancellationException) { }
    }

    @Test fun `concurrent selections share one complete publication and player catalog until explicit invalidation`() = runBlocking {
        val pending = CompletableDeferred<String>()
        val source = Source(f.snapshot().toString()).apply { fetch = { pending.await() } }
        var playerReads = 0
        var gameReads = 0
        val delegate = reader()
        val repository = SupabasePublishedRatingsRepository(AuthenticatedReader { account, resource, parameters ->
            if (resource == "players") playerReads++
            if (resource == "games") gameReads++
            delegate.get(account, resource, parameters)
        }, f.actor, source)
        val general = async { repository.ranking(null) }
        yield()
        val game = async { repository.ranking(f.game) }
        pending.complete(f.snapshot().toString())
        val first = general.await()
        val second = game.await()
        assertEquals(1, source.reads)
        assertEquals(1, playerReads)
        assertNotNull(first.readAt)
        assertEquals(first.readAt, second.readAt)
        assertEquals(f.game, second.entries.first().gameId)
        repository.games(); repository.games()
        assertEquals(1, gameReads)
        assertTrue(source.verifications >= 2)
        repository.invalidate()
        source.fetch = { f.snapshot(3, UUID(0, 31)).toString() }
        val updated = repository.ranking(null)
        repository.games()
        assertEquals(2, source.reads)
        assertEquals(2, playerReads)
        assertEquals(2, gameReads)
        assertEquals(UUID(0, 31), updated.entries.first().publicationId)
    }

    @Test fun `invalidating an in flight publication prevents it repopulating the session cache`() = runBlocking {
        val pending = CompletableDeferred<String>()
        val source = Source(f.snapshot().toString()).apply { fetch = { withContext(NonCancellable) { pending.await() } } }
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        val old = async { repository.ranking(null) }
        yield()
        repository.invalidate()
        source.fetch = { f.snapshot(3, UUID(0, 31)).toString() }
        val current = async { repository.ranking(f.game) }
        pending.complete(f.snapshot().toString())
        try { old.await(); fail("Invalidated publication accepted") } catch (_: CancellationException) { }
        assertEquals(UUID(0, 31), current.await().entries.first().publicationId)
        assertEquals(2, source.reads)
        assertEquals(UUID(0, 31), repository.ranking(null).entries.first().publicationId)
        assertEquals(2, source.reads)
    }

    @Test fun `cached selections verify connectivity and revoked account before using the publication`() = runBlocking {
        val source = Source(f.snapshot().toString())
        val repository = SupabasePublishedRatingsRepository(reader(), f.actor, source)
        repository.ranking(null)
        source.failure = AuthProblem.NETWORK_UNAVAILABLE
        try { repository.ranking(f.game); fail("Offline cache accepted") }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        source.failure = null
        source.body = f.snapshot(3, UUID(0, 31)).toString()
        assertEquals(UUID(0, 31), repository.ranking(null).entries.first().publicationId)
        assertEquals(2, source.reads)
        source.failure = AuthProblem.INVALID_CREDENTIALS
        try { repository.games(); repository.games(); fail("Revoked cached catalog accepted") }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
    }

    @Test fun `separate authenticated session repositories cannot share a publication`() = runBlocking {
        val firstSource = Source(f.snapshot().toString())
        val nextSource = Source(f.snapshot(3, UUID(0, 31)).toString())
        val first = SupabasePublishedRatingsRepository(reader(), f.actor, firstSource)
        val next = SupabasePublishedRatingsRepository(reader(), f.actor, nextSource)
        assertEquals(f.publication, first.ranking(null).entries.first().publicationId)
        assertEquals(UUID(0, 31), next.ranking(null).entries.first().publicationId)
        first.close()
        assertEquals(UUID(0, 31), next.ranking(f.game).entries.first().publicationId)
        assertEquals(1, nextSource.reads)
    }
}
