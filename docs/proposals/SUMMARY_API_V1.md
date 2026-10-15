# API di riepilogo V1 — proposta da revisionare

Stato al 15 ottobre 2026: SQL approvato dall'utente e applicato al progetto Supabase ScoreKing (`rixivmeipbizoxyvrvaf`), dopo verifica del progetto e preflight. Dati, revisione/pubblicazione ELO, policy, grant e RPC precedenti sono invariati; le cinque nuove letture passano il collaudo SQL con ruolo authenticated e rifiutano accesso HTTP anonimo. [Evidenza del rollout](../evidence/summary-api-live-validation.json). Integrazione Android in corso; nessun deploy Vercel o modifica al codice web.

Il collaudo locale precedente comprende 45 gruppi di verifiche e tre benchmark: [risultati e limiti](SUMMARY_API_VALIDATION.md). Le sezioni sotto conservano il contratto approvato; le indicazioni di approvazione preventiva descrivono la procedura seguita, non un nuovo blocco per l'integrazione Android autorizzata.

SQL completo: [installazione](../../local-tests/summary-api/01-install.sql) e [rollback](../../local-tests/summary-api/02-rollback.sql). [Runner e limiti del collaudo PC](../../local-tests/summary-api/README.md).

## Obiettivo e confini

Calcolare nel database i riepiloghi che oggi richiedono di trasferire tutte le partite all'app. Il telefono riceverà conteggi e aggregati completi; i dettagli arriveranno quando si apre la relativa sezione. Il database dovrà ancora leggere i dati necessari: riduciamo trasferimenti, richieste ripetute e lavoro sul telefono, senza promettere tempi prima delle misure.

La Home conserva il riepilogo attuale. Statistiche deve riprendere contenuti e ordine della web app: Win Rate per Gioco, ELO Generale, Statistiche per Gioco con medie, giocatori e andamento ELO. Il confronto è in [STATISTICS_WEB_PARITY.md](STATISTICS_WEB_PARITY.md).

Decisioni confermate dall'utente:

1. Win rate = vittorie / partite a cui il giocatore ha partecipato: due vittorie su quattro partecipazioni danno 50%, anche se il gioco ha dieci partite globali.
2. Punteggi e durate mancanti sono esclusi dalle rispettive medie. Mostrare sempre quanti valori sono disponibili. Uno zero registrato resta un valore valido.

Login e Internet restano obbligatori. Nessuna registrazione, modifica credenziali/avatar o funzione di ricalcolo ELO nell'app.

## Basi verificate e controllo prima dell'installazione

La repo web su GitHub ha HEAD `78918e57e44e3fa385d0a0108ec3fa73b86edd1b`, uguale al checkout consultato in sola lettura. I file `scripts/atomic-play-migrations/02-elo-publication.sql` definiscono già `kingscore_elo.source_clock` e trigger su INSERT/UPDATE/DELETE/TRUNCATE di games, players, plays e play_scores. Il rollout è registrato nelle evidenze della 0.5.0. Non è stata eseguita una nuova interrogazione del catalogo cloud in questa attività.

Il contatore può essere riusato come versione dei dati, senza nuova tabella e senza nuovi trigger. È un valore opaco: non indica il numero delle partite e può avanzare più volte nella stessa transazione. I commenti non lo modificano e non fanno parte dei riepiloghi proposti.

Prima di qualsiasi futura installazione: verificare sul progetto di destinazione funzioni, owner, RLS, privilegi, indici e copertura effettiva dei trigger. Una modifica futura a RLS o visibilità deve cambiare anche la versione del contratto/scope: il contatore dei dati non rappresenta modifiche ai permessi.

## Modifiche Supabase proposte

