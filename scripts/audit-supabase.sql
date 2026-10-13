-- Catalog inspection only. No application rows, credentials or schema changes.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

SELECT jsonb_build_object(
  'database', current_database(),
  'read_only', current_setting('transaction_read_only'),
  'server_version', current_setting('server_version'),
  'tables', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.name) FROM (
    SELECT c.relname AS name, c.relrowsecurity AS rls_enabled,
      c.relforcerowsecurity AS rls_forced
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
  ) t),
  'columns', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.table_name, t.ordinal_position) FROM (
    SELECT table_name, column_name, ordinal_position, data_type, udt_name,
      is_nullable, column_default, numeric_precision, numeric_scale
    FROM information_schema.columns WHERE table_schema = 'public'
  ) t),
  'constraints', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.table_name, t.name) FROM (
    SELECT c.relname AS table_name, con.conname AS name, con.contype AS type,
      pg_get_constraintdef(con.oid) AS definition
    FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
  ) t),
  'indexes', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.tablename, t.indexname) FROM (
    SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'
  ) t),
  'policies', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.schemaname, t.tablename, t.policyname) FROM (
    SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
    FROM pg_policies WHERE schemaname IN ('public', 'storage')
  ) t),
  'table_grants', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.table_name, t.grantee, t.privilege_type) FROM (
    SELECT table_name, grantee, privilege_type FROM information_schema.table_privileges
    WHERE table_schema = 'public' AND grantee IN ('anon', 'authenticated', 'service_role')
  ) t),
  'functions', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.name) FROM (
    SELECT p.proname AS name, pg_get_function_identity_arguments(p.oid) AS arguments,
      p.prosecdef AS security_definer, p.provolatile AS volatility, p.proconfig AS settings,
      has_function_privilege('anon', p.oid, 'EXECUTE') AS anon_execute,
      has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
      pg_get_functiondef(p.oid) AS definition
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prokind = 'f'
      AND p.proname IN ('replace_play_scores', 'admin_list_registrations', 'get_play_user_ids',
        'set_play_comment_author', 'guard_linked_player_profile_update', 'update_updated_at_column')
  ) t),
  'triggers', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.table_name, t.name) FROM (
    SELECT c.relname AS table_name, tr.tgname AS name, tr.tgenabled AS enabled,
      pg_get_triggerdef(tr.oid) AS definition
    FROM pg_trigger tr JOIN pg_class c ON c.oid = tr.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND NOT tr.tgisinternal
  ) t)
) AS audit;

ROLLBACK;
