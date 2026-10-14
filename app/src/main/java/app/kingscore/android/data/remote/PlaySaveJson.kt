package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.serialization.json.*

/** RPCs use decimal text; existing REST row decoders continue to require numeric JSON. */
object PlaySaveJson {
    const val MAX_PAYLOAD_BYTES = 1048576
    private val decimal = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")

    fun editorArguments(playId: UUID) = buildJsonObject { put("p_play_id", playId.toString()) }
    fun createArguments(command: CreatePlayCommand) = buildJsonObject {
        require(command.payload.scores.all { it.id == null })
        put("p_request_id", command.request_id.toString()); put("p_payload", payload(command.payload))
    }
    fun updateArguments(command: UpdatePlayCommand) = buildJsonObject {
        put("p_request_id", command.request_id.toString()); put("p_play_id", command.play_id.toString())
        put("p_expected_revision", command.expected_revision); put("p_payload", payload(command.payload))
    }
    fun deleteArguments(command: DeletePlayCommand) = buildJsonObject {
        put("p_request_id", command.request_id.toString()); put("p_play_id", command.play_id.toString())
        put("p_expected_revision", command.expected_revision)
    }

    private fun plainScore(value: BigDecimal): String {
        // Check before toPlainString: zero can have a scale of billions despite fitting Float64.
        val scale = value.scale().toLong()
        val digits = value.precision().toLong()
        val length = if (scale > 0) maxOf(1L, digits - scale) + 1 + scale
            else if (value.signum() == 0) 1 else digits - scale
        require(length + (if (value.signum() < 0) 1 else 0) <= MAX_PAYLOAD_BYTES) { "Score exceeds payload limit" }
        return value.toPlainString().also { require(decimal.matches(it)) }
    }

    private fun payload(value: PlayInput): JsonObject {
        val input = value.copy(scores = value.scores.toList())
        return buildJsonObject {
            put("game_id", input.game_id.toString()); put("played_at", input.played_at.toString())
            put("duration_minutes", input.duration_minutes?.let(::JsonPrimitive) ?: JsonNull)
            put("source", input.source.wireValue); put("notes", input.notes?.let(::JsonPrimitive) ?: JsonNull)
            put("is_cooperative", input.is_cooperative); put("team_won", input.team_won?.let(::JsonPrimitive) ?: JsonNull)
            put("scores", buildJsonArray {
                input.scores.forEach { score -> add(buildJsonObject {
                    put("id", score.id?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                    put("player_id", score.player_id.toString())
                    put("score", score.score?.let { JsonPrimitive(plainScore(it)) } ?: JsonNull)
                    put("winner", score.winner)
                    put("color", score.color?.let(::JsonPrimitive) ?: JsonNull)
                    put("role", score.role?.let(::JsonPrimitive) ?: JsonNull)
                }) }
            })
        }.also { require(jsonbText(it).toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Payload exceeds jsonb byte limit" } }
    }

    internal fun jsonbText(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.joinToString(", ", "{", "}") { "${JsonPrimitive(it.key)}: ${jsonbText(it.value)}" }
        is JsonArray -> value.joinToString(", ", "[", "]") { jsonbText(it) }
        else -> value.toString()
    }

    fun editor(body: String, expectedPlay: UUID): PlayEditorResult {
        val root = objectBody(body, setOf("detail", "revision", "editability"))
        val detail = root.getValue("detail").jsonObject
        val play = RowDecoders.play(detail.getValue("play").jsonObject)
        require(play.id == expectedPlay)
        val game = detail.getValue("game").let { if (it == JsonNull) null else RowDecoders.game(it.jsonObject) }
        require(game == null || game.id == play.game_id)
        val scores = detail.getValue("scores").jsonArray.map { element ->
            val item = element.jsonObject
            val row = item.getValue("score").jsonObject
            val wire = row.getValue("score")
            val numeric = if (wire == JsonNull) JsonNull else {
                val text = text(wire); require(decimal.matches(text)); Json.parseToJsonElement(text)
            }
            val score = RowDecoders.playScore(JsonObject(row + ("score" to numeric)))
            require(score.play_id == expectedPlay)
            val player = item.getValue("player").let { if (it == JsonNull) null else RowDecoders.player(it.jsonObject) }
            require(player == null || player.id == score.player_id)
            ScoreWithPlayer(score, player)
        }
        require(scores.map { it.score.id }.distinct().size == scores.size)
        val editability = root.getValue("editability").jsonObject
        val issues = editability.getValue("issues").jsonArray.map { text(it).also { issue -> require(issue.isNotBlank()) } }
        return PlayEditorResult(PlayDetail(play, game, scores), revision(root),
            PlayEditability(boolean(editability.getValue("editable")), issues))
    }

    fun mutation(body: String, expectedPlay: UUID? = null): PlayMutationResult {
        val root = objectBody(body, setOf("play_id", "revision", "replayed"))
        val play = uuid(root.getValue("play_id")); require(expectedPlay == null || play == expectedPlay)
        return PlayMutationResult(play, revision(root), boolean(root.getValue("replayed")))
    }
    fun deletion(body: String, expectedPlay: UUID): DeletePlayResult {
        val root = objectBody(body, setOf("play_id", "deleted", "replayed"))
        val play = uuid(root.getValue("play_id")); require(play == expectedPlay && boolean(root.getValue("deleted")))
        return DeletePlayResult(play, true, boolean(root.getValue("replayed")))
    }
    private fun objectBody(body: String, keys: Set<String>): JsonObject = Json.parseToJsonElement(body).jsonObject.also {
        require(it.keys == keys) { "Unexpected RPC response fields" }
    }
    private fun revision(root: JsonObject) = text(root.getValue("revision")).also(::validatePlayRevision)
    private fun text(value: JsonElement) = value.jsonPrimitive.also { require(it.isString) }.content
    private fun boolean(value: JsonElement) = value.jsonPrimitive.also { require(!it.isString && it != JsonNull) }.boolean
    private fun uuid(value: JsonElement): UUID {
        val wire = text(value)
        return UUID.fromString(wire).also { require(it.toString().equals(wire, ignoreCase = true)) }
    }
}
