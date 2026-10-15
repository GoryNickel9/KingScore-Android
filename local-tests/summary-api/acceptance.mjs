import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import { performance } from 'node:perf_hooks';
import { id, literal } from '../local-http.mjs';

const actor = id(1), other = id(2), unlinked = id(3);
const game = id(10), game2 = id(11), player = id(20), second = id(21), third = id(22);
const functions = ['get_app_data_version_v1','get_app_summary_v1','get_statistics_overview_v1',
  'get_game_statistics_v1','get_elo_chart_v1'];
const argumentsFor = name => name==='get_app_summary_v1' ? { p_month: '2026-10-01' }
  : name==='get_game_statistics_v1' ? { p_game_id: game } : {};
const fixture = () => {
  const queries = [`INSERT INTO auth.users(id,email) VALUES (${literal(actor)},'one@example.test'),
    (${literal(other)},'two@example.test'),(${literal(unlinked)},'three@example.test');
    INSERT INTO public.games(id,name) VALUES (${literal(game)},'Gioco À'),(${literal(game2)},'Secondo'),
    (${literal(id(12))},'Terzo'),(${literal(id(13))},'Quarto'),(${literal(id(14))},'Senza partite');
    INSERT INTO public.players(id,user_id,account_user_id,name,alias,color) VALUES
    (${literal(player)},${literal(other)},${literal(actor)},'Nome originale','Àlias ♠','#ff6600'),
    (${literal(second)},${literal(actor)},${literal(other)},'Omonimo',NULL,'#00aa00'),
    (${literal(third)},${literal(other)},NULL,'Omonimo',NULL,NULL);`];
  const durations = [null,0,10,20,null,null,null,null,null,null];
  const scores = [0,-2,4,null];
  const bScores = [10,null,20,30,40,50,60,70,80,90];
  for (let n=0;n<10;n++) {
    queries.push(`INSERT INTO public.plays(id,user_id,game_id,played_at,duration_minutes) VALUES
      (${literal(id(101+n))},${literal(n%2 ? other : actor)},${literal(game)},'2026-10-${String(n<2 ? 1 : n+1).padStart(2,'0')}',${durations[n] ?? 'NULL'});
      INSERT INTO public.play_scores(play_id,player_id,score,winner,elo_ordinal) VALUES
      (${literal(id(101+n))},${literal(second)},${bScores[n] ?? 'NULL'},${n>=2},0);`);
    if (n<4) queries.push(`INSERT INTO public.play_scores(play_id,player_id,score,winner,elo_ordinal)
      VALUES (${literal(id(101+n))},${literal(player)},${scores[n] ?? 'NULL'},${n<2},1);`);
  }
  queries.push(`INSERT INTO public.play_scores(play_id,player_id,score,elo_ordinal)
    VALUES (${literal(id(110))},NULL,5,1);
    INSERT INTO public.plays(id,user_id,game_id,played_at,duration_minutes) VALUES
      (${literal(id(111))},${literal(other)},${literal(game2)},'2026-09-30',7),
      (${literal(id(112))},${literal(actor)},NULL,'2026-05-01',NULL),
      (${literal(id(113))},${literal(actor)},${literal(id(12))},'2026-11-01',0),
      (${literal(id(114))},${literal(actor)},${literal(id(13))},'2026-04-01',NULL);
    INSERT INTO public.play_scores(play_id,player_id,score,elo_ordinal) VALUES
      (${literal(id(111))},${literal(third)},1.25,0),
      (${literal(id(111))},${literal(second)},NULL,1),
      (${literal(id(114))},${literal(second)},NULL,0);`);
  return queries.join('\n');
};
function publication(publicationId, revision) {
  // A read-projection fixture: ratings are supplied, never calculated by these RPCs.
  const generalHistory = [
    [111,third,1010,'2026-09-30'],[111,second,990,'2026-09-30'],
    [101,player,1024,'2026-10-01'],[101,second,966,'2026-10-01'],
    [102,player,1045,'2026-10-01'],[102,second,945,'2026-10-01'],
  ].map(([play,playerId,rating,date]) => ({ playId:id(play),playerId,rating,date,playerName:'Fixture',ratingChange:0,gamesPlayed:1 }));
  return { publicationId, sourceRevision:revision,scopeId:'global',algorithm:'kingscore-elo-legacy-v1',
    result:{ processedPlays:3,skippedPlays:11,generalPlayers:{},gamePlayers:{},generalHistory,
      gameHistory:generalHistory.map(p => ({ ...p,gameId:p.playId===id(111)?game2:game })) } };
}
const publish = (http, publicationId) => {
  const revision=http.sql('SELECT revision FROM kingscore_elo.source_clock WHERE singleton');
  const body=publication(publicationId,revision);
  http.sql(`BEGIN; SET LOCAL ROLE service_role;
    SELECT set_config('request.jwt.claims','{"role":"service_role"}',true);
    SELECT public.publish_elo_v1(${literal(JSON.stringify(body))}::jsonb); COMMIT;`);
  return body;
};

