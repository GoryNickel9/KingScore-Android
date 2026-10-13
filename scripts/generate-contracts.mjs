import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evidence = JSON.parse(await readFile(path.join(root, 'docs/evidence/supabase-audit.json'), 'utf8'));
const tables = {
  games: 'GameRow', plays: 'PlayRow', play_scores: 'PlayScoreRow', players: 'PlayerRow',
  play_comments: 'PlayCommentRow', player_ratings: 'GameRatingRow',
  player_general_ratings: 'GeneralRatingRow', elo_history: 'EloHistoryRow',
};
const kotlinTypes = {
  uuid: 'UUID', text: 'String', integer: 'Int', boolean: 'Boolean',
  numeric: 'BigDecimal', date: 'LocalDate', 'timestamp with time zone': 'Instant',
};
const schema = { version: 1, source: 'docs/evidence/supabase-audit.json', tables: {} };
const classes = [];
for (const [table, name] of Object.entries(tables)) {
  const columns = evidence.audit.columns.filter(c => c.table_name === table);
  if (columns.length === 0) throw new Error(`Missing remote table: ${table}`);
  schema.tables[table] = { kotlinClass: name, columns: columns.map(c => {
    const kotlinType = kotlinTypes[c.data_type];
    if (!kotlinType) throw new Error(`Unsupported SQL type: ${c.data_type}`);
    return { name: c.column_name, sqlType: c.data_type, nullable: c.is_nullable === 'YES', kotlinType };
  }) };
  classes.push(`data class ${name}(\n${schema.tables[table].columns.map(c =>
    `    val ${c.name}: ${c.kotlinType}${c.nullable ? '?' : ''},`).join('\n')}\n)`);
}
const outputDir = path.join(root, 'contracts/v1');
await mkdir(outputDir, { recursive: true });
await writeFile(path.join(outputDir, 'schema.json'), JSON.stringify(schema, null, 2) + '\n');
await writeFile(path.join(outputDir, 'Rows.kt'), `// Generated from the captured remote catalog; do not edit by hand.
// Wire adapters and JSON serializers belong to phase 2.
package kingscore.contracts.v1

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

${classes.join('\n\n')}
`);
console.log(`Generated ${classes.length} Kotlin row contracts from remote SQL types and nullability.`);

const decoders = { games: 'Game', plays: 'Play', play_scores: 'PlayScore', players: 'Player', play_comments: 'PlayComment', player_ratings: 'GameRating', player_general_ratings: 'GeneralRating', elo_history: 'EloHistory' };
const readers = { UUID: 'uuid', String: 'text', Int: 'integer', Boolean: 'boolean', BigDecimal: 'decimal', LocalDate: 'date', Instant: 'instant' };
const decoderDir = path.join(root, 'app/src/main/java/app/kingscore/android/data/remote');
await mkdir(decoderDir, { recursive: true });
await writeFile(path.join(decoderDir, 'RowDecoders.kt'), `// Generated from contracts/v1/schema.json. Keeps JSON numeric precision.
package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.serialization.json.*

object RowDecoders {
${Object.entries(schema.tables).map(([table, contract]) => `    fun ${decoders[table][0].toLowerCase() + decoders[table].slice(1)}(row: JsonObject): ${contract.kotlinClass} = with(WireRow(row)) {
        ${contract.kotlinClass}(
${contract.columns.map(c => `            ${c.name} = ${readers[c.kotlinType]}${c.nullable ? 'OrNull' : ''}("${c.name}"),`).join('\n')}
        )
    }`).join('\n\n')}

    fun playDetail(row: JsonObject): PlayDetail = PlayDetail(
        play = play(row),
        game = row.getValue("games").let { if (it == JsonNull) null else game(it.jsonObject) },
        scores = row.getValue("play_scores").jsonArray.map { element ->
            val score = element.jsonObject
            ScoreWithPlayer(playScore(score), score.getValue("players").let { if (it == JsonNull) null else player(it.jsonObject) })
        },
    )
}

private class WireRow(private val row: JsonObject) {
    private fun primitive(name: String): JsonPrimitive = row.getValue(name).jsonPrimitive
    private fun missing(name: String): Boolean = row.getValue(name) == JsonNull
    fun text(name: String): String = primitive(name).also { require(it.isString) { "Expected text: $name" } }.content
    fun integer(name: String): Int = primitive(name).also { require(!it.isString) { "Expected integer: $name" } }.int
    fun boolean(name: String): Boolean = primitive(name).also { require(!it.isString) { "Expected boolean: $name" } }.boolean
    fun decimal(name: String): BigDecimal = primitive(name).also { require(!it.isString) { "Expected numeric: $name" } }.content.toBigDecimal()
    fun uuid(name: String): UUID = UUID.fromString(text(name))
    fun date(name: String): LocalDate = LocalDate.parse(text(name))
    fun instant(name: String): Instant = Instant.parse(text(name))
${Object.values(readers).map(reader => `    fun ${reader}OrNull(name: String) = if (missing(name)) null else ${reader}(name)`).join('\n')}
}
`);
console.log('Generated strict JSON decoders for all 8 row contracts and play detail.');