| Oggetto nuovo | Scopo | Modalità |
| --- | --- | --- |
| `get_app_data_version_v1()` | Leggere soltanto la revisione esistente | STABLE, SECURITY DEFINER limitato al contatore |
| `get_app_summary_v1(p_month date)` | Prima apertura della Home: totali, sei mesi, tre giochi preferiti, riepilogo personale | STABLE, SECURITY INVOKER, aggregati sotto RLS |
| `get_statistics_overview_v1(p_expected_revision text, p_cursor text, p_limit integer)` | Top 10 win rate personale e pagina di riepiloghi per gioco | STABLE, SECURITY INVOKER, aggregati sotto RLS |
| `get_game_statistics_v1(p_game_id uuid, p_expected_revision text, p_cursor text, p_limit integer)` | Medie e statistiche dei giocatori del gioco aperto | STABLE, SECURITY INVOKER, aggregati sotto RLS |
| `get_elo_chart_v1(p_game_id uuid, p_expected_publication_id uuid, p_cursor text, p_limit_plays integer, p_player_ids uuid[])` | Finestra del grafico dalla pubblicazione ELO già esistente, con selezione facoltativa dei giocatori | STABLE, SECURITY INVOKER; proiezione della RPC di pubblicazione esistente |

Per velocizzare la Home bastano le prime due RPC. Le altre tre servono alla pagina Statistiche fedele al web, con caricamento progressivo. La candidata completa aggiunge anche lo schema `kingscore_summary` e otto helper condivisi per formati numerici, collegamento account, aggregati e cursori. Nessun helper modifica dati. I corpi e le firme con default sono nel SQL completo collegato sopra.

Parametri obbligatori: `p_month` per la Home e `p_game_id` per il dettaglio gioco. Nelle RPC paginate, `p_cursor` e la versione attesa hanno default NULL per la prima richiesta; un cursore richiede invece la relativa versione. `p_limit` ha default 20 nella panoramica e 50 nel dettaglio. Nel grafico `p_game_id` ha default NULL per indicare l'ELO generale, `p_limit_plays` ha default 100 e `p_player_ids` ha default NULL.

Non occorrono nuove tabelle di riepilogo, colonne, cron, cache persistenti o modifiche ai writer. Le funzioni esistenti e la pubblicazione ELO restano disponibili; il vecchio APK continua a funzionare.

### Permessi da includere nella futura migrazione

Tutte le nuove RPC sono riservate al ruolo `authenticated`; controllano anche `auth.uid()` e il ruolo JWT. L'identità personale deriva dal token, mai da un account ID ricevuto dal telefono. I dati globali includono tutti gli account visibili secondo le attuali policy, senza filtro sul proprietario della partita.

Le funzioni di aggregazione usano SECURITY INVOKER, `search_path = ''` e nomi qualificati. Non ampliano i privilegi SELECT e non aggirano RLS. Il solo helper di revisione usa SECURITY DEFINER perché il contatore è privato: legge un numero, non partite, profili o contenuti della pubblicazione. Non concede accesso diretto allo schema `kingscore_elo`.

Lo schema helper `kingscore_summary` concede USAGE ed EXECUTE ad authenticated, necessari alle chiamate invoker. Non viene aggiunto agli schemi esposti da PostgREST. Le letture dei grafici verificano anche la visibilità di partite, giocatori e righe punteggio, pur partendo dalla pubblicazione ELO globale già pubblica.

Esempio **dei soli permessi proposti**, da eseguire in futuro nella stessa transazione di creazione delle funzioni, dopo i test locali:

```sql
REVOKE ALL ON FUNCTION
  public.get_app_data_version_v1(),
  public.get_app_summary_v1(date),
  public.get_statistics_overview_v1(text, text, integer),
  public.get_game_statistics_v1(uuid, text, text, integer),
  public.get_elo_chart_v1(uuid, uuid, text, integer, uuid[])
FROM PUBLIC, anon, authenticated, service_role;

GRANT EXECUTE ON FUNCTION
  public.get_app_data_version_v1(),
  public.get_app_summary_v1(date),
  public.get_statistics_overview_v1(text, text, integer),
  public.get_game_statistics_v1(uuid, text, text, integer),
  public.get_elo_chart_v1(uuid, uuid, text, integer, uuid[])
TO authenticated;
```

Non modificare i privilegi globali delle funzioni del progetto. Verificare i privilegi effettivi, inclusi quelli ereditati, usando i ruoli reali nel test locale. La creazione deve fallire se un nome V1 è già occupato, anziché sovrascriverlo senza verifica.

### Indici

L'audit salvato contiene indici su play_scores.play_id e play_scores.player_id e l'indice univoco parziale su players.account_user_id. Non documenta un indice su plays.game_id; non basta per dedurre lo stato cloud attuale.

