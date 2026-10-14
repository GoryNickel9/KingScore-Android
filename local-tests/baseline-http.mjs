import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { contract } from '../contracts/tests/play-save-validation.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const [postgresBin, pgPortText, runDirectory, postgrestBin] = process.argv.slice(2);
const pgPort = Number(pgPortText);
assert(Number.isInteger(pgPort) && pgPort > 1024 && pgPort < 65536);
assert(path.resolve(runDirectory).startsWith(path.join(root, '.work') + path.sep));
assert(path.resolve(postgrestBin).startsWith(root));
const childEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(PG|PGRST|POSTGRES|NODE_OPTIONS|HTTPS?_PROXY|ALL_PROXY)/i.test(key)));
const report = { status: 'FAIL', mode: 'baseline-characterisation', node: process.version, host: '127.0.0.1', cases: [], futureContractImplemented: false, goTrueLoginTested: false, remoteContacted: false, ownGatewayStopped: false };
const id = value => `00000000-0000-0000-0000-${String(value).padStart(12, '0')}`;
const actor = id(1); const ordinary = id(2); const secondAdmin = id(3); const game = id(10); const player = id(11);
const secret = randomBytes(32).toString('hex');
function token(subject, admin, expires = Math.floor(Date.now() / 1000) + 600, key = secret, spoof = false) {
  const encoded = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = `${encoded({ alg: 'HS256', typ: 'JWT' })}.${encoded({ sub: subject, role: 'authenticated', exp: expires, app_metadata: { role: admin ? 'admin' : 'user' }, user_metadata: { role: spoof ? 'admin' : 'user' } })}`;
  return `${unsigned}.${createHmac('sha256', key).update(unsigned).digest('base64url')}`;
}
function sql(query) {
  const result = spawnSync(path.join(postgresBin, 'psql.exe'), ['-X', '-w', '-h', '127.0.0.1', '-p', String(pgPort), '-U', 'postgres', '-d', 'kingscore_local_tests', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', query], { env: childEnv, encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 0, 'Local SQL check failed');
  return result.stdout.trim();
}
async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}
let gateway;
let gatewayExited;
let base;
async function request(name, method, resource, body, jwt, status, code) {
  assert(/^[a-z0-9_/?=&.,*%()-]+$/i.test(resource), 'Unexpected test resource');
  const response = await fetch(base + resource, { method, redirect: 'error', signal: AbortSignal.timeout(5000),
    headers: { ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}), 'Content-Type': 'application/json', Prefer: 'return=representation' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const raw = await response.text();
  const value = raw ? JSON.parse(raw) : null;
  assert.equal(response.status, status, `${name}: unexpected HTTP status`);
  if (code) assert.equal(value.code, code, `${name}: unexpected SQL code`);
  report.cases.push({ name, method, status, ...(code ? { code } : {}) });
  return { value, raw };
}
async function run() {
  report.postgres = sql('SELECT version()');
  report.postgrest = spawnSync(postgrestBin, ['--version'], { env: childEnv, encoding: 'utf8', windowsHide: true }).stdout.trim();
  sql(`INSERT INTO auth.users(id,email) VALUES ('${actor}','admin@example.test'),('${ordinary}','user@example.test'),('${secondAdmin}','admin2@example.test');
    INSERT INTO games(id,name) VALUES ('${game}','Synthetic game');
    INSERT INTO players(id,user_id,name) VALUES ('${player}','${actor}','Synthetic player');
    INSERT INTO player_ratings(player_id,game_id,rating) VALUES ('${player}','${game}',1400);`);
  const ratingBefore = sql('SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY id),\'[]\')::text FROM player_ratings r');
  const gatewayPort = await freePort(); report.gatewayPort = gatewayPort;
  const configPath = path.join(runDirectory, 'postgrest.conf');
  writeFileSync(configPath, `db-uri = "postgres://local_gateway@127.0.0.1:${pgPort}/kingscore_local_tests"\ndb-schemas = "public"\ndb-anon-role = "anon"\nserver-host = "127.0.0.1"\nserver-port = ${gatewayPort}\njwt-secret = "${secret}"\nlog-level = "error"\n`);
  gateway = spawn(postgrestBin, [configPath], { env: childEnv, stdio: 'ignore', windowsHide: true });
  gatewayExited = new Promise(resolve => { gateway.once('exit', resolve); gateway.once('error', resolve); });
  base = `http://127.0.0.1:${gatewayPort}/`;
  let ready = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    if (gateway.exitCode !== null) throw new Error('Local gateway exited before readiness');
    try { ready = (await fetch(base + 'plays?limit=0', { signal: AbortSignal.timeout(500), redirect: 'error' })).ok; } catch { /* Startup only; no mutation retry. */ }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert(ready, 'Local gateway did not become ready');
  const admin = token(actor, true); const user = token(ordinary, false); const admin2 = token(secondAdmin, true);
  const play = notes => ({ game_id: game, user_id: actor, played_at: '2026-10-14', source: 'offline', notes });
  await request('anonymous cannot create', 'POST', 'plays', play(null), undefined, 401, '42501');
  await request('ordinary account cannot create', 'POST', 'plays', play(null), user, 403, '42501');
  await request('user metadata cannot grant admin', 'POST', 'plays', play(null), token(ordinary, false, undefined, secret, true), 403, '42501');
  await request('bad JWT signature rejected', 'POST', 'plays', play(null), token(actor, true, undefined, 'invalid-key'), 401, 'PGRST301');
  await request('expired JWT rejected', 'POST', 'plays', play(null), token(actor, true, 1), 401, 'PGRST303');
  const created = (await request('first create request commits parent', 'POST', 'plays', play('original'), admin, 201)).value[0];
  await request('second create request fails score FK', 'POST', 'play_scores', [{ play_id: created.id, player_id: id(999), score: '5' }], admin, 409, '23503');
  const partial = (await request('partial create leaves parent without scores', 'GET', `plays?id=eq.${created.id}&select=id,play_scores(id)`, undefined, admin, 200)).value[0];
  assert.deepEqual(partial.play_scores, []);
  const original = (await request('insert exact numeric score and metadata', 'POST', 'play_scores', { play_id: created.id, player_id: player, score: '9007199254740993.125', color: 'red', role: 'builder', winner: true, position: 1 }, admin, 201));
  assert.match(original.raw, /"score"\s*:\s*9007199254740993\.125/);
  const originalId = original.value[0].id;
  await request('metadata edit commits separately', 'PATCH', `plays?id=eq.${created.id}`, { notes: 'new notes' }, admin, 200);
  await request('score replacement fails and rolls back its own transaction', 'POST', 'rpc/replace_play_scores', { p_play_id: created.id, p_scores: [{ player_id: id(999), score: '7', winner: true, position: 1 }] }, admin, 409, '23503');
  const edited = (await request('partial edit keeps new notes and old score', 'GET', `plays?id=eq.${created.id}&select=notes,play_scores(id,color,role)`, undefined, admin, 200)).value[0];
  assert.equal(edited.notes, 'new notes'); assert.equal(edited.play_scores[0].id, originalId); assert.equal(edited.play_scores[0].color, 'red');
  await request('ordinary account rejected by legacy RPC', 'POST', 'rpc/replace_play_scores', { p_play_id: created.id, p_scores: [] }, user, 403, '42501');
  const replacement = (await request('legacy replacement discards supplied metadata and ID', 'POST', 'rpc/replace_play_scores', { p_play_id: created.id, p_scores: [{ id: originalId, player_id: player, score: '7.25', winner: false, position: 2, color: 'blue', role: 'mage' }] }, admin, 200)).value[0];
  assert.notEqual(replacement.id, originalId); assert.equal(replacement.color, null); assert.equal(replacement.role, null);
  await request('direct score UPDATE fails incompatible trigger', 'PATCH', `play_scores?id=eq.${replacement.id}`, { score: '8' }, admin, 400, '42703');
  const simultaneous = await Promise.all([
    request('concurrent edit A accepted without revision', 'PATCH', `plays?id=eq.${created.id}`, { notes: 'A' }, admin, 200),
    request('concurrent edit B accepted without revision', 'PATCH', `plays?id=eq.${created.id}`, { notes: 'B' }, admin2, 200),
  ]); assert(simultaneous.every(result => result.value.length === 1));
  const duplicateA = (await request('create repetition A', 'POST', 'plays', play('same request'), admin, 201)).value[0];
  const duplicateB = (await request('create repetition B creates another ID', 'POST', 'plays', play('same request'), admin, 201)).value[0];
  assert.notEqual(duplicateA.id, duplicateB.id);
  await request('legacy RPC permits empty participant list', 'POST', 'rpc/replace_play_scores', { p_play_id: duplicateA.id, p_scores: [] }, admin, 200);
  await request('comment fixture for cascade', 'POST', 'play_comments', { play_id: created.id, content: 'synthetic comment' }, user, 201);
  await request('other admin changes target after displayed snapshot', 'PATCH', `plays?id=eq.${created.id}`, { notes: 'changed after snapshot' }, admin2, 200);
  await request('stale delete still accepted by existing backend', 'DELETE', `plays?id=eq.${created.id}`, undefined, admin, 200);
  const absent = (await request('delete removes parent', 'GET', `plays?id=eq.${created.id}`, undefined, admin, 200)).value;
  assert.deepEqual(absent, []);
  assert.equal(sql(`SELECT count(*) FROM play_scores WHERE play_id='${created.id}'`), '0');
  assert.equal(sql(`SELECT count(*) FROM play_comments WHERE play_id='${created.id}'`), '0');
  for (const [name, keys] of Object.entries(contract.rpc)) {
    const args = Object.fromEntries(keys.map(key => [key, key.includes('payload') || key.includes('metadata') ? {} : key.includes('positions') ? [] : key.includes('revision') ? 'opaque' : id(900)]));
    await request(`${name} is not implemented in baseline`, 'POST', `rpc/${name}`, args, admin, 404, 'PGRST202');
  }
  assert.equal(sql('SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY id),\'[]\')::text FROM player_ratings r'), ratingBefore);
  report.ratingSentinelUnchanged = true;
  report.problemsReproduced = ['partial-create', 'partial-update', 'lost-score-metadata-and-ids', 'broken-score-update-trigger', 'no-revision-conflict', 'duplicate-create', 'stale-delete'];
  report.status = 'PASS';
}
try { await run(); }
catch (error) { report.failure = error.message; throw error; }
finally {
  if (gateway) {
    gateway.kill();
    await Promise.race([gatewayExited, new Promise(resolve => setTimeout(resolve, 3000))]);
    report.ownGatewayStopped = gateway.exitCode !== null || gateway.signalCode !== null;
    if (!report.ownGatewayStopped) { report.status = 'FAIL'; process.exitCode = 1; }
  }
  report.caseCount = report.cases.length;
  writeFileSync(path.join(runDirectory, 'http-report.json'), JSON.stringify(report, null, 2));
  console.log(`${report.status}: ${report.caseCount} local baseline HTTP checks; future RPC acceptance remains pending.`);
}
