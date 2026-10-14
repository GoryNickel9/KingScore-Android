package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaySaveJsonTest {
    private val f = PlayWriteFixtures
    @Test fun `four RPC argument shapes preserve decimals as text and metadata`() {
        assertEquals(setOf("p_play_id"), PlaySaveJson.editorArguments(f.play).keys)
        val create = PlaySaveJson.createArguments(f.create())
        assertEquals(setOf("p_request_id", "p_payload"), create.keys)
        val scores = create.getValue("p_payload").jsonObject.getValue("scores").jsonArray
        val score = scores.single().jsonObject
        assertTrue(score.getValue("score").jsonPrimitive.isString)
        assertEquals("9007199254740993.125", score.getValue("score").jsonPrimitive.content)
        assertEquals(JsonNull, score.getValue("id")); assertEquals("red", score.getValue("color").jsonPrimitive.content)
        val update = PlaySaveJson.updateArguments(f.update())
        assertEquals(setOf("p_request_id", "p_play_id", "p_expected_revision", "p_payload"), update.keys)
        assertEquals(f.scoreId.toString(), update.getValue("p_payload").jsonObject.getValue("scores").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(setOf("p_request_id", "p_play_id", "p_expected_revision"), PlaySaveJson.deleteArguments(f.delete()).keys)
    }
    @Test fun `null zero negative exponent domain input and smallest subnormal encode exactly`() {
        for (value in listOf(null, "0", "-1.25", "1e3", "5e-324")) {
            val command = CreatePlayCommand(f.request, f.input(listOf(f.score(value))))
            val actual = PlaySaveJson.createArguments(command).getValue("p_payload").jsonObject.getValue("scores").jsonArray.single().jsonObject.getValue("score")
            assertEquals(value?.let { JsonPrimitive(BigDecimal(it).toPlainString()) } ?: JsonNull, actual)
        }
    }
    @Test fun `jsonb UTF8 boundary includes Unicode escapes and separator spaces`() {
        val plain = f.create().copy(payload = f.input().copy(notes = ""))
        fun payload(command: CreatePlayCommand) = PlaySaveJson.createArguments(command).getValue("p_payload")
        val overhead = PlaySaveJson.jsonbText(payload(plain)).toByteArray().size
        val boundary = plain.copy(payload = plain.payload.copy(notes = "a".repeat(PlaySaveJson.MAX_PAYLOAD_BYTES - overhead)))
        assertEquals(PlaySaveJson.MAX_PAYLOAD_BYTES, PlaySaveJson.jsonbText(payload(boundary)).toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) { payload(boundary.copy(payload = boundary.payload.copy(notes = boundary.payload.notes + "a"))) }
        assertThrows(IllegalArgumentException::class.java) { payload(plain.copy(payload = plain.payload.copy(notes = "😀".repeat(270000)))) }
        assertEquals("{\"notes\": \"😀à漢字\\n\\\"\\\\\"}", PlaySaveJson.jsonbText(buildJsonObject { put("notes", "😀à漢字\n\"\\") }))
    }
    @Test fun `score expansion is bounded before allocating a plain decimal`() {
        val hugeZero = f.score().copy(score = BigDecimal.ZERO.setScale(Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { PlaySaveJson.createArguments(CreatePlayCommand(f.request, f.input(listOf(hugeZero)))) }
    }
    @Test fun `invalid PostgreSQL text and revisions are rejected`() {
        for (text in listOf("a\u0000b", "\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) { f.input().copy(notes = text) }
            assertThrows(IllegalArgumentException::class.java) { f.score().copy(role = text) }
            assertThrows(IllegalArgumentException::class.java) { f.score().copy(color = text) }
        }
        for (revision in listOf("", " ", "a\nb", "à", "x".repeat(161))) {
            assertThrows(IllegalArgumentException::class.java) { f.update().copy(expected_revision = revision) }
            assertThrows(IllegalArgumentException::class.java) { f.delete().copy(expected_revision = revision) }
        }
    }
    @Test fun `mutable participant collections are revalidated before serialization`() {
        val scores = mutableListOf(f.score())
        val command = CreatePlayCommand(f.request, f.input(scores))
        scores.add(f.score())
        assertThrows(IllegalArgumentException::class.java) { PlaySaveJson.createArguments(command) }
        scores.clear(); scores.add(f.score().copy(id = f.scoreId))
        assertThrows(IllegalArgumentException::class.java) { PlaySaveJson.createArguments(command) }
    }
    @Test fun `editor preserves precision null historical fields and all 64 participants`() {
        val result = PlaySaveJson.editor(f.editor(), f.play)
        assertEquals(BigDecimal("9007199254740993.125"), result.detail.scores.single().score.score)
        assertTrue(result.editability.editable)
        assertNull(PlaySaveJson.editor(f.editor(null), f.play).detail.scores.single().score.score)
        val legacy = PlaySaveJson.editor(f.editor(legacy = true, participants = 64), f.play)
        assertEquals(64, legacy.detail.scores.size); assertFalse(legacy.editability.editable)
        assertNull(legacy.detail.play.source); assertNull(legacy.detail.scores.first().score.winner)
    }
    @Test fun `editor rejects wrong target inconsistent flags numeric score and malformed detail`() {
        for (body in listOf("{}", "[]", f.editor().replace("\"editable\":true", "\"editable\":false"),
            f.editor().replace("\"9007199254740993.125\"", "9007199254740993.125"),
            f.editor().replace("\"play_id\":\"${f.play}\"", "\"play_id\":\"${UUID(0, 99)}\""))) {
            assertThrows(Exception::class.java) { PlaySaveJson.editor(body, f.play) }
        }
        assertThrows(Exception::class.java) { PlaySaveJson.editor(f.editor(), UUID(0, 99)) }
    }
    @Test fun `mutation and delete verify opaque revision ID booleans and complete response`() {
        assertTrue(PlaySaveJson.mutation(f.saved(true), f.play).replayed)
        assertTrue(PlaySaveJson.deletion(f.deleted(true), f.play).deleted)
        for (body in listOf("{}", "[]", f.saved().replace("false", "\"false\""), f.saved().replace("v1:new", " "),
            f.saved().replace(f.play.toString(), UUID(0, 99).toString()), f.saved().replace("\"replayed\":false", "\"replayed\":false,\"skipped\":true"))) {
            assertThrows(Exception::class.java) { PlaySaveJson.mutation(body, f.play) }
        }
        assertThrows(Exception::class.java) { PlaySaveJson.deletion(f.deleted().replace("true", "false"), f.play) }
    }
}
