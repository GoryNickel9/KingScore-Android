# Roadmap KingScore Android

Avviare un'app Android nativa in Kotlin e Jetpack Compose, mantenendo Supabase e i dati condivisi con King-Score. La prima versione deve permettere di consultare lo storico, accedere con un account esistente, commentare e, per gli amministratori, aggiungere, modificare ed eliminare partite con gli stessi permessi del sito.

Piano iniziale: 13 ottobre 2026. Stato aggiornato: 15 ottobre 2026. Scelta nativa confermata dall'utente. Questa roadmap riguarda KingScore, l'app per giochi da tavolo.

Attività successiva alla 0.5.2: [API di riepilogo e Statistiche](docs/SUMMARY_STATISTICS.md). Il SQL approvato è applicato a ScoreKing dopo preflight, confronto dello stato precedente e letture con ruolo authenticated. Home usa il riepilogo RPC; Statistiche implementa le sezioni web con dettagli e grafici paginati. La 0.6.0/code 18 è in collaudo per la consegna firmata. Nessuna modifica al codice web o Vercel.

La prima stabile privata 0.5.0/code 15 ha l'editor admin abilitato. Backend e sito sono stati attivati il 15 ottobre su autorizzazione dell'utente; le preparazioni annullate delle fasi 4.1 e 4.2 restano storiche. Build firmata, aggiornamento RC4/code 12 → 15 e percorso UI completo con MainActivity e account reali sono verificati su emulatore API 35. I dati temporanei sono rimossi e il risultato ELO originale è verificato dopo cleanup. La CI della fonte dell'APK è passata; anche le pubblicazioni temporanee sono state rimosse. Report di consegna: [docs/STABILITY.md](docs/STABILITY.md) e [docs/LIVE_ROLLOUT.md](docs/LIVE_ROLLOUT.md).

La precedente stabile privata **0.5.1/code 16** ha riallineato Home, classifica e dettaglio alle proposte web approvate; 258 test JVM, lint senza errori e aggiornamento 15 → 16 verificati. Le prove native coprono 92 casi distinti riusciti e due integrazioni condizionali escluse, dopo la correzione dei driver dell’editor. Nessuna modifica web o cloud per quell'aggiornamento. Firma, limiti e report: [STABILITY.md](docs/STABILITY.md).

## Stabile privata corrente 0.5.2/code 17

La 0.5.2 implementa le nove richieste approvate: stile web per Partite/Statistiche/Giocatore, rimozione del dialogo storico punteggio da Rating ELO, meno letture ripetute, gesto di aggiornamento dalla cima, rimozione di Classifiche ELO dalla Home, preferiti prima delle ultime partite, tre menu ridisegnati, badge commenti e tutti i partecipanti con i punti nelle card.

1. **Implementazione presente:** pannelli e selettori, conteggi commenti in batch, valori numerici esatti, refresh accessibile. Il dettaglio Giocatore conserva lo storico paginato senza presentare la prima pagina come totale.
2. **Letture ridotte:** Home e Statistiche condividono un ViewModel della sessione; la scansione completa usa campi statistici ridotti e pagine richieste da 500. ELO riusa la pubblicazione in RAM, mantenendo la verifica online dell'accesso. La prima lettura richiede ancora la rete.
3. **Qualifica completata sul PC:** 301 test JVM debug e release, lint senza errori, CI della fonte `ca4908f` passata, APK firmato e aggiornamento 16 → 17 con dati conservati. Le prove native aggregano 106 casi distinti riusciti e due integrazioni escluse, dopo ripetizione delle classi con fallimenti iniziali; font di sistema a 2× verificato. Non è un singolo run completo verde.

Login e Internet restano obbligatori. Il riuso in RAM non abilita consultazione offline; la perdita di accesso rimuove lo stato privato. Formula ELO, backend e sito non cambiano in questo aggiornamento. Gli APK continuano a essere installati manualmente. Telefoni fisici e un nuovo accesso autenticato al backend reale restano fuori dal collaudo della 0.5.2.

## Vincoli confermati

1. L'app richiede una connessione Internet funzionante. In assenza di connessione mostra uno stato dedicato e blocca la consultazione e l'invio delle operazioni; non dispone di una modalità offline.
2. L'app legge rating e storico ELO prodotti dal backend. Non calcola ELO e non espone né invoca comandi o endpoint di ricalcolo, neppure dopo il salvataggio di una partita.
3. Gli utenti accedono con account esistenti. Registrazione, recupero o modifica password e modifica email restano nei flussi esterni all'app.
4. Gli amministratori possono aggiungere, modificare ed eliminare tutte le partite, con controlli server sul ruolo.
5. Gli utenti autenticati possono scrivere e gestire i propri commenti secondo i permessi esistenti. L'app non permette di cambiare avatar.

