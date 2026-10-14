# Piano di integrazione dei writer e dell'editor Android

Definito il 14 ottobre 2026. Il piano è salvato soltanto in KingScore-Android e non autorizza modifiche ad altre repo, pubblicazioni Vercel o operazioni Supabase. I test backend restano sul PC, con account sintetici e destinazioni loopback.

## Stato verificato e fonti

La candidata locale implementa cinque RPC, transazioni, ricevute e revisioni per partita. Il checkpoint 75a6c04 conserva 21 casi di accettazione superati, 1.592 confronti di ranking e 39 controlli Node. Risultati: [local-atomic-validation.json](evidence/local-atomic-validation.json). Questi risultati non certificano consumer reali o job ELO; la candidata SQL non è una migrazione pronta da applicare in produzione. Il 14 ottobre 2026 l'utente ha riattivato la CI Android e scelto di discutere prima le modifiche necessarie a web e backend.

La candidata Android attuale è 0.5.0-rc3, versionCode 11: APK firmato, login e Internet obbligatori, consultazione e commenti propri. Adattatori RPC, editability, journal cifrato, controller e schermata editor sono preparati e verificati con fixture; mancano factory e collegamento alla navigazione/sessione. L'editor reale resta disabilitato e le RPC risultano indisponibili secondo AGENTS.md; non sono state interrogate nuovamente nel cloud. Il collaudo della consultazione e dei commenti non certifica il percorso completo dell'editor admin: [STABILITY.md](STABILITY.md), [PLAY_EDITOR_STATE.md](PLAY_EDITOR_STATE.md), [final-pc-qa-validation.json](evidence/final-pc-qa-validation.json).

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

Le route di creazione/modifica usano ProtectedRoute requireAdmin e il componente verifica app_metadata.role. EditPlay filtra inoltre user_id uguale all'account corrente, e il SQL di replace_play_scores controlla ownership. Prima del passaggio alle RPC va confermata la matrice admin/proprietario: la candidata locale consente l'admin globale, ma questo non dimostra che tutti i percorsi attuali del sito lo consentano. Nessuna modifica implicita dei permessi durante la migrazione.

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

Prima di proseguire con il backend serve la discussione prevista da [AGENTS.md](../AGENTS.md): «Se una funzionalità richiede modifiche fuori da questa repo o non può funzionare entro i vincoli, discuterne con l'utente prima dell'implementazione». L'utente ha scelto questa discussione: deve stabilire come ottenere le API reali e chi adeguerà writer web e job; non autorizza automaticamente migrazioni, deploy o test su Supabase remoto. La CI Android è invece già autorizzata. La RC3 resta una candidata e l'editor rimane disabilitato.
