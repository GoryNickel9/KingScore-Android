import { readFileSync } from 'node:fs';

export const contract = JSON.parse(readFileSync(new URL('../v1/play-saves.json', import.meta.url), 'utf8'));
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const decimal = new RegExp(contract.wire.decimalPattern);
const requireValue = (valid, field) => { if (!valid) throw new TypeError(`Invalid ${field}`); };
const isUuid = value => typeof value === 'string' && uuid.test(value);
const text = value => typeof value === 'string' && value.isWellFormed() && !value.includes('\0');
const nullableText = value => value === null || text(value);

function fields(value, expected, name) {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value), name);
  requireValue(Object.keys(value).length === expected.length && expected.every(key => Object.hasOwn(value, key)), `${name} fields`);
}

// PostgreSQL jsonb::text inserts spaces after separators; key order does not change the byte count.
export function jsonbText(value) {
  if (Array.isArray(value)) return `[${value.map(jsonbText).join(', ')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.entries(value).map(([key, item]) => `${JSON.stringify(key)}: ${jsonbText(item)}`).join(', ')}}`;
  }
  return JSON.stringify(value);
}

function date(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function payload(value, create) {
  fields(value, contract.payloadFields, 'payload');
  requireValue(isUuid(value.game_id), 'game_id');
  requireValue(date(value.played_at), 'played_at');
  requireValue(value.duration_minutes === null || (Number.isInteger(value.duration_minutes) && value.duration_minutes >= 0 && value.duration_minutes <= 2147483647), 'duration_minutes');
  requireValue(['offline', 'online'].includes(value.source), 'source');
  requireValue(nullableText(value.notes), 'notes');
  requireValue(typeof value.is_cooperative === 'boolean', 'is_cooperative');
  requireValue(value.team_won === null || typeof value.team_won === 'boolean', 'team_won');
  requireValue(value.is_cooperative || value.team_won === null, 'competitive team_won');
  requireValue(Array.isArray(value.scores) && value.scores.length >= 1 && value.scores.length <= contract.limits.participants, 'participants');
  const players = new Set();
  const ids = new Set();
  for (const item of value.scores) {
    fields(item, contract.scoreFields, 'score');
    requireValue(item.id === null || isUuid(item.id), 'score.id');
    requireValue(!create || item.id === null, 'create score.id');
    requireValue(isUuid(item.player_id) && !players.has(item.player_id.toLowerCase()), 'unique player_id');
    players.add(item.player_id.toLowerCase());
    if (item.id !== null) {
      requireValue(!ids.has(item.id.toLowerCase()), 'unique score.id');
      ids.add(item.id.toLowerCase());
    }
    if (item.score !== null) {
      requireValue(typeof item.score === 'string' && decimal.test(item.score), 'decimal score');
      const comparisonValue = Number(item.score);
      requireValue(Number.isFinite(comparisonValue) && (comparisonValue !== 0 || !/[1-9]/.test(item.score)), 'finite ranking score');
    }
    requireValue(typeof item.winner === 'boolean', 'winner');
    requireValue(nullableText(item.color) && nullableText(item.role), 'score metadata');
    requireValue(!value.is_cooperative || value.team_won === null || item.winner === value.team_won, 'cooperative winner');
  }
  requireValue(Buffer.byteLength(jsonbText(value), 'utf8') <= contract.limits.payloadJsonbBytes, 'payload bytes');
}

/** Validates the proposed wire contract only. No HTTP, SQL or server authorization. */
export function validatePlaySave(name, args) {
  requireValue(Object.hasOwn(contract.rpc, name), 'RPC name');
  fields(args, contract.rpc[name], 'arguments');
  if (Object.hasOwn(args, 'p_play_id')) requireValue(isUuid(args.p_play_id), 'p_play_id');
  if (Object.hasOwn(args, 'p_request_id')) requireValue(isUuid(args.p_request_id), 'p_request_id');
  if (Object.hasOwn(args, 'p_expected_revision')) {
    const revision = args.p_expected_revision;
    requireValue(typeof revision === 'string' && revision.length <= contract.limits.revisionCharacters && /^[!-~]+$/.test(revision), 'revision');
  }
  if (Object.hasOwn(args, 'p_payload')) payload(args.p_payload, name !== 'update_play_v1');
  if (name === 'import_bga_play_v1') {
    fields(args.p_metadata, contract.metadataFields, 'import metadata');
    requireValue(nullableText(args.p_metadata.location), 'location');
    const tableId = args.p_metadata.bga_table_id;
    requireValue(text(tableId) && tableId.length >= 1 && [...tableId].length <= contract.limits.bgaTableIdCharacters && !/\s/.test(tableId), 'bga_table_id');
    requireValue(Array.isArray(args.p_positions) && args.p_positions.length === args.p_payload.scores.length &&
      args.p_positions.every(position => position === null || (Number.isInteger(position) && position > 0 && position <= 2147483647)), 'external positions');
  }
  return args;
}
