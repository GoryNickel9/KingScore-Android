# Piano di integrazione dei writer e dell'editor Android

Definito il 14 ottobre 2026. Il piano è salvato in KingScore-Android. L'utente ha ora autorizzato modifiche al codice King-Score e preparazione della migrazione, con test soltanto sul PC. Pubblicazione del sito, Vercel e Supabase restano esclusi. I test backend usano account sintetici e destinazioni loopback.

## Stato verificato e fonti

La candidata locale implementa cinque RPC, transazioni, ricevute e revisioni per partita. Il checkpoint 75a6c04 conserva 21 casi di accettazione superati, 1.592 confronti di ranking e 39 controlli Node. Risultati: [local-atomic-validation.json](evidence/local-atomic-validation.json). Questi risultati non certificano consumer reali o job ELO; la candidata SQL non è una migrazione pronta da applicare in produzione. Il 14 ottobre 2026 l'utente ha riattivato la CI Android e scelto di discutere prima le modifiche necessarie a web e backend.

La candidata Android firmata rimane 0.5.0-rc3, versionCode 11: login e Internet obbligatori, consultazione e commenti propri. Il codice del 15 ottobre prepara il factory con journal cifrato per account e collega l'editor alla navigazione/sessione. Passano 231 test JVM e 87 test nativi API 35 con fixture, incluse dieci nuove prove dell'editor; manca il collaudo del percorso completo RPC, cifratura e arresto del processo. ATOMIC_PLAY_EDITOR_ENABLED resta false e il factory reale è assente. Nessuna API è stata interrogata nel cloud. Il nuovo codice non è ancora in una candidata firmata: [STABILITY.md](STABILITY.md), [PLAY_EDITOR_STATE.md](PLAY_EDITOR_STATE.md), [editor-navigation-validation.json](evidence/editor-navigation-validation.json).

Il 14 ottobre 2026 il codice di King-Score è stato consultato in sola lettura, sul commit `a5e5dd6`, per la discussione scelta dall'utente. Nessun file del sito è stato modificato. Non sono stati interrogati Vercel o Supabase. La mappa seguente riguarda i percorsi individuati nei sorgenti; non certifica lo schema distribuito, i cron attivi o writer esterni. I documenti storici [PHASE4_WEB_WRITERS.md](PHASE4_WEB_WRITERS.md) e [PHASE4_ADMIN_RPC.md](PHASE4_ADMIN_RPC.md) restano relativi a modifiche annullate.

### Riscontri nel codice del sito

| Percorso letto | Comportamento osservato | Cambiamento da discutere |
| --- | --- | --- |
| `src/pages/AddPlay.tsx:355`, `:405` | Inserisce prima plays, poi play_scores con un'altra richiesta | Una sola create RPC per partita e risultati |
| `src/pages/EditPlay.tsx:191`, `:248` | Aggiorna plays, poi chiama replace_play_scores; la validazione degli score avviene dopo il parent update | Validazione prima dell'invio; update unico con revisione e metadati preservati |
| `src/pages/PlayDetail.tsx:117` | Delete diretta di plays, poi ricalcolo separato | Delete con snapshot/revisione e invalidazione dei risultati derivati |
| `src/services/bgaImport.ts:171`, `:196`, `:209` | Crea il parent prima degli score; un giocatore non creato viene saltato | Risolvere tutti i partecipanti prima della RPC; deduplica per actor/table |
| `src/pages/Games.tsx:220`, `:235` | Cancella partite del gioco, poi il gioco, in richieste separate | Operazione di catalogo controllata; non chiudere il DML plays lasciando questo consumer legacy |
| `src/pages/Players.tsx:191`, `src/pages/Admin.tsx:376` | Cancellano players; il SQL della repo dichiara cascade su play_scores | Verificare cascade, revisioni e invalidazione; schema reale ancora da confermare |
| `src/utils/elo/recalc.ts:146`, `:169`, `:182`, `:192`, `:228` | Cancella/reinserisce rating e storico e aggiorna rating generali in batch separati | Pubblicazione coerente su input ancora valido, comprese nuove partite/eliminazioni |
| `supabase/functions/nightly-elo-recalc/index.ts:20` | Importa la stessa performFullRecalc del frontend | Adeguare insieme i consumer browser e server; non aggiungere ricalcolo Android |

