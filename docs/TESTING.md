# Verifica KingScore Android

## Risultati della fase 1

| Controllo eseguito | Esito | Limite |
| --- | --- | --- |
| Catalogo remoto in READ ONLY | Dieci tabelle e RLS catturate | Nessuna mutazione o replay migrazioni |
| Confronto Row web e remoto | Dieci tabelle, zero differenze di campi/nullabilità TS | I tipi TS non distinguono date da timestamp o numeric da integer |
| Probe SQL | Quattro piani EXPLAIN e due dinieghi 42501 | Identità SQL sintetiche, nessun login Auth reale e nessuna DML eseguita |
| Probe HTTP | Otto letture con limit=0 e un diniego Auth 401 | Nessun JWT di un utente ordinario/admin |
| Contratti Kotlin | Dieci gruppi di controlli superati, modelli compilati | Nessun serializer JSON, client Android o test UI |

La suite Node comprende dieci test, tutti superati. Sono state verificate anche la sintassi dei moduli Node, quella dei quattro script PowerShell e l'assenza di credenziali nelle evidenze esportate.

Le verifiche Node aggiuntive controllano consistenza delle evidenze, tipi, policy, trigger autore, vincoli del perimetro e limiti delle RPC. I risultati dettagliati vengono prodotti dai comandi seguenti.

## Comandi locali ripetibili

```powershell
node --test scripts/check-evidence.test.mjs
./scripts/check-contracts.ps1
node scripts/compare-schema.mjs
```

`check-contracts.ps1` accetta `-KotlinCompiler` e `-JavaExecutable` se Kotlin/Java non sono nei percorsi rilevati. Richiede Kotlin JVM e Java 17 o successivo; il JAR temporaneo resta in `.work`, esclusa da Git. Non richiede un dispositivo, rete o credenziali.

Per rigenerare i contratti dopo aver acquisito una nuova evidenza: `node scripts/generate-contracts.mjs`. Esaminare le differenze prima di accettarle: la generazione non certifica un nuovo schema come compatibile.

## Verifiche remote ripetibili

Questi comandi usano le credenziali già conservate dalla CLI e la configurazione pubblica web senza esportarle. Richiedono accesso al progetto corretto.

```powershell
./scripts/audit-supabase.ps1
./scripts/probe-permissions.ps1
./scripts/probe-http.ps1
```

L'audit e le probe SQL usano READ ONLY. EXPLAIN non usa ANALYZE. Le sole chiamate alla RPC score sono eseguite con anon/non-admin e devono fallire prima della DML. Le probe HTTP richiedono zero righe applicative. Il progetto collegato deve rimanere quello indicato; lo script HTTP rifiuta host inattesi.

Per aggiornare i tipi remoti, catturare lo stdout della CLI in memoria, controllare l'exit code e scrivere `docs/evidence/schema.remote.ts` soltanto in caso di successo. Non sovrascrivere i tipi web senza un confronto semantico.

## Risultati della fase 2

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Build debug e APK test | Superati | Gradle 8.13, AGP 8.13.2, JVM target 17, SDK 36 |
| Test JVM | 23 superati, zero errori | 8 Auth SDK con MockEngine, 10 ViewModel, 5 decodifica JSON |
| Lint debug | Zero errori, 22 warning | Warning di aggiornamento strumenti/librerie e suggerimenti KTX; versioni fissate |
| Test Android API 31 | 5 superati | 3 Compose per login/blocco/ruoli; 2 Keystore per cifratura, ripristino e corruzione |
| Test evidenze Node | 10 superati | Contratti e audit remoto conservati dalla fase 1 |
| Perdita rete reale su AVD | Blocco e ripresa verificati | Wi-Fi/dati disattivati e modalità aereo; al ritorno della rete ricompare il login |

Auth è verificato con richieste simulate, inclusi refresh, revoca, backend indisponibile, logout locale prima della revoca remota e cancellazione durante il cleanup. Il ViewModel copre connessione persa, invalidazione SDK e mancata rimozione locale. Due regressioni verificano che rientro e timer preservino il login già aperto e ricontrollino una sessione attiva.

I decoder verificano gli otto DTO, join nullable, data senza fuso, timestamp con offset, UUID, score zero/null/negativo e numeri oltre la precisione Double. Campi mancanti e valori non finiti devono produrre un errore.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

In questa sessione i cinque test Android sono stati eseguiti con il runner direttamente via ADB, su un AVD dedicato API 31. I report JVM/lint si trovano in `app/build/reports`; risultati, checksum APK e limiti sono salvati in [evidence/phase2-validation.json](evidence/phase2-validation.json). La build locale ha richiesto il workaround JVM descritto in [PHASE2.md](PHASE2.md).

