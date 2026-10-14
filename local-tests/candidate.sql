-- Local candidate only. Never apply to a cloud project or an application database.
BEGIN;
DO $$ BEGIN
  IF current_database() <> 'kingscore_local_tests' OR inet_server_addr() <> '127.0.0.1'::inet OR
     current_setting('kingscore.local_test',true) IS DISTINCT FROM 'isolated-fixture-v1' THEN
    RAISE EXCEPTION 'Dedicated local fixture required';
  END IF;
END $$;

CREATE SCHEMA kingscore_atomic;
REVOKE ALL ON SCHEMA kingscore_atomic FROM PUBLIC, anon, authenticated, service_role;
CREATE TABLE kingscore_atomic.receipts (
  actor_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  request_id uuid NOT NULL,
  request jsonb NOT NULL,
  result jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(actor_id,request_id)
);
ALTER TABLE kingscore_atomic.receipts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON kingscore_atomic.receipts FROM PUBLIC, anon, authenticated, service_role;
DROP TRIGGER update_play_scores_updated_at ON public.play_scores;
REVOKE TRUNCATE, REFERENCES, TRIGGER ON public.plays, public.play_scores FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION kingscore_atomic.require_admin() RETURNS uuid
LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE actor uuid := auth.uid();
BEGIN
  IF actor IS NULL OR NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor) THEN
    RAISE EXCEPTION USING ERRCODE='PT401', MESSAGE='Verified account required';
  END IF;
  IF auth.jwt()->'app_metadata'->>'role' IS DISTINCT FROM 'admin' THEN
    RAISE EXCEPTION USING ERRCODE='42501', MESSAGE='Administrator required';
  END IF;
  RETURN actor;
END $$;

CREATE FUNCTION kingscore_atomic.valid_score(value text) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE approximate double precision; exact numeric;
BEGIN
  IF value IS NULL THEN RETURN true; END IF;
  IF value !~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$' THEN RETURN false; END IF;
  approximate := value::double precision;
  IF approximate IN ('Infinity'::double precision,'-Infinity'::double precision,'NaN'::double precision) THEN RETURN false; END IF;
  exact := value::numeric;
  RETURN approximate <> 0 OR exact=0;
EXCEPTION WHEN data_exception THEN RETURN false;
END $$;

CREATE FUNCTION kingscore_atomic.compare_scores(scores jsonb, a integer, b integer) RETURNS integer
LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE av double precision; bv double precision;
BEGIN
  IF (scores->a->>'winner')::boolean IS DISTINCT FROM (scores->b->>'winner')::boolean THEN
    RETURN CASE WHEN (scores->a->>'winner')::boolean THEN -1 ELSE 1 END;
  END IF;
  IF (scores->a->>'winner')::boolean THEN RETURN 0; END IF;
  IF scores->a->'score'='null'::jsonb OR scores->b->'score'='null'::jsonb THEN RETURN 0; END IF;
  av := (scores->a->>'score')::double precision; bv := (scores->b->>'score')::double precision;
  RETURN CASE WHEN av>bv THEN -1 WHEN av<bv THEN 1 ELSE 0 END;
END $$;

-- For arrays below 64, V8 uses one natural run followed by stable binary insertion.
-- NULL comparison is nontransitive; a SQL ORDER BY or NULLS LAST changes the site rule.
CREATE FUNCTION kingscore_atomic.rank_positions(scores jsonb, cooperative boolean, team boolean) RETURNS integer[]
LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE n integer:=jsonb_array_length(scores); ordering integer[]; positions integer[];
  run_end integer:=2; descending boolean; pivot integer; lo integer; hi integer; mid integer; i integer; j integer;