Nel percorso ELO ispezionato non è stata individuata una scrittura delle posizioni in play_scores: la candidata locale apply_positions è un adattatore sintetico, non una prova di un writer attivo del sito. L'inventario deve coprire anche la chiusura dei vecchi percorsi di cancellazione cataloghi; il contratto partita V1 da solo non definisce queste operazioni.

Le route di creazione/modifica usano ProtectedRoute requireAdmin e il componente verifica app_metadata.role. EditPlay filtra inoltre user_id uguale all'account corrente, e il SQL di replace_play_scores controlla ownership. L'utente ha confermato il nuovo comportamento: amministratori su tutte le partite, come nella candidata locale. Il nuovo writer dovrà rimuovere il filtro frontend sul solo proprietario e verificare questa matrice nel database; il proprietario storico resta preservato nell'update.

### Primo checkpoint web locale

Checkout: `.work/King-Score-atomic`, branch web `codex/atomic-play-writers`, commit locale `5eb614221bcb84f57a13aa92328d5279492aa7de`. Il checkout primario del sito e i suoi file non committati preesistenti restano invariati. Il branch non è stato pubblicato.

Il modulo `src/services/playSaveRequests.ts` prepara richieste V1 immutabili e validate, conserva UUID e metadati, mantiene i punteggi come stringhe decimali e distingue null/zero. Le prove locali superano 16 nuovi casi, 62 test della suite completa, typecheck app/script ed ESLint dei due nuovi file. Nessuna richiesta HTTP nei test. Il modulo non è ancora un writer completo: mancano trasporto, autenticazione, registro degli esiti e collegamento alle pagine; non attiva le API inesistenti.

### Compatibilità della migrazione da verificare sul PC

La revisione locale distingue la fixture dal codice da migrare. Non basta eliminare il guard di candidate.sql:

1. Lo storico SQL e il catalogo salvato divergono su `played_at` date/timestamptz, `bga_table_id`, score color/role e timestamp. Alcune migrazioni storiche aggiungono due volte position/alias. Il preflight deve dichiarare lo schema supportato e rifiutare varianti incompatibili, senza conversioni silenziose.
2. Il DROP incondizionato del trigger score rimuoverebbe anche un trigger valido nella variante con updated_at. La riparazione va condizionata alla struttura. Verificare lo scambio di partecipanti sugli ID score mantenuti: UNIQUE(play_id,player_id) immediato può far fallire gli UPDATE intermedi.
3. Il dedupe BGA non deve nascondere duplicati storici con SELECT LIMIT 1. Preflight e protezione della chiave actor/table devono rifiutare ambiguità; nessuna cancellazione automatica dei dati storici.
4. RPC additive, migrazione web e cutover sono fasi separate. La chiusura DML/replace_play_scores deve includere le cancellazioni indirette da giochi e giocatori; il solo blocco UI non protegge le cascade.
5. La pubblicazione ELO resta distinta dal salvataggio partita. apply_positions non integra performFullRecalc; rating e storico richiedono un protocollo proprio per input e pubblicazione coerenti.

La revisione non esegue SQL né certifica lo schema remoto attuale. Migrazione di compatibilità, RPC e cutover dovranno essere collaudati localmente prima di qualsiasi proposta di applicazione reale.

## Ordine delle attività

1. Preparare contratti e client RPC Android con test locali, mantenendo l'editor disabilitato.
2. Dopo autorizzazione per King-Score, inventariare tutti i writer e adeguare creazione, modifica, eliminazione e import; provarli sul PC.
3. Definire e verificare il salvataggio coerente dei risultati dei processi server, incluse aggiunte ed eliminazioni concorrenti.
4. Preparare migrazione e passaggio ai nuovi permessi; eseguirli sul backend reale soltanto dopo autorizzazione separata.
5. Collegare e abilitare l'editor Android dopo disponibilità delle API, chiusura dei vecchi accessi e verifiche concordate dei permessi. Collaudare i percorsi sul PC con emulatori e account sintetici; non usare Supabase remoto come ambiente di prova.

