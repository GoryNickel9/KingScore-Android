-- Candidate only. Tested on a disposable PC cluster; not applied to Supabase.
-- Additive reads: does not alter source data, policies, writers or ELO calculations.
BEGIN;
SET LOCAL search_path = '';
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

DO $preflight$
DECLARE target text; owner_oid oid;
BEGIN
  IF current_setting('server_version_num')::integer<150000 THEN
    RAISE EXCEPTION 'PostgreSQL 15 or newer required'; END IF;
  SELECT oid INTO owner_oid FROM pg_catalog.pg_roles
    WHERE rolname = current_user AND (rolsuper OR rolbypassrls)
      AND rolname NOT IN ('anon','authenticated','service_role','authenticator');
  IF owner_oid IS NULL THEN RAISE EXCEPTION 'Trusted migration owner required'; END IF;
  FOREACH target IN ARRAY ARRAY['anon','authenticated','service_role'] LOOP
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname=target)
      OR pg_catalog.pg_has_role(target,current_user,'MEMBER') THEN
      RAISE EXCEPTION 'Isolated client role required: %',target;
    END IF;
  END LOOP;
  IF EXISTS(SELECT FROM pg_catalog.pg_roles WHERE rolname IN ('anon','authenticated')
    AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Interactive client roles must enforce RLS'; END IF;
  FOREACH target IN ARRAY ARRAY['public.games','public.players','public.plays','public.play_scores'] LOOP
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_class WHERE oid=pg_catalog.to_regclass(target)
      AND relkind='r' AND relrowsecurity AND relowner=owner_oid)
      OR EXISTS(SELECT FROM pg_catalog.pg_inherits WHERE inhparent=pg_catalog.to_regclass(target)
        OR inhrelid=pg_catalog.to_regclass(target)) THEN
      RAISE EXCEPTION 'Owned source table with RLS required: %',target;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_trigger WHERE tgrelid=pg_catalog.to_regclass(target)
      AND tgfoid=pg_catalog.to_regprocedure('kingscore_elo.mark_dirty()') AND tgenabled IN ('O','A')
      AND tgtype=62 AND tgnargs=0 AND tgqual IS NULL) THEN
      RAISE EXCEPTION 'Existing ELO revision trigger required: %',target;
    END IF;
  END LOOP;
  IF pg_catalog.to_regclass('kingscore_elo.source_clock') IS NULL
    OR pg_catalog.to_regprocedure('public.get_elo_publication_v1()') IS NULL THEN
    RAISE EXCEPTION 'Existing ELO V1 publication and revision required';
  END IF;
  IF NOT EXISTS(SELECT FROM pg_catalog.pg_class WHERE oid='kingscore_elo.source_clock'::regclass
    AND relkind='r' AND relowner=owner_oid AND relrowsecurity)
    OR NOT EXISTS(SELECT FROM pg_catalog.pg_proc WHERE oid='public.get_elo_publication_v1()'::regprocedure
      AND proowner=owner_oid AND prosecdef AND provolatile='s') THEN
    RAISE EXCEPTION 'Owned private clock and stable ELO reader required'; END IF;
  IF (SELECT count(*) FROM kingscore_elo.source_clock WHERE singleton AND revision>=0)<>1 THEN
    RAISE EXCEPTION 'Exactly one valid revision required';
  END IF;
  IF pg_catalog.to_regnamespace('kingscore_summary') IS NOT NULL OR EXISTS (
    SELECT FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='public' AND p.proname IN ('get_app_data_version_v1','get_app_summary_v1',
      'get_statistics_overview_v1','get_game_statistics_v1','get_elo_chart_v1')) THEN
    RAISE EXCEPTION 'Summary V1 name already occupied; explicit upgrade required';
  END IF;
  IF EXISTS (SELECT FROM public.plays WHERE duration_minutes<0)
    OR EXISTS (SELECT FROM public.play_scores WHERE score::text IN ('NaN','Infinity','-Infinity'))
    OR EXISTS (SELECT FROM public.play_scores WHERE play_id IS NOT NULL AND player_id IS NOT NULL
      GROUP BY play_id,player_id HAVING count(*)>1) THEN
    RAISE EXCEPTION 'Invalid historical durations, scores or duplicate participants; review without rewriting';
  END IF;
