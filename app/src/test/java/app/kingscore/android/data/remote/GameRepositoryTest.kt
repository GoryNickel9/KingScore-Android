package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameRepositoryTest {
    private val account = UUID(0, 99)
    private val id = UUID(0, 10)
    private fun game(key: UUID = id) = """{"id":"$key","name":"Azul","bga_id":null,"image_url":null,"min_players":null,"max_players":4,"created_at":null}"""
    private fun repository(body: String) = SupabasePlaysRepository(AuthenticatedReader { owner, resource, parameters ->
        assertEquals(account, owner)
        assertEquals("games", resource)
        assertEquals(listOf("select" to "*", "id" to "eq.$id", "limit" to "1"), parameters)
        body
    }, account)

    @Test fun `game is a scoped verified read preserving nullable metadata`() = runBlocking {
        val row = repository("[${game()}]").game(id)!!
        assertEquals(id, row.id)
        assertEquals("Azul", row.name)
        assertNull(row.min_players)
        assertEquals(4, row.max_players)
        assertNull(row.image_url)
        assertNull(repository("[]").game(id))
    }

    @Test fun `wrong game duplicate rows and malformed response fail explicitly`() = runBlocking<Unit> {
        for (body in listOf("[${game(UUID(0, 11))}]", "[${game()},${game()}]", "{}", "[{\"id\":\"$id\"}]", "not JSON")) {
            try { repository(body).game(id); fail("Expected invalid response") }
            catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
        }
    }

    @Test fun `permission failure is not returned as a missing game`() = runBlocking<Unit> {
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> throw DataFailure(DataProblem.FORBIDDEN) }, account)
        try { repository.game(id); fail("Expected permission failure") }
        catch (failure: DataFailure) { assertEquals(DataProblem.FORBIDDEN, failure.problem) }
    }
}