Le prime prove Android possono usare server HTTP simulati o il gateway PostgreSQL locale. Un editor di test non deve diventare accessibile nella build collegata al backend reale.

## Inventario dei writer e cambiamenti richiesti

| Writer | Cambiamento da implementare | Criterio di completamento |
| --- | --- | --- |
| Web: aggiunta partita | Sostituire salvataggio parent e score separati con create_play_v1; generare una richiesta immutabile con UUID | Un invio salva tutto o niente; ripetizione identica restituisce lo stesso ID |
| Web: modifica partita | Caricare get_play_editor_v1 e inviare update_play_v1 con revisione, ID score, color e role | Nessuna perdita di metadati; un conflitto conserva la bozza senza sovrascrivere il server |
| Web: eliminazione | Acquisire snapshot prima della conferma e inviare delete_play_v1 con revisione | Una modifica successiva allo snapshot provoca conflitto; delete e ricevuta sono atomici |
| Web: import BGA | Risolvere cataloghi prima del salvataggio; usare import_bga_play_v1 con location, table ID e posizioni | Nessun partecipante scartato; import ripetuto per lo stesso actor/table non crea un'altra partita |
| Web: servizi e callback condivisi | Legare richiesta e token all'account; validare risposta e distinguere errori certi/esito incerto | Nessun fallback sulle scritture dirette, redirect o retry automatico delle mutazioni |
| Server: job che aggiorna posizioni | Scrivere tramite un percorso riservato al server con revisione e lock | Non applica risultati calcolati su uno snapshot superato |
| Server: ricalcolo e pubblicazione rating/storico | Verificare tutte le dipendenze del calcolo prima di pubblicare un risultato coerente | Nessuna pubblicazione parziale o risultato obsoleto dopo modifiche, nuovi inserimenti o eliminazioni |
| Altri client, script, importatori e interventi manuali | Cercare DML su plays/play_scores, replace_play_scores, aggiornamenti rating e invalidazioni storico | Ogni percorso ha un contratto di scrittura e un test, oppure viene dismesso esplicitamente |
| Android: editor admin | Usare soltanto le quattro RPC Android, con sessione verificata e rete disponibile | CRUD, conflitti e recupero testati; nessuna scrittura anonima, offline o ELO |

L'inventario deve includere la creazione di giochi e giocatori temporanei nei flussi web. Il contratto V1 richiede cataloghi già esistenti: tali creazioni sono operazioni separate e non entrano nella transazione partita/score. Un errore della partita può quindi lasciare un nuovo elemento di catalogo; non promettere atomicità anche per il catalogo. L'MVP Android seleziona giochi e giocatori esistenti.

## Contratto comune ai consumer

Le richieste seguono [play-saves.json](../contracts/v1/play-saves.json). I punteggi viaggiano come stringhe decimali o null; il browser non deve convertirli in Number per salvarli e Android usa BigDecimal.toPlainString. NUMERIC resta esatto. Il confronto del ranking conserva il comportamento concordato, compresi i NULL.

L'editor legge detail, revision ed editability nello stesso snapshot. I dati storici incompatibili sono visibili, ma l'update è bloccato con motivazione; nessuna conversione di source manual/null o winner null, né troncamento oltre 63 partecipanti. L'eliminazione ha la propria conferma e controllo revisione.