export async function runAcceptance(http, report, sql) {
  const jwt=http.token(actor,false), jwt2=http.token(other,true);
  const check=async(name,action) => { await action(); report.cases.push({name,status:'PASS'}); console.log(`PASS ${name}`); };
  const rpc=async(name,args=argumentsFor(name),token=jwt,status=200) => {
    const response=await http.rpc(name,args,token);
    assert.equal(response.status,status,`${name}: ${response.raw}`);
    return response.value;
  };
  for (const name of functions) await check(`anonymous denied: ${name}`,async()=> {
    await rpc(name,argumentsFor(name),null,401);
  });
  await check('expired and invalid signature rejected',async()=> {
    await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),http.token(actor,false,{exp:1}),401);
    await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),http.token(actor,false,{},'wrong-key'),401);
  });
  await check('authenticated role without subject rejected',async()=> {
    for (const name of functions) await rpc(name,argumentsFor(name),http.token(actor,false,{sub:null}),403);
  });
  await check('service role has no new execute privileges',async()=> {
    const results=JSON.parse(http.sql(`SELECT jsonb_agg(has_function_privilege('service_role',p.oid,'EXECUTE'))
      FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname IN ('kingscore_summary') OR (n.nspname='public' AND p.proname IN (${functions.map(literal).join(',')}))`));
    assert(results.every(v=>v===false));
  });
  await check('empty database is a complete zero summary',async()=> {
    const value=await rpc('get_app_summary_v1');
    assert.deepEqual(value.totals,{plays:0,monthPlays:0,participatingGames:0,participatingPlayers:0,recordedMinutes:'0',durationSamples:0});
    assert.equal(value.personal.status,'unlinked'); assert.equal(value.personal.plays,null);
    assert.equal(value.months.length,6); assert(value.months.every(m=>m.plays===0));
    assert.deepEqual((await rpc('get_statistics_overview_v1')).games,[]);
    assert.equal((await rpc('get_elo_chart_v1')).status,'missing');
  });
  await check('month validation and supported calendar boundaries',async()=> {
    for (const p_month of [null,'2026-10-02','0001-05-01','10000-01-01','infinity','not-a-date']) {
      await rpc('get_app_summary_v1',{p_month},jwt,400);
    }
    for (const p_month of ['0001-06-01','9999-12-01','2024-02-01']) {
      const value=await rpc('get_app_summary_v1',{p_month}); assert.equal(value.months.length,6);
    }
  });
  http.sql(fixture());
  const summary=await rpc('get_app_summary_v1');
  const revision=summary.sourceRevision;
  await check('all owners, date boundaries and zero-filled months',async()=> {
    assert.deepEqual(summary.totals,{plays:14,monthPlays:10,participatingGames:4,participatingPlayers:3,
      recordedMinutes:'37',durationSamples:5});
    assert.deepEqual(summary.months.map(m=>m.plays),[1,0,0,0,1,10]);
    assert.deepEqual(summary.favoriteGames.map(g=>[g.gameId,g.plays]),[[game,10],[game2,1],[id(12),1]]);
    assert.deepEqual(summary.personal,{status:'linked',playerId:player,displayName:'Àlias ♠',plays:4,wins:2});
    const otherSummary=await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),jwt2);
    assert.deepEqual(otherSummary.totals,summary.totals); assert.equal(otherSummary.personal.playerId,second);
  });
  await check('unlinked account and linked player without participations differ',async()=> {
    const before=await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),http.token(unlinked,false));
    assert.equal(before.personal.plays,null);
    http.sql(`BEGIN; INSERT INTO public.players(id,account_user_id,name) VALUES (${literal(id(23))},${literal(unlinked)},'Empty'); COMMIT;`);
    const after=await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),http.token(unlinked,false));
    assert.equal(after.personal.status,'linked'); assert.equal(after.personal.plays,0); assert.equal(after.personal.wins,0);
    http.sql(`DELETE FROM public.players WHERE id=${literal(id(23))}`);
  });
  await check('approved win rate is 2/4 = 50%, not 2/10',async()=> {
    const overview=await rpc('get_statistics_overview_v1');
    assert.deepEqual(overview.winRateByGame,[{gameId:game,name:'Gioco À',participations:4,wins:2,percent:50}]);
  });
  await check('nulls excluded, zero and negative scores included, duration not multiplied',async()=> {
    const value=await rpc('get_game_statistics_v1');
    assert.deepEqual(value.game,{gameId:game,name:'Gioco À',imageUrl:null,plays:10,participations:15,
      scoreSum:'457',scoreSamples:13,scoreAverage:'35.15',recordedMinutes:'30',durationSamples:3,
      durationAverage:'10',unattributedParticipations:1});
    const personal=value.players.find(p=>p.playerId===player);
    assert.equal(personal.participations,4); assert.equal(personal.scoreSamples,3);
    assert.equal(personal.scoreSum,'2'); assert.equal(personal.scoreAverage,'0.67');
  });
  await check('no samples and game without plays return null averages',async()=> {
    for (const p_game_id of [id(13),id(14)]) {
      const value=await rpc('get_game_statistics_v1',{p_game_id});
      assert.equal(value.game.scoreAverage,null); assert.equal(value.game.durationAverage,null);
      assert.equal(value.game.scoreSamples,0);
    }
    await rpc('get_game_statistics_v1',{p_game_id:id(999)},jwt,404);
    await rpc('get_game_statistics_v1',{p_game_id:null},jwt,400);
  });
  await check('invalid new numeric data fails explicitly instead of returning misleading averages',async()=> {
    http.sql(`UPDATE public.plays SET duration_minutes=-1 WHERE id=${literal(id(101))}`);
    await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),jwt,422);
    await rpc('get_game_statistics_v1',argumentsFor('get_game_statistics_v1'),jwt,422);
    http.sql(`UPDATE public.plays SET duration_minutes=NULL WHERE id=${literal(id(101))};
      UPDATE public.play_scores SET score='NaN' WHERE player_id=${literal(third)}`);
    await rpc('get_game_statistics_v1',{p_game_id:game2},jwt,422);
    http.sql(`UPDATE public.play_scores SET score=1.25 WHERE player_id=${literal(third)};
      INSERT INTO public.play_scores(id,play_id,player_id,score,elo_ordinal)
        VALUES (${literal(id(777))},${literal(id(101))},${literal(player)},0,9)`);
    await rpc('get_game_statistics_v1',argumentsFor('get_game_statistics_v1'),jwt,422);
    http.sql(`DELETE FROM public.play_scores WHERE id=${literal(id(777))}`);
  });
  await check('exact decimal strings above JavaScript safe integer and HALF_UP ties',async()=> {
    http.sql(`BEGIN; UPDATE public.play_scores SET score=9007199254740993.125 WHERE player_id=${literal(third)};
      UPDATE public.play_scores SET score=-1.005 WHERE player_id=${literal(player)}; COMMIT;`);
    const big=await rpc('get_game_statistics_v1',{p_game_id:game2});
    assert.equal(big.game.scoreSum,'9007199254740993.125'); assert.equal(big.game.scoreAverage,'9007199254740993.13');
    const negative=await rpc('get_game_statistics_v1');
    assert.equal(negative.players.find(p=>p.playerId===player).scoreAverage,'-1.01');
    http.sql(`UPDATE public.play_scores SET score=CASE play_id WHEN ${literal(id(101))}::uuid THEN 0
      WHEN ${literal(id(102))}::uuid THEN -2 WHEN ${literal(id(103))}::uuid THEN 4 ELSE NULL END
      WHERE player_id=${literal(player)}; UPDATE public.play_scores SET score=1.25 WHERE player_id=${literal(third)}`);
  });
  await check('game pages cover the null-game bucket with stable ordering',async()=> {
    let cursor=null,expected=null; const result=[];
    do {
      const page=await rpc('get_statistics_overview_v1',{p_limit:1,p_cursor:cursor,p_expected_revision:expected});
      expected=page.sourceRevision; result.push(...page.games.map(g=>g.gameId)); cursor=page.nextCursor;
    } while(cursor);
    assert.deepEqual(result,[game,game2,id(12),id(13),null]);
  });
  await check('player pages sort exact means then stable IDs and keep names separate',async()=> {
    const first=await rpc('get_game_statistics_v1',{p_game_id:game,p_limit:1});
    const next=await rpc('get_game_statistics_v1',{p_game_id:game,p_limit:1,p_cursor:first.nextCursor,p_expected_revision:first.sourceRevision});
    assert.equal(first.players[0].playerId,second); assert.equal(next.players[0].playerId,player); assert.equal(next.nextCursor,null);
    const a=await rpc('get_game_statistics_v1',{p_game_id:game2});
    assert.equal(a.players[0].playerId,third); assert.equal(a.players[0].name,first.players[0].name);
  });
  await check('cursor input, wrong account/view/game and page limits rejected',async()=> {
    const page=await rpc('get_statistics_overview_v1',{p_limit:1});
    const args={p_limit:1,p_cursor:page.nextCursor,p_expected_revision:page.sourceRevision};
    await rpc('get_statistics_overview_v1',args,jwt2,400);
    await rpc('get_statistics_overview_v1',{...args,p_expected_revision:null},jwt,400);
    for (const p_cursor of ['',"'; SELECT pg_sleep(30); --",'a'.repeat(4097),Buffer.from('{"offset":-1}').toString('base64')]) {
      await rpc('get_statistics_overview_v1',{...args,p_cursor},jwt,400);
    }
    await rpc('get_game_statistics_v1',{p_game_id:game,p_cursor:page.nextCursor,p_expected_revision:page.sourceRevision},jwt,400);
    const gamePage=await rpc('get_game_statistics_v1',{p_game_id:game,p_limit:1});
    await rpc('get_game_statistics_v1',{p_game_id:game2,p_cursor:gamePage.nextCursor,p_expected_revision:page.sourceRevision},jwt,400);
    for (const p_limit of [null,0,-1,51]) await rpc('get_statistics_overview_v1',{p_limit},jwt,400);
    await rpc('get_game_statistics_v1',{p_game_id:game,p_limit:101},jwt,400);
    for (const p_expected_revision of ['', '-1','abc','9223372036854775808','0'.repeat(30)]) {
      await rpc('get_statistics_overview_v1',{p_expected_revision},jwt,400);
    }
  });
  await check('committed edits invalidate pages, rollback and reads do not change revision',async()=> {
    const page=await rpc('get_statistics_overview_v1',{p_limit:1});
    const before=await rpc('get_app_data_version_v1');
    http.sql(`BEGIN; UPDATE public.games SET name='rolled back' WHERE id=${literal(game)}; ROLLBACK;`);
    assert.equal(await rpc('get_app_data_version_v1'),before);
    await rpc('get_statistics_overview_v1',{p_expected_revision:before});
    assert.equal(await rpc('get_app_data_version_v1'),before);
    http.sql(`UPDATE public.games SET name=name WHERE id=${literal(game)}`);
    await rpc('get_statistics_overview_v1',{p_expected_revision:page.sourceRevision,p_cursor:page.nextCursor},jwt,409);
    assert.notEqual(await rpc('get_app_data_version_v1'),before);
  });
  await check('revision above 2^53 stays an exact decimal string; count overflow explicit',async()=> {
    const before=await rpc('get_app_data_version_v1');
    http.sql("UPDATE kingscore_elo.source_clock SET revision=9007199254740993 WHERE singleton");
    assert.equal((await rpc('get_app_summary_v1')).sourceRevision,'9007199254740993');
    const failed=http.sql("SELECT kingscore_summary.count_value(9007199254740992)",false);
    assert.notEqual(failed.status,0); assert.match(failed.stderr,/Count exceeds exact/);
    http.sql(`UPDATE kingscore_elo.source_clock SET revision=${before} WHERE singleton`);
  });
  await check('missing revision is an explicit service error, never a zero summary',async()=> {
    const before=await rpc('get_app_data_version_v1');
    http.sql('DELETE FROM kingscore_elo.source_clock');
    await rpc('get_app_summary_v1',argumentsFor('get_app_summary_v1'),jwt,503);
    http.sql(`INSERT INTO kingscore_elo.source_clock(singleton,revision) VALUES(true,${before})`);
  });
  await check('RLS restrictions apply to every source table and personal lookup',async()=> {
    // Restrictive policies compose with the captured permissive policies.
    http.sql(`CREATE POLICY summary_test_plays ON public.plays AS RESTRICTIVE FOR SELECT TO authenticated USING(user_id=auth.uid());
      CREATE POLICY summary_test_scores ON public.play_scores AS RESTRICTIVE FOR SELECT TO authenticated
        USING(play_id IN (SELECT id FROM public.plays));
      CREATE POLICY summary_test_players ON public.players AS RESTRICTIVE FOR SELECT TO authenticated USING(account_user_id=auth.uid());
      CREATE POLICY summary_test_games ON public.games AS RESTRICTIVE FOR SELECT TO authenticated USING(id=${literal(game)});`);
    const value=await rpc('get_app_summary_v1');
    assert.equal(value.totals.plays,8); assert.equal(value.personal.plays,2); assert.equal(value.personal.wins,1);
    const detail=await rpc('get_game_statistics_v1');
    assert.equal(detail.game.plays,5); assert.deepEqual(detail.players.map(p=>p.playerId),[player]);
    await rpc('get_game_statistics_v1',{p_game_id:game2},jwt,404);
    http.sql(`DROP POLICY summary_test_plays ON public.plays; DROP POLICY summary_test_scores ON public.play_scores;
      DROP POLICY summary_test_players ON public.players; DROP POLICY summary_test_games ON public.games;`);
  });
  await check('score RLS can hide every score without hiding play durations',async()=> {
    http.sql('CREATE POLICY summary_test_no_scores ON public.play_scores AS RESTRICTIVE FOR SELECT TO authenticated USING(false)');
    const value=await rpc('get_app_summary_v1');
    assert.equal(value.totals.plays,14); assert.equal(value.totals.participatingPlayers,0); assert.equal(value.personal.plays,0);
    const detail=await rpc('get_game_statistics_v1');
    assert.equal(detail.game.scoreSamples,0); assert.equal(detail.game.durationSamples,3); assert.deepEqual(detail.players,[]);
    http.sql('DROP POLICY summary_test_no_scores ON public.play_scores');
  });
  await check('one snapshot during an uncommitted play+score transaction and after commit',async()=> {
    await concurrentWriter(http,jwt,false);
  });
  await check('uncommitted data and revision rollback together',async()=> {
    await concurrentWriter(http,jwt,true);
  });
  let published;
  await check('current published ELO is projected without recalculation',async()=> {
    published=publish(http,id(900));
    const value=await rpc('get_elo_chart_v1');
    assert.equal(value.status,'current'); assert.equal(value.publicationId,published.publicationId);
    assert.equal(value.plays.length,3); assert.equal(value.series.length,3);
    assert.equal(value.series.filter(p=>p.displayName==='Omonimo').length,2);
    const projected=value.plays.flatMap(p=>p.ratings.map(r=>`${p.playId}:${r.playerId}:${r.rating}`)).sort();
    assert.deepEqual(projected,published.result.generalHistory.map(p=>`${p.playId}:${p.playerId}:${p.rating}`).sort());
  });
  await check('ELO pagination keeps same-day plays distinct and seeds only existing ratings',async()=> {
    const first=await rpc('get_elo_chart_v1',{p_limit_plays:1});
    assert.deepEqual(first.initialRatings,[]); assert.equal(first.plays[0].playId,id(111));
    const next=await rpc('get_elo_chart_v1',{p_limit_plays:1,p_cursor:first.nextCursor,p_expected_publication_id:first.publicationId});
    assert.equal(next.plays[0].playId,id(101)); assert.equal(next.plays[0].ratings.length,2);
    assert.deepEqual(next.initialRatings,[{playerId:second,rating:990},{playerId:third,rating:1010}]);
    const last=await rpc('get_elo_chart_v1',{p_limit_plays:1,p_cursor:next.nextCursor,p_expected_publication_id:next.publicationId});
    assert.equal(last.plays[0].playId,id(102)); assert.equal(last.plays[0].date,next.plays[0].date); assert.equal(last.nextCursor,null);
    const byGame=await rpc('get_elo_chart_v1',{p_game_id:game});
    assert.deepEqual(byGame.plays.map(p=>p.playId),[id(101),id(102)]);
  });
  await check('ELO cursor binds account, view, selection and publication',async()=> {
    const first=await rpc('get_elo_chart_v1',{p_limit_plays:1});
    const args={p_limit_plays:1,p_cursor:first.nextCursor,p_expected_publication_id:first.publicationId};
    await rpc('get_elo_chart_v1',args,jwt2,400);
    await rpc('get_elo_chart_v1',{...args,p_game_id:game},jwt,400);
    await rpc('get_elo_chart_v1',{...args,p_player_ids:[player]},jwt,400);
    await rpc('get_elo_chart_v1',{p_cursor:first.nextCursor},jwt,400);
    publish(http,id(901)); await rpc('get_elo_chart_v1',args,jwt,409);
    for (const p_limit_plays of [null,0,201]) await rpc('get_elo_chart_v1',{p_limit_plays},jwt,400);
    for (const p_player_ids of [[],[player,player],[id(999)],[null]]) await rpc('get_elo_chart_v1',{p_player_ids},jwt,400);
    const only=await rpc('get_elo_chart_v1',{p_player_ids:[player]});
    assert.deepEqual(only.series.map(p=>p.playerId),[player]); assert.equal(only.plays[0].ratings.length,0);
  });
  await check('ELO projection respects visible source rows',async()=> {
    http.sql(`CREATE POLICY summary_test_chart ON public.plays AS RESTRICTIVE FOR SELECT TO authenticated USING(id=${literal(id(101))})`);
    const value=await rpc('get_elo_chart_v1'); assert.deepEqual(value.plays.map(p=>p.playId),[id(101)]);
    http.sql('DROP POLICY summary_test_chart ON public.plays');
    http.sql('CREATE POLICY summary_test_chart_scores ON public.play_scores AS RESTRICTIVE FOR SELECT TO authenticated USING(false)');
    const hidden=await rpc('get_elo_chart_v1'); assert.deepEqual(hidden.plays,[]); assert.deepEqual(hidden.series,[]);
    http.sql('DROP POLICY summary_test_chart_scores ON public.play_scores');
  });
  await check('more than 64 ELO series require explicit selection without silent truncation',async()=> {
    const rows=Array.from({length:65},(_,n)=>`(${literal(id(1000+n))},'Extra ${n}')`).join(',');
    http.sql(`INSERT INTO public.players(id,name) VALUES ${rows};
      INSERT INTO public.play_scores(play_id,player_id,score,elo_ordinal)
      SELECT ${literal(id(101))}::uuid,id,0,row_number() OVER(ORDER BY id)+9
      FROM public.players WHERE id>=${literal(id(1000))}::uuid`);
    const body=publication(id(902),await rpc('get_app_data_version_v1'));
    for(let n=0;n<65;n++) {
      const point={playId:id(101),playerId:id(1000+n),rating:1000+n,ratingChange:0,gamesPlayed:1,date:'2026-10-01',playerName:`Extra ${n}`};
      body.result.generalHistory.push(point); body.result.gameHistory.push({...point,gameId:game});
    }
    http.sql(`BEGIN; SET LOCAL ROLE service_role; SELECT set_config('request.jwt.claims','{"role":"service_role"}',true);
      SELECT public.publish_elo_v1(${literal(JSON.stringify(body))}::jsonb); COMMIT;`);
    await rpc('get_elo_chart_v1',{},jwt,422);
    const selected=await rpc('get_elo_chart_v1',{p_player_ids:[player,second]});
    assert.equal(selected.availableSeries,68); assert.equal(selected.series.length,2);
    await rpc('get_elo_chart_v1',{p_player_ids:Array.from({length:65},(_,n)=>id(1000+n))},jwt,400);
    http.sql(`DELETE FROM public.play_scores WHERE player_id>=${literal(id(1000))}::uuid;
      DELETE FROM public.players WHERE id>=${literal(id(1000))}::uuid`);
  });
  await check('stale and missing publications never return chart points',async()=> {
    http.sql(`UPDATE public.games SET name=name WHERE id=${literal(game)}`);
    const stale=await rpc('get_elo_chart_v1'); assert.equal(stale.status,'stale'); assert.deepEqual(stale.plays,[]);
    assert.deepEqual(stale.series,[]);
    http.sql('DELETE FROM kingscore_elo.head');
    const missing=await rpc('get_elo_chart_v1'); assert.equal(missing.status,'missing'); assert.deepEqual(missing.plays,[]);
  });
  await check('all read functions keep source tables and publication unchanged',async()=> {
    const before=sourceFingerprint(http);
    for (const name of functions) await rpc(name);
    assert.equal(sourceFingerprint(http),before);
    const flags=JSON.parse(http.sql(`SELECT jsonb_agg(jsonb_build_object('name',p.proname,'stable',p.provolatile='s',
      'definer',p.prosecdef,'config',p.proconfig)) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='public' AND p.proname IN (${functions.map(literal).join(',')})`));
    assert(flags.every(p=>p.stable)); assert.deepEqual(flags.filter(p=>p.definer).map(p=>p.name),['get_app_data_version_v1']);
    assert(flags.every(p=>p.config.includes('search_path=""')));
  });
  await check('repeat install refuses occupied names without changing existing objects',async()=> {
    const before=sourceFingerprint(http);
    const response=http.sql(sql.install,false);
    assert.notEqual(response.status,0); assert.match(response.stderr,/already occupied/);
    assert.equal(sourceFingerprint(http),before); assert.equal((await rpc('get_app_summary_v1')).totals.plays,15);
  });
  await check('existing cascade deletions remove their rows without inventing missing-reference data',async()=> {
    http.sql(`INSERT INTO public.games(id,name) VALUES (${literal(id(800))},'Delete fixture');
      INSERT INTO public.players(id,name) VALUES (${literal(id(801))},'Delete player');
      INSERT INTO public.plays(id,game_id,played_at) VALUES (${literal(id(802))},${literal(id(800))},'2026-10-01');
      INSERT INTO public.play_scores(play_id,player_id,score,elo_ordinal) VALUES (${literal(id(802))},${literal(id(801))},12,0);
      DELETE FROM public.players WHERE id=${literal(id(801))}`);
    const detail=await rpc('get_game_statistics_v1',{p_game_id:id(800)});
    assert.equal(detail.game.plays,1); assert.equal(detail.game.scoreSamples,0);
    http.sql(`DELETE FROM public.games WHERE id=${literal(id(800))}`);
    const value=await rpc('get_statistics_overview_v1');
    const unknown=value.games.find(g=>g.gameId===null);
    assert.equal(unknown.plays,1); assert.equal(unknown.scoreSamples,0);
    assert.equal((await rpc('get_app_summary_v1')).totals.plays,15);
  });
  report.initialRevision=revision;
}

