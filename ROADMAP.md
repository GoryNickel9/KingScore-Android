# Roadmap KingScore Android

Avviare un'app Android nativa in Kotlin e Jetpack Compose, mantenendo Supabase e i dati condivisi con King-Score. La prima versione deve permettere di consultare lo storico, accedere con un account esistente, commentare e, per gli amministratori, aggiungere, modificare ed eliminare partite con gli stessi permessi del sito.

Data: 13 ottobre 2026. Scelta nativa confermata dall'utente. Questa roadmap riguarda KingScore, l'app per giochi da tavolo.

Vincolo aggiornato: lavorare soltanto in KingScore-Android. Le preparazioni backend e web delle fasi 4.1 e 4.2 sono state annullate; il codice di King-Score e il sito sono ripristinati. Le verifiche documentate restano storiche. Nessuna nuova migrazione era stata applicata a Supabase e le RPC proposte non sono disponibili.

## Vincoli confermati

1. L'app richiede una connessione Internet funzionante. In assenza di connessione mostra uno stato dedicato e blocca la consultazione e l'invio delle operazioni; non dispone di una modalità offline.
2. L'app legge rating e storico ELO prodotti dal backend. Non calcola ELO e non espone né invoca comandi o endpoint di ricalcolo, neppure dopo il salvataggio di una partita.
3. Gli utenti accedono con account esistenti. Registrazione, recupero o modifica password e modifica email restano nei flussi esterni all'app.
4. Gli amministratori possono aggiungere, modificare ed eliminare partite, rispettando i permessi server del sito.
5. Gli utenti autenticati possono scrivere e gestire i propri commenti secondo i permessi esistenti. L'app non permette di cambiare avatar.

Il login è obbligatorio per tutte le schermate funzionali: l'app non offre accesso pubblico, anche dove il sito lo consente. La prima versione sarà distribuita come APK privato da installare direttamente.

## Analisi dello stato attuale

`KingScore-Android` contiene i contratti verificati della fase 1, login e sessione cifrata della fase 2, le schermate dati della fase 3 e i commenti propri della fase 4. La build 0.4.0 comprende Home, storico con ricerca e filtri, dettagli partita/gioco/giocatore, copertine e avatar in lettura, creazione/modifica/eliminazione dei commenti propri, statistiche essenziali, classifiche e storico ELO. `../King-Score` contiene una web app React, TypeScript e Vite, con Supabase Auth, PostgreSQL, Storage e una funzione per il ricalcolo ELO notturno. L'app Android riusa il backend attraverso richieste verificate online.

Schema, grant, policy e funzioni sono stati verificati sul progetto Supabase collegato il 13 ottobre 2026, con probe SQL read-only e richieste HTTP senza dati applicativi. Il dettaglio dei risultati e dei controlli ancora necessari è in [docs/SUPABASE_AUDIT.md](docs/SUPABASE_AUDIT.md).

| Profilo | Permessi del sito rilevanti per Android |
| --- | --- |
| Visitatore | Accesso pubblico sul sito; nell'app può accedere soltanto alla schermata di login e allo stato connessione |
| Utente autenticato | Consultazione di dashboard, storico, statistiche ed ELO e gestione commenti propri; impostazioni email/password e modifica avatar escluse dall'app |
| Amministratore | Gestione partite, giochi, giocatori, immagini, import e collegamenti account |

Il ruolo proviene da `app_metadata.role`; `players.account_user_id` collega account e giocatore. Nascondere un comando nell'app deve accompagnarsi al controllo server del permesso. Il pannello admin completo e l'importazione sono estensioni dopo l'MVP.

### Dipendenze da risolvere

1. Schema e tipi web coincidono per campi Row e nullabilità delle dieci tabelle pubbliche. Resta da allineare lo storico migrazioni e alcuni servizi che usano `games.user_id`, assente anche sul database reale. Il README descrive funzioni BGA non presenti nel catalogo locale.
2. Le scritture complete partite richiedono un contratto atomico non disponibile nel backend corrente. La preparazione esterna è stata annullata; il lavoro consentito in questa repo non comprende modifiche al sito o al database.
3. Documentare quando il backend aggiorna i rating e come mostrare dati ELO non ancora aggiornati dopo una modifica. Riutilizzare i processi server esistenti, senza portare in Android il ricalcolo avviato dal browser. Un eventuale intervento sul motore ELO è un'attività backend separata.
4. Login, logout e rinnovo sono implementati nella fase 2. Verificare con account reali e aggiungere nella fase 3 la gestione dell'account non collegato a un giocatore, senza introdurre registrazione o modifica credenziali.
5. Definire il comportamento quando Internet o Supabase non sono raggiungibili: blocco delle operazioni, messaggio chiaro e ripresa dopo il ritorno della connessione. Non basta verificare che il Wi-Fi sia acceso.

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

Le stime sono ore di lavoro effettivo per una persona che conosce Kotlin e il backend. Includono implementazione, test e documentazione; escludono attese di pubblicazione e nuove funzionalità non descritte. Sono stime di pianificazione, da aggiornare dopo la prima fase.