END $preflight$;

CREATE SCHEMA kingscore_summary;
REVOKE ALL ON SCHEMA kingscore_summary FROM PUBLIC,anon,authenticated,service_role;
GRANT USAGE ON SCHEMA kingscore_summary TO authenticated;

CREATE FUNCTION public.get_app_data_version_v1() RETURNS text
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE version text;
BEGIN
  IF auth.uid() IS NULL OR auth.jwt()->>'role' IS DISTINCT FROM 'authenticated' THEN
    RAISE EXCEPTION USING ERRCODE='42501',MESSAGE='Authenticated account required';
  END IF;
  SELECT revision::text INTO version FROM kingscore_elo.source_clock WHERE singleton;
  IF version IS NULL THEN
    RAISE EXCEPTION USING ERRCODE='PT503',MESSAGE='Source revision unavailable'; END IF;
  RETURN version;
END $$;

CREATE FUNCTION kingscore_summary.count_value(value bigint) RETURNS bigint
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path='' AS $$
BEGIN
  IF value IS NULL OR value<0 OR value>9007199254740991 THEN
    RAISE EXCEPTION USING ERRCODE='PT422',MESSAGE='Count exceeds exact V1 range';
  END IF;
  RETURN value;
END $$;

CREATE FUNCTION kingscore_summary.envelope(version text) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT pg_catalog.jsonb_build_object('contractVersion',1,'scope','global-visible-v1',
    'sourceRevision',version,'computedAt',pg_catalog.to_char(
      pg_catalog.statement_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
$$;

CREATE FUNCTION kingscore_summary.decimal_value(value numeric, invalid boolean DEFAULT false) RETURNS text
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path='' AS $$
BEGIN
  IF invalid OR value::text IN ('NaN','Infinity','-Infinity') THEN
    RAISE EXCEPTION USING ERRCODE='PT422',MESSAGE='Invalid numeric source data; review required'; END IF;
  RETURN value::text;
END $$;

CREATE FUNCTION kingscore_summary.check_revision(expected text, actual text) RETURNS void
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path='' AS $$
BEGIN
  IF expected IS NOT NULL THEN
    IF length(expected)>19 OR expected !~ '^(0|[1-9][0-9]*)$'
      OR (length(expected)=19 AND expected COLLATE "C">'9223372036854775807') THEN
      RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid expected revision';
    END IF;
    IF expected<>actual THEN
      RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='Data revision changed; restart from the first page';
    END IF;
  END IF;
END $$;

-- Cursors are position tokens, not authorization credentials. RLS is always re-evaluated.
CREATE FUNCTION kingscore_summary.make_cursor(view_name text, version text, binding text, page_offset bigint)
RETURNS text LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT pg_catalog.encode(pg_catalog.convert_to(pg_catalog.jsonb_build_object(
    'v',1,'view',view_name,'revision',version,'account',auth.uid()::text,
    'binding',binding,'offset',page_offset)::text,'UTF8'),'base64');
$$;

CREATE FUNCTION kingscore_summary.read_cursor(token text, view_name text, version text,
  binding text, has_expected boolean) RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE value jsonb; position bigint;
BEGIN
  IF token IS NULL THEN RETURN 0; END IF;
  IF NOT has_expected OR length(token)>4096 OR length(token)=0 THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invalid cursor or missing expected version';
  END IF;
  BEGIN
    value:=pg_catalog.convert_from(pg_catalog.decode(token,'base64'),'UTF8')::jsonb;
    IF pg_catalog.jsonb_typeof(value) IS DISTINCT FROM 'object'
      OR pg_catalog.jsonb_typeof(value->'offset') IS DISTINCT FROM 'number'
      OR value->>'offset' !~ '^[1-9][0-9]{0,15}$' THEN
      RAISE EXCEPTION 'Invalid position';
    END IF;
    position:=(value->>'offset')::bigint;
    IF position>9007199254740991 THEN RAISE EXCEPTION 'Position out of range'; END IF;
    IF value IS DISTINCT FROM pg_catalog.jsonb_build_object('v',1,'view',view_name,
      'revision',version,'account',auth.uid()::text,'binding',binding,'offset',position) THEN
      RAISE EXCEPTION 'Cursor context mismatch';
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Malformed cursor or different account/view/version';
  END;
  RETURN position;
END $$;

CREATE FUNCTION kingscore_summary.linked_player() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE matches jsonb;
BEGIN
  SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('id',p.id,
    'displayName',COALESCE(NULLIF(p.alias,''),p.name))) INTO matches
  FROM (SELECT id,name,alias FROM public.players WHERE account_user_id=auth.uid() LIMIT 2) p;
  IF pg_catalog.jsonb_array_length(matches)>1 THEN
    RAISE EXCEPTION USING ERRCODE='PT422',MESSAGE='Multiple linked players';
  END IF;
  RETURN matches->0;
END $$;

-- Independent play and score aggregates prevent durations being multiplied by participants.
CREATE FUNCTION kingscore_summary.game_rows(selected_game uuid DEFAULT NULL)
RETURNS TABLE(game_id uuid, plays bigint, body jsonb)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  WITH visible AS MATERIALIZED (
    SELECT p.id,p.game_id,p.duration_minutes FROM public.plays p
    WHERE selected_game IS NULL OR p.game_id=selected_game
  ), games AS (
    SELECT v.game_id,count(*) AS plays,count(v.duration_minutes) AS duration_samples,
      COALESCE(sum(v.duration_minutes::numeric),0) AS minutes,
      round(avg(v.duration_minutes),0) AS duration_average,
      bool_or(v.duration_minutes<0) AS invalid_duration FROM visible v GROUP BY v.game_id
  ), scores AS (
    SELECT v.game_id,count(*) AS participations,count(s.score) AS score_samples,
      COALESCE(sum(s.score),0) AS score_sum,round(avg(s.score),2) AS score_average,
      count(*) FILTER(WHERE player.id IS NULL) AS unattributed,
      count(*) FILTER(WHERE s.player_id IS NOT NULL)<>
        count(DISTINCT (s.play_id,s.player_id)) FILTER(WHERE s.player_id IS NOT NULL) AS duplicate_participant
    FROM visible v JOIN public.play_scores s ON s.play_id=v.id
    LEFT JOIN public.players player ON player.id=s.player_id GROUP BY v.game_id
  )
  SELECT g.game_id,g.plays,pg_catalog.jsonb_build_object('gameId',g.game_id,
    'name',COALESCE(c.name,'Gioco non disponibile'),'imageUrl',c.image_url,
    'plays',kingscore_summary.count_value(g.plays),
    'participations',kingscore_summary.count_value(COALESCE(s.participations,0)),
    'scoreSum',kingscore_summary.decimal_value(COALESCE(s.score_sum,0),s.duplicate_participant),
    'scoreSamples',kingscore_summary.count_value(COALESCE(s.score_samples,0)),
    'scoreAverage',kingscore_summary.decimal_value(s.score_average),
    'recordedMinutes',kingscore_summary.decimal_value(g.minutes,g.invalid_duration),
    'durationSamples',kingscore_summary.count_value(g.duration_samples),
    'durationAverage',g.duration_average::text,
    'unattributedParticipations',kingscore_summary.count_value(COALESCE(s.unattributed,0)))
  FROM games g LEFT JOIN scores s ON s.game_id IS NOT DISTINCT FROM g.game_id
  LEFT JOIN public.games c ON c.id=g.game_id;
$$;

CREATE FUNCTION public.get_app_summary_v1(p_month date) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE version text; linked jsonb; answer jsonb;
BEGIN
  version:=public.get_app_data_version_v1();
  IF p_month IS NULL OR p_month<'0001-06-01'::date OR p_month>'9999-12-01'::date
    OR extract(day FROM p_month)<>1 THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Month must be a first day between 0001-06 and 9999-12';
  END IF;
  linked:=kingscore_summary.linked_player();
  WITH visible AS MATERIALIZED (SELECT p.id,p.game_id,p.played_at,p.duration_minutes FROM public.plays p),
  scores AS MATERIALIZED (SELECT s.play_id,s.player_id,s.winner FROM public.play_scores s
    JOIN visible v ON v.id=s.play_id),
  favorites AS (SELECT v.game_id,count(*) AS plays FROM visible v WHERE v.game_id IS NOT NULL
    GROUP BY v.game_id ORDER BY count(*) DESC,v.game_id LIMIT 3)
  SELECT kingscore_summary.envelope(version)||pg_catalog.jsonb_build_object(
    'month',pg_catalog.to_char(p_month,'YYYY-MM'),
    'totals',(SELECT pg_catalog.jsonb_build_object('plays',kingscore_summary.count_value(count(*)),
      'monthPlays',kingscore_summary.count_value(count(*) FILTER(WHERE played_at>=p_month AND played_at<p_month+interval '1 month')),
      'participatingGames',kingscore_summary.count_value(count(DISTINCT game_id)),
      'participatingPlayers',kingscore_summary.count_value((SELECT count(DISTINCT player_id) FROM scores)),
      'recordedMinutes',kingscore_summary.decimal_value(COALESCE(sum(duration_minutes::numeric),0),bool_or(duration_minutes<0)),
      'durationSamples',kingscore_summary.count_value(count(duration_minutes))) FROM visible),
    'months',(SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'month',pg_catalog.to_char(m.month,'YYYY-MM'),'plays',kingscore_summary.count_value(
        (SELECT count(*) FROM visible v WHERE v.played_at>=m.month AND v.played_at<m.month+interval '1 month')))
      ORDER BY m.month) FROM pg_catalog.generate_series(p_month-interval '5 months',p_month::timestamp,interval '1 month') m(month)),
    'favoriteGames',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'gameId',f.game_id,'name',COALESCE(g.name,'Gioco non disponibile'),'imageUrl',g.image_url,
      'plays',kingscore_summary.count_value(f.plays)) ORDER BY f.plays DESC,f.game_id)
      FROM favorites f LEFT JOIN public.games g ON g.id=f.game_id),'[]'::jsonb),
    'personal',CASE WHEN linked IS NULL THEN pg_catalog.jsonb_build_object('status','unlinked',
      'playerId',NULL,'displayName',NULL,'plays',NULL,'wins',NULL)
      ELSE pg_catalog.jsonb_build_object('status','linked','playerId',linked->>'id',
        'displayName',linked->>'displayName','plays',kingscore_summary.count_value(
          (SELECT count(DISTINCT play_id) FROM scores WHERE player_id=(linked->>'id')::uuid)),
        'wins',kingscore_summary.count_value((SELECT count(DISTINCT play_id) FROM scores
          WHERE player_id=(linked->>'id')::uuid AND winner IS TRUE))) END) INTO answer;
  RETURN answer;
