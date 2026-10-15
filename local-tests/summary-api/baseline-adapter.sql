-- Local fixture only: the captured Oct 13 catalogue predates the deployed atomic/ELO rollout.
-- Reproduce its known removal of the obsolete timestamp trigger (01-install-rpcs.sql:183).
DO $$
BEGIN
  IF current_database()<>'kingscore_local_tests' OR inet_server_addr()<>'127.0.0.1'::inet
    OR current_setting('kingscore.local_test',true) IS DISTINCT FROM 'isolated-fixture-v1' THEN
    RAISE EXCEPTION 'Disposable loopback fixture required';
  END IF;
  IF NOT EXISTS(SELECT FROM pg_trigger WHERE tgrelid='public.play_scores'::regclass
    AND tgname='update_play_scores_updated_at' AND tgfoid='public.update_updated_at_column()'::regprocedure)
    OR EXISTS(SELECT FROM pg_attribute WHERE attrelid='public.play_scores'::regclass
      AND attname='updated_at' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Unexpected historical timestamp trigger profile';
  END IF;
END $$;
DROP TRIGGER update_play_scores_updated_at ON public.play_scores;
-- Only the namespace satisfies the pinned ELO migration prerequisite. Writer RPCs are not under test.
CREATE SCHEMA kingscore_atomic;