| Ordine | Fase | Stima | Risultato verificabile |
| --- | --- | --- | --- |
| 1 | Contratti e preparazione | 12–20 ore | Schema, permessi e perimetro MVP verificati |
| 2 | Progetto nativo e login | 20–28 ore | APK con login, sessione, navigazione e controllo connessione |
| 3 | Consultazione online | 32–48 ore | Storico, dettagli, dashboard ed ELO coerenti con il web |
| 4 | Commenti e scritture admin | 28–48 ore | Commenti e gestione partite admin con salvataggi sicuri |
| 5 | Verifica e distribuzione beta | 20–32 ore | APK firmato e procedura di rilascio ripetibile |

Totale MVP: **112–176 ore**; riserva iniziale del 20% per integrazione e correzioni: **circa 135–212 ore**. La riserva non comprende l'estensione dopo l'MVP né una revisione del motore ELO backend.

### Fase 1 Contratti e preparazione

1. Verificare schema e RLS sul progetto collegato in sola lettura, senza esportare dati applicativi. Rigenerare e confrontare i tipi web e definire i corrispondenti DTO Kotlin, inclusi campi nullabili, date, identificatori e paginazione. Eseguire le future prove di scrittura in un ambiente di sviluppo isolato.
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

Implementazione completata: progetto nativo, Auth, sessione cifrata, controllo rete, navigazione, adattatori JSON e pipeline CI. APK debug installato su emulatore API 31. Le verifiche correnti restano sul PC, con account sintetici: account remoti e telefoni non sono certificati. La CI storica ha superato i controlli sul commit 2d14b4f della fase 3; oggi è sospesa. Dettagli della build iniziale: [docs/PHASE2.md](docs/PHASE2.md).
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

I commenti propri sono implementati nella 0.4.0: ownership anche per admin, conferma eliminazione, Unicode, stato invio, esiti incerti e riconciliazione completa. Le verifiche usano account e dati sintetici. Le preparazioni delle RPC partite e dei writer web sono state annullate; l'editor Android è disabilitato. I test precedenti restano registrati nei documenti storici [docs/PHASE4_ADMIN_RPC.md](docs/PHASE4_ADMIN_RPC.md) e [docs/PHASE4_WEB_WRITERS.md](docs/PHASE4_WEB_WRITERS.md). I commenti Android restano invariati: [docs/PHASE4_COMMENTS.md](docs/PHASE4_COMMENTS.md).

1. Implementare e testare RPC transazionali per creazione e modifica di partita con punteggi. Usare identificatori di richiesta per retry idempotenti e controlli server su ruolo, partecipanti e valori. Verificare il contratto di `replace_play_scores` rispetto alle nuove RPC.
2. Implementare aggiunta, modifica ed eliminazione partite per admin, selezione giochi e giocatori esistenti e creazione minima dove necessaria. Conservare i campi del modulo durante una perdita temporanea di rete finché il processo resta attivo; non accodare invii offline o inviare automaticamente dopo la riconnessione.
3. Mostrare separatamente partita salvata e rating disponibili. Dopo una scrittura rileggere i dati pubblicati senza richiedere il ricalcolo ELO; rispettare i tempi dei processi backend esistenti. Il comando di aggiornamento schermata esegue soltanto letture.
4. Aggiungere commenti con modifica/eliminazione limitate all'autore anche per gli admin, riepilogo del profilo in lettura e statistiche essenziali. Gestire account non collegati senza offrire modifica avatar o credenziali.

File backend previsti: nuove migrazioni in `King-Score/supabase/migrations/` e adattamenti dei flussi web `AddPlay` e `EditPlay` per i contratti condivisi. Non è previsto un endpoint di ricalcolo per Android. File Android: `feature/playeditor/`, `feature/comments/`, `feature/profile/`, `feature/stats/` e relativi repository.

Criterio di uscita: timeout dopo commit e doppio invio non duplicano partite; payload invalido non lascia punteggi parziali; un utente ordinario non può scrivere dati admin. Nessuna azione Android avvia il ricalcolo ELO. Il web continua a funzionare.

### Fase 5 Verifica e distribuzione beta

1. Eseguire test unitari di repository e ViewModel, test di integrazione Auth/RLS/RPC e test Compose dei percorsi principali. Confrontare statistiche e rating con le fixture del web.
2. Provare rete lenta, perdita di connessione durante lettura o salvataggio, backend irraggiungibile con Wi-Fi attivo, scadenza sessione, rotazione, terminazione del processo e cambio account. Verificare che riconnettersi non reinvii operazioni ambigue o duplicati.
3. Misurare avvio, prima pagina dello storico, fluidità di scorrimento, memoria e numero di richieste sul PC con emulatori e dati sintetici. Registrare emulatore, Android, dataset, rete, commit e build; fissare le soglie dopo la prima misurazione. Non usare Supabase remoto come ambiente di test.
4. Produrre APK firmato per beta privata, mantenendo la stessa chiave per gli aggiornamenti. Gestire la chiave di firma fuori da Git, dentro il perimetro locale autorizzato, con versioni, checksum, note di rilascio e istruzioni per gli aggiornamenti.
5. Consegnare l'APK tramite un canale privato concordato, documentare i dati usati dall'app e raccogliere feedback sui percorsi consultazione, commento e inserimento partita. La prima versione non comprende AAB o pubblicazione su Google Play.