Il login è obbligatorio per tutte le schermate funzionali: l'app non offre accesso pubblico, anche dove il sito lo consente. La prima versione sarà distribuita come APK privato da installare direttamente.

## Analisi dello stato attuale

`KingScore-Android` contiene i contratti della fase 1, login e sessione cifrata della fase 2, le schermate dati della fase 3 e le scritture della fase 4. La candidata 0.5.0 comprende Home, storico con ricerca e filtri, dettagli partita/gioco/giocatore, immagini in lettura, commenti propri, statistiche essenziali e gestione partite admin. Classifiche globali e storici leggono la stessa pubblicazione ELO; la formula è invariata. La web app React/TypeScript/Vite usa lo stesso backend Supabase e gli stessi salvataggi atomici. L'app Android verifica le richieste online e non avvia ricalcoli.

Schema, grant, policy e funzioni sono stati verificati sul progetto Supabase collegato il 13 ottobre 2026, con probe SQL read-only e richieste HTTP senza dati applicativi. Il dettaglio dei risultati e dei controlli ancora necessari è in [docs/SUPABASE_AUDIT.md](docs/SUPABASE_AUDIT.md).

| Profilo | Permessi del sito rilevanti per Android |
| --- | --- |
| Visitatore | Accesso pubblico sul sito; nell'app può accedere soltanto alla schermata di login e allo stato connessione |
| Utente autenticato | Consultazione di dashboard, storico, statistiche ed ELO e gestione commenti propri; impostazioni email/password e modifica avatar escluse dall'app |
| Amministratore | Gestione partite, giochi, giocatori, immagini, import e collegamenti account |

Il ruolo proviene da `app_metadata.role`; `players.account_user_id` collega account e giocatore. Nascondere un comando nell'app deve accompagnarsi al controllo server del permesso. Il pannello admin completo e l'importazione sono estensioni dopo l'MVP.

### Dipendenze e verifiche correnti

1. Le RPC atomiche e i writer web sono attivi. I test automatici distruttivi restano sul PC; è autorizzato un collaudo remoto limitato con account e dati temporanei.
2. La pubblicazione ELO è globale e verificata per generazione. Stati mancanti o superati richiedono nuove letture; la formula e l'ordine storico dei partecipanti sono conservati.
3. Login, logout, rinnovo, account non collegati e blocco senza Internet sono implementati. La prova UI dell'APK firmato con account reali restituisce `PASS_FULL` e comprende cambio account, commenti propri e modifica admin di una partita altrui.
4. La code 15 supera 256 test JVM e lint senza errori, con 24 avvisi preesistenti; firma e aggiornamento RC4/code 12 → 15 sono verificati. La CI della fonte `df39f5b` è passata; lo stesso workflow verifica documentazione e report di consegna.

## Decisioni di architettura

Usare una singola applicazione Gradle e organizzare il codice per funzionalità. Introdurre altri moduli soltanto quando build o riuso lo richiedono.

| Componente | Scelta proposta | Motivo |
| --- | --- | --- |
| Interfaccia | Kotlin, Jetpack Compose, Material 3 | Interfaccia Android nativa scelta dall'utente |
| Stato e navigazione | ViewModel, StateFlow, coroutines e navigazione Compose | Stato delle schermate separato dalle chiamate di rete |
| Backend | Supabase esistente attraverso `supabase-kt` e Ktor | Riutilizzo di Auth, PostgREST, RPC e Storage |
| Sessione e preferenze | Persistenza protetta della sessione; DataStore solo per preferenze necessarie | Accesso ripetuto senza memorizzare la password |
| Connessione | Monitoraggio rete e gestione degli errori delle richieste | Blocco dell'utilizzo quando Internet o il backend non sono raggiungibili |

Il flusso previsto è `Compose → ViewModel → Repository → Supabase`. Le raccomandazioni Android descrivono questa separazione fra interfaccia e dati e l'uso di coroutines e Flow. [Architettura Android](https://developer.android.com/topic/architecture/recommendations).

