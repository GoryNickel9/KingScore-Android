import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { LocalHttp, id, literal } from './local-http.mjs';
import { jsonbText } from '../contracts/tests/play-save-validation.mjs';
import { rankingVectors } from './ranking-reference.mjs';

const h = new LocalHttp(...process.argv.slice(2));
const manifest = JSON.parse(readFileSync(new URL('acceptance-cases.json', import.meta.url), 'utf8'));
const report = { status: 'FAIL', mode: 'local-atomic-acceptance', cases: [], host: '127.0.0.1', remoteContacted: false,
  goTrueLoginTested: false, realWritersIntegrated: false, ownGatewayStopped: false, node: process.version, v8: process.versions.v8 };
const actor = id(9001), second = id(9002), ordinary = id(9003), game = id(9010), player = id(9011), player2 = id(9012);
let requestSequence = 20000;
const nextRequest = () => id(++requestSequence);
let admin, admin2, user;
const score = (value = '5', playerId = player) => ({ id: null, player_id: playerId, score: value, winner: false, color: 'red', role: 'builder' });
const input = (scores = [score()]) => ({ game_id: game, played_at: '2026-10-14', duration_minutes: 30,
  source: 'offline', notes: null, is_cooperative: false, team_won: null, scores });
function expect(response, status = 200, code) {
  assert.equal(response.status, status, JSON.stringify(response.value));
  if (code) assert.equal(response.value.code, code);
  return response.value;
}
async function create(payload = input(), jwt = admin) {
  const command = { p_request_id: nextRequest(), p_payload: payload };
  const value = expect(await h.rpc('create_play_v1', command, jwt));
  assert.match(value.revision, /^v1:[0-9a-f]{64}$/); assert.equal(value.replayed, false);
  return { ...value, command };
}
async function editor(play, jwt = admin) { return expect(await h.rpc('get_play_editor_v1', { p_play_id: play }, jwt)); }
function updateCommand(play, snapshot, payload) {
  const p = snapshot.detail.play;
  return { p_request_id: nextRequest(), p_play_id: play, p_expected_revision: snapshot.revision, p_payload: payload ?? {
    game_id: p.game_id, played_at: p.played_at, duration_minutes: p.duration_minutes, source: p.source, notes: p.notes,
    is_cooperative: p.is_cooperative, team_won: p.team_won,
    scores: snapshot.detail.scores.map(({ score: s }) => ({ id: s.id, player_id: s.player_id, score: s.score, winner: s.winner, color: s.color, role: s.role })),
  } };
}
const deleteCommand = (play, snapshot) => ({ p_request_id: nextRequest(), p_play_id: play, p_expected_revision: snapshot.revision });
const jsonSql = value => `${literal(JSON.stringify(value))}::jsonb`;
function state() {
  return h.sql(`SELECT jsonb_build_object('plays',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM plays t),
    'scores',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM play_scores t),
    'receipts',(SELECT jsonb_agg(to_jsonb(t) ORDER BY actor_id,request_id) FROM kingscore_atomic.receipts t),
    'comments',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM play_comments t),
    'history',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM elo_history t))::text`);
}
function faultOn(request) {
  h.sql(`CREATE FUNCTION kingscore_atomic.acceptance_fail_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.request_id=${literal(request)}::uuid THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Synthetic late failure'; END IF;
    RETURN NEW; END $$;
    CREATE TRIGGER acceptance_fail_receipt BEFORE INSERT ON kingscore_atomic.receipts FOR EACH ROW EXECUTE FUNCTION kingscore_atomic.acceptance_fail_receipt();`);
}
function clearFault() { h.sql('DROP TRIGGER acceptance_fail_receipt ON kingscore_atomic.receipts; DROP FUNCTION kingscore_atomic.acceptance_fail_receipt()'); }
function applyPositions(play, snapshot, positions, revision = snapshot.revision) {
  const ids = snapshot.detail.scores.map(item => literal(item.score.id) + '::uuid').join(',');
  return h.sql(`BEGIN; SET LOCAL ROLE service_role; SELECT kingscore_atomic.apply_positions(${literal(play)}::uuid,
    ${literal(revision)},ARRAY[${ids}]::uuid[],ARRAY[${positions.map(value => value === null ? 'NULL' : value).join(',')}]::integer[]); ROLLBACK;`, false);
}
const tests = new Map();
tests.set('create-rollback', async () => {
  const before = state();
  for (const payload of [input([score('1', id(999999))]), { ...input(), game_id: id(999999) }, input([score('1e400')]), input([]),
    { ...input(), game_id: game.replaceAll('-', '') }, { ...input(), played_at: '2026-02-30' },
    { ...input(), user_id: actor }, { ...input(), duration_minutes: -1 },
    input([score(), score()]), { ...input(), is_cooperative: true, team_won: true }]) {
    expect(await h.rpc('create_play_v1', { p_request_id: nextRequest(), p_payload: payload }, admin), 400);
    assert.equal(state(), before);
  }
  const command = { p_request_id: nextRequest(), p_payload: input() }; faultOn(command.p_request_id);
  try { expect(await h.rpc('create_play_v1', command, admin), 400); assert.equal(state(), before); } finally { clearFault(); }
});
tests.set('update-rollback', async () => {
  const c = await create(); const e = await editor(c.play_id); const before = state();
  const invalid = updateCommand(c.play_id, e); invalid.p_payload.notes = 'must not commit'; invalid.p_payload.scores[0].player_id = id(999999);
  expect(await h.rpc('update_play_v1', invalid, admin), 400); assert.equal(state(), before);
  const foreign = await create(); const other = await editor(foreign.play_id);
  const foreignCommand = updateCommand(c.play_id, e); foreignCommand.p_payload.scores[0].id = other.detail.scores[0].score.id;
  const afterSetup = state(); expect(await h.rpc('update_play_v1', foreignCommand, admin), 400); assert.equal(state(), afterSetup);
  const missingId = updateCommand(c.play_id, e); missingId.p_payload.scores[0].id = null;
  expect(await h.rpc('update_play_v1', missingId, admin), 400); assert.equal(state(), afterSetup);
  const command = updateCommand(c.play_id, e); command.p_payload.notes = 'late failure'; faultOn(command.p_request_id);
  try { expect(await h.rpc('update_play_v1', command, admin), 400); assert.equal(state(), afterSetup); } finally { clearFault(); }
});
tests.set('delete-rollback', async () => {
  const c = await create(); const e = await editor(c.play_id);
  expect(await h.request('POST', 'play_comments', { play_id: c.play_id, content: 'cascade fixture' }, user), 201);
  const before = state(); const command = deleteCommand(c.play_id, e); faultOn(command.p_request_id);
  try { expect(await h.rpc('delete_play_v1', command, admin), 400); assert.equal(state(), before); } finally { clearFault(); }
  expect(await h.rpc('delete_play_v1', command, admin));
  assert.equal(h.sql(`SELECT count(*) FROM play_scores WHERE play_id=${literal(c.play_id)}::uuid`), '0');
  assert.equal(h.sql(`SELECT count(*) FROM play_comments WHERE play_id=${literal(c.play_id)}::uuid`), '0');
});
tests.set('permission', async () => {
  const c = await create(); const e = await editor(c.play_id);
  const args = [c.command, { p_play_id: c.play_id }, updateCommand(c.play_id, e), deleteCommand(c.play_id, e),
    { p_request_id: nextRequest(), p_payload: input(), p_metadata: { location: null, bga_table_id: 'permission' }, p_positions: [1] }];
  const names = ['create_play_v1', 'get_play_editor_v1', 'update_play_v1', 'delete_play_v1', 'import_bga_play_v1'];
  const before = state();
  for (let i = 0; i < names.length; i++) {
    expect(await h.rpc(names[i], args[i], undefined), 401);
    expect(await h.rpc(names[i], args[i], user), 403, '42501');
    expect(await h.rpc(names[i], args[i], h.token(ordinary, false, { user_metadata: { role: 'admin' } })), 403);
  }
  expect(await h.rpc('create_play_v1', c.command, h.token(actor, true, {}, 'wrong-secret')), 401);
  expect(await h.rpc('create_play_v1', c.command, h.token(actor, true, { exp: 1 })), 401);
  assert.equal(state(), before);
  expect(await h.request('GET', 'receipts?limit=0', undefined, admin), 404);
  expect(await h.request('GET', 'receipts?limit=0', undefined, admin, { 'Accept-Profile': 'kingscore_atomic' }), 406);
  assert.equal(h.sql("SELECT has_function_privilege('authenticated','kingscore_atomic.apply_positions(uuid,text,uuid[],integer[])','EXECUTE')"), 'f');
});
tests.set('global-admin', async () => {
  const c = await create(input(), admin2);
  h.sql(`UPDATE plays SET location='old location',bga_table_id='old table' WHERE id=${literal(c.play_id)}::uuid`);
  const e = await editor(c.play_id); const command = updateCommand(c.play_id, e); command.p_payload.notes = 'edited by another admin';
  expect(await h.rpc('update_play_v1', command, admin)); const after = await editor(c.play_id);
  for (const field of ['user_id', 'location', 'bga_table_id', 'created_at']) assert.equal(after.detail.play[field], e.detail.play[field]);
  assert.equal(after.detail.play.user_id, second);
});
tests.set('score-metadata', async () => {
  const c = await create(input([score('3'), score('4', player2)])); const e = await editor(c.play_id);
  const command = updateCommand(c.play_id, e); command.p_payload.scores[0].score = '8.5';
  command.p_payload.scores[0].color = 'blue'; command.p_payload.scores[0].role = 'mage';
  expect(await h.rpc('update_play_v1', command, admin)); const after = await editor(c.play_id);
  for (const item of command.p_payload.scores) {
    const actual = after.detail.scores.find(row => row.score.id === item.id).score;
    for (const field of ['id', 'player_id', 'score', 'color', 'role']) assert.equal(actual[field], item[field]);
  }
});
tests.set('numeric-wire', async () => {
  for (const value of [null, '0', '-1.25', '9007199254740993.125', '0.' + '0'.repeat(323) + '5']) {
    const c = await create(input([score(value)])); const e = await editor(c.play_id);
    assert.equal(e.detail.scores[0].score.score, value);
    const command = updateCommand(c.play_id, e); command.p_payload.notes = 'precision update';
    expect(await h.rpc('update_play_v1', command, admin)); assert.equal((await editor(c.play_id)).detail.scores[0].score.score, value);
  }
  const before = state();
  for (const value of [1.25, 'NaN', 'Infinity', '01', '1,25', '1' + '0'.repeat(400), '0.' + '0'.repeat(400) + '1']) {
    expect(await h.rpc('create_play_v1', { p_request_id: nextRequest(), p_payload: input([score(value)]) }, admin), 400);
    assert.equal(state(), before);
  }
});
tests.set('jsonb-size', async () => {
  for (const notes of [null, 'quote " slash \\ tab\t newline\n', '😀à漢字']) {
    const payload = { ...input(), notes };
    assert.equal(Number(h.sql(`SELECT octet_length(${jsonSql(payload)}::text)`)), Buffer.byteLength(jsonbText(payload), 'utf8'));
  }
  const payload = { ...input(), notes: '' };
  payload.notes = 'a'.repeat(1048576 - Buffer.byteLength(jsonbText(payload)));
  assert.equal(Number(h.sqlFile('byte-boundary', `SELECT octet_length(${jsonSql(payload)}::text);`)), 1048576);
  await create(payload);
  payload.notes += 'a';
  const before = h.sql('SELECT count(*) FROM kingscore_atomic.receipts');
  expect(await h.rpc('create_play_v1', { p_request_id: nextRequest(), p_payload: payload }, admin), 400);
  assert.equal(h.sql('SELECT count(*) FROM kingscore_atomic.receipts'), before);
});
tests.set('snapshot-revision', async () => {
  const c = await create(); const e = await editor(c.play_id);
  const revisionAt = zone => h.sql(`BEGIN; SET LOCAL timezone=${literal(zone)}; SELECT kingscore_atomic.snapshot(${literal(c.play_id)}::uuid)->>'revision'; ROLLBACK;`);
  assert.equal(revisionAt('UTC'), e.revision); assert.equal(revisionAt('Pacific/Honolulu'), e.revision);
  const consistent = h.sql(`WITH snap AS MATERIALIZED(SELECT kingscore_atomic.snapshot(${literal(c.play_id)}::uuid) s),
    raw_scores AS(SELECT (v->'score')||jsonb_build_object('score',(v->'score'->>'score')::numeric) raw
      FROM snap,jsonb_array_elements(s->'detail'->'scores') v)
    SELECT s->>'revision'='v1:'||encode(sha256(convert_to(jsonb_build_object('play',s->'detail'->'play',
      'scores',(SELECT jsonb_agg(raw ORDER BY raw->>'id') FROM raw_scores))::text,'UTF8')),'hex') FROM snap;`);
  assert.equal(consistent, 't');
  h.sql(`UPDATE games SET name='renamed catalog' WHERE id=${literal(game)}::uuid`);
  assert.equal((await editor(c.play_id)).revision, e.revision);
  expect(await h.request('POST', 'play_comments', { play_id: c.play_id, content: 'does not affect revision' }, user), 201);
  assert.equal((await editor(c.play_id)).revision, e.revision);
});
tests.set('stale-update-delete', async () => {
  const c = await create(); const e = await editor(c.play_id); const command = updateCommand(c.play_id, e); command.p_payload.notes = 'new';
  expect(await h.rpc('update_play_v1', command, admin)); const before = state();
  expect(await h.rpc('update_play_v1', updateCommand(c.play_id, e), admin), 409, 'PT409');
  expect(await h.rpc('delete_play_v1', deleteCommand(c.play_id, e), admin), 409, 'PT409'); assert.equal(state(), before);
  expect(await h.rpc('get_play_editor_v1', { p_play_id: id(999999) }, admin), 404, 'PT404');
});
tests.set('parallel-update', async () => {
  const c = await create(); const e = await editor(c.play_id);
  const a = updateCommand(c.play_id, e), b = updateCommand(c.play_id, e); a.p_payload.notes = 'A'; b.p_payload.notes = 'B';
  const responses = await Promise.all([h.rpc('update_play_v1', a, admin), h.rpc('update_play_v1', b, admin2)]);
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 409]);
  const after = await editor(c.play_id); assert(['A', 'B'].includes(after.detail.play.notes));
  assert.equal(after.detail.scores[0].score.id, e.detail.scores[0].score.id);
});
tests.set('parallel-create-replay', async () => {
  const command = { p_request_id: nextRequest(), p_payload: input() };
  const replies = (await Promise.all([h.rpc('create_play_v1', command, admin), h.rpc('create_play_v1', command, admin)])).map(r => expect(r));
  assert.equal(replies[0].play_id, replies[1].play_id); assert.deepEqual(replies.map(r => r.replayed).sort(), [false, true]);
  assert.equal(h.sql(`SELECT count(*) FROM kingscore_atomic.receipts WHERE request_id=${literal(command.p_request_id)}::uuid`), '1');
});
tests.set('same-key-other-request', async () => {
  const c = await create(); const other = await create(); const e = await editor(c.play_id);
  const update = updateCommand(c.play_id, e); update.p_payload.notes = 'committed request';
  expect(await h.rpc('update_play_v1', update, admin)); const before = state();
  const changed = structuredClone(c.command); changed.p_payload.notes = 'different';
  expect(await h.rpc('create_play_v1', changed, admin), 409, 'PT409');
  expect(await h.rpc('delete_play_v1', { ...deleteCommand(c.play_id, e), p_request_id: c.command.p_request_id }, admin), 409, 'PT409');
  expect(await h.rpc('update_play_v1', { ...update, p_play_id: other.play_id }, admin), 409, 'PT409');
  expect(await h.rpc('update_play_v1', { ...update, p_expected_revision: 'different-revision' }, admin), 409, 'PT409');
  assert.equal(state(), before);
});
tests.set('replay-after-delete', async () => {
  const c = await create(); const e = await editor(c.play_id); const update = updateCommand(c.play_id, e); update.p_payload.notes = 'updated';
  expect(await h.rpc('update_play_v1', update, admin)); const updated = await editor(c.play_id); const deletion = deleteCommand(c.play_id, updated);
  expect(await h.rpc('delete_play_v1', deletion, admin));
  assert.equal(expect(await h.rpc('update_play_v1', update, admin)).replayed, true);
  assert.equal(expect(await h.rpc('delete_play_v1', deletion, admin)).replayed, true);
  assert.equal(expect(await h.rpc('create_play_v1', c.command, admin)).replayed, true);
  expect(await h.rpc('delete_play_v1', { ...deletion, p_request_id: nextRequest() }, admin), 404);
});
tests.set('role-before-replay', async () => {
  const c = await create(); const before = state();
  expect(await h.rpc('create_play_v1', c.command, h.token(actor, false)), 403, '42501'); assert.equal(state(), before);
});
tests.set('ranking', async () => {
  const vectors = rankingVectors();
  const values = vectors.map((v, index) => `(${index},${jsonSql(v.scores)},${v.cooperative},${v.team === null ? 'NULL' : v.team})`).join(',\n');
  const actual = JSON.parse(h.sqlFile('ranking-vectors', `WITH vectors(i,scores,coop,team) AS(VALUES ${values})
    SELECT jsonb_agg(to_jsonb(kingscore_atomic.rank_positions(scores,coop,team)) ORDER BY i) FROM vectors;`));
  assert.deepEqual(actual, vectors.map(v => v.expected)); report.rankingVectors = vectors.length;
  const c = await create(input([score(null), score('0', player2)])); const e = await editor(c.play_id); const command = updateCommand(c.play_id, e);
  command.p_payload.scores.reverse(); command.p_payload.notes = 'only metadata'; command.p_payload.scores[0].color = 'green';
  expect(await h.rpc('update_play_v1', command, admin)); const after = await editor(c.play_id);
  for (const row of e.detail.scores) assert.equal(after.detail.scores.find(v => v.score.id === row.score.id).score.position, row.score.position);
});
tests.set('bga-import', async () => {
  const command = { p_request_id: nextRequest(), p_payload: input([score(null), score('1.25', player2)]),
    p_metadata: { location: 'BGA location', bga_table_id: 'table-123' }, p_positions: [null, 7] };
  const replies = (await Promise.all([h.rpc('import_bga_play_v1', command, admin), h.rpc('import_bga_play_v1', { ...command, p_request_id: nextRequest() }, admin)])).map(r => expect(r));
  assert.equal(replies[0].play_id, replies[1].play_id); assert.deepEqual(replies.map(r => r.skipped).sort(), [false, true]);
  const e = await editor(replies[0].play_id); assert.equal(e.detail.play.location, 'BGA location');
  assert.equal(e.detail.scores.find(v => v.score.player_id === player).score.position, null);
  assert.equal(e.detail.scores.find(v => v.score.player_id === player2).score.position, 7);
  const before = state(); assert.equal(expect(await h.rpc('import_bga_play_v1', command, admin)).replayed, true); assert.equal(state(), before);
  const separate = expect(await h.rpc('import_bga_play_v1', { ...command, p_request_id: nextRequest() }, admin2)); assert.notEqual(separate.play_id, replies[0].play_id);
  expect(await h.rpc('import_bga_play_v1', { ...command, p_request_id: nextRequest(), p_positions: [0, 7] }, admin), 400);
  expect(await h.rpc('import_bga_play_v1', { ...command, p_request_id: nextRequest(), p_payload: { ...command.p_payload, source: 'invalid' } }, admin), 400);
  const same = { ...command, p_request_id: nextRequest(), p_metadata: { ...command.p_metadata, bga_table_id: 'parallel-replay' } };
  const replay = (await Promise.all([h.rpc('import_bga_play_v1', same, admin), h.rpc('import_bga_play_v1', same, admin)])).map(r => expect(r));
  assert.equal(replay[0].play_id, replay[1].play_id); assert.deepEqual(replay.map(r => r.replayed).sort(), [false, true]);
  const failing = { ...same, p_request_id: nextRequest(), p_metadata: { ...same.p_metadata, bga_table_id: 'rollback-import' } };
  const beforeFailure = state(); faultOn(failing.p_request_id);
  try { expect(await h.rpc('import_bga_play_v1', failing, admin), 400); assert.equal(state(), beforeFailure); } finally { clearFault(); }
  const boundary = { ...command, p_request_id: nextRequest(), p_metadata: { location: '', bga_table_id: 'metadata-boundary' } };
  boundary.p_metadata.location = 'a'.repeat(1048576 - Buffer.byteLength(jsonbText(boundary.p_metadata)));
  assert.equal(Number(h.sqlFile('metadata-boundary', `SELECT octet_length(${jsonSql(boundary.p_metadata)}::text);`)), 1048576);
  expect(await h.rpc('import_bga_play_v1', boundary, admin));
  boundary.p_request_id = nextRequest(); boundary.p_metadata.location += 'a';
  const receiptCount = h.sql('SELECT count(*) FROM kingscore_atomic.receipts');
  expect(await h.rpc('import_bga_play_v1', boundary, admin), 400);
  assert.equal(h.sql('SELECT count(*) FROM kingscore_atomic.receipts'), receiptCount);
});
tests.set('elo-sentinels', async () => {
  const sentinels = () => h.sql(`SELECT jsonb_build_object('game',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM player_ratings t),
    'general',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM player_general_ratings t),
    'runs',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM elo_recalc_runs t))::text`);
  const before = sentinels(); const c = await create(); const other = await create();
  h.sql(`INSERT INTO elo_history(play_id,player_id,player_name,rating,rating_change,date,is_general,user_id)
    VALUES(${literal(c.play_id)}::uuid,${literal(player)}::uuid,'synthetic',1300,0,now(),true,${literal(actor)}::uuid),
    (${literal(other.play_id)}::uuid,${literal(player)}::uuid,'synthetic',1300,0,now(),true,${literal(actor)}::uuid)`);
  const e = await editor(c.play_id); const update = updateCommand(c.play_id, e); update.p_payload.scores[0].score = '99';
  expect(await h.rpc('update_play_v1', update, admin)); expect(await h.rpc('delete_play_v1', deleteCommand(c.play_id, await editor(c.play_id)), admin));
  assert.equal(sentinels(), before);
  assert.equal(h.sql(`SELECT count(*) FROM elo_history WHERE play_id=${literal(c.play_id)}::uuid`), '0');
  assert.equal(h.sql(`SELECT count(*) FROM elo_history WHERE play_id=${literal(other.play_id)}::uuid`), '1');
});
tests.set('legacy-writers', async () => {
  h.sqlFile('local-cutover', readFileSync(new URL('candidate-cutover.sql', import.meta.url), 'utf8'));
  const c = await create(); const e = await editor(c.play_id);
  for (const table of ['plays', 'play_scores']) {
    const row = table === 'plays' ? c.play_id : e.detail.scores[0].score.id;
    const body = table === 'plays' ? { played_at: '2026-10-14' } : { play_id: c.play_id, player_id: player };
    expect(await h.request('POST', table, body, admin), 403, '42501');
    expect(await h.request('PATCH', `${table}?id=eq.${row}`, table === 'plays' ? { notes: 'bypass' } : { position: 9 }, admin), 403, '42501');
    expect(await h.request('DELETE', `${table}?id=eq.${row}`, undefined, admin), 403, '42501');
  }
  expect(await h.rpc('replace_play_scores', { p_play_id: c.play_id, p_scores: [] }, admin), 403, '42501');
  expect(await h.request('GET', `plays?id=eq.${c.play_id}`, undefined, admin));
  assert.equal((await editor(c.play_id)).revision, e.revision);
});
tests.set('writer-cooperation', async () => {
  const c = await create(); const e = await editor(c.play_id); const command = updateCommand(c.play_id, e); command.p_payload.notes = 'newer user write';
  expect(await h.rpc('update_play_v1', command, admin)); const newer = await editor(c.play_id); const before = state();
  const stale = applyPositions(c.play_id, e, [7]); assert.notEqual(stale.status, 0); assert.match(stale.stderr, /stale/i); assert.equal(state(), before);
  const direct = h.sql(`BEGIN; SET LOCAL ROLE service_role; UPDATE play_scores SET position=7 WHERE play_id=${literal(c.play_id)}::uuid; ROLLBACK;`, false);
  assert.notEqual(direct.status, 0); assert.match(direct.stderr, /permission denied/i); assert.equal(state(), before);
  const fresh = applyPositions(c.play_id, newer, [7]); assert.equal(fresh.status, 0, fresh.stderr); assert.equal(state(), before); // rollback proves authorization without committing
  const scoreId = newer.detail.scores[0].score.id;
  h.sql(`BEGIN; SET LOCAL ROLE service_role; SELECT kingscore_atomic.apply_positions(${literal(c.play_id)}::uuid,${literal(newer.revision)},
    ARRAY[${literal(scoreId)}::uuid],ARRAY[7]); COMMIT;`);
  const applied = await editor(c.play_id); assert.equal(applied.detail.scores[0].score.position, 7); assert.notEqual(applied.revision, newer.revision);
  expect(await h.rpc('update_play_v1', updateCommand(c.play_id, newer), admin), 409);
});
tests.set('legacy-values', async () => {
  for (const variant of ['manual', 'null-source', 'null-game', 'null-winner', 'inconsistent-team', '64-participants']) {
    const play = id(++requestSequence);
    h.sql(`INSERT INTO plays(id,user_id,game_id,played_at,source) VALUES(${literal(play)}::uuid,${literal(actor)}::uuid,
      ${variant === 'null-game' ? 'NULL' : literal(game) + '::uuid'},'2026-10-14',${variant === 'null-source' ? 'NULL' : literal(variant === 'manual' ? 'manual' : 'offline')})`);
    if (variant === '64-participants') {
      h.sql(`INSERT INTO players(id,user_id,name) SELECT ('00000000-0000-0000-0000-'||lpad(i::text,12,'0'))::uuid,${literal(actor)}::uuid,'synthetic' FROM generate_series(30001,30064) i;
        INSERT INTO play_scores(play_id,player_id,winner) SELECT ${literal(play)}::uuid,('00000000-0000-0000-0000-'||lpad(i::text,12,'0'))::uuid,false FROM generate_series(30001,30064) i`);
    } else h.sql(`INSERT INTO play_scores(play_id,player_id,winner) VALUES(${literal(play)}::uuid,${literal(player)}::uuid,${variant === 'null-winner' ? 'NULL' : 'false'})`);
    if (variant === 'inconsistent-team') h.sql(`UPDATE plays SET is_cooperative=true,team_won=true WHERE id=${literal(play)}::uuid`);
    const e = await editor(play); assert.equal(e.editability.editable, false); assert(e.editability.issues.length > 0);
    if (variant === '64-participants') assert.equal(e.detail.scores.length, 64);
    const before = state(); expect(await h.rpc('update_play_v1', updateCommand(play, e, input()), admin), 400); assert.equal(state(), before);
  }
});