| Esito | Azione del consumer |
| --- | --- |
| Rete assente prima dell'invio | Non inviare; bloccare l'uso dell'app fino alla verifica online |
| Successo valido | Validare ID, campi e tipi; aggiornare dettaglio/storico con nuove letture |
| 401 | Un solo rinnovo della sessione; eventuale ripetizione conserva account, richiesta e UUID; al fallimento tornare al login |
| 403 | Negare l'azione e aggiornare la disponibilità dei comandi admin |
| 400 / 404 | Mostrare validazione o target assente; non dichiarare successo |
| 409 | Conservare la bozza e proporre rilettura; nessun riutilizzo della revisione superata per una nuova modifica |
| Timeout, 5xx o successo non interpretabile | Segnare esito incerto, bloccare nuovi invii, mantenere richiesta originale per un recupero esplicito |

La stessa chiave identifica una sola richiesta completa. Dopo un esito incerto non si genera un nuovo UUID per tentare di nuovo. Una modifica alla bozza produce una nuova richiesta solo quando l'esito precedente è risolto. Un replay dopo delete può confermare una vecchia creazione senza indicare che la partita esista ancora: la UI deve rileggere lo stato corrente.

Il rinnovo dopo una risposta 401 certa è distinto dal retry dopo timeout o 5xx. Nessuna riconnessione, ripartenza del processo o attività in background invia automaticamente una mutazione.

## Coerenza dei processi server

`kingscore_atomic.apply_positions` è un adattatore sintetico per una sola partita. Non calcola ELO, non pubblica rating e non definisce il protocollo del job reale. La sua presenza non autorizza a revocare subito il DML dei job.

Un job che legge un archivio e aggiorna rating può diventare obsoleto anche se tutte le partite inizialmente lette conservano la stessa revisione: una partita nuova, assente dall'elenco iniziale, cambia l'input. Il protocollo deve rilevare anche inserimenti ed eliminazioni nell'intero ambito del calcolo, oltre a modifiche di punteggi e altre dipendenze effettivamente lette dal job.

Prima di implementarlo, l'inventario dovrà stabilire ambiti del calcolo, tabelle aggiornate, invalidazioni dopo save e ordine dei lock. Le alternative da valutare nel backend sono una versione per ambito incrementata da ogni writer pertinente, oppure una transazione/snapshot con protezioni equivalenti. La sola verifica dei singoli ID già letti è insufficiente. L'ordine dei lock deve essere unico per import, RPC e job, evitando inversioni tra ambiti, partite e score.

Il job calcola a partire da un input identificato. Al momento della pubblicazione verifica che quell'input sia ancora corrente e pubblica insieme i risultati che devono essere coerenti. Se la verifica fallisce, scarta il risultato e riparte secondo la politica del job. Le funzioni server espongono soltanto le operazioni necessarie e restano inaccessibili ai client ordinari e admin dell'app.

Le RPC partite non avviano ELO. Android rilegge i rating pubblicati e non promette che siano già aggiornati. I comportamenti ELO esistenti del sito, incluse invalidazioni e trattamento dell'owner storico, dovranno essere inventariati: mantenerli non significa copiarli nelle RPC Android. L'algoritmo di calcolo non viene modificato da questo piano.

## File Android previsti

| File o area | Lavoro |
| --- | --- |
| contracts/v1/Commands.kt | Aggiungere editability; allineare validazioni di revisione, testo e dimensione al contratto; conservare i DTO delle letture storiche |
| data/remote/PlaySaveJson.kt, nuovi | Encoder delle richieste RPC e decoder editor/risultati con punteggi testuali esatti |
| data/repository/PlayWrites.kt, nuovo | Interfaccia tipizzata per getter e CRUD; errori separati per conflitto, permesso, validazione e incertezza |
| data/remote/SupabasePlayWrites.kt, nuovo | Sessione e account verificati, trasporto senza retry automatici, controllo delle risposte; nessun writer REST generico |
| feature/playeditor/, nuova | Form Compose e ViewModel per creazione/modifica, conferma delete, stato invio, conflitto e recupero |
| feature/navigation/ e feature/plays/ | Destinazioni editor e comandi admin; refresh delle letture dopo salvataggio |
| app/src/test/ e app/src/androidTest/ | Test di trasporto, dominio, lifecycle e percorsi Compose, con fixture sintetiche |