`supabase-kt` è una libreria mantenuta dalla community. La fase 2 ha fissato e verificato la versione 3.1.4 con Ktor 3.1.2 tramite test Auth con MockEngine e build Android; letture e RPC saranno verificate nelle rispettive fasi. URL e chiave pubblica possono essere configurati nell'app; la chiave `service_role` deve restare sul server. [Client Kotlin Supabase](https://supabase.com/docs/reference/kotlin/introduction).

Kotlin leggerà rating e storico già pubblicati dal backend, senza calcolarli o richiederne il ricalcolo. Per statistiche e posizioni, usare fixture condivise con il web oppure funzioni server: ogni eventuale porting deve avere test di equivalenza. Room, coda di sincronizzazione e WorkManager non sono previsti.

La fase 2 usa Kotlin 2.1.20, Gradle 8.13, AGP 8.13.2, min SDK 26 e compile/target SDK 36. La build è installata e testata su emulatore API 31; i telefoni destinatari restano da verificare. La distribuzione iniziale non richiede una pubblicazione su Google Play.

## Piano di implementazione

Le stime seguenti sono il piano iniziale del 13 ottobre, in ore di lavoro effettivo. Includono implementazione, test e documentazione; non misurano il lavoro residuo della candidata 0.5.0.

| Ordine | Fase | Stima | Risultato verificabile |
| --- | --- | --- | --- |
| 1 | Contratti e preparazione | 12–20 ore | Schema, permessi e perimetro MVP verificati |
| 2 | Progetto nativo e login | 20–28 ore | APK con login, sessione, navigazione e controllo connessione |
| 3 | Consultazione online | 32–48 ore | Storico, dettagli, dashboard ed ELO coerenti con il web |
| 4 | Commenti e scritture admin | 28–48 ore | Commenti e gestione partite admin con salvataggi sicuri |
| 5 | Verifica e distribuzione beta | 20–32 ore | APK firmato e procedura di rilascio ripetibile |

Totale MVP: **112–176 ore**; riserva iniziale del 20% per integrazione e correzioni: **circa 135–212 ore**. La riserva non comprende l'estensione dopo l'MVP né una revisione del motore ELO backend.

### Fase 1 Contratti e preparazione

1. Verificare schema e RLS sul progetto collegato in sola lettura, senza esportare dati applicativi. Rigenerare e confrontare i tipi web e definire i corrispondenti DTO Kotlin, inclusi campi nullabili, date, identificatori e paginazione. Eseguire le prove automatiche di scrittura su PostgreSQL/PostgREST locali con dati sintetici.
2. Documentare la matrice login, utente e admin, compresi ownership, account linking e commenti. Bloccare tutte le schermate funzionali senza sessione valida; provare anche richieste dirette che l'interfaccia non espone. Escludere dalla UI le operazioni account vietate e il ricalcolo ELO. Il login obbligatorio nell'app non richiede di rimuovere l'accesso pubblico dal sito o cambiarne le policy.
3. Specificare i contratti per creazione e modifica atomica di partita e punteggi, idempotenza e lettura dei rating pubblicati. Le migrazioni backend rimangono in `King-Score/supabase`.
4. Fissare package ID, dispositivi supportati, versioni degli strumenti, ambienti e consegna dell'APK privato. Definire copertura schermate e fixture per pareggi, cooperative, punteggi nulli e zero, account non collegati e archivi oltre 1.000 record.

File Android previsti: `README.md`, `docs/ARCHITECTURE.md`, `docs/API_CONTRACTS.md`, `docs/TESTING.md` e `docs/DECISIONS.md`.

Criterio di uscita: schema e permessi effettivi verificati sul progetto collegato, contratti di lettura compilati/testati e dipendenze delle scritture esplicite. Prima di abilitare mutazioni Android, verificarle in un ambiente isolato con account di prova. Conservare gli errori di rete distinti dalle liste realmente vuote.

### Fase 2 Progetto nativo e login

1. Creare Gradle Wrapper, catalogo versioni, modulo `app`, manifest, tema Compose, navigazione e pipeline CI per build, lint e unit test.
2. Implementare `AuthRepository`, sessione persistente protetta, login con account esistente e logout. Verificare rinnovo token dopo ritorno dal background. Non implementare registrazione, recupero password, cambio email/password o modifica avatar.
3. Implementare lo stato connessione: interrompere nuove operazioni quando la rete manca, distinguere problemi di rete da backend indisponibile e ricaricare i dati dopo la riconnessione. Ripristinare la sessione online prima di riabilitare le scritture.
4. Mostrare il login senza sessione valida e aggiungere navigazione per utente e admin, con stati caricamento, errore e assenza dati. Logout e sessione non recuperabile riportano al login. Aprire PartyScore nel browser del dispositivo.

