# Contratti API KingScore Android

Versione 1, basata sul catalogo Supabase catturato il 13 ottobre 2026. `contracts/v1/schema.json` elenca ogni colonna con tipo SQL, nullabilità e tipo Kotlin; `Rows.kt` contiene otto DTO completi generati. `operations.json` definisce il perimetro consentito dell'app.

## Accesso e connessione

Tutte le richieste dati richiedono una sessione valida e Internet raggiungibile, anche se il backend consente SELECT anonime. Usare la chiave pubblica e il JWT utente, mai `service_role`. Il ruolo esposto dalla sessione abilita i comandi UI; il server rimane l'autorità per i permessi.

Auth consentita: login email/password di un account esistente, refresh della sessione, `GET /auth/v1/user` per convalidare il ripristino e logout della sessione corrente. Registrazione, reset password, modifica email/password/avatar sono esclusi. Il logout cancella comunque la sessione locale; se il server non è raggiungibile, non riaprire schermate dati.

Alla perdita di connessione mostrare lo stato dedicato e sospendere consultazione e invio. La sola presenza del Wi-Fi non prova la raggiungibilità. Dopo la riconnessione verificare la sessione e rileggere i dati. Non accodare scritture e non reinviare automaticamente un'operazione dall'esito ambiguo. Un eventuale refresh token avviene una sola volta per la richiesta; una sessione non recuperabile riporta al login.

## Tipi e decodifica

| SQL | Kotlin | Convenzione wire |
| --- | --- | --- |
| uuid | `UUID` | Stringa UUID validata |
| date | `LocalDate` | `YYYY-MM-DD`, senza conversioni UTC o ora locale |
| timestamptz | `Instant` | Timestamp ISO con offset; preservare l'istante |
| numeric | `BigDecimal` | Token JSON numerico decodificato senza passare da Double |
| integer, boolean, text | `Int`, `Boolean`, `String` | Conservare il tipo e la nullabilità del catalogo |

La fase 2 implementa `data/remote/RowDecoders.kt`, generato dal catalogo insieme ai DTO e testato per gli otto modelli. Il client Auth è operativo; la fase 3 implementa storico, dettaglio e commenti in lettura tramite SupabasePlaysRepository e SupabaseReader. Campi nullable storici non diventano automaticamente zero, false o stringa vuota. Un valore NUMERIC non finito o non decodificabile produce un errore esplicito. I join mancanti non vengono sostituiti con oggetti inventati.

`PlayDetail` compone una `PlayRow`, il gioco nullable e gli score con giocatore nullable. Per il dettaglio, usare la proiezione PostgREST `*,games(*),play_scores(*,players(*))`; l'adattatore scompone i campi annidati nei DTO. Il nome visualizzato del giocatore usa alias, poi name. `user_id` e `account_user_id` non sono intercambiabili.

`source` storico resta `String?`: nel database può contenere `manual`, mentre il web scrive `offline` per IRL e `online` per partite online. Nei nuovi input usare questi due valori web. `offline` identifica il luogo di gioco, non una modalità dell'app.

## Letture esistenti

| Dati | Risorsa e ordine |
| --- | --- |
| Storico partite | `GET /rest/v1/plays`; ordine `played_at.desc,id.desc`; filtri tipizzati per gioco, date e source |
| Dettaglio | `plays?id=eq.<uuid>` con join gioco, score e giocatori; array vuoto significa risorsa assente, non successo di una modifica |
| Cataloghi e profilo | `games`, `players`; il collegamento account usa `account_user_id=eq.<session-user-id>` |
| Commenti | `play_comments?play_id=eq.<uuid>`; ordine `created_at.asc,id.asc` |
| ELO | `player_ratings`, `player_general_ratings`, `elo_history`; filtri player/game, ordine deterministico anche per pari rating |