BEGIN
  IF n NOT BETWEEN 1 AND 63 THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Participant count outside V1'; END IF;
  IF cooperative AND team IS NOT NULL THEN RETURN array_fill(CASE WHEN team THEN 1 ELSE 2 END,ARRAY[n]); END IF;
  SELECT array_agg(k) INTO ordering FROM generate_series(0,n-1) k;
  positions := array_fill(0,ARRAY[n]);
  IF n>1 THEN
    descending := kingscore_atomic.compare_scores(scores,ordering[2],ordering[1])<0;
    WHILE run_end<n LOOP
      EXIT WHEN CASE WHEN descending THEN kingscore_atomic.compare_scores(scores,ordering[run_end+1],ordering[run_end])>=0
                    ELSE kingscore_atomic.compare_scores(scores,ordering[run_end+1],ordering[run_end])<0 END;
      run_end:=run_end+1;
    END LOOP;
    IF descending THEN
      FOR i IN 1..(run_end/2) LOOP
        pivot:=ordering[i]; ordering[i]:=ordering[run_end+1-i]; ordering[run_end+1-i]:=pivot;
      END LOOP;
    END IF;
    IF run_end<n THEN
      FOR i IN (run_end+1)..n LOOP
        pivot:=ordering[i]; lo:=1; hi:=i;
        WHILE lo<hi LOOP
          mid:=(lo+hi)/2;
          IF kingscore_atomic.compare_scores(scores,pivot,ordering[mid])<0 THEN hi:=mid; ELSE lo:=mid+1; END IF;
        END LOOP;
        j:=i;
        WHILE j>lo LOOP ordering[j]:=ordering[j-1]; j:=j-1; END LOOP;
        ordering[lo]:=pivot;
      END LOOP;
    END IF;
  END IF;
  FOR i IN 1..n LOOP positions[ordering[i]+1]:=CASE WHEN (scores->ordering[i]->>'winner')::boolean THEN 1 ELSE i END; END LOOP;
  RETURN positions;
END $$;

CREATE FUNCTION kingscore_atomic.validate_input(payload jsonb, creating boolean, play_id uuid)
RETURNS void LANGUAGE plpgsql SET search_path='' AS $$
DECLARE item jsonb; item_id uuid; player uuid; game uuid; day date; duration numeric;
  players uuid[]:='{}'; ids uuid[]:='{}';
  keys text[]:=ARRAY['game_id','played_at','duration_minutes','source','notes','is_cooperative','team_won','scores'];
BEGIN
  IF payload IS NULL OR jsonb_typeof(payload)<>'object' THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Payload object required'; END IF;
  IF octet_length(payload::text)>1048576 OR NOT payload ?& keys OR
     (SELECT count(*) FROM jsonb_object_keys(payload))<>cardinality(keys) THEN
    RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid payload fields or bytes';
  END IF;
  IF jsonb_typeof(payload->'game_id')<>'string' OR jsonb_typeof(payload->'played_at')<>'string' OR
     payload->>'played_at' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' OR left(payload->>'played_at',4)='0000' OR
     jsonb_typeof(payload->'source')<>'string' OR payload->>'source' NOT IN ('offline','online') OR
     jsonb_typeof(payload->'notes') NOT IN ('string','null') OR jsonb_typeof(payload->'is_cooperative')<>'boolean' OR
     jsonb_typeof(payload->'team_won') NOT IN ('boolean','null') OR jsonb_typeof(payload->'scores')<>'array' THEN
    RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid field types';
  END IF;
  game:=(payload->>'game_id')::uuid; day:=(payload->>'played_at')::date;
  IF payload->>'game_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Canonical game UUID required';
  END IF;
  IF extract(year FROM day) NOT BETWEEN 1 AND 9999 THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Date outside V1'; END IF;
  PERFORM 1 FROM public.games WHERE id=game FOR KEY SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Game does not exist'; END IF;
  IF payload->'duration_minutes'<>'null'::jsonb THEN
    IF jsonb_typeof(payload->'duration_minutes')<>'number' THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Integer duration required'; END IF;
    duration:=(payload->>'duration_minutes')::numeric;
    IF duration<>trunc(duration) OR duration NOT BETWEEN 0 AND 2147483647 THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid duration'; END IF;
  END IF;
  IF NOT (payload->>'is_cooperative')::boolean AND payload->'team_won'<>'null'::jsonb THEN
    RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Competitive team outcome forbidden';
  END IF;
  IF jsonb_array_length(payload->'scores') NOT BETWEEN 1 AND 63 THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid participant count'; END IF;
  FOR item IN SELECT value FROM jsonb_array_elements(payload->'scores') LOOP
    IF jsonb_typeof(item)<>'object' OR NOT item ?& ARRAY['id','player_id','score','winner','color','role'] OR
       (SELECT count(*) FROM jsonb_object_keys(item))<>6 OR
       jsonb_typeof(item->'id') NOT IN ('null','string') OR jsonb_typeof(item->'player_id')<>'string' OR
       jsonb_typeof(item->'score') NOT IN ('null','string') OR jsonb_typeof(item->'winner')<>'boolean' OR
       jsonb_typeof(item->'color') NOT IN ('null','string') OR jsonb_typeof(item->'role') NOT IN ('null','string') THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid score fields';
    END IF;
    player:=(item->>'player_id')::uuid; item_id:=(item->>'id')::uuid;
    IF item->>'player_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' OR
       (item_id IS NOT NULL AND item->>'id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Canonical participant UUID required';
    END IF;
    IF player=ANY(players) OR (item_id IS NOT NULL AND item_id=ANY(ids)) OR (creating AND item_id IS NOT NULL) THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Duplicate participant or invalid score ID';
    END IF;
    players:=array_append(players,player); ids:=array_append(ids,item_id);
    IF item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.play_scores s WHERE s.id=item_id AND s.play_id=validate_input.play_id) THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Score belongs to another play';
    END IF;
    IF NOT creating AND item_id IS NULL AND EXISTS(SELECT 1 FROM public.play_scores s WHERE s.play_id=validate_input.play_id AND s.player_id=player) THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Existing participant requires its score ID';
    END IF;
    IF NOT kingscore_atomic.valid_score(item->>'score') THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid exact score'; END IF;
    IF (payload->>'is_cooperative')::boolean AND payload->'team_won'<>'null'::jsonb AND item->'winner'<>payload->'team_won' THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Cooperative result inconsistent';
    END IF;
  END LOOP;
  FOR player IN SELECT value FROM unnest(players) value ORDER BY value LOOP
    PERFORM 1 FROM public.players WHERE id=player FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Player does not exist'; END IF;
  END LOOP;