Percorsi principali: `app/src/main/.../core/`, `data/auth/`, `feature/auth/`, `feature/navigation/` e `.github/workflows/android-ci.yml`. I nomi definitivi dipendono dal package ID.

Criterio di uscita: APK installabile su emulatore e telefono; login, logout, sessione dopo riavvio, blocco senza connessione e impossibilità di aprire schermate dati senza login verificati. Nessun token nei log o nelle fixture.

### Stato della fase 2

Implementazione completata: progetto nativo, Auth, sessione cifrata, controllo rete, navigazione, adattatori JSON e pipeline CI. APK debug iniziale installato su emulatore API 31. Il collaudo finale usa anche account remoti temporanei; i telefoni fisici non sono certificati. La CI Android è riattivata e va verificata sul commit di consegna. Dettagli storici della build iniziale: [docs/PHASE2.md](docs/PHASE2.md).
### Fase 3 Consultazione online

1. Implementare storico paginato con ricerca e filtri, dettaglio partita, punteggi, vincitori, risultato cooperativo, note e commenti in lettura. Conservare la distinzione fra punteggio zero e assente.
2. Aggiungere dashboard, dettagli gioco e giocatore secondo i permessi, classifiche ELO generali e per gioco e relativo storico. Evitare il download integrale dell'archivio per ogni schermata.
3. Aggiungere aggiornamento manuale, caricamento paginato e indicazione dell'ultimo aggiornamento. Rimuovere lo stato privato dalla memoria al logout o cambio account. Quando manca la connessione, mostrare lo stato dedicato anziché continuare la consultazione dei dati già caricati.
4. Implementare navigazione di ritorno, ripristino filtri, caricamento immagini ed errori recuperabili; verificare contrasto, TalkBack, caratteri grandi e tastiera.

Percorsi principali: `data/remote/`, `data/repository/`, `feature/dashboard/`, `feature/plays/`, `feature/games/`, `feature/players/` e `feature/ratings/`.

Criterio di uscita: stesso risultato del web sulle fixture, nessun troncamento con oltre 1.000 partite, blocco della consultazione in modalità aereo e ripresa dopo riconnessione. Rating e storico sono esclusivamente quelli restituiti dal server.

### Stato dello storico e dettaglio della fase 3

Implementati pagine da 50 con cursori, dettaglio, punteggi e commenti in lettura. La build 0.3.1 aggiunge Home, statistiche essenziali, classifiche generali/per gioco e storico ELO; la 0.3.2 aggiunge ricerca letterale in gioco o note e filtri combinabili per gioco, partecipante, modalità e date. La 0.3.3 aggiunge il dettaglio gioco; la 0.3.4 aggiunge il dettaglio giocatore con identità account e storico del partecipante paginato, conservando tutti i punteggi. La 0.3.5 aggiunge immagini e avatar con fallback e retry, loader limitato all'account connesso e senza cache persistente. Le richieste verificano l'utente online; perdita di accesso e cambio account cancellano ViewModel, filtri, cataloghi e loader immagini. Le prove di accessibilità della fase 5 restano sugli emulatori del PC. Risultati e limiti: [docs/PHASE3.md](docs/PHASE3.md), [docs/PHASE3_SEARCH.md](docs/PHASE3_SEARCH.md), [docs/PHASE3_GAME.md](docs/PHASE3_GAME.md), [docs/PHASE3_PLAYER.md](docs/PHASE3_PLAYER.md), [docs/PHASE3_IMAGES.md](docs/PHASE3_IMAGES.md).

### Fase 4 Commenti e scritture admin

I commenti propri sono implementati dalla 0.4.0: ownership anche per admin, conferma eliminazione, Unicode, stato invio, esiti incerti e riconciliazione completa. La 0.5.0 aggiunge l'editor admin abilitato, le RPC atomiche, journal cifrato, conflitti e recupero esplicito. I writer web sono integrati e distribuiti. Le precedenti preparazioni annullate restano nei documenti storici [docs/PHASE4_ADMIN_RPC.md](docs/PHASE4_ADMIN_RPC.md) e [docs/PHASE4_WEB_WRITERS.md](docs/PHASE4_WEB_WRITERS.md). Stato corrente: [docs/LIVE_ROLLOUT.md](docs/LIVE_ROLLOUT.md).

