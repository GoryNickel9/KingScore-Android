package kingscore.contracts.v1

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private val playId = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val playerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val requestId = UUID.fromString("00000000-0000-0000-0000-000000000003")
private val otherPlayerId = UUID.fromString("00000000-0000-0000-0000-000000000004")

private fun score(value: String? = null, winner: Boolean = false) =
    ScoreInput(null, playerId, value?.let(::BigDecimal), winner, "red", "builder")

private fun input(scores: List<ScoreInput> = listOf(score())) =
    PlayInput(playId, LocalDate.parse("2026-10-13"), null, PlaySource.IRL, null, false, null, scores)

fun main() {
    var checks = 0
    fun test(name: String, body: () -> Unit) {
        body()
        checks++
        println("PASS $name")
    }
    fun rejects(body: () -> Unit) {
        try { body() } catch (_: IllegalArgumentException) { return }
        error("Expected validation failure")
    }

    test("null score differs from zero and decimal scores retain precision") {
        check(score().score == null)
        check(score("0").score == BigDecimal.ZERO)
        check(score("-1.25").score == BigDecimal("-1.25"))
        check(score("9007199254740993.125").score!!.toPlainString() == "9007199254740993.125")
    }
    test("played_at is a calendar date with no timezone conversion") {
        check(input().played_at.toString() == "2026-10-13")
    }
    test("comment boundaries follow PostgreSQL Unicode character count") {
        CreateComment(playId, "😀".repeat(1000))
        rejects { CreateComment(playId, "😀".repeat(1001)) }
        rejects { UpdateComment("") }
        rejects { UpdateComment("  text  ") }
        rejects { UpdateComment("a\u0000b") }
        rejects { UpdateComment("\uD800") }
        rejects { UpdateComment("\uDC00") }
    }
    test("duration cannot be negative and unknown duration remains null") {
        check(input().duration_minutes == null)
        input().copy(duration_minutes = 0)
        rejects { input().copy(duration_minutes = -1) }
    }
    test("empty and duplicate participants are rejected") {
        rejects { input(emptyList()) }
        rejects { input(listOf(score(), score("3"))) }
    }
    test("duplicate existing score IDs are rejected") {
        val rowId = requestId
        rejects { input(listOf(score().copy(id = rowId), score().copy(id = rowId, player_id = otherPlayerId))) }
    }
    test("cooperative outcome is consistent for every participant") {
        input(listOf(score(winner = true))).copy(is_cooperative = true, team_won = true)
        input().copy(is_cooperative = true, team_won = false)
        input().copy(is_cooperative = true, team_won = null)
        rejects { input().copy(team_won = true) }
        rejects { input().copy(is_cooperative = true, team_won = true) }
    }
    test("updates preserve participant metadata") {
        val command = UpdatePlayCommand(requestId, playId, "opaque-revision", input())
        check(command.payload.scores.single().color == "red")
        check(command.payload.scores.single().role == "builder")
    }
    test("create cannot reuse existing scores and mutations require a revision") {
        CreatePlayCommand(requestId, input())
        rejects { CreatePlayCommand(requestId, input(listOf(score().copy(id = requestId)))) }
        rejects { UpdatePlayCommand(requestId, playId, "", input()) }
        rejects { DeletePlayCommand(requestId, playId, " ") }
    }
    test("IRL source does not enable offline app usage") {
        check(PlaySource.IRL.wireValue == "offline")
        check(PlaySource.ONLINE.wireValue == "online")
    }
    println("$checks contract checks passed")
}