async function run() {
  report.postgres = h.sql('SELECT version()');
  h.sql(`INSERT INTO auth.users(id,email) VALUES(${literal(actor)}::uuid,'admin@example.test'),(${literal(second)}::uuid,'admin2@example.test'),(${literal(ordinary)}::uuid,'user@example.test');
    INSERT INTO games(id,name) VALUES(${literal(game)}::uuid,'atomic fixture');
    INSERT INTO players(id,user_id,name) VALUES(${literal(player)}::uuid,${literal(actor)}::uuid,'one'),(${literal(player2)}::uuid,${literal(actor)}::uuid,'two');
    INSERT INTO player_ratings(player_id,game_id,rating) VALUES(${literal(player)}::uuid,${literal(game)}::uuid,1300);
    INSERT INTO player_general_ratings(player_id,user_id,rating) VALUES(${literal(player)}::uuid,${literal(actor)}::uuid,1400);
    INSERT INTO elo_recalc_runs(status,summary) VALUES('synthetic','{"fixture":true}')`);
  await h.start(); admin = h.token(actor); admin2 = h.token(second); user = h.token(ordinary, false);
  assert.deepEqual([...tests.keys()], manifest.cases.map(c => c.id), 'Acceptance manifest coverage mismatch');
  for (const item of manifest.cases) {
    await tests.get(item.id)(); report.cases.push({ id: item.id, status: 'PASS' }); console.log(`PASS ${item.id}`);
  }
  report.status = 'PASS';
}
try { await run(); }
catch (error) { report.failure = error.message; report.failedCase = manifest.cases[report.cases.length]?.id; throw error; }
finally {
  report.ownGatewayStopped = await h.close();
  if (!report.ownGatewayStopped) { report.status = 'FAIL'; process.exitCode = 1; }
  report.httpRequests = h.requests;
  writeFileSync(path.join(h.directory, 'acceptance-report.json'), JSON.stringify(report, null, 2));
  console.log(`${report.status}: ${report.cases.length}/${manifest.cases.length} local acceptance cases`);
}