Richiedere pagine di 50 elementi, massimo 100 per richiesta nell'app. Per lo storico usare un cursore `(played_at,id)` e per i commenti `(created_at,id)`; la pagina successiva applica il confronto lessicografico coerente con l'ordine. Non cambiare o normalizzare il dataset in memoria per aggirare il limite server. Validare UUID, date e cursori prima di costruire i filtri SDK.

Per statistiche che richiedono l'intero intervallo, attraversare tutte le pagine oppure introdurre in seguito un'aggregazione server verificata. Non considerare la prima pagina come totale. Paginazione su dati concorrenti non equivale a uno snapshot immutabile: il refresh riparte dal primo cursore.

La build 0.3.2 applica `HistoryFilters` su ogni pagina: ricerca letterale in gioco OR note, gioco UUID, partecipante UUID, source e date inclusive. L'alias separato per il partecipante evita di troncare i punteggi; ricerca e cursore condividono un unico gruppo AND. `filterOptions()` attraversa tutti i cataloghi giochi/giocatori per ID ASC, senza derivare le opzioni dalla prima pagina dello storico. Dettagli di escaping e stati UI: [PHASE3_SEARCH.md](PHASE3_SEARCH.md).

ELO è solo lettura: nessun insert/update/delete sui rating, nessuna chiamata alla funzione notturna o a `get_play_user_ids`, nessuna lettura di `elo_recalc_runs`. Mostrare `updated_at` del rating quando disponibile; se manca, indicare data non disponibile senza inventare lo stato del job.

La build 0.3.1 legge il collegamento profilo con `players.account_user_id=eq.<account>` e rifiuta collegamenti ambigui. La classifica generale filtra l'archivio del proprietario storico del giocatore (`players.user_id`), oppure dell'account quando non c'è un proprietario collegato. La classifica per gioco usa soltanto `game_id`. Cataloghi e rating vengono attraversati per `id ASC` prima di ordinarli per nome o rating; i valori mancanti non diventano zero. Lo storico rating usa cursori `date DESC,id DESC`, con filtri per player, tipo e archivio generale. Le posizioni 1,1,3 sono una presentazione dei rating pubblicati, non un calcolo ELO.

## Scritture commenti esistenti

Creazione: `POST /rest/v1/play_comments` con `{play_id,content}`. Modifica: PATCH per `id` e `user_id` della sessione con `{content}`. Eliminazione: DELETE per gli stessi identificatori. Richiedere la rappresentazione della riga modificata o eliminata per distinguere successo da zero righe.

Normalizzare il contenuto con trim e accettare da 1 a 1.000 code point Unicode. Il server applica il proprio vincolo SQL e RLS. Non inviare autore, user_id, timestamp o ruolo. Il trigger deriva `author_name` dal JWT e modifica `updated_at`; il nome resta una presentazione, non un'identità autorizzativa. Anche gli admin possono eliminare soltanto i propri commenti.

La modifica commento non dispone attualmente di un controllo optimistic locking: prevale l'ultimo salvataggio autorizzato. La creazione commento non è idempotente: dopo un timeout non eseguire retry automatici. Rileggere i commenti e rendere esplicito l'esito incerto all'utente.

## RPC partite proposte

Queste funzioni **non sono deployate**. Gli input Kotlin in `Commands.kt` ne definiscono i payload, non autorizzano un fallback sulle scritture dirette. Fino a deploy e test isolati, il client può implementare la consultazione e i commenti, ma non abilitare l'editor partite.

| RPC proposta | Argomenti | Risultato |
| --- | --- | --- |
| `get_play_editor_v1` | `p_play_id: uuid` | `{detail,revision}` per admin; detail è la composizione PlayDetail |
| `create_play_v1` | `p_request_id: uuid`, `p_payload: jsonb` | `{play_id,revision,replayed}` |
| `update_play_v1` | `p_request_id`, `p_play_id`, `p_expected_revision: text`, `p_payload` | Stessa risposta; conflitto se revisione diversa |
| `delete_play_v1` | `p_request_id`, `p_play_id`, `p_expected_revision` | `{play_id,deleted,replayed}` con deleted=true su successo |