## Risultati di storico e dettaglio, fase 3

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Test JVM completi | 48 superati | Include i 23 casi iniziali e 25 verifiche aggiuntive per accesso, cursori e stato |
| Paginazione sintetica | 1.051 righe in 22 richieste | Pagine da 50, data uguale e UUID oltre il confine signed/unsigned, nessuna perdita o duplicato |
| Test Android API 31 | 10 superati | Include storico/dettaglio/ritorno, cooperative, zero/null, logout/cambio account e riconnessione con dati rimossi |
| Layout con testo al 130% | Percorso storico/dettaglio/ritorno verificato | Finestra di circa 376 dp in verticale e finestra catturata 2160×1080 pixel con densità 460 in orizzontale |
| Probe Supabase limit=0 | Due HTTP 200, zero righe | Sintassi join e cursori; chiave pubblica, fuori dal client Android, nessun login reale |
| Lint debug | Zero errori, 22 warning | Versioni fissate e suggerimenti KTX |

Il test di pagina successiva fallita verifica che il cursore resti quello precedente; le risposte tardive vengono scartate dopo cambio filtro o rimozione dello stato. Il dettaglio distingue partita assente, permesso negato e risposta invalida. Commenti e dettaglio conservano stati di errore separati.

I test Ktor verificano GET utente prima di ogni GET dati, Bearer utente, nessuna richiesta anonima o offline, account differente rifiutato, un solo refresh 401 e 403 senza logout. I risultati della suite e il checksum dell'APK 0.3.0 sono in [evidence/phase3-validation.json](evidence/phase3-validation.json). Le immagini salvate mostrano esclusivamente fixture sintetiche.

## Risultati Home, statistiche ed ELO, build 0.3.1

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Test JVM completi | 75 superati, zero errori | Repository, cursori, metriche, scope proprietario e cancellazione delle risposte obsolete |
| Archivi sintetici oltre 1.000 righe | 1.051 righe attraversate | Catalogo, classifiche e aggregati non troncati alla prima pagina |
| Test Android API 31 | 13 superati | Navigazione, fixture pubblicate, profilo non collegato, rating null e blocco senza rete, oltre ai percorsi precedenti |
| Test evidenze Node | 10 superati | Contratti e limiti del perimetro |
| Probe Supabase limit=0 | Cinque HTTP 200, zero righe | Sintassi delle nuove letture; nessun account reale o mutazione |
| Lint debug | Zero errori, 22 warning | Versioni fissate e suggerimenti KTX |

Checksum APK, suite, runner Android e screenshot sono in [evidence/phase31-validation.json](evidence/phase31-validation.json). Le catture usano la radice Compose per mostrare l'app, senza dipendere dalla cattura delle barre di sistema. I dati dei test sono sintetici. Home carica soltanto la prima pagina; Statistiche pubblica i totali dopo una scansione completa. Le classifiche leggono valori backend, senza ricalcolo ELO.

La CI esegue i controlli Node, build, test JVM e lint. Non esegue i test Android su emulatore. I commit [2d14b4f](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38414918651) e [ab9c534](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38422829676) hanno superato la pipeline.

## Risultati ricerca e filtri, build 0.3.2

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Test JVM completi | 87 superati, zero errori | Include combinazioni di filtri, 14 casi di escaping, date inclusive, cataloghi oltre 1.000 righe e risposte obsolete |
| Test Android API 31 | 18 superati | Ricerca, filtri, cataloghi, paginazione, ritorno dal dettaglio, reset, date errate, cambio account e ripristino dopo ricreazione del ViewModel |
| Layout con testo al 130% | Percorso completo superato in verticale e orizzontale | Larghezza verticale circa 376 dp, cataloghi scorrevoli, tastiera e dialogo date; animazioni disattivate |
| Test evidenze Node | 10 superati | Contratti e limiti del perimetro |
| Probe Supabase limit=0 | Tre HTTP 200, zero righe | Ricerca OR gioco/note, AND con cursore/filtri e punteggiatura letterale |
| Lint debug | Zero errori, 22 warning preesistenti | Nessun warning aggiunto dal nuovo layout |

Il test di ripristino svuota lo store dei ViewModel e verifica una nuova lettura con query, modalità e data applicate. Il blocco di accesso elimina anche lo stato UI salvato dell'account: dialoghi e bozze non riappaiono alla riconnessione. Il test di annullamento aspetta il caricamento iniziale prima di verificare l'assenza di nuove richieste.