EXCEPTION WHEN data_exception THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid value representation';
END $$;

CREATE FUNCTION kingscore_atomic.editability(play_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE p public.plays; issues text[]:='{}'; n integer;
BEGIN
  SELECT * INTO p FROM public.plays WHERE id=play_id;
  IF p.source IS NULL OR p.source NOT IN ('offline','online') THEN issues:=array_append(issues,'source-not-supported'); END IF;
  IF p.game_id IS NULL THEN issues:=array_append(issues,'missing-game'); END IF;
  IF p.is_cooperative IS NULL THEN issues:=array_append(issues,'nullable-mode'); END IF;
  IF extract(year FROM p.played_at) NOT BETWEEN 1 AND 9999 THEN issues:=array_append(issues,'date-outside-V1'); END IF;
  IF p.duration_minutes<0 THEN issues:=array_append(issues,'negative-duration'); END IF;
  IF (NOT p.is_cooperative AND p.team_won IS NOT NULL) OR
     (p.is_cooperative AND p.team_won IS NOT NULL AND EXISTS(SELECT 1 FROM public.play_scores s
       WHERE s.play_id=editability.play_id AND s.winner IS DISTINCT FROM p.team_won)) THEN
    issues:=array_append(issues,'inconsistent-team-outcome');
  END IF;
  SELECT count(*) INTO n FROM public.play_scores s WHERE s.play_id=editability.play_id;
  IF n NOT BETWEEN 1 AND 63 THEN issues:=array_append(issues,'participant-count'); END IF;
  IF EXISTS(SELECT 1 FROM public.play_scores s WHERE s.play_id=editability.play_id AND s.winner IS NULL) THEN issues:=array_append(issues,'nullable-winner'); END IF;
  IF EXISTS(SELECT 1 FROM public.play_scores s WHERE s.play_id=editability.play_id AND s.player_id IS NULL) THEN issues:=array_append(issues,'missing-player'); END IF;
  IF EXISTS(SELECT player_id FROM public.play_scores s WHERE s.play_id=editability.play_id GROUP BY player_id HAVING count(*)>1) THEN issues:=array_append(issues,'duplicate-player'); END IF;
  IF EXISTS(SELECT 1 FROM public.play_scores s WHERE s.play_id=editability.play_id AND NOT kingscore_atomic.valid_score(s.score::text)) THEN issues:=array_append(issues,'score-outside-V1'); END IF;
  RETURN jsonb_build_object('editable',cardinality(issues)=0,'issues',to_jsonb(issues));
END $$;

CREATE FUNCTION kingscore_atomic.snapshot(play_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path='' SET timezone='UTC' AS $$
  SELECT jsonb_build_object(
    'detail',jsonb_build_object('play',to_jsonb(p),
      'game',(SELECT to_jsonb(g) FROM public.games g WHERE g.id=p.game_id),
      'scores',COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'score',to_jsonb(s)||jsonb_build_object('score',s.score::text),
        'player',(SELECT to_jsonb(pl) FROM public.players pl WHERE pl.id=s.player_id))
        ORDER BY s.position NULLS LAST,s.id) FROM public.play_scores s WHERE s.play_id=p.id),'[]'::jsonb)),
    'revision','v1:'||encode(sha256(convert_to(jsonb_build_object('play',to_jsonb(p),'scores',COALESCE(
      (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM public.play_scores s WHERE s.play_id=p.id),'[]'::jsonb))::text,'UTF8')),'hex'),
    'editability',kingscore_atomic.editability(p.id))
  FROM public.plays p WHERE p.id=play_id;