END $$;

CREATE FUNCTION public.get_statistics_overview_v1(p_expected_revision text DEFAULT NULL,
  p_cursor text DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE version text; page_offset bigint; linked jsonb; answer jsonb;
BEGIN
  version:=public.get_app_data_version_v1();
  PERFORM kingscore_summary.check_revision(p_expected_revision,version);
  IF p_limit IS NULL OR p_limit<1 OR p_limit>50 THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Page limit must be 1..50'; END IF;
  page_offset:=kingscore_summary.read_cursor(p_cursor,'overview',version,'',p_expected_revision IS NOT NULL);
  linked:=kingscore_summary.linked_player();
  WITH games AS MATERIALIZED (SELECT * FROM kingscore_summary.game_rows()),
  page AS (SELECT * FROM games ORDER BY plays DESC,game_id NULLS LAST OFFSET page_offset LIMIT p_limit),
  personal AS (SELECT p.game_id,count(DISTINCT p.id) AS participations,
    count(DISTINCT p.id) FILTER(WHERE s.winner IS TRUE) AS wins
    FROM public.plays p JOIN public.play_scores s ON s.play_id=p.id
    WHERE s.player_id=(linked->>'id')::uuid GROUP BY p.game_id
    ORDER BY count(DISTINCT p.id) DESC,p.game_id NULLS LAST LIMIT 10)
  SELECT kingscore_summary.envelope(version)||pg_catalog.jsonb_build_object(
    'personalStatus',CASE WHEN linked IS NULL THEN 'unlinked' ELSE 'linked' END,
    'winRateByGame',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'gameId',p.game_id,'name',COALESCE(g.name,'Gioco non disponibile'),
      'participations',kingscore_summary.count_value(p.participations),'wins',kingscore_summary.count_value(p.wins),
      'percent',round(100::numeric*p.wins/NULLIF(p.participations,0))) ORDER BY p.participations DESC,p.game_id NULLS LAST)
      FROM personal p LEFT JOIN public.games g ON g.id=p.game_id),'[]'::jsonb),
    'games',COALESCE((SELECT pg_catalog.jsonb_agg(body ORDER BY plays DESC,game_id NULLS LAST) FROM page),'[]'::jsonb),
    'totalGames',kingscore_summary.count_value((SELECT count(*) FROM games)),
    'nextCursor',CASE WHEN page_offset+p_limit<(SELECT count(*) FROM games)
      THEN kingscore_summary.make_cursor('overview',version,'',page_offset+p_limit) ELSE NULL END) INTO answer;
  RETURN answer;