File previsti: `app/src/test/`, `app/src/androidTest/`, `docs/BENCHMARKS.md`, `docs/RELEASE.md`, `CHANGELOG.md` e pipeline di rilascio.

Criterio di uscita: build ripetibile, controlli locali e CI superati, nessun bug noto che comprometta dati o permessi, installazione e aggiornamento verificati sugli emulatori del PC. La CI è sospesa e il suo criterio resta aperto: non eseguirla finché l'utente non la autorizza di nuovo. La verifica remota con account reali e le prove su telefoni non sono consentite dal perimetro corrente; non sostituirle con dichiarazioni non verificate. I benchmark devono distinguere dati misurati e casi non collaudati.

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

La fase 2 aggiunge build Android, test JVM per Auth/stati/JSON e test Compose/Keystore su emulatore API 31. La fase 3 estende questi controlli a letture, cursori e schermate dati. I risultati sono in [docs/TESTING.md](docs/TESTING.md). Login con account reali, telefono fisico e scritture isolate restano da verificare. Non sono stati eseguiti benchmark mobili né applicate migrazioni o modifiche dati remote.

## Lavoro residuo per la versione 1.0 stabile

Stima conservativa dopo i commenti propri, 13 ottobre 2026. Il lavoro sulle scritture admin rimane da valutare con le RPC nell'ambiente isolato; la fascia complessiva resta 58–96 ore fino a quelle prove. La 1.0 comprende il perimetro MVP concordato e distribuzione privata: consultazione, commenti propri, gestione partite admin, login obbligatorio e funzionamento solo con Internet. L'APK attuale è un'anteprima con firma debug; il superamento della CI non sostituisce le verifiche con account reali e telefoni.

| Lavoro residuo | Stima effettiva | Criterio di completamento |
| --- | --- | --- |
| CRUD partite admin e integrazione backend | 28–48 ore | RPC atomiche deployate e testate in ambiente isolato, permessi, conflitti e retry verificati |
| Verifica e release privata | 20–32 ore | Account reali, telefoni, rete e aggiornamento APK testati; chiave di firma definitiva e procedura di rilascio |
| Totale con riserva del 20% | **58–96 ore** | Circa 7–12 giornate da 8 ore; stima di lavoro, non scadenza di calendario |

La dipendenza delle scritture admin resta irrisolta: le RPC proposte e i writer web sono stati ritirati. La stima sopra precede il nuovo vincolo di lavorare soltanto nella repo Android e va riesaminata prima di promettere il CRUD completo. Wrapped, import BGA nell'app e amministrazione completa dei cataloghi restano estensioni dopo l'MVP.

## Prossima attività

Il contratto dei salvataggi e le cinque RPC candidate sono implementati soltanto sul PC: [docs/PLAY_SAVE_CONTRACT.md](docs/PLAY_SAVE_CONTRACT.md) e [local-tests/README.md](local-tests/README.md). Il collaudo supera 21 casi di accettazione, 1.592 vettori di ranking e 29 prove HTTP che caratterizzano la base precedente. Evidenze: [local-atomic-validation.json](docs/evidence/local-atomic-validation.json).

Il piano è definito in [docs/WRITER_INTEGRATION.md](docs/WRITER_INTEGRATION.md): mappa dei writer da verificare, contratti comuni, coerenza dei job su più partite, test dei consumer e ordine del rilascio. King-Score e rilasci Vercel/Supabase restano esclusi. Il test del cutover e l'adattatore server sono soltanto locali; nessun consumer reale è integrato. Le scritture partite Android restano disabilitate.

Il client RPC Android, il journal cifrato, il controller e la schermata editor con fixture sono implementati: [PHASE4_ANDROID_RPC_CLIENT.md](docs/PHASE4_ANDROID_RPC_CLIENT.md) e [PLAY_EDITOR_STATE.md](docs/PLAY_EDITOR_STATE.md). I percorsi reali restano disabilitati. La candidata firmata 0.5.0-rc2 e la pipeline locale di aggiornamento sono descritte in [PRIVATE_RELEASE.md](docs/PRIVATE_RELEASE.md). Il trattamento dei dati storici incompatibili resta conservativo: lettura completa e modifica bloccata.

L'obiettivo autonomo è ora la prima versione stabile, con commit e push nei passaggi principali. Stato e criteri di uscita: [STABILITY.md](docs/STABILITY.md). Non vengono chieste conferme per il lavoro già autorizzato nella repo; le dipendenze esterne restano esplicite.