Un indice candidato è `public.plays(game_id, played_at, id)`, da proporre solo se manca un indice equivalente e se EXPLAIN ANALYZE locale dimostra utilità sui dettagli per gioco. Nessun indice obbligatorio aggiunto preventivamente. Un indice non elimina la scansione necessaria per aggregati globali esatti.

## Contratto della prima risposta

Richiesta: `POST /rest/v1/rpc/get_app_summary_v1`, con token utente e chiave pubblica del client.

```json
{"p_month":"2026-10-01"}
```

`p_month` è il primo giorno del mese corrente nel calendario del dispositivo, tra giugno dell'anno 1 e dicembre 9999. `played_at` è una data, non un timestamp: confronti tra date con intervalli chiusi a sinistra e aperti a destra. La finestra comprende quel mese e i cinque precedenti; i mesi senza partite hanno conteggio zero.

Esempio sintetico del corpo, non dati di produzione:

```json
{
  "contractVersion": 1,
  "scope": "global-visible-v1",
  "sourceRevision": "123",
  "computedAt": "2026-10-15T17:00:00Z",
  "month": "2026-10",
  "totals": {
    "plays": 3,
    "monthPlays": 3,
    "participatingGames": 1,
    "participatingPlayers": 2,
    "recordedMinutes": "60",
    "durationSamples": 2
  },
  "months": [
    {"month":"2026-05","plays":0},
    {"month":"2026-06","plays":0},
    {"month":"2026-07","plays":0},
    {"month":"2026-08","plays":0},
    {"month":"2026-09","plays":0},
    {"month":"2026-10","plays":3}
  ],
  "favoriteGames": [
    {"gameId":"00000000-0000-0000-0000-000000000010","name":"Gioco esempio","imageUrl":null,"plays":3}
  ],
  "personal": {
    "status":"linked",
    "playerId":"00000000-0000-0000-0000-000000000020",
    "displayName":"Giocatore esempio",
    "plays":2,
    "wins":1
  }
}
```

La risposta non contiene partite individuali, note, punteggi individuali o testi dei commenti. I tre preferiti seguono il comportamento Android corrente: giochi più giocati dal gruppo, ordinati per conteggio decrescente e UUID crescente a parità.

I conteggi sono interi JSON esatti, entro il limite sicuro JavaScript; il decoder Android usa Long e rifiuta overflow. Revisione e somme sono stringhe decimali per evitare perdita di precisione. Il server deve rifiutare valori fuori contratto, senza conversione approssimata.

L'account si collega al giocatore tramite `players.account_user_id = auth.uid()`, come nell'Android attuale. `user_id`/`is_me` storici non sono un'alternativa. Se manca il collegamento, `personal.status` è `unlinked`, playerId/displayName sono null e plays/wins sono null; l'interfaccia mostra il collegamento assente. Un account collegato senza partecipazioni ha invece conteggi zero.

## Contratto Statistiche fedele al web

### Panoramica e paginazione giochi

`get_statistics_overview_v1` restituisce sourceRevision, computedAt, winRateByGame, games e nextCursor. La prima chiamata può avere expectedRevision nullo; le successive devono usare la revisione ottenuta. Default pagina 20, massimo 50; nessun troncamento silenzioso.

Win rate personale: contare una partecipazione per coppia partita/giocatore collegato; una vittoria se winner è true. Non dedurla da punteggi, posizione o ELO. Includere solo giochi a cui il giocatore ha partecipato. Top 10 ordinata per sue partecipazioni decrescenti e UUID crescente; percentuale intera arrotondata come nel web, ma con il denominatore approvato. Senza partecipazioni la percentuale è null, non zero.

Ogni gioco espone ID, nome, copertina, partite totali, somme/campioni e medie di punteggio e durata. Le partite con riferimento gioco NULL formano il gruppo `gameId: null`, etichettato come non disponibile e senza pulsante dettaglio. Non viene creato un UUID fittizio. Il conteggio dei giochi distinti in Home esclude quel gruppo. I vincoli attuali eliminano invece partite e punteggi associati quando viene cancellato un gioco: la candidata conserva questa regola e non ricostruisce righe cancellate.

