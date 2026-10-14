-- Local enforcement test, separate from installing the candidate endpoints.
BEGIN;
DO $$ BEGIN
  IF current_database()<>'kingscore_local_tests' OR inet_server_addr()<>'127.0.0.1'::inet OR
     current_setting('kingscore.local_test',true) IS DISTINCT FROM 'isolated-fixture-v1' THEN
    RAISE EXCEPTION 'Dedicated local fixture required';
  END IF;
END $$;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON public.plays,public.play_scores FROM PUBLIC,anon,authenticated,service_role;
REVOKE EXECUTE ON FUNCTION public.replace_play_scores(uuid,jsonb) FROM PUBLIC,anon,authenticated,service_role;
COMMIT;
