import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import { writeFileSync, openSync, closeSync } from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
export const id = value => `00000000-0000-0000-0000-${String(value).padStart(12, '0')}`;
export const literal = value => `'${String(value).replaceAll("'", "''")}'`;

/** Only the runner's loopback cluster and this repository's files can be addressed. */
export class LocalHttp {
  constructor(postgresBin, portText, runDirectory, postgrestBin) {
    this.port = Number(portText);
    assert(Number.isInteger(this.port) && this.port > 1024 && this.port < 65536);
    assert(path.resolve(runDirectory).startsWith(path.join(root, '.work') + path.sep));
    assert(path.resolve(postgrestBin).startsWith(root));
    this.directory = runDirectory; this.gatewayBin = postgrestBin;
    this.psql = path.join(postgresBin, 'psql.exe');
    this.env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(PG|PGRST|POSTGRES|NODE_OPTIONS|HTTPS?_PROXY|ALL_PROXY)/i.test(key)));
    this.secret = randomBytes(32).toString('hex');
    this.requests = 0;
    this.queries = 0;
    this.env.PGPASSFILE = path.join(runDirectory, 'empty.pgpass');
    this.connection = ['-X', '-w', '-q', '-h', '127.0.0.1', '-p', String(this.port), '-U', 'postgres', '-d', 'kingscore_local_tests', '-A', '-t', '-v', 'ON_ERROR_STOP=1'];
  }
  sql(query, expectSuccess = true) {
    // Windows psql's -c argument can lose Unicode through the native code page.
    const file = path.join(this.directory, `query-${++this.queries}.sql`);
    writeFileSync(file, query, 'utf8');
    const result = spawnSync(this.psql, [...this.connection, '-f', file], { env: this.env, encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
    if (expectSuccess) assert.equal(result.status, 0, result.stderr);
    return expectSuccess ? result.stdout.trim() : result;
  }
  sqlFile(name, query) {
    const file = path.join(this.directory, `${name}.sql`);
    writeFileSync(file, query);
    const result = spawnSync(this.psql, [...this.connection, '-f', file], { env: this.env, encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  }
  token(subject, admin = true, overrides = {}, key = this.secret) {
    const encoded = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const unsigned = `${encoded({ alg: 'HS256', typ: 'JWT' })}.${encoded({ sub: subject, role: 'authenticated', exp: Math.floor(Date.now() / 1000) + 3600, app_metadata: { role: admin ? 'admin' : 'user' }, ...overrides })}`;
    return `${unsigned}.${createHmac('sha256', key).update(unsigned).digest('base64url')}`;
  }
  async start() {
    const reservation = createServer();
    // Keep the listener outside Windows' default ephemeral client-port range.
    this.gatewayPort = 20000 + randomBytes(2).readUInt16BE(0) % 20000;
    await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(this.gatewayPort, '127.0.0.1', resolve); });
    await new Promise((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()));
    const config = path.join(this.directory, 'candidate-postgrest.conf');
    writeFileSync(config, `db-uri = "postgres://local_gateway@127.0.0.1:${this.port}/kingscore_local_tests"\ndb-schemas = "public"\ndb-anon-role = "anon"\nserver-host = "127.0.0.1"\nserver-port = ${this.gatewayPort}\njwt-secret = "${this.secret}"\nlog-level = "error"\n`);
    const output = openSync(path.join(this.directory, 'candidate-gateway.stdout.log'), 'w');
    const errors = openSync(path.join(this.directory, 'candidate-gateway.stderr.log'), 'w');
    try { this.gateway = spawn(this.gatewayBin, [config], { env: this.env, stdio: ['ignore', output, errors], windowsHide: true }); }
    finally { closeSync(output); closeSync(errors); }
    this.exited = new Promise(resolve => { this.gateway.once('exit', resolve); this.gateway.once('error', resolve); });
    this.base = `http://127.0.0.1:${this.gatewayPort}/`;
    await new Promise(resolve => setTimeout(resolve, 200));
    for (let attempt = 0; attempt < 50; attempt++) {
      assert.equal(this.gateway.exitCode, null, 'Own gateway exited during startup');
      try { if ((await fetch(this.base + 'plays?limit=0', { signal: AbortSignal.timeout(500), redirect: 'error' })).ok) return; } catch { /* Startup only. */ }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('Own gateway did not become ready');
  }
  async request(method, resource, body, jwt, extraHeaders = {}) {
    assert(/^[a-z0-9_/?=&.,*%()-]+$/i.test(resource));
    this.requests++;
    const response = await fetch(this.base + resource, { method, redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { 'Content-Type': 'application/json', Prefer: 'return=representation', ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}), ...extraHeaders },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const raw = await response.text();
    return { status: response.status, value: raw ? JSON.parse(raw) : null, raw };
  }
  rpc(name, args, jwt) { return this.request('POST', `rpc/${name}`, args, jwt); }
  async close() {
    if (!this.gateway) return true;
    this.gateway.kill();
    await Promise.race([this.exited, new Promise(resolve => setTimeout(resolve, 3000))]);
    return this.gateway.exitCode !== null || this.gateway.signalCode !== null;
  }
}