1. Implementare e testare RPC transazionali per creazione e modifica di partita con punteggi. Usare identificatori di richiesta per retry idempotenti e controlli server su ruolo, partecipanti e valori. Verificare il contratto di `replace_play_scores` rispetto alle nuove RPC.
2. Implementare aggiunta, modifica ed eliminazione partite per admin e selezione di giochi e giocatori esistenti. Conservare le bozze durante la rotazione; perdita di accesso e cambio account rimuovono la sessione. Conservare le richieste incerte nel journal cifrato, senza accodare invii offline o inviare automaticamente dopo la riconnessione.
3. Mostrare separatamente partita salvata e rating disponibili. Dopo una scrittura rileggere i dati pubblicati senza richiedere il ricalcolo ELO; rispettare i tempi dei processi backend esistenti. Il comando di aggiornamento schermata esegue soltanto letture.
4. Aggiungere commenti con modifica/eliminazione limitate all'autore anche per gli admin, riepilogo del profilo in lettura e statistiche essenziali. Gestire account non collegati senza offrire modifica avatar o credenziali.

File backend previsti: nuove migrazioni in `King-Score/supabase/migrations/` e adattamenti dei flussi web `AddPlay` e `EditPlay` per i contratti condivisi. Non è previsto un endpoint di ricalcolo per Android. File Android: `feature/playeditor/`, `feature/comments/`, `feature/profile/`, `feature/stats/` e relativi repository.

Criterio di uscita: timeout dopo commit e doppio invio non duplicano partite; payload invalido non lascia punteggi parziali; un utente ordinario non può scrivere dati admin. Nessuna azione Android avvia il ricalcolo ELO. Il web continua a funzionare.

### Fase 5 Verifica e distribuzione beta

1. Eseguire test unitari di repository e ViewModel, test di integrazione Auth/RLS/RPC e test Compose dei percorsi principali. Confrontare statistiche e rating con le fixture del web.
2. Provare rete lenta, perdita di connessione durante lettura o salvataggio, backend irraggiungibile con Wi-Fi attivo, scadenza sessione, rotazione, terminazione del processo e cambio account. Verificare che riconnettersi non reinvii operazioni ambigue o duplicati.
3. Misurare avvio, prima pagina dello storico, fluidità di scorrimento, memoria e numero di richieste sul PC con emulatori e dati sintetici. Registrare emulatore, Android, dataset, rete, commit e build. Mantenere separato il collaudo remoto limitato autorizzato il 15 ottobre e rimuoverne i dati temporanei al termine.
4. Produrre APK firmato per beta privata, mantenendo la stessa chiave per gli aggiornamenti. Gestire la chiave di firma fuori da Git, dentro il perimetro locale autorizzato, con versioni, checksum, note di rilascio e istruzioni per gli aggiornamenti.
5. Consegnare l'APK tramite un canale privato concordato, documentare i dati usati dall'app e raccogliere feedback sui percorsi consultazione, commento e inserimento partita. La prima versione non comprende AAB o pubblicazione su Google Play.

File: `app/src/test/`, `app/src/androidTest/`, `docs/BENCHMARKS.md`, `docs/PRIVATE_RELEASE.md`, `CHANGELOG.md` e pipeline di rilascio.

Criterio di uscita: build ripetibile, controlli locali e CI superati, nessun bug noto che comprometta dati o permessi, installazione e aggiornamento verificati sugli emulatori del PC. La code 15 supera la prova UI con accesso reale e il cleanup dei dati temporanei; la CI della fonte dell'APK è passata. I benchmark distinguono dati misurati e casi non collaudati; telefoni fisici e interfaccia browser del sito restano fuori dai risultati verificati.

## Dopo la prima versione

Completare queste estensioni soltanto dopo la beta, aggiornando le stime sulla base del codice effettivo.

| Priorità | Estensione | Stima aggiuntiva |
| --- | --- | --- |
| 1 | Statistiche complete, filtri per numero giocatori e Wrapped | 24–40 ore |
| 2 | Gestione admin completa di catalogo giochi, giocatori e collegamento account, entro i vincoli confermati | 16–24 ore |
| 3 | Import BGA con anteprima, disambiguazione e deduplicazione | 24–40 ore |

L'import deve partire dal flusso attuale di parsing e conferma dei dati, senza assumere che le Edge Functions BGA citate nel README esistano. Anche le estensioni richiedono Internet e non introducono ricalcolo ELO, registrazione, modifica credenziali o modifica avatar.