$$;

CREATE FUNCTION kingscore_atomic.write_scores(play_id uuid, payload jsonb, positions integer[], preserve boolean)
RETURNS void LANGUAGE plpgsql SET search_path='' AS $$
DECLARE item jsonb; ordinal integer:=0; existing uuid; keep uuid[];
BEGIN
  SELECT COALESCE(array_agg((value->>'id')::uuid) FILTER(WHERE value->>'id' IS NOT NULL),'{}'::uuid[])
    INTO keep FROM jsonb_array_elements(payload->'scores');
  DELETE FROM public.play_scores s WHERE s.play_id=write_scores.play_id AND NOT s.id=ANY(keep);
  FOR item IN SELECT value FROM jsonb_array_elements(payload->'scores') LOOP
    ordinal:=ordinal+1; existing:=(item->>'id')::uuid;
    IF existing IS NULL THEN
      INSERT INTO public.play_scores(play_id,player_id,score,winner,color,role,position)
      VALUES(write_scores.play_id,(item->>'player_id')::uuid,(item->>'score')::numeric,(item->>'winner')::boolean,item->>'color',item->>'role',positions[ordinal]);
    ELSE
      UPDATE public.play_scores s SET player_id=(item->>'player_id')::uuid,score=(item->>'score')::numeric,
        winner=(item->>'winner')::boolean,color=item->>'color',role=item->>'role',position=CASE WHEN preserve THEN s.position ELSE positions[ordinal] END
      WHERE s.id=existing AND s.play_id=write_scores.play_id;
    END IF;
  END LOOP;
END $$;

