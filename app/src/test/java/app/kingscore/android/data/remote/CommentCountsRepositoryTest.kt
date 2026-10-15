package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommentCountsRepositoryTest {
    private val account = id(1)

    @Test fun `empty request performs no read`() = runBlocking {
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> error("Unexpected read") }, account)
        assertEquals(emptyMap<UUID, Int>(), repository.commentCounts(emptySet()))
    }

    @Test fun `one batch counts more than fifty comments and retains real zeros`() = runBlocking {
        val requested = setOf(id(2), id(3), id(4))
        val comments = (1..127).map { id(1000 + it) to if (it <= 100) id(2) else id(3) }
        var calls = 0
        val reader = pagedReader(comments, 50) { calls++ }
        val counts = SupabasePlaysRepository(reader, account).commentCounts(requested)
        assertEquals(mapOf(id(2) to 100, id(3) to 27, id(4) to 0), counts)
        assertTrue("Reads must be paginated batches, not one per comment", calls in 3..4)
    }

    @Test fun `server cap below requested page size cannot silently truncate totals`() = runBlocking {
        val comments = (1..127).map { id(1000 + it) to id(2) }
        val counts = SupabasePlaysRepository(pagedReader(comments, 20), account).commentCounts(setOf(id(2)))
        assertEquals(mapOf(id(2) to 127), counts)
    }

    @Test fun `more than fifty plays use bounded batches instead of one request per play`() = runBlocking {
        val requested = (2..54).map(::id).toSet()
        val comments = requested.mapIndexed { index, play -> id(1000 + index) to play }
        var calls = 0
        val reader = pagedReader(comments, 50) { calls++ }
        val counts = SupabasePlaysRepository(reader, account).commentCounts(requested)
        assertEquals(requested.associateWith { 1 }, counts)
        assertTrue("53 plays should need at most four batch reads", calls <= 4)
    }

    @Test fun `corrupt foreign duplicate unordered and oversized responses are rejected`() = runBlocking {
        val play = id(2)
        val row = json(id(1000), play)
        val invalid = listOf(
            "not JSON",
            "[{\"id\":\"${id(1000)}\"}]",
            "[{\"id\":\"not-a-uuid\",\"play_id\":\"$play\"}]",
            "[${json(id(1000), id(99))}]",
            "[$row,$row]",
            "[${json(id(1001), play)},$row]",
            (1..51).joinToString(prefix = "[", postfix = "]") { json(id(1000 + it), play) },
        )
        invalid.forEach { body ->
            expectInvalid { SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> body }, account).commentCounts(setOf(play)) }
        }
    }

    @Test fun `non advancing later page fails rather than double counting or looping`() = runBlocking {
        var calls = 0
        val body = (1..50).joinToString(prefix = "[", postfix = "]") { json(id(1000 + it), id(2)) }
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ ->
            calls++
            check(calls <= 2) { "Non advancing cursor was not stopped" }
            body
        }, account)
        expectInvalid { repository.commentCounts(setOf(id(2))) }
        assertEquals(2, calls)
    }

    @Test fun `forbidden counts preserve their error instead of becoming zero`() = runBlocking {
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> throw DataFailure(DataProblem.FORBIDDEN) }, account)
        try { repository.commentCounts(setOf(id(2))); fail("Expected denied read") }
        catch (failure: DataFailure) { assertEquals(DataProblem.FORBIDDEN, failure.problem) }
    }

    private fun pagedReader(rows: List<Pair<UUID, UUID>>, cap: Int, called: () -> Unit = {}) = AuthenticatedReader { owner, resource, parameters ->
        called()
        assertEquals(account, owner)
        assertEquals("play_comments", resource)
        val query = parameters.toMap()
        assertEquals("id,play_id", query["select"])
        assertEquals("id.asc", query["order"])
        assertEquals("50", query["limit"])
        assertNull(query["offset"])
        val requested = query.getValue("play_id").removePrefix("in.(").removeSuffix(")").split(',').map(UUID::fromString).toSet()
        assertTrue(requested.size <= 50)
        val after = query["id"]?.removePrefix("gt.")
        rows.filter { (_, play) -> play in requested }.filter { (comment, _) -> after == null || comment.toString() > after }
            .sortedBy { it.first.toString() }.take(cap)
            .joinToString(prefix = "[", postfix = "]") { (comment, play) -> json(comment, play) }
    }

    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try { action(); fail("Expected invalid response") }
        catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }
    private fun json(comment: UUID, play: UUID) = """{"id":"$comment","play_id":"$play"}"""
    private fun id(n: Int) = UUID(0, n.toLong())
}
