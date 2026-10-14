import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LocalHttp, id, literal } from './local-http.mjs';

const args = process.argv.slice(2);
const base = new LocalHttp(...args.slice(0, 4));
const migrationPath = path.resolve(args[4], '01-install-rpcs.sql');
assert(migrationPath.startsWith(path.resolve(fileURLToPath(new URL('../.work/', import.meta.url))) + path.sep));
const migration = readFileSync(migrationPath, 'utf8');
const report = { status: 'FAIL', mode: 'prepared-web-migration-profiles', host: '127.0.0.1', remoteContacted: false,
  migrationSha256: createHash('sha256').update(migration).digest('hex'), cases: [], failure: null };
report.harnessSha256 = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex');
const resultFile = path.join(base.directory, 'web-migration-profiles-report.json');
assert.equal(base.sql("SELECT current_database()='kingscore_local_tests' AND inet_server_addr()='127.0.0.1'::inet AND current_setting('kingscore.local_test',true)='isolated-fixture-v1'"), 't');
const actor = id(80001), game = id(80002), players = [id(80003), id(80004)], play = id(80005), scores = [id(80006), id(80007)];
let index = 0;
const ownedDatabases = new Set();
function clone() {
  const database = `web_migration_profile_${++index}`;
  base.sql(`CREATE DATABASE ${database} TEMPLATE kingscore_local_tests`);
  ownedDatabases.add(database);
  base.sql(`ALTER DATABASE ${database} SET kingscore.local_test = 'isolated-fixture-v1'`);
  const directory = path.join(base.directory, `profile-${index}`);
  mkdirSync(directory); writeFileSync(path.join(directory, 'empty.pgpass'), '');
  const h = new LocalHttp(args[0], args[1], directory, args[3]);
  h.connection[h.connection.indexOf('-d') + 1] = database;
  assert.equal(h.sql("SELECT current_database() || ':' || host(inet_server_addr()) || ':' || current_setting('kingscore.local_test')"),
    `${database}:127.0.0.1:isolated-fixture-v1`);
  return h;
}
function seed(h) {
  h.sql(`INSERT INTO auth.users(id,email) VALUES(${literal(actor)}::uuid,'migration-local@example.invalid');
    INSERT INTO public.games(id,name) VALUES(${literal(game)}::uuid,'Synthetic migration game');
    INSERT INTO public.players(id,user_id,name) VALUES(${literal(players[0])}::uuid,${literal(actor)}::uuid,'Synthetic A'),
      (${literal(players[1])}::uuid,${literal(actor)}::uuid,'Synthetic B');
    INSERT INTO public.plays(id,user_id,game_id,played_at,source,is_cooperative,location,bga_table_id)
      VALUES(${literal(play)}::uuid,${literal(actor)}::uuid,${literal(game)}::uuid,'2026-10-14','online',false,'Old location','Old table');
    INSERT INTO public.play_scores(id,play_id,player_id,score,winner,color,role,position)
      VALUES(${literal(scores[0])}::uuid,${literal(play)}::uuid,${literal(players[0])}::uuid,5,false,'red','builder',1),
      (${literal(scores[1])}::uuid,${literal(play)}::uuid,${literal(players[1])}::uuid,4,false,'blue','farmer',2);`);
}
function rows(h) { return h.sql(`SELECT jsonb_build_object('play',(SELECT to_jsonb(p) FROM public.plays p WHERE p.id=${literal(play)}::uuid),
  'scores',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.play_scores s WHERE s.play_id=${literal(play)}::uuid))::text`); }
function fingerprint(h) {
  return h.sql(`SELECT jsonb_build_object(
    'namespaces',(SELECT jsonb_agg(jsonb_build_object('name',nspname,'owner',nspowner,'acl',nspacl) ORDER BY nspname)
      FROM pg_namespace WHERE nspname IN ('public','kingscore_atomic')),
    'columns',(SELECT jsonb_agg(jsonb_build_object('table',c.relname,'attribute',to_jsonb(a),'default',pg_get_expr(d.adbin,d.adrelid)) ORDER BY c.relname,a.attnum)
      FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE n.nspname IN ('public','kingscore_atomic') AND a.attnum>0 AND NOT a.attisdropped),
    'constraints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.oid) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname IN ('public','kingscore_atomic')),
    'triggers',(SELECT jsonb_agg(to_jsonb(t) ORDER BY t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','kingscore_atomic')),
    'functions',(SELECT jsonb_agg(jsonb_build_object('definition',pg_get_functiondef(p.oid),'acl',p.proacl,'owner',p.proowner) ORDER BY p.oid)
      FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','kingscore_atomic') AND p.prokind='f'),
    'plays',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM public.plays p),
    'scores',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.play_scores s))::text`);
}
function test(name, operation) {
  try {
    operation(); report.cases.push({ name, status: 'PASS' });
    process.stdout.write(`[${name}] PASS\n`);
  } finally {
    // Only clones created by this runner in its verified isolated cluster, never application databases.
    for (const database of ownedDatabases) {
      assert.match(database, /^web_migration_profile_[0-9]+$/);
      base.sql(`DROP DATABASE ${database} WITH (FORCE)`);
      ownedDatabases.delete(database);
    }
  }
}
function rejected(name, setup) {
  test(name, () => {
    const h = clone(); setup(h); const before = fingerprint(h);
    const result = h.sql(migration, false);
    assert.notEqual(result.status, 0, 'Unsupported migration profile was accepted');
    assert.equal(fingerprint(h), before, 'Failed installation left schema/data changes');
  });
}
function install(h) { h.sqlFile(`migration-profile-${index}`, migration); }
function invoke(h, name, params) {
  return JSON.parse(h.sql(`BEGIN; SET LOCAL ROLE authenticated;
    SET LOCAL request.jwt.claims=${literal(JSON.stringify({ sub: actor, app_metadata: { role: 'admin' } }))};
    SELECT public.${name}(${params}); COMMIT;`));
}