function sourceFingerprint(http) {
  return http.sql(`SELECT md5(jsonb_build_object('plays',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.plays t),
    'scores',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.play_scores t),
    'players',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.players t),
    'games',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.games t),
    'clock',(SELECT jsonb_agg(to_jsonb(t)) FROM kingscore_elo.source_clock t),
    'publications',(SELECT jsonb_agg(to_jsonb(t) ORDER BY publication_id) FROM kingscore_elo.publications t),
    'head',(SELECT jsonb_agg(to_jsonb(t)) FROM kingscore_elo.head t))::text)`);
}

async function concurrentWriter(http,jwt,rollback) {
  const before=(await http.rpc('get_app_summary_v1',{p_month:'2026-10-01'},jwt)).value;
  const play=id(rollback?501:500);
  const file=path.join(http.directory,`concurrent-${rollback}.sql`);
  writeFileSync(file,`BEGIN; SET application_name='summary-concurrent-writer';
    INSERT INTO public.plays(id,user_id,game_id,played_at,duration_minutes)
      VALUES (${literal(play)},${literal(actor)},${literal(game)},'2026-10-15',9);
    SELECT pg_sleep(2);
    INSERT INTO public.play_scores(play_id,player_id,score,elo_ordinal) VALUES (${literal(play)},${literal(player)},7,0);
    ${rollback?'ROLLBACK':'COMMIT'};`);
  const writer=spawn(http.psql,[...http.connection,'-f',file],{env:http.env,windowsHide:true,stdio:['ignore','ignore','pipe']});
  const done=new Promise((resolve,reject)=>{ let error=''; writer.stderr.on('data',b=>error+=b); writer.once('error',reject);
    writer.once('exit',code=>code===0?resolve():reject(new Error(error))); });
  try {
    let sleeping=false;
    for(let n=0;n<30;n++) {
      if(http.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='summary-concurrent-writer' AND wait_event='PgSleep'")==='1') { sleeping=true; break; }
      await new Promise(r=>setTimeout(r,25));
    }
    assert(sleeping,'writer reached its uncommitted pause');
    const during=(await http.rpc('get_app_summary_v1',{p_month:'2026-10-01'},jwt)).value;
    assert.equal(during.sourceRevision,before.sourceRevision); assert.deepEqual(during.totals,before.totals);
    await done;
    const after=(await http.rpc('get_app_summary_v1',{p_month:'2026-10-01'},jwt)).value;
    if(rollback) { assert.equal(after.sourceRevision,before.sourceRevision); assert.deepEqual(after.totals,before.totals); }
    else { assert.notEqual(after.sourceRevision,before.sourceRevision); assert.equal(after.totals.plays,before.totals.plays+1);
      assert.equal(after.personal.plays,before.personal.plays+1); }
  } finally { await done; }
}

export async function runBenchmarks(http, report, sql) {
  report.hardware={cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,totalMemoryBytes:os.totalmem(),
    os:`${os.type()} ${os.release()} ${os.arch()}`,repetitions:3,seed:'deterministic-series-v1'};
  const jwt=http.token(actor,false);
  for(const size of [1000,10000,100000]) {
    http.sql(`TRUNCATE public.play_scores,public.plays CASCADE;
      INSERT INTO public.plays(id,user_id,game_id,played_at,duration_minutes)
        SELECT md5('summary-play-'||n)::uuid,${literal(actor)},${literal(game)},
          '2026-01-01'::date+(n%300),CASE WHEN n%5=0 THEN NULL ELSE n%120 END FROM generate_series(1,${size}) n;
      INSERT INTO public.play_scores(id,play_id,player_id,score,winner,elo_ordinal)
        SELECT md5('summary-score-'||n||'-'||x)::uuid,md5('summary-play-'||n)::uuid,
          CASE WHEN x=0 THEN ${literal(player)}::uuid ELSE ${literal(second)}::uuid END,
          CASE WHEN n%7=0 THEN NULL ELSE (n%200-100)::numeric/10 END,n%2=x,x
        FROM generate_series(1,${size}) n CROSS JOIN generate_series(0,1) x;
      ANALYZE public.plays; ANALYZE public.play_scores;`);
    const timings=[]; let bytes; let value;
    for(let r=0;r<4;r++) {
      const start=performance.now(); const response=await http.rpc('get_app_summary_v1',{p_month:'2026-10-01'},jwt);
      assert.equal(response.status,200,response.raw); value=response.value;
      if(r)timings.push(performance.now()-start); bytes=Buffer.byteLength(response.raw);
    }
    // Independent JS reference, not copied from the SQL aggregation.
    let minutes=0,samples=0,wins=0,monthPlays=0,scoreSamples=0,scoreTenths=0;
    const months=new Map(['2026-05','2026-06','2026-07','2026-08','2026-09','2026-10'].map(m=>[m,0]));
    for(let n=1;n<=size;n++) {
      if(n%5!==0){minutes+=n%120;samples++;} if(n%2===0)wins++;
      if(n%7!==0){scoreSamples++;scoreTenths+=n%200-100;}
      const date=new Date(Date.UTC(2026,0,1)+(n%300)*86400000).toISOString().slice(0,7);
      if(date==='2026-10')monthPlays++; if(months.has(date))months.set(date,months.get(date)+1);
    }
    assert.equal(value.totals.plays,size); assert.equal(value.totals.durationSamples,samples);
    assert.equal(value.totals.recordedMinutes,String(minutes)); assert.equal(value.totals.monthPlays,monthPlays);
    assert.equal(value.personal.wins,wins); assert.deepEqual(value.months.map(m=>m.plays),[...months.values()]);
    const overview=await http.rpc('get_statistics_overview_v1',{},jwt);
    assert.equal(overview.status,200,overview.raw);
    assert.equal(overview.value.games[0].plays,size);
    assert.equal(overview.value.winRateByGame[0].percent,Math.round(100*wins/size));
    const detail=await http.rpc('get_game_statistics_v1',{p_game_id:game},jwt);
    assert.equal(detail.status,200,detail.raw);
    assert.equal(detail.value.game.scoreSamples,scoreSamples*2);
    assert.equal(scaled(detail.value.game.scoreSum,1),BigInt(scoreTenths)*2n);
    const expectedMean=halfUp(BigInt(scoreTenths)*10n,BigInt(scoreSamples));
    assert.equal(scaled(detail.value.game.scoreAverage,2),expectedMean);
    assert.equal(BigInt(detail.value.game.durationAverage),halfUp(BigInt(minutes),BigInt(samples)));
    for(const p of detail.value.players) {
      assert.equal(p.participations,size);assert.equal(p.scoreSamples,scoreSamples);
      assert.equal(scaled(p.scoreAverage,2),expectedMean);
    }
    report.cases.push({name:`independent reference verifies Home, win rate and game/player means on ${size} plays`,status:'PASS'});
    // HTTP reference mirrors the current Android's lean projection, page size 500, terminates on empty page.
    let oldBytes=0,oldRequests=0,oldRows=0; const oldStart=performance.now();
    for(let offset=0;;offset+=500) {
      const response=await http.request('GET',`plays?select=id,played_at,duration_minutes,game_id,games(name,image_url),play_scores(player_id,winner)&order=played_at.desc,id.desc&limit=500&offset=${offset}`,undefined,jwt);
      assert.equal(response.status,200,response.raw); oldBytes+=Buffer.byteLength(response.raw); oldRequests++;
      oldRows+=response.value.length; if(!response.value.length)break;
    }
    assert.equal(oldRows,size);
    const oldMs=performance.now()-oldStart;
    const plan=http.sql(`BEGIN; SET LOCAL ROLE authenticated;
      SELECT set_config('request.jwt.claims',${literal(JSON.stringify({sub:actor,role:'authenticated'}))},true);
      EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT count(*),sum(duration_minutes::numeric),
        count(*) FILTER(WHERE played_at>='2026-10-01' AND played_at<'2026-11-01') FROM public.plays;
      EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT * FROM kingscore_summary.game_rows(${literal(game)}::uuid); ROLLBACK;`);
    writeFileSync(path.join(http.directory,`plan-${size}.txt`),plan);
    const row={plays:size,scoreRows:size*2,summaryBytes:bytes,summaryRequests:1,
      summaryHttpMs:timings,summaryMedianMs:[...timings].sort((a,b)=>a-b)[1],
      scanBytes:oldBytes,scanRequests:oldRequests,scanHttpMs:oldMs,indexAdded:false};
    report.benchmarks.push(row); console.log(JSON.stringify(row));
  }
  const before=sourceFingerprint(http);
  http.sqlFile('summary-rollback',sql.rollback);
  assert.equal(sourceFingerprint(http),before);
  assert.equal(http.sql("SELECT to_regnamespace('kingscore_summary') IS NULL"),'t');
  assert.equal(http.sql("SELECT to_regprocedure('public.get_app_summary_v1(date)') IS NULL"),'t');
  assert.equal(http.sql("SELECT to_regprocedure('public.get_elo_publication_v1()') IS NOT NULL"),'t');
  report.cases.push({name:'rollback removes only the candidate, preserving source and ELO',status:'PASS'});
  for(const [name,mutate,restore] of [
    ['negative historical duration',"UPDATE public.plays SET duration_minutes=-1 WHERE id=md5('summary-play-1')::uuid",
      "UPDATE public.plays SET duration_minutes=1 WHERE id=md5('summary-play-1')::uuid"],
    ['non-finite historical score',"UPDATE public.play_scores SET score='NaN' WHERE id=md5('summary-score-1-0')::uuid",
      "UPDATE public.play_scores SET score=-9.9 WHERE id=md5('summary-score-1-0')::uuid"],
    ['duplicate historical participant',`INSERT INTO public.play_scores(id,play_id,player_id,score,elo_ordinal)
      VALUES (${literal(id(9999))},md5('summary-play-1')::uuid,${literal(player)},0,9)`,
      `DELETE FROM public.play_scores WHERE id=${literal(id(9999))}`],
  ]) {
    http.sql(mutate);
    const failed=http.sql(sql.install,false);
    assert.notEqual(failed.status,0); assert.match(failed.stderr,/Invalid historical/);
    assert.equal(http.sql("SELECT to_regnamespace('kingscore_summary') IS NULL"),'t');
    http.sql(restore); report.cases.push({name:`preflight refuses ${name} atomically`,status:'PASS'});
  }
  const trigger=http.sql("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid='public.games'::regclass AND tgname='kingscore_elo_dirty_games'");
  http.sql('ALTER TABLE public.games DISABLE TRIGGER kingscore_elo_dirty_games');
  const disabled=http.sql(sql.install,false); assert.notEqual(disabled.status,0); assert.match(disabled.stderr,/revision trigger required/);
  http.sql('ALTER TABLE public.games ENABLE TRIGGER kingscore_elo_dirty_games');
  assert.equal(http.sql("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid='public.games'::regclass AND tgname='kingscore_elo_dirty_games'"),trigger);
  report.cases.push({name:'preflight refuses incomplete source revision coverage',status:'PASS'});
  // Reinstallation validates preflight on large fixtures and confirms rollback left no names behind.
  http.sqlFile('summary-reinstall',sql.install);
  // NOTIFY reload is asynchronous: wait only for catalog-readiness errors, never retry a data error.
  let reinstalled;
  for(let attempt=0;attempt<50;attempt++) {
    reinstalled=await http.rpc('get_app_summary_v1',{p_month:'2026-10-01'},jwt);
    if(reinstalled.status===200)break;
    assert(['PGRST202','PGRST002'].includes(reinstalled.value?.code),reinstalled.raw);
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.equal(reinstalled.status,200,reinstalled.raw);
  assert.equal(reinstalled.value.totals.plays,100000);
  report.cases.push({name:'clean reinstall after rollback succeeds',status:'PASS'});
}

function scaled(value,digits) {
  const match=/^(-?)([0-9]+)(?:\.([0-9]+))?$/.exec(value); assert(match,`Invalid decimal ${value}`);
  const fraction=(match[3]??'').padEnd(digits,'0'); assert(/^0*$/.test(fraction.slice(digits)));
  return BigInt(`${match[1]}${match[2]}${fraction.slice(0,digits)}`);
}
function halfUp(numerator,denominator) {
  const sign=numerator<0n?-1n:1n; const positive=numerator*sign;
  return sign*((2n*positive+denominator)/(2n*denominator));
}