CREATE FUNCTION kingscore_atomic.mutate(operation text, request_id uuid, play_id uuid, expected_revision text,
  payload jsonb, metadata jsonb DEFAULT NULL, external_positions jsonb DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SET search_path='' SET timezone='UTC' AS $$
DECLARE actor uuid:=kingscore_atomic.require_admin(); request jsonb; previous kingscore_atomic.receipts;
  target uuid:=play_id; result jsonb; snapshot jsonb; positions integer[]; preserve boolean:=false; table_id text;
BEGIN
  IF request_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Request ID required'; END IF;
  IF operation NOT IN ('create','update','delete','import') THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Unknown operation'; END IF;
  request:=jsonb_build_object('operation',operation,'play_id',play_id,'revision',expected_revision,
    'payload',payload,'metadata',metadata,'positions',external_positions);
  PERFORM pg_advisory_xact_lock(hashtextextended('request:'||actor::text||':'||request_id::text,0));
  SELECT * INTO previous FROM kingscore_atomic.receipts r WHERE r.actor_id=actor AND r.request_id=mutate.request_id;
  IF FOUND THEN
    IF previous.request IS DISTINCT FROM request THEN RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='Request ID reused with different content'; END IF;
    RETURN previous.result||jsonb_build_object('replayed',true);
  END IF;
  IF operation IN ('update','delete') THEN
    IF target IS NULL OR expected_revision IS NULL OR length(expected_revision) NOT BETWEEN 1 AND 160 OR expected_revision !~ '^[!-~]+$' THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Target and opaque revision required';
    END IF;
    PERFORM 1 FROM public.plays p WHERE p.id=target FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='PT404',MESSAGE='Play not found'; END IF;
    PERFORM 1 FROM public.play_scores s WHERE s.play_id=target ORDER BY s.id FOR UPDATE;
    snapshot:=kingscore_atomic.snapshot(target);
    IF snapshot->>'revision'<>expected_revision THEN RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='Play changed since snapshot'; END IF;
    IF operation='update' AND NOT (snapshot->'editability'->>'editable')::boolean THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Legacy values require an explicit editor decision';
    END IF;
  END IF;
  IF operation='import' THEN
    IF metadata IS NULL OR jsonb_typeof(metadata)<>'object' OR NOT metadata ?& ARRAY['location','bga_table_id'] OR
       (SELECT count(*) FROM jsonb_object_keys(metadata))<>2 OR octet_length(metadata::text)>1048576 OR
       jsonb_typeof(metadata->'location') NOT IN ('string','null') OR jsonb_typeof(metadata->'bga_table_id')<>'string' THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid import metadata';
    END IF;
    table_id:=metadata->>'bga_table_id';
    IF char_length(table_id) NOT BETWEEN 1 AND 256 OR
       table_id ~ E'[\\t\\n\\v\\f\\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]' THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid BGA table ID';
    END IF;
    IF external_positions IS NULL OR jsonb_typeof(external_positions)<>'array' OR
       jsonb_typeof(payload->'scores') IS DISTINCT FROM 'array' THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Import positions required';
    END IF;
    IF jsonb_array_length(external_positions)<>jsonb_array_length(payload->'scores') OR
       EXISTS(SELECT 1 FROM jsonb_array_elements(external_positions) v WHERE v<>'null'::jsonb AND
         (jsonb_typeof(v)<>'number' OR (v::text)::numeric<>trunc((v::text)::numeric) OR (v::text)::numeric NOT BETWEEN 1 AND 2147483647)) THEN
      RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid import positions';
    END IF;
    PERFORM kingscore_atomic.validate_input(payload,true,NULL);
    PERFORM pg_advisory_xact_lock(hashtextextended('bga:'||actor::text||':'||table_id,0));
    SELECT p.id INTO target FROM public.plays p WHERE p.user_id=actor AND p.bga_table_id=table_id ORDER BY p.id LIMIT 1 FOR UPDATE;
    IF target IS NOT NULL THEN
      PERFORM 1 FROM public.play_scores s WHERE s.play_id=target ORDER BY s.id FOR UPDATE;
      snapshot:=kingscore_atomic.snapshot(target);
      result:=jsonb_build_object('play_id',target,'revision',snapshot->>'revision','replayed',false,'skipped',true);
      INSERT INTO kingscore_atomic.receipts VALUES(actor,request_id,request,result,now());
      RETURN result;
    END IF;
  END IF;
  IF operation IN ('create','update','import') THEN
    IF operation<>'import' THEN PERFORM kingscore_atomic.validate_input(payload,operation<>'update',target); END IF;
    IF operation='update' THEN
      preserve:=snapshot->'detail'->'play'->'is_cooperative' IS NOT DISTINCT FROM payload->'is_cooperative'
        AND snapshot->'detail'->'play'->'team_won' IS NOT DISTINCT FROM payload->'team_won'
        AND (SELECT count(*) FROM public.play_scores s WHERE s.play_id=target)=jsonb_array_length(payload->'scores')
        AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(payload->'scores') v LEFT JOIN public.play_scores s
          ON s.id=(v->>'id')::uuid AND s.play_id=target
          WHERE s.id IS NULL OR s.player_id IS DISTINCT FROM (v->>'player_id')::uuid OR
            s.score IS DISTINCT FROM (v->>'score')::numeric OR s.winner IS DISTINCT FROM (v->>'winner')::boolean);
      UPDATE public.plays SET game_id=(payload->>'game_id')::uuid,played_at=(payload->>'played_at')::date,
        duration_minutes=(payload->>'duration_minutes')::numeric::integer,source=payload->>'source',notes=payload->>'notes',
        is_cooperative=(payload->>'is_cooperative')::boolean,team_won=(payload->>'team_won')::boolean WHERE id=target;
    ELSE
      INSERT INTO public.plays(user_id,game_id,played_at,duration_minutes,source,notes,is_cooperative,team_won,location,bga_table_id)
      VALUES(actor,(payload->>'game_id')::uuid,(payload->>'played_at')::date,(payload->>'duration_minutes')::numeric::integer,
        payload->>'source',payload->>'notes',(payload->>'is_cooperative')::boolean,(payload->>'team_won')::boolean,
        metadata->>'location',table_id) RETURNING id INTO target;
    END IF;
    IF operation='import' THEN
      SELECT array_agg(CASE WHEN value='null'::jsonb THEN NULL ELSE value::text::numeric::integer END ORDER BY ordinality)
        INTO positions FROM jsonb_array_elements(external_positions) WITH ORDINALITY;
    ELSE positions:=kingscore_atomic.rank_positions(payload->'scores',(payload->>'is_cooperative')::boolean,(payload->>'team_won')::boolean);
    END IF;
    PERFORM kingscore_atomic.write_scores(target,payload,positions,preserve);
    snapshot:=kingscore_atomic.snapshot(target);
    result:=jsonb_build_object('play_id',target,'revision',snapshot->>'revision','replayed',false);
    IF operation='import' THEN result:=result||jsonb_build_object('skipped',false); END IF;
  ELSE
    DELETE FROM public.plays WHERE id=target;
    result:=jsonb_build_object('play_id',target,'deleted',true,'replayed',false);
  END IF;
  INSERT INTO kingscore_atomic.receipts VALUES(actor,request_id,request,result,now());
  RETURN result;