Riusare `withVerifiedSession` e il comportamento del trasporto commenti senza riscrivere il writer commenti. Il token viene acquisito dopo la verifica online e rimane associato alla richiesta. `isAdmin` regola la UI; il server resta responsabile dell'autorizzazione. Logout e cambio account impediscono alle risposte tardive di modificare lo stato di un altro account.

Prima della UI va deciso il recupero dopo morte del processo. Le possibilità sono conservare in modo protetto la richiesta incerta, per una verifica/ripetizione manuale soltanto online, oppure dichiarare il limite del recupero in memoria e prevedere una verifica esplicita dello storico. Nessuna opzione costituisce una coda di invio offline. Il primo incremento di trasporto non introduce persistenza o una promessa di recupero dopo riavvio.

## Test locali da aggiungere

I casi sotto sono pianificati, non eseguiti. Si aggiungono ai 21 test della candidata; non sostituiscono i test dei consumer reali. HTTP e PostgreSQL restano loopback. JWT, persone, partite e rating sono sintetici.

| Gruppo | Prova | Risultato atteso |
| --- | --- | --- |
| Contratti Android | Serializzare getter e tre mutazioni, null/zero/negativi/decimali oltre 2^53; leggere editability | Argomenti esatti, nessuna perdita di precisione o conversione storica |
| Trasporto Android | 400, 401, 403, 404, 409, 5xx, redirect e corpo 2xx invalido | Errori distinti; nessun fallback; rinnovo controllato solo dopo 401 |
| Trasporto Android | TCP chiuso dopo commit e 503 Retry-After | Un solo invio automatico; replay manuale usa richiesta e UUID identici |
| Lifecycle Android | Rete persa, Checking, rotazione, uscita dall'editor, logout e cambio account durante invio | Nessuna risposta tardiva nel nuovo contesto; incertezza non cancellata per errore |
| UI Android | Account ordinario/admin, selezione cataloghi, create/edit/delete, storico incompatibile | Comandi coerenti col ruolo; conferma delete; update legacy bloccato e motivato |
| UI Android | Conflitto fra due editor e successo/replay di una partita poi eliminata | Bozza conservata; rilettura esplicita; stato attuale non dedotto dalla sola ricevuta |
| Web create/edit/delete | Test del servizio e dei callback, più percorso browser locale completo | Un'unica RPC per mutazione; ID/metadati preservati; form non perde la bozza su conflitto |
| Web cataloghi | Errore nel risolvere un partecipante o creare un catalogo | Nessun salvataggio incompleto o partecipante omesso; cataloghi separati documentati |
| BGA | Doppio import, account diversi, errore a metà batch e timeout dopo commit | Deduplica actor/table; posizioni esterne conservate; riepilogo parziale corretto e stop sull'incertezza |
| Job: stessa partita | Scrittura admin durante calcolo delle posizioni | Risultato vecchio rifiutato; nessuna modifica dei dati nuovi |
| Job: archivio | Create, edit e delete durante un calcolo rating che usa più partite | Tutte le modifiche invalidano l'input; nessuna pubblicazione obsoleta |
| Job: pubblicazione | Errore dopo la prima scrittura derivata e due job concorrenti | Nessun risultato parziale; esito concorrente conforme al protocollo definito |
| Writer misti | Web, Android, import e job sullo stesso ambito, con ordine dei lock e timeout | Conflitti gestiti, nessuna perdita di aggiornamenti o attesa senza limite |
| Chiusura legacy | Vecchia scheda browser, DML diretto, replace_play_scores e service_role dopo revoca | Vecchi accessi rifiutati; messaggio di aggiornamento; writer nuovi e job autorizzati funzionano |
| Regressioni | Letture, commenti propri, metadati importati, owner storico e cascade | Funzioni esistenti conservate; nessun nuovo comando ELO Android |

Il workflow CI esegue controlli Node, JVM, lint e APK ed è ora autorizzato. Le prove native PostgreSQL/PostgREST e i percorsi con emulatore/browser si eseguono sul PC. Il rilascio reale richiederà una procedura concordata per verificare disponibilità delle API e permessi; questo piano non propone Supabase come ambiente di prova.

