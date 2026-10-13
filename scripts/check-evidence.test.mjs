import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';

const readJson = async p => JSON.parse(await readFile(new URL(p, import.meta.url), 'utf8'));
const { audit } = await readJson('../docs/evidence/supabase-audit.json');
const schema = await readJson('../contracts/v1/schema.json');
const operations = await readJson('../contracts/v1/operations.json');
const probes = await readJson('../docs/evidence/permission-probes.json');
const http = await readJson('../docs/evidence/http-probes.json');
const comparison = await readJson('../docs/evidence/schema-comparison.json');
const column = (table, name) => audit.columns.find(c => c.table_name === table && c.column_name === name);

test('remote audit was read-only and every exposed table has RLS', () => {
  assert.equal(audit.read_only, 'on');
  assert.equal(audit.tables.length, 10);
  assert.ok(audit.tables.every(t => t.rls_enabled));
});

test('Kotlin contract includes all remote columns and preserves nullability', () => {
  assert.equal(Object.keys(schema.tables).length, 8);
  for (const [table, contract] of Object.entries(schema.tables)) {
    const liveColumns = audit.columns.filter(c => c.table_name === table);
    assert.equal(contract.columns.length, liveColumns.length);
    for (const c of contract.columns) {
      const live = column(table, c.name);
      assert.ok(live, `${table}.${c.name}`);
      assert.equal(c.sqlType, live.data_type);
      assert.equal(c.nullable, live.is_nullable === 'YES');
    }
  }
});

test('regenerated public row schema matches the existing web schema', () => {
  assert.equal(comparison.tablesCompared, 10);
  assert.deepEqual(comparison.differences, []);
});

test('HTTP probes establish public reads without retrieving data and Auth requires a session', () => {
  assert.equal(http.probes.length, 9);
  assert.ok(http.probes.slice(0, 8).every(p => p.status === 200 && p.rows === 0));
  assert.equal(http.probes[8].status, 401);
});

test('calendar date, integer ELO and arbitrary-precision scores are distinguished', () => {
  assert.equal(column('plays', 'played_at').data_type, 'date');
  assert.equal(column('play_scores', 'score').data_type, 'numeric');
  assert.equal(column('elo_history', 'rating').data_type, 'integer');
  assert.equal(column('elo_history', 'date').data_type, 'timestamp with time zone');
  assert.equal(column('plays', 'updated_at'), undefined);
  assert.equal(column('games', 'user_id'), undefined);
});

test('non-admin play writes require the admin claim on both USING and CHECK', () => {
  const policies = audit.policies.filter(p => p.schemaname === 'public' && p.tablename === 'plays' && p.cmd !== 'SELECT');
  assert.equal(policies.length, 1);
  assert.equal(policies[0].cmd, 'ALL');
  assert.match(policies[0].qual, /app_metadata.*role.*admin/);
  assert.match(policies[0].with_check, /app_metadata.*role.*admin/);
});

test('comment deletion is author-only even for admin and trigger controls author name', () => {
  const deletes = audit.policies.filter(p => p.schemaname === 'public' && p.tablename === 'play_comments' && ['DELETE', 'ALL'].includes(p.cmd));
  assert.equal(deletes.length, 1);
  assert.equal(deletes[0].qual, '(auth.uid() = user_id)');
  assert.ok(audit.triggers.some(t => t.table_name === 'play_comments' && t.enabled === 'O' && t.definition.includes('set_play_comment_author')));
  assert.match(audit.functions.find(f => f.name === 'set_play_comment_author').definition, /NEW.author_name/);
});

test('score RPC has guarded definer privileges; server-only helpers remain unavailable', () => {
  const rpc = audit.functions.find(f => f.name === 'replace_play_scores');
  assert.equal(rpc.security_definer, true);
  assert.equal(rpc.anon_execute, false);
  assert.equal(rpc.authenticated_execute, true);
  assert.match(rpc.definition, /IS DISTINCT FROM 'admin'/);
  assert.ok(rpc.definition.indexOf('42501') < rpc.definition.indexOf('DELETE FROM'));
  assert.equal(audit.functions.find(f => f.name === 'get_play_user_ids').authenticated_execute, false);
  assert.equal(audit.policies.filter(p => p.tablename === 'elo_recalc_runs').length, 0);
});

test('read-only probes contain actual denials and ownership-filtered plans', () => {
  assert.equal(probes.probes.length, 6);
  assert.ok(probes.probes.every(p => p.passed));
  assert.equal(probes.probes.find(p => p.name === 'anonymous_scores_rpc_denied').sqlstate, '42501');
  assert.equal(probes.probes.find(p => p.name === 'user_scores_rpc_denied').sqlstate, '42501');
  assert.match(JSON.stringify(probes.probes.find(p => p.name === 'anonymous_delete_plan').rows), /One-Time Filter.*false/);
  assert.match(JSON.stringify(probes.probes.find(p => p.name === 'user_delete_plan').rows), /app_metadata.*admin/);
  assert.match(JSON.stringify(probes.probes.find(p => p.name === 'admin_comment_delete_plan').rows), /user_id/);
});

test('app scope cannot enable forbidden auth, ELO, avatar or offline operations', () => {
  assert.equal(operations.requiresInternet, true);
  assert.equal(operations.requiresLoginForData, true);
  assert.deepEqual(operations.auth, ['password-login', 'refresh-session', 'validate-current-user', 'logout']);
  for (const forbidden of ['sign-up', 'change-email', 'change-password', 'change-avatar', 'recalculate-elo', 'offline-data-access']) {
    assert.ok(operations.forbidden.includes(forbidden));
  }
  assert.equal(operations.adminPlayWrites.status, 'blocked-until-transactional-RPCs-are-deployed-and-tested');
});