Ordinamento giochi: partite globali decrescenti, UUID crescente, gruppo nullo ultimo a parità. Il cursore lega posizione, revisione e account chiamante; il telefono lo tratta come opaco. Il server valida struttura, lunghezza e tipi, senza concatenazione SQL dinamica. Se la revisione cambia, errore 409 e nuova prima pagina: non proseguire su una classifica modificata.

### Gioco espanso e medie

`get_game_statistics_v1` restituisce il riepilogo del gioco e una pagina di giocatori: ID, nome/alias, avatar in lettura, partecipazioni, somma punteggi, campioni disponibili e media. Default 50 giocatori, massimo 100; ordinamento per media decrescente, assenza di media in fondo, UUID crescente a parità. Le pagine usano la stessa revisione.

Per punteggi e durate restituire numeratore e denominatore, oltre al valore formattabile. I punteggi NUMERIC restano stringhe decimali; sono ammessi valori negativi e zero. La media punteggi è arrotondata a due decimali con HALF_UP. La durata media della scheda gioco è arrotondata al minuto, come nel web; le somme e i campioni permettono alla Home di mantenere il proprio formato senza doppio arrotondamento.

NULL non entra né nel numeratore né nel denominatore della relativa media. Senza campioni: media null, messaggio «non disponibile». La copertura distingue, per esempio, «punteggio medio su 7 di 10 partecipazioni» e «durata media su 3 di 5 partite». Una durata registrata pari a zero entra nella media. Durate negative, score non finiti o partecipazioni duplicate dello stesso giocatore impediscono l'installazione nel preflight; gli aggregati interessati rifiutano inoltre dati invalidi successivi con HTTP 422. Nessuna normalizzazione o riscrittura storica implicita.

Punteggi con player_id nullo contribuiscono alla media del gioco se il punteggio è presente, ma non inventano un singolo giocatore «Unknown». Esporre separatamente il numero di righe non attribuibili. Nomi e alias non sono chiavi d'identità.

### Grafici ELO

La nuova RPC proietta la pubblicazione già disponibile tramite `get_elo_publication_v1()`. Non consulta le vecchie tabelle ELO come fonte alternativa e non avvia worker, pubblicazioni o calcoli. Invoker, guard autenticato e ACL dei nuovi endpoint si applicano anche a questa proiezione.

La proiezione riduce i dati inviati al telefono, ma il database deve ancora leggere ed elaborare il JSON della pubblicazione esistente. Non equivale a uno storico ELO indicizzato: costo e dimensione della pubblicazione vanno misurati prima di promettere un miglioramento del tempo del server.

Contratto: publicationId, sourceRevision, publishedRevision, status current/stale/missing, giocatori con ID e colori, punti raggruppati per partita e nextCursor. Mai dividere i partecipanti della stessa partita tra due pagine. Il cursore è legato alla pubblicazione; se cambia la generazione, 409. Se la pubblicazione è stale/missing, nessun punto presentato come corrente: mostrare lo stato ELO separatamente dalle statistiche numeriche aggiornate.

Prima finestra di 100 partite in ordine cronologico, massimo 200 per richiesta. Il grafico indica la finestra caricata e permette di navigare/caricare il resto; non spaccia una finestra per lo storico intero. Restituisce l'ultimo rating precedente per inizializzare il forward-fill, senza attribuire un rating prima del primo punto reale. Non unisce giocatori con lo stesso nome e non collassa partite dello stesso giorno. Oltre 64 serie restituisce HTTP 422 richiedendo una selezione esplicita tramite `p_player_ids` (massimo 64 ID distinti), senza esclusioni silenziose. La selezione è legata al cursore; `availableSeries` indica quante serie sono disponibili.

Lo storico individuale eliminato dalla schermata Rating ELO rimane eliminato. I grafici di confronto della pagina Statistiche riprendono quelli della pagina web richiesta dall'utente.

## Coerenza, errori e uso Android

Ogni aggregato usa un'unica vista dei dati commessi per la chiamata. Funzioni STABLE e un solo statement di aggregazione leggono contatore e dati nello stesso snapshot; una transazione admin a metà non può produrre «partita salvata senza punteggi» nel risultato.

sourceRevision è un controllo di consistenza, non un archivio storico: due RPC separate non condividono automaticamente uno snapshot SQL. expectedRevision permette di rifiutare pagine/dettagli nuovi rispetto al riepilogo. Non usare computedAt come versione. Non aggiungere subito un «not modified» che salti i controlli di visibilità.