## Preparazione del rilascio, senza eseguirlo

La migrazione futura va ricostruita rispetto allo schema e alla storia effettivi del backend, quando la loro verifica sarà autorizzata. Non copiare la fixture, i ruoli Auth sintetici, i JWT locali o rimuovere semplicemente la protezione della candidata per applicarla in produzione. Non eseguire indistintamente le vecchie migrazioni del sito.

| Passaggio futuro | Condizione prima di procedere |
| --- | --- |
| Installare le API aggiuntive | Migrazione revisionata, compatibilità e procedura di recupero definite; autorizzazione backend |
| Pubblicare writer web e adeguare job | Test locali completi, inventario chiuso; autorizzazione repo web e pubblicazione |
| Chiudere i percorsi legacy | Nessun writer noto dipende dal DML revocato; gestione delle vecchie schede browser pronta |
| Verificare il backend reale | Disponibilità API e permessi effettivi confermati con controlli concordati |
| Abilitare Android | Tutti i passaggi precedenti completati e client/UI collaudati; login e Internet obbligatori |

L'intervallo in cui vecchi e nuovi writer convivono non offre ancora la garanzia globale di concorrenza. Android resta disabilitato durante quella transizione. Dopo la revoca, un rollback della sola web app alla versione legacy non ripristina il servizio: anche i permessi e i job devono avere una procedura compatibile. Le ricevute V1 non hanno scadenza automatica; introdurre retention richiede una scelta esplicita sul periodo garantito di idempotenza.

## Prossimo incremento entro l'ambito attuale

Encoder/decoder, trasporto RPC, recupero cifrato, ViewModel e UI editor sono preparati. Il collegamento ad Application, navigazione e sessione rimane da implementare e collaudare: non è completato dalla ricreazione delle schermate di consultazione. L'inventario attuale non basta per una stima affidabile dei job server o del rollout.

La discussione richiesta da [AGENTS.md](../AGENTS.md) è stata svolta: l'utente ha autorizzato codice web, preparazione della migrazione e test locali, confermando gli admin globali. Queste decisioni non autorizzano pubblicazione, applicazione SQL o test su Supabase remoto. La CI Android è autorizzata e passa anche sul checkpoint e65e158. La RC3 resta una candidata e l'editor rimane disabilitato fino alle API reali e al collaudo dell'integrazione.

## Checkpoint del client web locale

Il branch locale web `codex/atomic-play-writers`, commit `2c58f49` (trasporto iniziale `f2a3d1c`), aggiunge richieste immutabili, trasporto delle quattro mutazioni, journal browser e verifica dell'identità tramite Auth. Prima del POST conserva corpo e UUID; dopo un esito incerto consente solo recupero esplicito della stessa richiesta. Rifiuta redirect, limita la ricevuta a 32 KiB, applica timeout di 30 secondi alla verifica account e all'HTTP, e usa lock per account condivisi fra schede. Un cambio account impedisce di presentare il risultato o cancellare il journal precedente.

Il registro conserva anche la risposta confermata prima di restituirla alla UI: al riavvio la recupera senza un altro POST. Un nuovo salvataggio esplicito con un nuovo UUID sostituisce la conferma; lo stesso UUID con contenuto diverso resta bloccato.

La suite web passa con 97 test, inclusi 16 del preparatore e 35 nuovi del writer/identità. Typecheck app/script ed ESLint dei nuovi file passano; build Vite superata sul checkpoint f2a3d1c, prima dell'aggiunta della conferma persistente. La prova di redirect usa HTTP reale su loopback; Auth e storage sono simulati. Non è una verifica di Supabase remoto o del comportamento di localStorage/Web Locks in un browser reale.

