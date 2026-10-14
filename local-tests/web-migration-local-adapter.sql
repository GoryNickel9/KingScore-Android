-- Synthetic acceptance fixture only. Excluded from prepared web migration/production APIs.
BEGIN;
DO $$ BEGIN
  IF current_database()<>'kingscore_local_tests' OR inet_server_addr()<>'127.0.0.1'::inet OR
     current_setting('kingscore.local_test',true) IS DISTINCT FROM 'isolated-fixture-v1' THEN
    RAISE EXCEPTION 'Dedicated local fixture required';
  END IF;
END $$;
CREATE FUNCTION kingscore_atomic.apply_positions(p_play_id uuid,p_expected_revision text,p_score_ids uuid[],p_positions integer[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' SET timezone='UTC' AS $$
DECLARE snapshot jsonb; n integer; i integer;
BEGIN
  IF current_setting('role')<>'service_role' THEN RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Server role required'; END IF;
  PERFORM 1 FROM public.plays WHERE id=p_play_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='PT404',MESSAGE='Play not found'; END IF;
  PERFORM 1 FROM public.play_scores WHERE play_id=p_play_id ORDER BY id FOR UPDATE;
  snapshot:=kingscore_atomic.snapshot(p_play_id);
  IF p_expected_revision IS NULL OR snapshot->>'revision'<>p_expected_revision THEN RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='Server computation is stale'; END IF;
  SELECT count(*) INTO n FROM public.play_scores WHERE play_id=p_play_id;
  IF n NOT BETWEEN 1 AND 63 OR p_score_ids IS NULL OR p_positions IS NULL OR
     array_ndims(p_score_ids) IS DISTINCT FROM 1 OR array_ndims(p_positions) IS DISTINCT FROM 1 OR
     array_lower(p_score_ids,1) IS DISTINCT FROM 1 OR array_lower(p_positions,1) IS DISTINCT FROM 1 OR
     cardinality(p_score_ids)<>n OR cardinality(p_positions)<>n OR
     (SELECT count(DISTINCT v) FROM unnest(p_score_ids) v)<>n OR
     EXISTS(SELECT 1 FROM unnest(p_score_ids) v WHERE NOT EXISTS(SELECT 1 FROM public.play_scores s WHERE s.id=v AND s.play_id=p_play_id)) OR
     EXISTS(SELECT 1 FROM unnest(p_positions) v WHERE v IS NOT NULL AND v<1) THEN
    RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid server position vector';
  END IF;
  FOR i IN 1..n LOOP UPDATE public.play_scores SET position=p_positions[i] WHERE id=p_score_ids[i]; END LOOP;
  RETURN jsonb_build_object('play_id',p_play_id,'revision',kingscore_atomic.snapshot(p_play_id)->>'revision');
END $$;
REVOKE ALL ON FUNCTION kingscore_atomic.apply_positions(uuid,text,uuid[],integer[]) FROM PUBLIC,anon,authenticated,service_role;
GRANT USAGE ON SCHEMA kingscore_atomic TO service_role;
GRANT EXECUTE ON FUNCTION kingscore_atomic.apply_positions(uuid,text,uuid[],integer[]) TO service_role;
COMMIT;