END $$;

CREATE FUNCTION public.get_game_statistics_v1(p_game_id uuid,p_expected_revision text DEFAULT NULL,
  p_cursor text DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE version text; page_offset bigint; game_body jsonb; answer jsonb;
BEGIN
  version:=public.get_app_data_version_v1();
  PERFORM kingscore_summary.check_revision(p_expected_revision,version);
  IF p_limit IS NULL OR p_limit<1 OR p_limit>100 OR p_game_id IS NULL THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Game ID and page limit 1..100 required'; END IF;
  IF NOT EXISTS(SELECT FROM public.games WHERE id=p_game_id) THEN
    RAISE EXCEPTION USING ERRCODE='PT404',MESSAGE='Game not available'; END IF;
  page_offset:=kingscore_summary.read_cursor(p_cursor,'game',version,p_game_id::text,p_expected_revision IS NOT NULL);
  SELECT body INTO game_body FROM kingscore_summary.game_rows(p_game_id);
  IF game_body IS NULL THEN
    SELECT pg_catalog.jsonb_build_object('gameId',id,'name',name,'imageUrl',image_url,
      'plays',0,'participations',0,'scoreSum','0','scoreSamples',0,'scoreAverage',NULL,
      'recordedMinutes','0','durationSamples',0,'durationAverage',NULL,'unattributedParticipations',0)
    INTO game_body FROM public.games WHERE id=p_game_id;
  END IF;
  WITH players AS MATERIALIZED (
    SELECT player.id,player.name,player.alias,player.avatar_url,
      count(DISTINCT p.id) AS participations,count(s.score) AS samples,count(*) AS score_rows,
      COALESCE(sum(s.score),0) AS score_sum,avg(s.score) AS average
    FROM public.plays p JOIN public.play_scores s ON s.play_id=p.id
    JOIN public.players player ON player.id=s.player_id WHERE p.game_id=p_game_id
    GROUP BY player.id,player.name,player.alias,player.avatar_url
  ), page AS (SELECT * FROM players ORDER BY average DESC NULLS LAST,id OFFSET page_offset LIMIT p_limit)
  SELECT kingscore_summary.envelope(version)||pg_catalog.jsonb_build_object('game',game_body,
    'players',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('playerId',id,'name',name,
      'alias',alias,'avatarUrl',avatar_url,'participations',kingscore_summary.count_value(participations),
      'scoreSum',kingscore_summary.decimal_value(score_sum,score_rows<>participations),
      'scoreSamples',kingscore_summary.count_value(samples),
      'scoreAverage',kingscore_summary.decimal_value(round(average,2))) ORDER BY average DESC NULLS LAST,id) FROM page),'[]'::jsonb),
    'totalPlayers',kingscore_summary.count_value((SELECT count(*) FROM players)),
    'nextCursor',CASE WHEN page_offset+p_limit<(SELECT count(*) FROM players)
      THEN kingscore_summary.make_cursor('game',version,p_game_id::text,page_offset+p_limit) ELSE NULL END) INTO answer;
  RETURN answer;