Risultati del runner, checksum APK e screenshot sono in [evidence/phase32-validation.json](evidence/phase32-validation.json). Sono fixture sintetiche; le probe remote certificano la sintassi, senza un JWT utente reale. La CI del nuovo commit viene creata al push: il suo esito è disponibile nella [pagina Actions](https://github.com/GoryNickel9/KingScore-Android/actions).

## Risultati dettaglio gioco, build 0.3.3

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Test JVM completi | 95 superati, zero errori | Otto nuovi test per query gioco, nullabilità, errori, refresh identico e risposte obsolete |
| Test Android API 31 | 23 superati | Cinque nuove prove per gioco/pagina/partita/ritorno, refresh, retry, dati mancanti, perdita rete e logout |
| Layout con testo al 130% | Percorso gioco/pagina/partita/ritorno/refresh superato in verticale e orizzontale | Finestra verticale circa 376 dp, animazioni disattivate; stesso APK |
| Test evidenze Node | 10 superati | Contratti e limiti del perimetro |
| Lint debug | Zero errori, 22 warning preesistenti | Nessun nuovo warning |

La prima build locale è stata interrotta da un comando di stop ricevuto dal daemon Gradle condiviso. La verifica finale usa un registro daemon isolato in `.work`, riutilizzando le cache esistenti, insieme al workaround JVM già documentato. Entrambi restano fuori da APK e CI. La revisione statica del subagent non ha trovato regressioni P1/P2; non sostituisce i test eseguiti.

Risultati, runner, checksum e immagini sono in [evidence/phase33-validation.json](evidence/phase33-validation.json). Sono fixture sintetiche; non sono state eseguite mutazioni o nuove probe remote. La lettura usa i campi games già verificati e il filtro game_id dello storico esistente.

Nella cattura verticale al 130%, l'etichetta Statistiche della barra inferiore esistente risulta troncata. Il percorso resta utilizzabile; correggere e verificare le etichette della navigazione con caratteri grandi prima della release stabile. Il corpo del dettaglio gioco è scorrevole anche in orizzontale.

## Risultati dettaglio giocatore, build 0.3.4

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Test JVM completi | 104 superati, zero errori | Nove nuovi test per profilo, query, pagina partecipante, refresh e risposte/errori obsoleti |
| Storico partecipante sintetico | 1.051 partite in 22 richieste | Filtro conservato in ogni pagina e tutti i punteggi restituiti |
| Test Android API 31 | 31 superati | Otto nuove prove per percorso completo, errori, alias/identità account, rete, logout e cambio account |
| Layout con testo al 130% | Percorso giocatore/pagina/partita/ritorno/refresh superato in verticale e orizzontale | Circa 376 dp in verticale e viewport corta orizzontale, animazioni disattivate |
| Test evidenze Node | 10 superati | Contratti e limiti del perimetro |
| Lint debug | Zero errori, 22 warning preesistenti | Nessun nuovo warning |

Gioco e giocatore riusano `DetailHistory` per caricamento, errori, conteggio e cursore. La suite completa include i percorsi del gioco per verificare l'estrazione. Il test del giocatore apre anche la partita e controlla l'altro partecipante e il punteggio zero, prima di riaprire il profilo con le pagine conservate. Il flag storico is_me non viene usato per identificare l'account corrente.

La prima prova orizzontale falliva dopo il tap sul partecipante. Il test scorreva fino all'item, ma il pulsante in fondo a una riga più alta della viewport poteva restare fuori dall'area visibile. La correzione scorre fino al pulsante e ne verifica la visibilità prima del tap; il percorso orizzontale è poi passato con la stessa build dell'app. La suite Android completa è rieseguita con questo helper aggiornato.

Risultati, runner, checksum e immagini sono in [evidence/phase34-validation.json](evidence/phase34-validation.json). Fixture sintetiche; nessuna nuova probe o mutazione Supabase. La build locale usa gli stessi workaround ignorati della 0.3.3. La CI verifica Node, test JVM, lint e APK; i test Android sono eseguiti localmente sull'AVD dedicato.

## Risultati immagini e avatar in lettura, build 0.3.5

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Build debug e APK test | Superati in 10 minuti e 34 secondi | Stessa toolchain; Coil 3.1.0 fissata |
| Test JVM completi | 114 superati, zero errori | Dieci nuovi test URL, Storage, Unicode, rete e isolamento account |
| Test Android API 31 | 37 superati in 139,738 secondi | Sei nuove prove con decoder PNG reale e HTTP simulato, oltre ai percorsi precedenti |
| Test evidenze Node | 10 superati | Contratti e limiti del perimetro |
| Layout con testo al 130% | Due percorsi superati in verticale e orizzontale | Giocatore/paginazione/partita/ritorno/refresh e decodifica immagini; circa 376 dp in verticale, animazioni disattivate |
| Lint debug | Zero errori, 24 warning | 22 preesistenti e due avvisi di nuove versioni Coil |

Le prove immagini verificano pixel decodificati, URL del bucket player-images, assenza di Authorization/apikey/Cookie e cache memoria/disco disabilitate. Il retry dopo 404 non apre la scheda contenitore. Revoca, perdita rete, logout e cambio account rimuovono le immagini; la risposta tardiva dell'account precedente non sostituisce quella nuova. Immagini corrotte o troppo grandi restano errori facoltativi quando l'accesso è valido.

La cattura verticale conferma che Statistiche compare su due righe senza perdere caratteri; in orizzontale compare su una riga. La divisione della parola in verticale può essere rifinita nel collaudo finale sui telefoni. I risultati non certificano TalkBack o altri dispositivi.

Risultati, runner, checksum APK e cinque catture sono in [evidence/phase35-validation.json](evidence/phase35-validation.json). Le immagini rosa sono PNG sintetici usati per verificare la decodifica, senza dati personali. Nessuna richiesta a CDN reali, nuova probe o mutazione Supabase. La revisione statica del subagent non ha rilevato problemi P1/P2; non sostituisce i controlli runtime. La CI esegue Node, test JVM, lint e APK; le prove Android sono locali.

## Risultati commenti propri, build 0.4.0

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Build debug e APK test | Superati in 6 minuti e 26 secondi | Nessuna nuova dipendenza; firma debug |
| Test JVM completi | 138 superati, zero errori | 24 nuovi casi per payload, bearer, ownership, Unicode, esiti incerti e lifecycle |
| Test Android API 31 | 45 superati in 200,071 secondi | Otto nuovi percorsi commenti, oltre alle funzionalità precedenti |
| Contratti Kotlin e test Node | Dieci gruppi Kotlin e dieci test Node superati | Include rifiuto NUL e surrogate isolate |
| Layout con testo al 130% | Due percorsi superati in verticale e orizzontale | Modulo con tastiera, CRUD e riconciliazione; circa 376 dp in verticale, animazioni disattivate |
| Lint debug | Zero errori, 24 warning preesistenti | Nessun nuovo warning |

I test del writer verificano rappresentazione di una riga, autore/partita/contenuto attesi, zero righe, 403 senza logout, un solo refresh 401, timeout e 5xx senza replay. Una fixture TCP locale risponde 503 Retry-After: 0: il trasporto OkHttp effettua una sola richiesta. L'esito incerto blocca nuovi invii; la rilettura attraversa tutte le pagine, anche dopo un errore del dettaglio o ricreazione del ViewModel.

La prima compilazione dei test non trovava com.sun HttpServer nel classpath Android; la fixture usa ora ServerSocket. La prima prova Checking cercava la Home al rientro, mentre la navigazione ripristina il dettaglio. Il test corretto verifica quella destinazione, nuove letture su tutte le pagine e un solo invio. La suite completa e i layout sono rieseguiti sull'APK finale.

La revisione statica ha rilevato e fatto correggere la perdita di UNCERTAIN nei percorsi refresh/retry e durante Checking. Il registro per account conserva solo UUID di partite, non testo o command. Logout e cambio account cancellano questo stato; le risposte tardive non ripristinano editor o righe. L'ultima revisione non ha rilevato problemi P1/P2 residui.

Risultati, runner, checksum e nove catture sono in [evidence/phase40-validation.json](evidence/phase40-validation.json). Le immagini mostrano fixture sintetiche. Il backend non è stato modificato e nessuna mutazione con JWT reale è stata eseguita. Prima della stabile servono test isolati con account ordinario/admin e collaudo su telefoni. Il backend commenti non offre receipt o idempotenza: nessuna garanzia exactly-once, e l'indicatore in memoria non sopravvive alla morte del processo. Dettagli: [PHASE4_COMMENTS.md](PHASE4_COMMENTS.md).

## Controlli ancora aperti

La build 0.4.0 implementa i commenti propri. Le prove simulate non sostituiscono le mutazioni con JWT reali in un database isolato; il server non è stato modificato durante questo incremento.

1. Login con account reale ordinario/admin, revoca remota effettiva e riavvio del processo con sessione reale. Nessuna credenziale utente è stata usata nei test.
2. Installazione e aggiornamento su telefono fisico, API minima 26, TalkBack e caratteri grandi. I risultati dell'emulatore API 31 non coprono questi dispositivi.
3. Nella fase 4: RPC e scritture partite admin isolate secondo i controlli sotto. Nel collaudo finale: mutazioni commenti con JWT reali, CDN e rifiniture accessibilità.

Non sono stati eseguiti benchmark mobili. Home, Statistiche ed ELO sono implementate nell'incremento 0.3.1; la verifica con dati reali rimane aperta.

## Controlli prima delle scritture partite

Le preparazioni backend delle fasi 4.1 e 4.2 sono state annullate il 13 ottobre 2026. I risultati sotto sono storici: non certificano funzioni disponibili sul backend attuale. Le scritture partite Android restano disabilitate; sono escluse ulteriori modifiche alle altre repo, Vercel e Supabase.

La preparazione locale è completata: migrazione delle quattro RPC applicata a un cluster PostgreSQL 17.4 separato, suite SQL superata, tre prove concorrenti superate e 1.616 casi di ranking uguali alla funzione web effettiva con Node 24.19.0 / V8 13.6.233.17-node.51. Anche gli 11 controlli Kotlin dei contratti e i 10 controlli Node dell'audit passano. Il server locale è stato arrestato dal runner. Metodi, limiti e gate di rollout: [PHASE4_ADMIN_RPC.md](PHASE4_ADMIN_RPC.md); risultati e hash dei sorgenti: [evidence/phase41-admin-rpc-validation.json](evidence/phase41-admin-rpc-validation.json).

Le prove della fase 4.1 usano claim SQL simulate. L'incremento 4.2 aggiunge un gateway HTTP e JWT firmati sintetici, con i risultati riportati sotto. Il collaudo con account Supabase reali resta obbligatorio. Nessun deploy remoto, build APK o benchmark mobile è stato eseguito in questa preparazione. La CI GitHub resta sospesa.

1. In un database isolato, creare/modificare/eliminare partite con almeno un partecipante, score null/zero/negativi/decimali, più vincitori, score uguali e cooperative con risultato true/false/null.
2. Verificare JWT ordinario/admin via HTTP, ownership commenti, rifiuto di ID score di un'altra partita e rollback integrale per payload o FK invalidi.
3. Simulare timeout dopo commit, retry con stesso request_id, riuso della chiave con payload diverso e aggiornamenti simultanei su revisione vecchia. Verificare receipt e conflitti.
4. Verificare conservazione di color, role, location, campi importati e owner storico, comportamento del trigger score e cascade dell'eliminazione. Nessun percorso deve avviare ELO.
5. Eseguire i flussi web dopo l'adozione delle stesse RPC. Misurare tempi e richieste su dispositivo soltanto quando esiste una build Android; nessun benchmark mobile è stato eseguito in fase 1.

## Risultati writer web e HTTP locale, fase 4.2

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Vitest web completo | 96 test superati in sette file | Servizi, import, invalidazione ELO per owner e sette prove di callback React |
| Typecheck, lint e build web | Superati; lint zero errori e 17 warning | Build con flag predefinito false e con VITE_ATOMIC_PLAY_WRITES=true |
| Suite SQL e concorrenza | Superate; sei collisioni e 1.616 confronti di ranking | Tre migrazioni, rollback, ricevute, revisioni, BGA, web/Android e cutover locale |
| HTTP PostgREST 14.1 | 43 verifiche superate | JWT firmati, permessi, 400/404/409, precisione NUMERIC, replay, import e revoche DML |

Il cluster PostgreSQL 17.4 usa schema e account sintetici. Il gateway verifica firma HS256 e scadenza; l'emissione dei token è locale, senza login GoTrue. Le tabelle ELO sentinella restano invariate. PostgreSQL e PostgREST sono arrestati al termine. I callback React non verificano DOM, focus o accessibilità; non sono stati eseguiti test browser reali. L'APK Android resta alla 0.4.0 e l'editor partite resta disabilitato.

Risultati e hash dei sorgenti: [evidence/phase42-web-writers-validation.json](evidence/phase42-web-writers-validation.json). Procedura e limiti: [PHASE4_WEB_WRITERS.md](PHASE4_WEB_WRITERS.md). Il rollout remoto e il collaudo con account Supabase reali restano aperti; il database del sito non è stato modificato.

## Contratti e base locale, 14 ottobre 2026

La preparazione iniziale usa soltanto questa repo e il PC. Il cluster PostgreSQL 17.4 e il gateway PostgREST 14.1 replicano il catalogo pubblico già auditato, con Auth SQL e JWT sintetici. Non è un progetto Supabase; nessun test contatta Supabase remoto o Vercel. In quel primo incremento le RPC atomiche non erano implementate; il collaudo della nuova candidata è riportato sotto.

Il runner verifica i payload e poi riproduce via HTTP i difetti della base attuale: creazione/modifica parziale, metadati e ID persi, UPDATE incompatibile, assenza di revisioni, duplicati e stale delete. PASS certifica la riproduzione dei problemi, senza dichiararli risolti. Procedura: [local-tests/README.md](../local-tests/README.md); definizione: [PLAY_SAVE_CONTRACT.md](PLAY_SAVE_CONTRACT.md). Risultati e hash: [evidence/local-play-preparation.json](evidence/local-play-preparation.json).

I primi avvii hanno fatto correggere tre errori del nuovo runner: selezione di più eseguibili Node, pipe Windows trattenute dal PostgreSQL in background e punto e virgola assente dopo le definizioni delle funzioni nel generatore. Il run completo finale arresta entrambi i server e conserva i log locali. Nessun codice dell'app o repository web è stato modificato; nessun nuovo APK, login reale o benchmark mobile viene certificato da queste prove.

## Salvataggi atomici locali, 14 ottobre 2026

La candidata [candidate.sql](../local-tests/candidate.sql) implementa getter editor, creazione, modifica, eliminazione e import BGA. Metadati, score e ricevuta committono insieme. I test iniettano errori anche dopo la modifica dei dati, verificano i conflitti fra richieste concorrenti e conservano gli ID dei partecipanti mantenuti. Il trigger UPDATE incompatibile viene rimosso soltanto dalla fixture.

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Accettazione atomica | 21/21 casi, 170 richieste HTTP | PostgreSQL 17.4, PostgREST 14.1, JWT HS256 sintetici |
| Ranking SQL contro caratterizzazione JS | 1.592 vettori uguali | NULL, pareggi, winner, cooperative, da 1 a 63 partecipanti; Node 24.19.0 / V8 13.6.233.17-node.51 |
| Contratti ed evidenze Node | 39 superati | 29 controlli payload e 10 controlli dell'audit salvato |
| Base precedente | 29 prove HTTP superate | Riproduzione dei difetti prima di installare la candidata |
| Arresto dei processi propri | Superato | Entrambi i gateway e PostgreSQL terminati dal runner |

Il run completo dura 78,115 secondi; è un tempo di collaudo, non un benchmark dei salvataggi. Il caso 19 applica un cutover separato soltanto al database locale e nega DML diretto e RPC legacy. Il caso 20 verifica un adattatore server sintetico con revisione; nessun job reale è integrato. Le sentinelle rating e recalc restano invariate; la cancellazione conserva le cascade preesistenti sullo storico collegato.

Durante la preparazione un gateway è terminato all'avvio senza log diagnostici: il runner ora conserva stdout/stderr. Una prova Unicode falliva perché psql su Windows perdeva caratteri nell'argomento `-c`: le query passano ora da file UTF-8. Un initdb ha superato il limite di 60 secondi; il runner usa 120 secondi e conserva i log anche in caso di timeout. Il collaudo finale completo passa dopo queste correzioni.

Procedura: `pwsh -NoProfile -File scripts/test-local-play-saves.ps1 -Atomic`. Report, versioni, 21 risultati e hash dei sorgenti: [local-atomic-validation.json](evidence/local-atomic-validation.json). La CI verifica contratti Node, test JVM, lint e APK; i test PostgreSQL/HTTP restano sul PC. Non sono verificati GoTrue reale, revoca remota, consumer web/Android o rollout. L'APK resta 0.4.0 e l'editor partite disabilitato. Nessuna modifica a King-Score, Vercel o Supabase remoto.

## Client RPC Android disabilitato, 14 ottobre 2026

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Suite JVM completa | 165 superati, zero errori | 27 nuovi test: nove encoder/decoder e 18 client/trasporto |
| Contratti Kotlin separati | 13 gruppi superati | Due nuovi gruppi per Unicode delle partite, revisione ed editability |
| Contratti ed evidenze Node | 39 superati | Payload V1 e catalogo salvato |
| Lint debug | Zero errori, 16 warning | Nessun warning sui nuovi file del client RPC |
| APK debug | Build superata in 9 minuti e 53 secondi | Versione 0.4.0; client non collegato e disabilitato per default |

Le prove verificano richieste immutabili, punteggi testuali esatti, limiti JSONB e rifiuto di scale numeriche enormi prima dell'allocazione. HTTP simulato distingue conflitto, permesso, RPC mancante ed esito incerto; un solo rinnovo 401 conserva corpo e UUID. Timeout, annullamento, rete persa e chiusura mantengono l'incertezza; un successivo errore di sessione o permesso non la cancella. Le prove TCP loopback controllano 503, redirect, connessione interrotta e filtraggio dei codici errore.

Il primo test TCP contava anche una connessione senza richiesta HTTP; il controllo corretto conta soltanto richieste ricevute. Nella revisione è stata aggiunta la lettura limitata del codice errore per distinguere PGRST202: il comportamento predefinito del trasporto commenti resta invariato e la suite completa passa dopo questa modifica.

La build locale riusa il workaround Java TCP ignorato e un registro daemon dedicato, con JBR 21. L'avvio senza workaround falliva prima della compilazione; il tentativo con JDK 17 non caricava l'agent locale già compilato per Java 21. Questi strumenti non entrano nell'APK o nella CI. Il tempo riportato è quello della build, non un benchmark del client.

Implementazione e limiti: [PHASE4_ANDROID_RPC_CLIENT.md](PHASE4_ANDROID_RPC_CLIENT.md). Risultati e hash: [android-rpc-client-validation.json](evidence/android-rpc-client-validation.json). Nessuna nuova prova SQL, richiesta Supabase reale, schermata editor o test su dispositivo. Le richieste pendenti restano nella sola istanza del client; il recupero dopo morte del processo e la gestione del ciclo di vita UI restano da definire.

## Journal cifrato e recupero, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| Suite JVM completa | 175 superati | Dieci nuovi casi per write-ahead, client nuovo, conferma, errori storage e isolamento account |
| Android API 31 completo | 48 superati | 45 percorsi precedenti e tre nuovi test Keystore/file |
| Arresto forzato e processo nuovo | Superato | Probe nel solo APK di test: record cifrato scritto, force-stop e lettura da PID diverso |
| Lint e APK | Zero errori, 16 warning; build debug e test superate | Nessuna abilitazione editor o API reale |
| Node | 39 superati | Contratti V1 e audit salvato |

`scripts/test-pending-recovery.ps1` installa gli APK debug su un emulatore già avviato, esegue l'intera suite, prova il recupero in due processi e ripristina le impostazioni di animazione. Rifiuta seriali di telefoni fisici. Il runner Android personalizzato delega ad AndroidJUnitRunner quando non riceve il modo probe; non entra nell'APK dell'app.

La prima suite dopo l'avvio a freddo aveva un errore No compose hierarchies nel test di retry dello storico gioco. Quel caso passa da solo e le due successive suite complete passano; nessun crash dell'app risulta dal controllo delle eccezioni runtime. Il primo tentativo della probe separata non era registrato nel manifest generato da Gradle: ora il runner è configurato nel build del solo APK di test e la prova con processo nuovo passa. Questi tentativi non vengono conteggiati come successi.

La prova di processo verifica lo store cifrato; i test JVM verificano separatamente il client con Auth simulata. Non è un collaudo RPC/SQL completo dopo force-stop, non usa account reali e non certifica guasti del filesystem o perdita dei dati dell'app. Lo store cifrato deve ancora essere iniettato nel factory dell'editor. Stato: [STABILITY.md](STABILITY.md); risultati e hash: [pending-journal-validation.json](evidence/pending-journal-validation.json).

## Controller editor con fixture, 14 ottobre 2026

La suite JVM comprende 200 test, con 25 casi aggiunti: 23 per bozza/controller, uno per il registro dei journal e uno per la lettura della conferma sotto lock. Verifica validazione locale, precisione decimale, budget aggregato del payload, conservazione degli ID, cancellazione esplicita, conflitti, recupero, doppio tocco, perdita rete e risposte tardive dopo distruzione del controller.

I test mantengono leggibili tutti i 64 partecipanti di una fixture storica incompatibile, senza crearne una bozza modificabile. Una richiesta pendente conserva la propria identità anche se il caricamento dei cataloghi fallisce. Lo svuotamento differito della cache non elimina una conferma durante la lettura; il registro conserva lo stesso lock per account.

Node supera 39 controlli; lint riporta zero errori e 16 warning preesistenti. Le build debug e APK di test vengono verificate. Nessun nuovo test Android viene eseguito in questo incremento: non esiste ancora la schermata editor. Nessuna nuova prova SQL o richiesta al backend reale. Descrizione: [PLAY_EDITOR_STATE.md](PLAY_EDITOR_STATE.md); risultati e hash: [editor-viewmodel-validation.json](evidence/editor-viewmodel-validation.json).

## Schermata editor con fixture, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| JVM | 202 test superati | Due nuovi casi per bozza modificata, aggiornamenti rapidi e disponibilità dei controlli |
| Android API 31 completo | 65 test superati | 48 precedenti e 17 nuovi percorsi dell'editor |
| Journal dopo arresto forzato | Superato | Scrittura nel PID 7060, lettura nel PID 7106, impostazioni emulatore ripristinate |
| Node | 39 controlli superati | Contratti ed evidenze salvate |
| Lint e APK | Zero errori, 16 warning preesistenti; build superate | APK debug e test, senza attivare l'editor reale |

Le prove UI verificano scelta di gioco e partecipanti, modalità online, decimali esatti e negativi, dati nullable, risultati cooperativi, rimozione dei partecipanti e validazione della data. Coprono confronto dei conflitti, recupero della richiesta originale anche per un'altra partita, errori di storage/catalogo, 64 partecipanti storici, conferma della cancellazione e uscita dalla bozza. Account non admin e capacità disabilitata non caricano cataloghi o controlli di scrittura. Perdita rete e chiusura dell'account impediscono il ritorno dei dati e dei successi tardivi.

Il ripristino della composizione mantiene la bozza con lo stesso ViewModel; non equivale ancora alla ricreazione dell'Activity attraverso la navigazione reale. Il caso con font 1,8× verifica che campi e salvataggio restino raggiungibili. Le tre schermate acquisite sono state controllate visivamente e rimangono in `.work/editor-ui/screenshots`.

Il primo lint ha analizzato un file modificato durante l'esecuzione e ha segnalato un'indentazione con numeri di riga non allineati. La build ripetuta a sorgenti fermi passa. La build finale include la tastiera testuale dei punteggi, che consente anche il segno meno; la suite completa usa questi APK. Durata della suite: 266,819 secondi, non un benchmark dell'app.

Nessuna nuova prova SQL, richiesta al backend reale o modifica fuori repo. Il journal dopo arresto forzato viene verificato separatamente dalle fixture UI: non è ancora un collaudo completo RPC/editor/SQL con morte del processo. Fonte dei risultati e hash: [editor-ui-validation.json](evidence/editor-ui-validation.json).

## Recupero commenti dopo arresto del processo, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| JVM | 216 test superati, zero errori | 14 nuovi casi per persistenza, account, errori storage e conferma manuale |
| Android API 31 completo | 70 test superati | Cinque nuovi casi per cifratura, formato partite precedente e controlli UI |
| Arresto forzato e processo nuovo | Superato | Registro commenti e journal partite scritti nel PID 7886 e letti nel PID 7934 |
| Node | 39 controlli superati | Contratti ed evidenze salvate, senza interrogare il database remoto |
| Lint e APK | Zero errori, 16 warning preesistenti; build debug e test superate | Build corretta in 1 minuto e 17 secondi |

L'Application inietta il registro cifrato dei commenti. Il client salva gli UUID delle partite prima dell'HTTP; nessun testo o comando viene accodato. Dopo un esito incerto, una rilettura completa rende disponibile Ho controllato i commenti e non sblocca automaticamente le scritture, neppure con elenco vuoto. Errori di storage lasciano disponibili le letture e impediscono le mutazioni.

La prima suite completa ha eseguito 70 test con un errore: checkingDuringDispatchReconcilesAllPagesWhenTheSameAccountReturns conservava la vecchia aspettativa di sblocco automatico. Il controllo aggiornato verifica il blocco e la conferma manuale; la successiva suite completa supera tutti i 70 casi. Durata: 160,848 secondi, non un benchmark. Il runner verifica anche il recupero commenti in un nuovo processo e ripristina le impostazioni dell'emulatore. La schermata di verifica è stata acquisita e controllata visivamente.

Le prove di storage, HTTP simulato e UI rimangono separate; non certificano una mutazione remota completa dopo morte del processo. Il registro non conserva receipt confermate e il backend esistente non garantisce exactly-once. Nessuna modifica fuori repo, richiesta Supabase remota o firma di rilascio. L'editor partite reale resta disabilitato. La CI GitHub non è stata avviata, secondo le ultime istruzioni dell'utente; il commit usa `[skip ci]`. Risultati e hash: [comment-recovery-validation.json](evidence/comment-recovery-validation.json).