Push, widget e funzionalità social aggiuntive richiedono un caso d'uso specifico e una stima separata.

## Registro iniziale dei rischi

| Rischio | Conseguenza | Controllo previsto |
| --- | --- | --- |
| Schema e policy locali diversi dal backend attivo | Query incompatibili o permessi errati | Verifica e contratti nella fase 1 |
| Scritture interrotte e retry | Partite incomplete o duplicate | Transazioni e idempotenza prima dell'editor |
| Rating aggiornati dopo il salvataggio con tempi backend diversi | Dati ELO temporaneamente precedenti alla modifica | Indicazione della data disponibile e letture aggiornate; nessun ricalcolo dall'app |
| Logica diversa fra web e Kotlin | Statistiche o posizioni discordanti | Calcolo server e fixture condivise |
| Perdita di rete durante un salvataggio | Esito ambiguo e possibile doppio invio | Idempotenza, verifica dell'esito e nessun reinvio automatico |

## Validazione della roadmap

La ricognizione comprende routing, autenticazione, schema tipizzato, servizi dati, salvataggio partite, core ELO, import e migrazioni del repository web. Il codice backend è stato esaminato anche da un subagent in sola lettura. La fase 1 ha aggiunto catalogo remoto, confronto dei tipi, probe di permessi e contratti Kotlin: [docs/TESTING.md](docs/TESTING.md) contiene metodi e limiti.

La fase 2 aggiunge build Android, test JVM per Auth/stati/JSON e test Compose/Keystore su emulatore API 31. La fase 3 estende questi controlli a letture, cursori e schermate dati. I risultati storici sono in [docs/TESTING.md](docs/TESTING.md). Il 15 ottobre sono state installate le RPC reali e verificati cinque casi SDK con account temporanei. La code 15 supera 256 test JVM, lint senza errori e il percorso UI completo dell'APK firmato con accesso reale su API 35. La regressione immagini è verificata come RED sulla code 14 e GREEN sulla code 15. Firma, aggiornamento e prove finali sono in [docs/STABILITY.md](docs/STABILITY.md). Le prove su telefoni fisici non sono comprese nei risultati.

## Prima stabile privata 0.5.0

La prima stabile privata 0.5.0/code 15 comprende consultazione, commenti propri e gestione partite admin, con login e Internet obbligatori. Le prove verificano la fonte pulita `df39f5b3bc1fad0e11991784864421193c983225`, la firma privata conservata, l'aggiornamento RC4/code 12 → 15 e il percorso UI completo dell'APK con Auth e backend reali. Backend e writer web sono attivi.

| Area | Stato al 15 ottobre | Lavoro residuo |
| --- | --- | --- |
| CRUD partite admin e backend | RPC e writer reali attivi; editor Android abilitato; modifica di una partita altrui confermata dalla UI reale | Nessuna implementazione aggiuntiva nel perimetro 0.5.0 |
| Verifica e rilascio privato | Firma, checksum, aggiornamento RC4 → code 15, cifratura, UI reale e blocco offline verificati; dati temporanei rimossi ed ELO originale verificato | Quattro pubblicazioni temporanee eliminate; workflow Android attivo anche per i report |

La stima residua iniziale del 13 ottobre era 58–96 ore, prima dei collaudi e della firma privata. È conservata come dato storico e non rappresenta lavoro ancora da implementare per la 0.5.0. Wrapped, import BGA nell'app e amministrazione completa dei cataloghi restano estensioni dopo la prima stabile.

## Prossima attività

Chiudere la consegna della code 15 conservando APK, chiave privata e prove collegate alla fonte immutabile. Le pubblicazioni temporanee sono rimosse; il workflow dei report è distinto dalla CI della fonte immutabile dell'APK: [STABILITY.md](docs/STABILITY.md). Procedura e prove del rollout: [LIVE_ROLLOUT.md](docs/LIVE_ROLLOUT.md).

I 21 casi atomici locali, i vettori di ranking e le prove HTTP precedenti restano riproducibili: [local-tests/README.md](local-tests/README.md). I checkpoint del client e dell'editor sono documentati in [PHASE4_ANDROID_RPC_CLIENT.md](docs/PHASE4_ANDROID_RPC_CLIENT.md), [PLAY_EDITOR_STATE.md](docs/PLAY_EDITOR_STATE.md) e [EDITOR_RPC_PC_QA.md](docs/EDITOR_RPC_PC_QA.md). I dati storici incompatibili restano leggibili, con modifica bloccata.