401/403 attivano le attuali protezioni di accesso; input non valido è 400, revisione cambiata 409, gioco assente 404, dati numerici invalidi o selezione serie necessaria 422, versione sorgente indisponibile 503. Errori di timeout o lettura sono espliciti, mai riepiloghi di zeri. Un database realmente vuoto restituisce 200 e aggregati completi vuoti.

Android mantiene auth verificata, rete validata, risorse per account, cancellazione su logout/offline e scarto delle risposte tardive. Cache condivisa Home/Statistiche, invalidazione dopo salvataggi partite e recupero manuale tramite pull-to-refresh. Nessun riuso offline. Il cambio di revisione invalida anche una pubblicazione ELO in RAM di versione diversa.

Flusso Home: una RPC di riepilogo, più la lettura già paginata delle partite recenti. Flusso Statistiche: panoramica giochi e grafico ELO indipendenti, con dettagli caricati all'espansione. Il caricamento ELO non deve bloccare win rate e giochi. Una risposta 409 consente un solo tentativo automatico di ricaricamento; ulteriori modifiche concorrenti richiedono un refresh dell'utente.

Il numero indicato riguarda le richieste dati: restano verifiche Auth e download delle immagini. Le risposte sono limitate per sezione. Misurare byte effettivi e tempi, senza promettere un budget in millisecondi o un payload costante per testi di lunghezza arbitraria.

## Validazione locale prima del SQL da applicare

1. Confronto con un riferimento indipendente su database vuoto, una partita e oltre 1.000 partite: globali, mesi mancanti, preferiti e aggregati per gioco. Home deve conservare i valori corretti esistenti.
2. Regole approvate: 2 vittorie / 4 partecipazioni / 10 partite globali = 50%; NULL esclusi, zero incluso, score negativi e decimali esatti, medie senza campioni e copertura corretta. Account scollegato, omonimi e riferimenti cancellati distinti.
3. Sicurezza con ruoli anon/authenticated/admin e token assente/scaduto; RLS restrittiva di prova, account diversi, tentativi di scegliere l'account altrui, cursori malformati e injection. Nessuna funzione muta dati o prende lock di scrittura.
4. Concorrenza: reader durante transazione partita+punteggi, commit e rollback, versione modificata tra pagine, cancellazione, cambio metadati, nuova pubblicazione ELO e ripetizioni senza duplicati. Mai mescolare versioni o restituire parziali come totali.
5. EXPLAIN ANALYZE e misure riproducibili su fixture da 1.000, 10.000 e 100.000 partite, registrando hardware, righe punteggi, ruoli, piani, byte e tempi. Confrontare la scansione Android attuale e gli aggregati SQL; introdurre un indice solo con evidenza. Le [misure completate](SUMMARY_API_VALIDATION.md) riportano l'hash del SQL collaudato e distinguono latenza loopback e prestazioni cloud non misurate.

## Sequenza e rollback

Implementazione, prove sul PC, SQL completo con ACL/preflight e rollback sono disponibili. Il passaggio corrente è la revisione dell'utente; nessuna applicazione cloud prima dell'autorizzazione.

Dopo l'eventuale approvazione: installazione additiva verificata sul progetto corretto, smoke limitato autorizzato, quindi integrazione Android e UI Statistiche allineata al web. L'APK 0.5.2 mantiene il comportamento attuale finché non viene pubblicato un nuovo APK. Un eventuale aggiornamento della web app ai nuovi calcoli è una modifica distinta, non applicata da questo progetto.

Rollback: tornare al repository di lettura precedente nell'app e revocare/rimuovere solo le nuove RPC, eliminando l'helper per ultimo. Non usare DROP CASCADE; non rimuovere source_clock, trigger o funzioni ELO esistenti. Un eventuale indice nuovo si gestisce separatamente. Nessun dato da ripristinare perché le nuove letture non scrivono.

Fonti tecniche: [Supabase, funzioni e sicurezza](https://supabase.com/docs/guides/database/functions), [PostgreSQL, snapshot delle funzioni STABLE](https://www.postgresql.org/docs/current/xfunc-volatility.html). La proposta non costituisce un test o una verifica attuale dello schema cloud.
