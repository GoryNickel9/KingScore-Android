package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*

internal suspend fun <T> AuthenticatedReader.readAllById(
    accountId: UUID, resource: String, parameters: List<Pair<String, String>>,
    decoder: (JsonObject) -> T, id: (T) -> UUID,
): List<T> {
    val items = mutableListOf<T>()
    var after: UUID? = null
    do {
        coroutineContext.ensureActive()
        val query = parameters + listOf("order" to "id.asc", "limit" to PAGE_SIZE.toString()) + after?.let { listOf("id" to "gt.$it") }.orEmpty()
        val body = get(accountId, resource, query)
        val page = try {
            Json.parseToJsonElement(body).jsonArray.map { decoder(it.jsonObject) }.also { page ->
                require(page.size <= PAGE_SIZE)
                require(page.zipWithNext().all { (a, b) -> id(a).toString() < id(b).toString() })
                require(after == null || page.all { id(it).toString() > after.toString() })
            }
        } catch (_: Exception) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
        items += page
        after = page.lastOrNull()?.takeIf { page.size == PAGE_SIZE }?.let(id)
    } while (after != null)
    return items
}