END $$;

CREATE FUNCTION public.get_elo_chart_v1(p_game_id uuid DEFAULT NULL,p_expected_publication_id uuid DEFAULT NULL,
  p_cursor text DEFAULT NULL,p_limit_plays integer DEFAULT 100,p_player_ids uuid[] DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE version text; publication jsonb; history jsonb; answer jsonb; binding text;
  page_offset bigint; available uuid[]; selected uuid[];
BEGIN
  version:=public.get_app_data_version_v1();
  IF p_limit_plays IS NULL OR p_limit_plays<1 OR p_limit_plays>200 THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Play limit must be 1..200'; END IF;
  IF p_game_id IS NOT NULL AND NOT EXISTS(SELECT FROM public.games WHERE id=p_game_id) THEN
    RAISE EXCEPTION USING ERRCODE='PT404',MESSAGE='Game not available'; END IF;
  IF p_player_ids IS NOT NULL AND (cardinality(p_player_ids)<1 OR cardinality(p_player_ids)>64
    OR array_position(p_player_ids,NULL) IS NOT NULL OR array_ndims(p_player_ids)<>1) THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Select 1..64 distinct player IDs'; END IF;
  publication:=public.get_elo_publication_v1();
  IF publication IS NULL OR publication->>'sourceRevision' IS DISTINCT FROM version THEN
    RAISE EXCEPTION USING ERRCODE='PT503',MESSAGE='ELO source version unavailable'; END IF;
  IF p_expected_publication_id IS NOT NULL AND
    p_expected_publication_id::text IS DISTINCT FROM publication->>'publicationId' THEN
    RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='ELO publication changed; restart from the first page'; END IF;
  IF p_cursor IS NOT NULL AND p_expected_publication_id IS NULL THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Expected publication required with cursor'; END IF;
  IF publication->>'status' IN ('stale','missing') THEN
    IF p_cursor IS NOT NULL THEN
      RAISE EXCEPTION USING ERRCODE='PT409',MESSAGE='ELO publication is no longer current'; END IF;
    RETURN kingscore_summary.envelope(version)||(publication-'result')||pg_catalog.jsonb_build_object(
      'gameId',p_game_id,'series','[]'::jsonb,'initialRatings','[]'::jsonb,'plays','[]'::jsonb,
      'totalPlays',0,'nextCursor',NULL);
  END IF;
  IF publication->>'status' IS DISTINCT FROM 'current'
    OR pg_catalog.jsonb_typeof(publication->'result'->'generalHistory') IS DISTINCT FROM 'array'
    OR pg_catalog.jsonb_typeof(publication->'result'->'gameHistory') IS DISTINCT FROM 'array' THEN
    RAISE EXCEPTION USING ERRCODE='PT503',MESSAGE='Invalid ELO publication'; END IF;
  -- Existing publication is global/public. Join visible source rows for the new endpoint's RLS scope.
  SELECT COALESCE(pg_catalog.jsonb_agg(point ORDER BY point->>'date',point->>'playId',point->>'playerId'),'[]'::jsonb)
    INTO history FROM pg_catalog.jsonb_array_elements(publication->'result'->
      CASE WHEN p_game_id IS NULL THEN 'generalHistory' ELSE 'gameHistory' END) h(point)
    JOIN public.plays p ON p.id=(point->>'playId')::uuid
    JOIN public.players player ON player.id=(point->>'playerId')::uuid
    WHERE (p_game_id IS NULL OR (p.game_id=p_game_id AND point->>'gameId'=p_game_id::text))
      AND EXISTS(SELECT FROM public.play_scores s WHERE s.play_id=p.id AND s.player_id=player.id);
  SELECT array_agg(DISTINCT (point->>'playerId')::uuid ORDER BY (point->>'playerId')::uuid)
    INTO available FROM pg_catalog.jsonb_array_elements(history) h(point);
  IF p_player_ids IS NULL AND COALESCE(cardinality(available),0)>64 THEN
    RAISE EXCEPTION USING ERRCODE='PT422',MESSAGE='Select up to 64 players explicitly',
      DETAIL=pg_catalog.jsonb_build_object('availableSeries',cardinality(available))::text;
  END IF;
  selected:=COALESCE(p_player_ids,available,ARRAY[]::uuid[]);
  IF p_player_ids IS NOT NULL AND (NOT p_player_ids<@COALESCE(available,ARRAY[]::uuid[])
    OR cardinality(p_player_ids)<>(SELECT count(DISTINCT x) FROM unnest(p_player_ids) x)) THEN
    RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Unknown or duplicate selected player'; END IF;
  SELECT COALESCE(array_agg(x ORDER BY x),ARRAY[]::uuid[]) INTO selected FROM unnest(selected) x;
  binding:=COALESCE(p_game_id::text,'general')||':'||selected::text;
  page_offset:=kingscore_summary.read_cursor(p_cursor,'elo',
    (publication->>'publicationId')||':'||version,binding,p_expected_publication_id IS NOT NULL);
  WITH points AS MATERIALIZED (SELECT point,(point->>'playId')::uuid AS play_id,
    (point->>'playerId')::uuid AS player_id,point->>'date' AS played_at
    FROM pg_catalog.jsonb_array_elements(history) h(point)),
  ordered AS MATERIALIZED (SELECT play_id,min(played_at) AS played_at,
    row_number() OVER(ORDER BY min(played_at),play_id) AS ordinal FROM points GROUP BY play_id),
  page AS (SELECT * FROM ordered ORDER BY ordinal OFFSET page_offset LIMIT p_limit_plays),
  prior AS (SELECT DISTINCT ON (p.player_id) p.player_id,p.point
    FROM points p JOIN ordered o ON o.play_id=p.play_id
    WHERE o.ordinal<=page_offset AND p.player_id=ANY(selected)
    ORDER BY p.player_id,o.ordinal DESC)
  SELECT kingscore_summary.envelope(version)||(publication-'result')||pg_catalog.jsonb_build_object(
    'gameId',p_game_id,'availableSeries',COALESCE(cardinality(available),0),
    'series',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('playerId',id,
      'displayName',COALESCE(NULLIF(alias,''),name),'color',color) ORDER BY id)
      FROM public.players WHERE id=ANY(selected)),'[]'::jsonb),
    'initialRatings',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'playerId',player_id,'rating',(point->>'rating')::bigint) ORDER BY player_id) FROM prior),'[]'::jsonb),
    'plays',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('playId',page.play_id,
      'date',page.played_at,'ordinal',page.ordinal,'ratings',COALESCE((SELECT pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object('playerId',p.player_id,'rating',(p.point->>'rating')::bigint)
        ORDER BY p.player_id) FROM points p WHERE p.play_id=page.play_id AND p.player_id=ANY(selected)),
        '[]'::jsonb)) ORDER BY page.ordinal) FROM page),'[]'::jsonb),
    'totalPlays',kingscore_summary.count_value((SELECT count(*) FROM ordered)),
    'nextCursor',CASE WHEN page_offset+p_limit_plays<(SELECT count(*) FROM ordered) THEN
      kingscore_summary.make_cursor('elo',(publication->>'publicationId')||':'||version,
        binding,page_offset+p_limit_plays) ELSE NULL END) INTO answer;
  RETURN answer;
END $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA kingscore_summary FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA kingscore_summary TO authenticated;
REVOKE ALL ON FUNCTION public.get_app_data_version_v1(),public.get_app_summary_v1(date),
  public.get_statistics_overview_v1(text,text,integer),public.get_game_statistics_v1(uuid,text,text,integer),
  public.get_elo_chart_v1(uuid,uuid,text,integer,uuid[]) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.get_app_data_version_v1(),public.get_app_summary_v1(date),
  public.get_statistics_overview_v1(text,text,integer),public.get_game_statistics_v1(uuid,text,text,integer),
  public.get_elo_chart_v1(uuid,uuid,text,integer,uuid[]) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