Gli adattatori mappano i campi dei command Kotlin sugli argomenti `p_*`. Il payload contiene `game_id`, `played_at`, `duration_minutes`, `source`, `notes`, `is_cooperative`, `team_won`, `scores`. Ogni score contiene `id` nullable, `player_id`, `score` nullable, `winner`, `color` nullable e `role` nullable. In creazione gli ID score sono null; in modifica un ID esistente deve appartenere a quella partita. I campi score non modificati, inclusi color e role, devono essere conservati.

Esempio di argomenti per creazione, con soli identificatori sintetici:

```json
{
  "p_request_id": "00000000-0000-0000-0000-000000000003",
  "p_payload": {
    "game_id": "00000000-0000-0000-0000-000000000001",
    "played_at": "2026-10-13",
    "duration_minutes": 45,
    "source": "offline",
    "notes": null,
    "is_cooperative": false,
    "team_won": null,
    "scores": [
      {"id": null, "player_id": "00000000-0000-0000-0000-000000000002", "score": 0, "winner": true, "color": null, "role": null}
    ]
  }
}
```

### Invarianti server delle RPC

1. Verificare JWT e ruolo admin prima di qualsiasi mutazione. Il backend corrente ammette admin globale, senza filtro owner: l'editor web applica invece alcuni filtri owner, una differenza UI da considerare quando lo si adegua. Il nuovo contratto segue RLS globale, senza ampliare privilegi sul backend.
2. Verificare gioco e giocatori esistenti, almeno un partecipante, player_id e score ID non duplicati, duration non negativa, valori numerici finiti e ID appartenenti alla partita. Eventuali limiti del gioco devono essere espliciti; i dati storici in lettura non vengono rifiutati per le nuove regole input.
3. Per una partita competitiva `team_won` è null. Se cooperativa con risultato noto, tutti i winner coincidono col risultato e le posizioni sono 1 per vittoria, 2 per sconfitta. Per gli altri casi seguire le regole web di `positions.ts`, caratterizzando con fixture anche score nulli, più vincitori e pareggi; non usare soltanto il confronto dei punteggi per determinare i winner.
4. Salvare metadati e score in un'unica transazione. Conservare user_id storico, location, bga_table_id, created_at e metadati non editabili. Correggere il trigger score incompatibile prima di usare UPDATE; mantenere color e role. Nessun passo invoca ELO.
5. Gestire concorrenza e idempotenza: lock della partita e score, revisione server calcolata sull'intero contenuto editabile e receipt privata per `(actor_id,request_id)`. Stessa richiesta e payload restituiscono l'esito precedente; stessa chiave con payload diverso è conflitto. Tutti i flussi web di scrittura devono adottare le stesse regole di lock e transazione.

La revisione è opaca e viene letta dal server; non esiste oggi una colonna su cui basare il controllo. Per delete, receipt ed eliminazione dei figli devono essere nella stessa transazione. I rating possono restare precedenti al dato modificato: la risposta non promette ELO aggiornato.

### Errori

| Caso | Comportamento client |
| --- | --- |
| Rete assente o timeout | Bloccare invio; distinguere esito sconosciuto da richiesta non inviata |
| Sessione invalida | Refresh singolo, poi login; non inviare dati anonimamente |
| `42501`, HTTP 403 | Permesso negato; aggiornare capacità della UI |
| `P0002`, risorsa assente | Risorsa non trovata; non dichiarare successo su zero righe |
| Conflitto revisione o chiave riusata, HTTP 409 | Rileggere il dettaglio e chiedere una nuova modifica esplicita |
| Payload/FK/vincolo invalido | Errore di validazione, senza modifica parziale |

SQLSTATE specifici dei nuovi conflitti e la mappatura PostgREST saranno fissati nella migrazione delle RPC. Non considerare HTTP 200 prova di aver modificato una riga; controllare il risultato restituito.
