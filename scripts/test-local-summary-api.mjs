import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, openSync, closeSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { generateBaseline } from '../local-tests/generate-baseline.mjs';
import { LocalHttp } from '../local-tests/local-http.mjs';
import { runAcceptance, runBenchmarks } from '../local-tests/summary-api/acceptance.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const postgresBin = process.argv[2] ?? 'C:\\Program Files\\PostgreSQL\\17\\bin';
const gatewayBin = path.join(root, '.work/undone-external-files/postgrest-14.1/postgrest.exe');
const hash = file => createHash('sha256').update(readFileSync(file)).digest('hex');
assert.equal(hash(gatewayBin), '13e575ad59a05df3f1554164883f5a756c8231e4e4dd495ee48053fb3acfad5c');
assert.equal(hash(path.join(root, 'local-tests/summary-api/elo-baseline.sql')),
  '55cc801aa6674fac50c317754e16db8e92e81dcbf998afe5fb404b4ab45dcfbb');
const directory = path.join(root, '.work/summary-api-tests', randomUUID());
mkdirSync(directory, { recursive: true });
const cluster = path.join(directory, 'cluster');
const sql = {
  install:readFileSync(path.join(root,'local-tests/summary-api/01-install.sql'),'utf8'),
  rollback:readFileSync(path.join(root,'local-tests/summary-api/02-rollback.sql'),'utf8'),
};
writeFileSync(path.join(directory,'candidate-install.sql'),sql.install);
writeFileSync(path.join(directory,'candidate-rollback.sql'),sql.rollback);
const acceptanceOnly=process.argv[3]==='--acceptance-only';
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
  !/^(PG|PGRST|POSTGRES|NODE_OPTIONS|HTTPS?_PROXY|ALL_PROXY)/i.test(key)));
env.PGPASSFILE = path.join(directory, 'empty.pgpass');
env.PGCLIENTENCODING = 'UTF8';
writeFileSync(env.PGPASSFILE, '');
let launched = false;
let http;
const report = { status: 'FAIL', host: '127.0.0.1', remoteContacted: false,
  mode:acceptanceOnly?'acceptance-only':'acceptance-and-benchmarks-and-rollback',
  goTrueTested: false, androidChanged: false, cases: [], benchmarks: [],
  ownGatewayStopped: false, ownPostgresStopped: false, startedAt: new Date().toISOString(),
  installSha256: hash(path.join(root, 'local-tests/summary-api/01-install.sql')),
  rollbackSha256: hash(path.join(root, 'local-tests/summary-api/02-rollback.sql')) };
function processStep(name, binary, args, timeout = 120000) {
  const stdout = openSync(path.join(directory, `${name}.stdout.log`), 'w');
  const stderr = openSync(path.join(directory, `${name}.stderr.log`), 'w');
  let result;
  try { result = spawnSync(binary, args, { env, cwd: root, windowsHide: true,
    stdio: ['ignore', stdout, stderr], timeout }); }
  finally { closeSync(stdout); closeSync(stderr); }
  assert.equal(result.status, 0, `${name} failed: ${readFileSync(path.join(directory, `${name}.stderr.log`), 'utf8')}`);
}
try {
  processStep('initdb', path.join(postgresBin, 'initdb.exe'),
    ['-D', cluster, '-U', 'postgres', '--auth=trust', '--encoding=UTF8', '--locale=C']);
  const reservation = createServer();
  await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolve); });
  const port = reservation.address().port;
  await new Promise((resolve, reject) => reservation.close(e => e ? reject(e) : resolve()));
  launched = true;
  processStep('start', path.join(postgresBin, 'pg_ctl.exe'), ['-D', cluster, '-l', path.join(directory, 'postgres.log'),
    '-o', `-h 127.0.0.1 -p ${port}`, '-w', '-t', '30', 'start']);
  const connection = ['-X', '-w', '-h', '127.0.0.1', '-p', String(port), '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'];
  processStep('create-database', path.join(postgresBin, 'psql.exe'), [...connection, '-c', 'CREATE DATABASE kingscore_local_tests']);
  http = new LocalHttp(postgresBin, String(port), directory, gatewayBin);
  http.env.PGCLIENTENCODING = 'UTF8';
  http.sql("ALTER DATABASE kingscore_local_tests SET kingscore.local_test='isolated-fixture-v1'");
  http.sqlFile('baseline', generateBaseline(JSON.parse(readFileSync(path.join(root, 'docs/evidence/supabase-audit.json'), 'utf8')).audit));
  http.sqlFile('baseline-adapter',readFileSync(path.join(root,'local-tests/summary-api/baseline-adapter.sql'),'utf8'));
  http.sqlFile('elo-baseline', readFileSync(path.join(root, 'local-tests/summary-api/elo-baseline.sql'), 'utf8'));
  http.sqlFile('summary-install',sql.install);
  report.postgres = http.sql('SELECT version()');
  report.postgrest = spawnSync(gatewayBin, ['--version'], { env, encoding: 'utf8', windowsHide: true }).stdout.trim();
  report.node = process.version;
  await http.start();
  await runAcceptance(http, report, sql);
  if(!acceptanceOnly) await runBenchmarks(http, report, sql);
  assert.equal(hash(path.join(root,'local-tests/summary-api/01-install.sql')),report.installSha256,'SQL changed during validation');
  assert.equal(hash(path.join(root,'local-tests/summary-api/02-rollback.sql')),report.rollbackSha256,'Rollback changed during validation');
  report.status = 'PASS';
} catch (error) {
  report.failure = error.stack;
  process.exitCode = 1;
  console.error(error.stack);
} finally {
  if (http) report.ownGatewayStopped = await http.close();
  if (launched) {
    try {
      processStep('stop', path.join(postgresBin, 'pg_ctl.exe'), ['-D', cluster, '-m', 'fast', '-w', '-t', '30', 'stop']);
      report.ownPostgresStopped = true;
    } catch (error) { report.stopFailure = error.message; report.status = 'FAIL'; process.exitCode = 1; }
  }
  if (!report.ownGatewayStopped || !report.ownPostgresStopped) { report.status = 'FAIL'; process.exitCode = 1; }
  report.finishedAt = new Date().toISOString();
  report.requests = http?.requests ?? 0;
  writeFileSync(path.join(directory, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ status: report.status, cases: report.cases.length,
    benchmarks: report.benchmarks.length, report: path.join(directory, 'report.json') }));
}