EXCEPTION WHEN data_exception OR foreign_key_violation OR check_violation OR not_null_violation THEN
  RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Invalid values or missing referenced data';
END $$;

CREATE FUNCTION public.get_play_editor_v1(p_play_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER STABLE SET search_path='' SET timezone='UTC' AS $$
DECLARE snapshot jsonb;
BEGIN
  PERFORM kingscore_atomic.require_admin();
  IF p_play_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='PT400',MESSAGE='Play ID required'; END IF;
  snapshot:=kingscore_atomic.snapshot(p_play_id);
  IF snapshot IS NULL THEN RAISE EXCEPTION USING ERRCODE='PT404',MESSAGE='Play not found'; END IF;
  RETURN snapshot;
END $$;
CREATE FUNCTION public.create_play_v1(p_request_id uuid,p_payload jsonb) RETURNS jsonb
LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$ SELECT kingscore_atomic.mutate('create',p_request_id,NULL,NULL,p_payload) $$;
CREATE FUNCTION public.update_play_v1(p_request_id uuid,p_play_id uuid,p_expected_revision text,p_payload jsonb) RETURNS jsonb
LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$ SELECT kingscore_atomic.mutate('update',p_request_id,p_play_id,p_expected_revision,p_payload) $$;
CREATE FUNCTION public.delete_play_v1(p_request_id uuid,p_play_id uuid,p_expected_revision text) RETURNS jsonb
LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$ SELECT kingscore_atomic.mutate('delete',p_request_id,p_play_id,p_expected_revision,NULL) $$;
CREATE FUNCTION public.import_bga_play_v1(p_request_id uuid,p_payload jsonb,p_metadata jsonb,p_positions jsonb) RETURNS jsonb
LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$ SELECT kingscore_atomic.mutate('import',p_request_id,NULL,NULL,p_payload,p_metadata,p_positions) $$;

-- Synthetic server-writer adapter: apply an already-computed position vector only to its original revision.
-- It computes no ELO and is unavailable to client roles and the public HTTP schema.
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
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA kingscore_atomic FROM PUBLIC, anon, authenticated, service_role;
GRANT USAGE ON SCHEMA kingscore_atomic TO service_role;
GRANT EXECUTE ON FUNCTION kingscore_atomic.apply_positions(uuid,text,uuid[],integer[]) TO service_role;
REVOKE ALL ON FUNCTION public.get_play_editor_v1(uuid),public.create_play_v1(uuid,jsonb),
  public.update_play_v1(uuid,uuid,text,jsonb),public.delete_play_v1(uuid,uuid,text),public.import_bga_play_v1(uuid,jsonb,jsonb,jsonb)
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.get_play_editor_v1(uuid),public.create_play_v1(uuid,jsonb),
  public.update_play_v1(uuid,uuid,text,jsonb),public.delete_play_v1(uuid,uuid,text),public.import_bga_play_v1(uuid,jsonb,jsonb,jsonb) TO authenticated;
COMMIT;
