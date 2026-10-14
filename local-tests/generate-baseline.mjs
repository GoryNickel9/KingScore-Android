import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const quoted = value => `"${value.replaceAll('"', '""')}"`;

/** Uses only captured catalog metadata. The fixture has no real accounts or application data. */
export function generateBaseline(audit) {
  const sql = [
    '-- Generated from docs/evidence/supabase-audit.json; synthetic local fixture only.',
    'BEGIN;',
    `DO $$ BEGIN
      IF current_database() <> 'kingscore_local_tests' OR inet_server_addr() <> '127.0.0.1'::inet OR
         current_setting('kingscore.local_test', true) IS DISTINCT FROM 'isolated-fixture-v1' THEN
        RAISE EXCEPTION 'This fixture requires the dedicated local test database';
      END IF;
    END $$;`,
    'CREATE ROLE anon NOLOGIN;',
    'CREATE ROLE authenticated NOLOGIN;',
    'CREATE ROLE service_role NOLOGIN BYPASSRLS;',
    'CREATE ROLE local_gateway LOGIN NOINHERIT;',
    'GRANT anon, authenticated TO local_gateway;',
    'CREATE SCHEMA auth;',
    'CREATE SCHEMA extensions;',
    'CREATE EXTENSION pgcrypto WITH SCHEMA extensions;',
    `CREATE TABLE auth.users (id uuid PRIMARY KEY, email text, raw_user_meta_data jsonb DEFAULT '{}',
      raw_app_meta_data jsonb DEFAULT '{}', created_at timestamptz DEFAULT now(),
      email_confirmed_at timestamptz, last_sign_in_at timestamptz);`,
    `CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
      SELECT COALESCE(NULLIF(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb)
    $$;`,
    `CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
      SELECT (auth.jwt()->>'sub')::uuid
    $$;`,
    'GRANT USAGE ON SCHEMA public, auth, extensions TO anon, authenticated, service_role;',
    'GRANT EXECUTE ON FUNCTION auth.jwt(), auth.uid() TO anon, authenticated, service_role;',
    'SET search_path = public, extensions;',
  ];
  for (const table of audit.tables) {
    const columns = audit.columns.filter(column => column.table_name === table.name);
    if (!columns.length) throw new Error(`Missing columns for ${table.name}`);
    sql.push(`CREATE TABLE public.${quoted(table.name)} (\n${columns.map(column =>
      `  ${quoted(column.column_name)} ${column.data_type}${column.column_default === null ? '' : ` DEFAULT ${column.column_default}`}${column.is_nullable === 'NO' ? ' NOT NULL' : ''}`
    ).join(',\n')}\n);`);
  }
  for (const constraint of [...audit.constraints.filter(item => item.type !== 'f'), ...audit.constraints.filter(item => item.type === 'f')]) {
    sql.push(`ALTER TABLE public.${quoted(constraint.table_name)} ADD CONSTRAINT ${quoted(constraint.name)} ${constraint.definition};`);
  }
  const existingIndexes = new Set(audit.constraints.filter(item => ['p', 'u'].includes(item.type)).map(item => item.name));
  for (const index of audit.indexes) if (!existingIndexes.has(index.indexname)) sql.push(`${index.indexdef};`);
  for (const fn of audit.functions) sql.push(`${fn.definition.trimEnd()};`);
  for (const table of audit.tables) {
    if (table.rls_enabled) sql.push(`ALTER TABLE public.${quoted(table.name)} ENABLE ROW LEVEL SECURITY;`);
    if (table.rls_forced) sql.push(`ALTER TABLE public.${quoted(table.name)} FORCE ROW LEVEL SECURITY;`);
  }
  for (const policy of audit.policies.filter(item => item.schemaname === 'public')) {
    const roles = policy.roles.map(role => role === 'public' ? 'PUBLIC' : quoted(role)).join(', ');
    sql.push(`CREATE POLICY ${quoted(policy.policyname)} ON public.${quoted(policy.tablename)} AS ${policy.permissive} FOR ${policy.cmd} TO ${roles}${policy.qual === null ? '' : ` USING (${policy.qual})`}${policy.with_check === null ? '' : ` WITH CHECK (${policy.with_check})`};`);
  }
  const privileges = new Set(['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER', 'MAINTAIN']);
  for (const grant of audit.table_grants) {
    if (!privileges.has(grant.privilege_type) || !['anon', 'authenticated', 'service_role'].includes(grant.grantee)) throw new Error('Unexpected captured grant');
    sql.push(`GRANT ${grant.privilege_type} ON public.${quoted(grant.table_name)} TO ${quoted(grant.grantee)};`);
  }
  for (const fn of audit.functions) {
    const signature = `public.${quoted(fn.name)}(${fn.arguments})`;
    sql.push(`REVOKE ALL ON FUNCTION ${signature} FROM PUBLIC, anon, authenticated;`);
    if (fn.anon_execute) sql.push(`GRANT EXECUTE ON FUNCTION ${signature} TO anon;`);
    if (fn.authenticated_execute) sql.push(`GRANT EXECUTE ON FUNCTION ${signature} TO authenticated;`);
  }
  for (const trigger of audit.triggers) {
    sql.push(`${trigger.definition};`);
    if (trigger.enabled !== 'O') throw new Error('Unsupported trigger enablement in captured fixture');
  }
  sql.push('COMMIT;', '');
  return sql.join('\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const output = path.resolve(process.argv[2] ?? path.join(root, '.work/local-play-baseline.sql'));
  if (!output.startsWith(path.join(root, '.work') + path.sep)) throw new Error('Generated SQL must stay in this repository .work directory');
  const evidence = JSON.parse(readFileSync(path.join(root, 'docs/evidence/supabase-audit.json'), 'utf8'));
  writeFileSync(output, generateBaseline(evidence.audit), 'utf8');
  console.log('Generated local baseline from captured metadata; no remote request.');
}