try {
  test('nondeferrable-bulk-swap-reproduces-unique-violation', () => {
    const h = clone();
    // The captured fixture has a broken timestamp trigger; remove only in this synthetic probe.
    h.sql('DROP TRIGGER update_play_scores_updated_at ON public.play_scores');
    seed(h);
    h.sql('ALTER TABLE public.play_scores ADD CONSTRAINT probe_participants UNIQUE(play_id,player_id)');
    const before = rows(h);
    const result = h.sql(`UPDATE public.play_scores SET player_id=CASE id WHEN ${literal(scores[0])}::uuid THEN ${literal(players[1])}::uuid
      ELSE ${literal(players[0])}::uuid END WHERE play_id=${literal(play)}::uuid`, false);
    assert.notEqual(result.status, 0); assert.match(result.stderr, /duplicate key value violates unique constraint/);
    assert.equal(rows(h), before);
  });

  test('captured-date-schema-installs-preserving-rows-and-legacy-grants', () => {
    const h = clone(); seed(h); const before = rows(h);
    install(h); assert.equal(rows(h), before);
    assert.equal(h.sql("SELECT has_table_privilege('authenticated','public.plays','INSERT')"), 't');
    assert.equal(h.sql("SELECT to_regprocedure('kingscore_atomic.apply_positions(uuid,text,uuid[],integer[])') IS NULL"), 't');
    assert.equal(h.sql("SELECT has_schema_privilege('authenticated','kingscore_atomic','USAGE')"), 'f');
    assert.equal(h.sql("SELECT has_function_privilege('anon','public.create_play_v1(uuid,jsonb)','EXECUTE')"), 'f');
  });

  test('valid-timestamp-trigger-and-created-metadata-preserved', () => {
    const h = clone();
    h.sql('ALTER TABLE public.play_scores ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now()'); seed(h);
    const before = rows(h); const trigger = h.sql("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname='update_play_scores_updated_at'");
    install(h); assert.equal(rows(h), before);
    assert.equal(h.sql("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname='update_play_scores_updated_at'"), trigger);
  });

  test('missing-optional-metadata-added-with-null-history', () => {
    const h = clone(); seed(h);
    h.sql('ALTER TABLE public.plays DROP COLUMN location, DROP COLUMN bga_table_id; ALTER TABLE public.play_scores DROP COLUMN color, DROP COLUMN role');
    const before = JSON.parse(rows(h)); install(h);
    const after = JSON.parse(rows(h));
    for (const key of ['location','bga_table_id']) { assert.equal(after.play[key], null); delete after.play[key]; }
    for (const score of after.scores) for (const key of ['color','role']) { assert.equal(score[key], null); delete score[key]; }
    assert.deepEqual(after, before);
  });

  for (const reverse of [false, true]) test(`supported-unique-${reverse ? 'reversed' : 'normal'}-rpc-swap-preserves-score-identities`, () => {
    const h = clone();
    h.sql("ALTER TABLE public.play_scores ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT '2025-10-03T10:00:00Z', ADD COLUMN legacy_note text DEFAULT 'Preserve historical metadata'");
    seed(h);
    h.sql(`ALTER TABLE public.play_scores ADD CONSTRAINT participants_unique UNIQUE(${reverse ? 'player_id,play_id' : 'play_id,player_id'}); COMMENT ON CONSTRAINT participants_unique ON public.play_scores IS 'Keep constraint comment'; COMMENT ON INDEX public.participants_unique IS 'Keep index comment'`);
    const before = JSON.parse(rows(h)); install(h); assert.deepEqual(JSON.parse(rows(h)), before);
    assert.equal(h.sql("SELECT condeferrable AND NOT condeferred FROM pg_constraint WHERE conname='participants_unique'"), 't');
    assert.equal(h.sql("SELECT obj_description(oid,'pg_constraint') FROM pg_constraint WHERE conname='participants_unique'"), 'Keep constraint comment');
    assert.equal(h.sql("SELECT obj_description('public.participants_unique'::regclass,'pg_class')"), 'Keep index comment');
    const snapshot = invoke(h, 'get_play_editor_v1', `${literal(play)}::uuid`);
    const payload = { game_id: game, played_at: '2026-10-14', duration_minutes: null, source: 'online', notes: null,
      is_cooperative: false, team_won: null, scores: before.scores.map((s, i) => ({ id: s.id, player_id: players[1-i],
        score: String(s.score), winner: s.winner, color: s.color, role: s.role })) };
    invoke(h, 'update_play_v1', `${literal(id(80008))}::uuid,${literal(play)}::uuid,${literal(snapshot.revision)},${literal(JSON.stringify(payload))}::jsonb`);
    const after = JSON.parse(rows(h));
    for (let i = 0; i < 2; i++) {
      const previous = before.scores[i], changed = after.scores[i];
      assert.equal(changed.player_id, players[1-i]);
      for (const key of Object.keys(previous).filter(key => !['player_id','position','updated_at'].includes(key))) {
        assert.deepEqual(changed[key], previous[key], `Retained score metadata changed: ${key}`);
      }
    }
    for (const key of ['id','user_id','location','bga_table_id','created_at']) assert.equal(after.play[key], before.play[key]);
    const upsert = h.sql(`INSERT INTO public.play_scores(play_id,player_id,score,winner)
      VALUES(${literal(play)}::uuid,${literal(players[0])}::uuid,1,false) ON CONFLICT(play_id,player_id) DO NOTHING`, false);
    assert.notEqual(upsert.status, 0);
    assert.match(upsert.stderr, /ON CONFLICT does not support deferrable unique/);
  });

  test('legacy-bga-duplicates-after-install-reject-import-without-receipt-or-data-change', () => {
    const h = clone(); seed(h); install(h);
    h.sql(`UPDATE public.plays SET bga_table_id='123456' WHERE id=${literal(play)}::uuid;
      INSERT INTO public.plays(user_id,game_id,played_at,source,is_cooperative,bga_table_id)
      VALUES(${literal(actor)}::uuid,${literal(game)}::uuid,'2026-10-14','online',false,'123456')`);
    const before = fingerprint(h);
    const payload = { game_id: game, played_at: '2026-10-14', duration_minutes: null, source: 'online', notes: null,
      is_cooperative: false, team_won: null,
      scores: [{ id: null, player_id: players[0], score: '5', winner: false, color: null, role: null }] };
    const result = h.sql(`\\set VERBOSITY verbose
      BEGIN; SET LOCAL ROLE authenticated;
      SET LOCAL request.jwt.claims=${literal(JSON.stringify({ sub: actor, app_metadata: { role: 'admin' } }))};
      SELECT public.import_bga_play_v1(${literal(id(80009))}::uuid,${literal(JSON.stringify(payload))}::jsonb,
        '{"location":null,"bga_table_id":"123456"}'::jsonb,'[1]'::jsonb); COMMIT;`, false);
    assert.notEqual(result.status, 0); assert.match(result.stderr, /PT409/);
    assert.equal(fingerprint(h), before);
    assert.equal(h.sql('SELECT count(*) FROM kingscore_atomic.receipts'), '0');
  });

  rejected('timestamp-played-at-refused-with-full-rollback', h => h.sql('ALTER TABLE public.plays ALTER COLUMN played_at TYPE timestamptz USING played_at::timestamptz'));
  rejected('bga-duplicates-refused-without-data-cleanup', h => {
    seed(h); h.sql(`INSERT INTO public.plays(user_id,game_id,played_at,source,is_cooperative,bga_table_id)
      VALUES(${literal(actor)}::uuid,${literal(game)}::uuid,'2026-10-14','online',false,'Old table')`);
  });
  rejected('unsupported-unique-include-refused-with-full-rollback', h => h.sql('CREATE UNIQUE INDEX participants_with_include ON public.play_scores(play_id,player_id) INCLUDE(score)'));
  rejected('numeric-scale-refused-with-full-rollback', h => h.sql('ALTER TABLE public.play_scores ALTER COLUMN score TYPE numeric(12,2)'));
  rejected('inheritance-refused-before-data-scan', h => h.sql('CREATE TABLE public.child_plays() INHERITS(public.plays)'));
  rejected('unknown-active-trigger-refused', h => h.sql(`CREATE FUNCTION public.unknown_score_trigger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
    CREATE TRIGGER unknown_score_trigger BEFORE UPDATE ON public.play_scores FOR EACH ROW EXECUTE FUNCTION public.unknown_score_trigger()`));
  rejected('dependent-foreign-key-prevents-unique-conversion', h => h.sql(`ALTER TABLE public.play_scores ADD CONSTRAINT referenced_participants UNIQUE(play_id,player_id);
    CREATE TABLE public.score_links(play_id uuid,player_id uuid,FOREIGN KEY(play_id,player_id) REFERENCES public.play_scores(play_id,player_id))`));
  rejected('missing-auth-primary-key-refused-with-full-rollback', h => h.sql('ALTER TABLE auth.users DROP CONSTRAINT users_pkey CASCADE'));
  rejected('repeated-install-refused-without-changing-first-install', install);
  report.status = 'PASS';
  report.ownedProfileDatabasesRemoved = ownedDatabases.size === 0;
} catch (error) {
  report.failure = error.message; throw error;
} finally { writeFileSync(resultFile, JSON.stringify(report, null, 2)); }
