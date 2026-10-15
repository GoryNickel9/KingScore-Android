package app.kingscore.android.data.remote

import java.util.UUID
import kotlinx.serialization.json.*

internal object SummaryFixture {
    val actor = UUID(0,1)
    val game = UUID(0,10)
    val player = UUID(0,20)
    val second = UUID(0,21)
    val publication = UUID(0,90)
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val envelope = obj("""{"contractVersion":1,"scope":"global-visible-v1","sourceRevision":"7","computedAt":"2026-10-15T18:00:00Z"}""")
    val gameRow = obj("""{"gameId":"$game","name":"Skull King","imageUrl":null,"plays":4,"participations":8,
      "scoreSum":"10","scoreSamples":4,"scoreAverage":"2.50","recordedMinutes":"40","durationSamples":2,
      "durationAverage":"20","unattributedParticipations":0}""")
    fun home() = JsonObject(envelope + obj("""{"month":"2026-10","totals":{"plays":4,"monthPlays":4,"participatingGames":1,
      "participatingPlayers":2,"recordedMinutes":"40","durationSamples":2},"favoriteGames":[{"gameId":"$game","name":"Skull King","plays":4,"imageUrl":null}],
      "personal":{"status":"linked","playerId":"$player","displayName":"Anna","plays":4,"wins":2},
      "months":[{"month":"2026-05","plays":0},{"month":"2026-06","plays":0},{"month":"2026-07","plays":0},
      {"month":"2026-08","plays":0},{"month":"2026-09","plays":0},{"month":"2026-10","plays":4}]}"""))
    fun overview(next: String? = null) = JsonObject(envelope + obj("""{"personalStatus":"linked","winRateByGame":[{"gameId":"$game","name":"Skull King",
      "participations":4,"wins":2,"percent":50}],"games":[$gameRow],"totalGames":1,"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""))
    fun details() = JsonObject(envelope + obj("""{"game":$gameRow,"players":[{"playerId":"$player","name":"Anna","alias":null,"avatarUrl":null,
      "participations":4,"scoreSum":"5","scoreSamples":2,"scoreAverage":"2.50"},{"playerId":"$second","name":"Bruno","alias":null,"avatarUrl":null,
      "participations":4,"scoreSum":"5","scoreSamples":2,"scoreAverage":"2.50"}],"totalPlayers":2,"nextCursor":null}"""))
    fun chart() = JsonObject(envelope + obj("""{"scopeId":"global","algorithm":"kingscore-elo-legacy-v1","status":"current","publicationId":"$publication",
      "publishedRevision":"7","gameId":null,"series":[{"playerId":"$player","displayName":"Anna","color":"#10b981"},
      {"playerId":"$second","displayName":"Bruno","color":null}],"initialRatings":[],"plays":[
      {"playId":"${UUID(0,100)}","date":"2026-10-01","ordinal":1,"ratings":[{"playerId":"$player","rating":1024},{"playerId":"$second","rating":976}]},
      {"playId":"${UUID(0,101)}","date":"2026-10-01","ordinal":2,"ratings":[{"playerId":"$player","rating":1045},{"playerId":"$second","rating":955}]}],
      "totalPlays":3,"nextCursor":"opaque-next","availableSeries":2}"""))
}