Il servizio non è collegato alle pagine. Restano da completare lettura dell'editor, UI di conflitto/recupero e consumer create/edit/delete/import, oltre alla compatibilità SQL e ai test integrati PostgreSQL/PostgREST. I 409 e i rifiuti durante un recupero conservano la richiesta: la risoluzione esplicita resta da implementare nella UI. Le modifiche web sono committate solo sul PC e non pubblicate. Il checkout primario mantiene i suoi file non committati preesistenti.

Il checkpoint web `ddd9968` aggiunge un preflight SQL diagnostico, distinto dalla migrazione. Su PostgreSQL 17.4 passa 19 casi sintetici: tipi e colonne, trigger orfano/valido, UNIQUE partecipanti, duplicati BGA per owner, cascade, oggetti foreign/ereditati e letture limitate da RLS. Il report è `blocked` o `conditional` quando servono interventi o decisioni; non converte date, modifica vincoli o ripara dati. Le scansioni escludono ereditarietà e RLS attivo per evitare accessi esterni o falsi negativi. Ogni caso verifica la transazione read-only e fingerprint invariati di schema/dati. Il server locale proprio è stato arrestato. Prove registrate in [web-atomic-preflight-validation.json](evidence/web-atomic-preflight-validation.json).

Il preflight non verifica tutte le FK/default, Auth, grant o correttezza semantica dei trigger; non installa le RPC e non rende replayabile lo storico SQL. Applicazione cloud e pubblicazione web richiedono autorizzazione separata.

## Migrazione predisposta e collaudata sul PC

Il checkpoint web locale `f697efc` aggiunge `scripts/atomic-play-migrations/01-install-rpcs.sql`, fuori dallo storico di deploy Supabase. Installa le cinque RPC V1 soltanto su uno schema compatibile, senza creare Auth o ruoli, convertire date o correggere dati storici. Aggiunge le quattro colonne opzionali mancanti come text NULL e ripara soltanto il trigger score orfano verificato. Conserva trigger validi, owner, score ID e metadati; i profili non supportati fanno fallire l'intera transazione.

La UNIQUE ordinaria esatta sui partecipanti viene resa DEFERRABLE INITIALLY IMMEDIATE per consentire lo scambio con UPDATE bulk, conservando nome, ordine e commenti. La conversione ricostruisce l'indice e ne cambia l'uso come arbiter ON CONFLICT: il test verifica questo effetto. Vincoli o indici con proprietà non preservabili e FK dipendenti sono rifiutati. Non è una conversione applicata al backend reale.

La fonte congelata passa 16 profili di compatibilità e i 21 casi di accettazione su PostgreSQL 17.4/PostgREST 14.1 loopback: 1.592 confronti del ranking e 170 richieste HTTP. Lo scambio passa con entrambi gli ordini del vincolo preservando anche created_at e un campo storico aggiuntivo; duplicati BGA introducibili dai writer legacy vengono rifiutati con PT409 senza nuove ricevute. Il comando completo passa e arresta gateway/server propri. Prove: [run](evidence/prepared-web-migration-run.json), [profili](evidence/prepared-web-migration-profiles.json), [accettazione](evidence/prepared-web-migration-acceptance.json).

L'adattatore server per le posizioni e il cutover dei 21 casi rimangono fixture locali distinte. Il nuovo SQL installa soltanto le API: non chiude DML legacy, cascade cataloghi o job, non integra le pagine e non abilita Android. Non è stato collaudato Auth/GoTrue reale o un browser collegato alle RPC. La deadline HTTP del client non certifica un timeout statement server: la configurazione esterna richiede una verifica distinta prima del rollout.

L'inventario ELO include anche `src/utils/eloCalculator.ts`: getELOHistoryForGame e getGeneralELOHistory chiamano saveELOHistoryEntries in caso di cache miss (righe 247/359), con upsert su elo_history (riga 145). Sono usati da PlayDetail e Stats; quindi anche letture delle pagine possono pubblicare cache derivate. Questa scrittura deve essere considerata nel futuro protocollo dei dati derivati. La ricerca del sorgente non trova upsert sulla coppia UNIQUE di play_scores, ma non verifica consumer esterni al checkout.
