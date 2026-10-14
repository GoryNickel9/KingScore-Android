package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.util.UUID
import java.time.LocalDate
import app.kingscore.android.data.repository.PlayMutationKind
import app.kingscore.android.data.repository.StoredPlayMutation
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

    /** Validate restored records before replay; endpoints and RPC names never come from storage. */
    fun validateStored(record: StoredPlayMutation) {
        val keys = when (record.kind) {
            PlayMutationKind.CREATE -> setOf("p_request_id", "p_payload")
            PlayMutationKind.UPDATE -> setOf("p_request_id", "p_play_id", "p_expected_revision", "p_payload")
            PlayMutationKind.DELETE -> setOf("p_request_id", "p_play_id", "p_expected_revision")
        }
        val args = objectBody(record.body, keys)
        require(uuid(args.getValue("p_request_id")) == UUID.fromString(record.requestId))
        if (record.kind != PlayMutationKind.CREATE) {
            require(uuid(args.getValue("p_play_id")) == UUID.fromString(record.playId))
            validatePlayRevision(text(args.getValue("p_expected_revision")))
        }
        if (record.kind != PlayMutationKind.DELETE) {
            val row = args.getValue("p_payload").jsonObject
            require(row.keys == setOf("game_id", "played_at", "duration_minutes", "source", "notes", "is_cooperative", "team_won", "scores"))
            fun nullableText(value: JsonElement) = if (value == JsonNull) null else text(value)
            val scores = row.getValue("scores").jsonArray.map {
                val score = it.jsonObject
                require(score.keys == setOf("id", "player_id", "score", "winner", "color", "role"))
                val wireScore = nullableText(score.getValue("score"))?.also { value -> require(decimal.matches(value)) }
                ScoreInput(score.getValue("id").let { value -> if (value == JsonNull) null else uuid(value) },
                    uuid(score.getValue("player_id")), wireScore?.let(::BigDecimal), boolean(score.getValue("winner")),
                    nullableText(score.getValue("color")), nullableText(score.getValue("role")))
            }
            val duration = row.getValue("duration_minutes").let {
                if (it == JsonNull) null else it.jsonPrimitive.also { value -> require(!value.isString) }.int
            }
            val source = PlaySource.entries.single { it.wireValue == text(row.getValue("source")) }
            val input = PlayInput(uuid(row.getValue("game_id")), LocalDate.parse(text(row.getValue("played_at"))), duration,
                source, nullableText(row.getValue("notes")), boolean(row.getValue("is_cooperative")),
                row.getValue("team_won").let { if (it == JsonNull) null else boolean(it) }, scores)
            if (record.kind == PlayMutationKind.CREATE) CreatePlayCommand(UUID.fromString(record.requestId), input)
            payload(input)
        }
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
