import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LocalHttp, id, literal } from './local-http.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const [pg, port, directory, postgrest, serial, adb] = process.argv.slice(2);
assert.match(serial, /^emulator-[0-9]+$/);
assert(path.isAbsolute(adb));
const h = new LocalHttp(pg, port, directory, postgrest);
const actor = id(9301), second = id(9302), ordinary = id(9303), game = id(9310), player = id(9311);
const actors = [actor, second, ordinary];
const proof = randomBytes(24).toString('hex');
const report = { status: 'FAIL', remoteDatabaseContacted: false, authIsSynthetic: true, realGoTrueVerified: false,
  serial, ownGatewayStopped: false, ownBridgeStopped: false, reverseRemoved: false,
  phases: [], mutations: [], reads: [], fixtureErrors: [], authRequests: 0, readRequests: 0, failure: null };
const signals = new Map();
const sockets = new Set();
let bridge, bridgePort, fault, adbSequence = 0, hold, emulatorVerified = false;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const hash = value => createHash('sha256').update(value).digest('hex');
function sqlState() {
  return JSON.parse(h.sql(`SELECT jsonb_build_object('plays',(SELECT count(*) FROM plays WHERE game_id=${literal(game)}::uuid),
    'scores',(SELECT count(*) FROM play_scores WHERE player_id=${literal(player)}::uuid),
    'receipts',(SELECT count(*) FROM kingscore_atomic.receipts WHERE actor_id IN (${actors.map(literal).join(',')})),
    'score',(SELECT score::text FROM play_scores WHERE player_id=${literal(player)}::uuid LIMIT 1))::text`));
}
function user(token) {
  const parts = token?.split('.'); assert.equal(parts?.length, 3, 'Synthetic bearer required');
  const expected = createHmac('sha256', h.secret).update(parts.slice(0, 2).join('.')).digest();
  const signature = Buffer.from(parts[2], 'base64url');
  assert.equal(signature.length, expected.length); assert(timingSafeEqual(signature, expected));
  const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  assert(actors.includes(claims.sub)); assert(claims.exp > Date.now() / 1000);
  return JSON.parse(h.sql(`SELECT jsonb_build_object('id',id,'aud','authenticated','email',email,
    'app_metadata',raw_app_meta_data,'user_metadata',raw_user_meta_data)::text FROM auth.users WHERE id=${literal(claims.sub)}::uuid`));
}
async function body(req) {
  let bytes = 0; const chunks = [];
  for await (const chunk of req) { bytes += chunk.length; assert(bytes <= 1048576 + 4096); chunks.push(chunk); }
  return Buffer.concat(chunks).toString('utf8');
}
const json = (res, value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
async function handle(req, res) {
  assert.equal(req.socket.remoteAddress, '127.0.0.1');
  const url = new URL(req.url, `http://127.0.0.1:${bridgePort}`);
  const raw = await body(req);
  if (url.pathname.startsWith('/__fixture/')) {
    assert.equal(req.headers['x-local-proof'], proof, 'Fixture proof required');
    if (url.pathname === '/__fixture/bootstrap') {
      json(res, { actor, second, ordinary, game, player, tokens: Object.fromEntries(actors.map((a, i) => [a, h.token(a, i < 2)])) }); return;
    }
    if (url.pathname === '/__fixture/fault') {
      assert.equal(JSON.parse(raw).rpc, 'create_play_v1'); assert.equal(fault, undefined);
      fault = 'create_play_v1'; json(res, {}); return;
    }
    if (url.pathname === '/__fixture/signal') {
      const value = JSON.parse(raw); assert(['pending', 'confirmed', 'final'].includes(value.stage));
      assert(Number.isInteger(value.pid)); signals.set(value.stage, value); json(res, {}); return;
    }
    if (url.pathname === '/__fixture/state') {
      json(res, { ...sqlState(), mutations: report.mutations.length, awaitingHttp: Boolean(hold && !hold.destroyed && !hold.writableEnded) }); return;
    }
    throw new Error('Unknown local control path');
  }
  const token = req.headers.authorization?.replace(/^Bearer /, '');
  if (url.pathname === '/auth/v1/user') { report.authRequests++; json(res, user(token)); return; }
  if (url.pathname === '/auth/v1/token') {
    assert.equal(url.searchParams.get('grant_type'), 'refresh_token');
    const subject = JSON.parse(raw).refresh_token; assert(actors.includes(subject));
    const jwt = h.token(subject, subject !== ordinary);
    json(res, { access_token: jwt, refresh_token: subject, expires_in: 3600, token_type: 'bearer', user: user(jwt) }); return;
  }
  assert(url.pathname.startsWith('/rest/v1/'));
  const resource = url.pathname.slice('/rest/v1/'.length);
  const mutation = ['create_play_v1', 'update_play_v1', 'delete_play_v1'].some(name => resource === `rpc/${name}`);
  assert(req.method === 'GET' || (req.method === 'POST' && (mutation || resource === 'rpc/get_play_editor_v1')),
    'Direct writes and unapproved RPCs are forbidden in this Android fixture');
  if (!mutation) report.readRequests++;
  const upstream = await fetch(h.base + resource + url.search, { method: req.method, redirect: 'error',
    signal: AbortSignal.timeout(15000), headers: { Authorization: req.headers.authorization,
      'Content-Type': 'application/json', Accept: 'application/json' }, ...(raw ? { body: raw } : {}) });
  const response = await upstream.text();
  if (!mutation) report.reads.push({ resource, status: upstream.status, error: upstream.ok ? null : JSON.parse(response) });
  if (mutation) {
    const args = JSON.parse(raw);
    report.mutations.push({ rpc: resource.slice(4), requestId: args.p_request_id, bodySha256: hash(raw), status: upstream.status,
      response: JSON.parse(response), actor: user(token).id });
    if (fault === resource.slice(4)) {
      assert.equal(upstream.status, 200); fault = 'consumed'; hold = res; return;
    }
  }
  res.writeHead(upstream.status, { 'Content-Type': 'application/json' }); res.end(response);
}
function command(name, args, timeoutMs = 120000) {
  const file = path.join(directory, `android-${++adbSequence}-${name}.log`);
  const child = spawn(adb, ['-s', serial, ...args], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; child.stdout.on('data', data => output += data); child.stderr.on('data', data => output += data);
  const completion = new Promise((resolve, reject) => {
    const timer = setTimeout(() => { child.kill(); reject(new Error(`Own ADB command timed out: ${name}`)); }, timeoutMs);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('exit', code => { clearTimeout(timer); writeFileSync(file, output); resolve({ code, output, log: path.relative(root, file) }); });
  });
  return { child, completion };
}
async function adbOk(name, args) { const result = await command(name, args).completion; assert.equal(result.code, 0, result.output); return result; }
async function waitSignal(stage, task) {
  let ended = false; task.completion.finally(() => ended = true).catch(() => {});
  for (let attempt = 0; attempt < 900; attempt++) {
    if (signals.has(stage)) return signals.get(stage);
    assert(!ended, `Instrumentation ended before ${stage}; inspect its log`);
    await delay(100);
  }
  throw new Error(`Native phase did not reach ${stage}`);
}
function instrument(mode) {
  return command(mode, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'app.kingscore.android.ui.PlayEditorRpcRecoveryTest',
    '-e', 'mode', mode, '-e', 'localPort', String(bridgePort), '-e', 'localProof', proof,
    '-e', 'disableAnalytics', 'true', 'app.kingscore.android.test/app.kingscore.android.data.local.PendingRecoveryProbe']);
}
async function stopAt(stage, mode) {
  const task = instrument(mode); const signal = await waitSignal(stage, task);
  const pid = (await adbOk(`${stage}-pid`, ['shell', 'pidof', 'app.kingscore.android'])).output.trim();
  assert.equal(Number(pid), signal.pid, 'Live native process differs from signalled process');
  if (stage === 'pending') {
    assert(hold && !hold.destroyed && !hold.writableEnded, 'Native request is no longer awaiting its HTTP response');
    assert.deepEqual(sqlState(), { plays: 1, scores: 1, receipts: 1, score: '9007199254740993.125' });
    assert.equal(report.mutations.length, 1); assert.equal(report.mutations[0].response.replayed, false);
  }
  await adbOk(`${stage}-force-stop`, ['shell', 'am', 'force-stop', 'app.kingscore.android']);
  const gone = await command(`${stage}-gone`, ['shell', 'pidof', 'app.kingscore.android']).completion;
  assert.equal(gone.output.trim(), '', 'Native process did not stop');
  const result = await task.completion;
  assert.match(result.output, /Process crashed|INSTRUMENTATION_FAILED|shortMsg/);
  report.phases.push({ stage, ...signal, forceStopVerified: true, log: result.log, expectedInstrumentTermination: true });
  if (stage === 'pending') { hold?.destroy(); hold = undefined; }
}
try {
  assert.equal(h.sql("SELECT current_database()||'|'||host(inet_server_addr())||'|'||current_setting('kingscore.local_test')"),
    'kingscore_local_tests|127.0.0.1|isolated-fixture-v1');
  assert.equal((await adbOk('avd', ['emu', 'avd', 'name'])).output.split('\n')[0].trim(), 'KingScoreStable35');
  emulatorVerified = true;
  assert.equal((await adbOk('boot', ['shell', 'getprop', 'sys.boot_completed'])).output.trim(), '1');
  h.sql(`INSERT INTO auth.users(id,email,raw_app_meta_data,raw_user_meta_data) VALUES
    (${literal(actor)},'admin-a@example.invalid','{"role":"admin"}','{"name":"RPC Admin A"}'),
    (${literal(second)},'admin-b@example.invalid','{"role":"admin"}','{"name":"RPC Admin B"}'),
    (${literal(ordinary)},'user@example.invalid','{"role":"user"}','{"name":"RPC User"}');
    INSERT INTO games(id,name) VALUES(${literal(game)},'Local RPC game');
    INSERT INTO players(id,name) VALUES(${literal(player)},'Local RPC player');`);
  await h.start();
  bridge = createServer((req, res) => handle(req, res).catch(error => {
    report.fixtureErrors.push({ path: req.url.split('?')[0], name: error.name, message: error.message });
    if (!res.headersSent) json(res, { code: 'LOCAL_FIXTURE_FAILURE' }, 500); else res.destroy();
  }));
  bridge.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  await new Promise((resolve, reject) => { bridge.once('error', reject); bridge.listen(0, '127.0.0.1', resolve); });
  bridgePort = bridge.address().port;
  await adbOk('reverse', ['reverse', `tcp:${bridgePort}`, `tcp:${bridgePort}`]);
  await adbOk('install', ['install', '-r', path.join(root, 'app/build/outputs/apk/debug/app-debug.apk')]);
  await adbOk('install-test', ['install', '-r', path.join(root, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]);
  await stopAt('pending', 'editor-pending');
  await stopAt('confirmed', 'editor-confirmed');
  assert.notEqual(report.phases[0].pid, report.phases[1].pid);
  assert.equal(report.mutations.length, 2);
  assert.equal(report.mutations[0].bodySha256, report.mutations[1].bodySha256);
  assert.equal(report.mutations[0].requestId, report.mutations[1].requestId);
  assert.equal(report.mutations[1].response.replayed, true);
  const final = await instrument('editor-final').completion;
  assert.equal(final.code, 0); assert.match(final.output, /OK \(1 test\)/);
  const signal = signals.get('final'); assert(signal); assert(!report.phases.some(p => p.pid === signal.pid));
  report.phases.push({ stage: 'final', ...signal, log: final.log });
  assert.deepEqual(report.mutations.map(m => m.rpc), ['create_play_v1', 'create_play_v1', 'update_play_v1', 'delete_play_v1']);
  assert.deepEqual(sqlState(), { plays: 0, scores: 0, receipts: 3, score: null });
  report.status = 'PASS';
} catch (error) { report.failure = error.message; }
finally {
  if (emulatorVerified) {
    const result = await command('cleanup-native', ['shell', 'am', 'force-stop', 'app.kingscore.android']).completion;
    report.nativeStopped = result.code === 0;
  }
  if (bridgePort) {
    const result = await command('remove-reverse', ['reverse', '--remove', `tcp:${bridgePort}`]).completion;
    report.reverseRemoved = result.code === 0;
  }
  if (bridge) {
    const closed = new Promise(resolve => bridge.close(resolve));
    for (const socket of sockets) socket.destroy();
    await closed; report.ownBridgeStopped = true;
  }
  report.ownGatewayStopped = await h.close();
  if (!report.ownGatewayStopped || !report.ownBridgeStopped || !report.reverseRemoved || !report.nativeStopped) report.status = 'FAIL';
  writeFileSync(path.join(directory, 'android-editor-report.json'), JSON.stringify(report, null, 2) + '\n');
}
console.log(`${report.status}: Android editor HTTP/cipher/process integration`);
if (report.status !== 'PASS') process.exitCode = 1;
