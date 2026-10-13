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

## Controlli ancora aperti

1. Login con account reale ordinario/admin, revoca remota effettiva e riavvio del processo con sessione reale. Nessuna credenziale utente è stata usata nei test.
2. Installazione e aggiornamento su telefono fisico, API minima 26, TalkBack e caratteri grandi. I risultati dell'emulatore API 31 non coprono questi dispositivi.
3. Nella fase 3: immagini e rifiniture accessibilità. Nella fase 4: commenti e scritture isolate secondo i controlli sotto.

Non sono stati eseguiti benchmark mobili. Home, Statistiche ed ELO sono implementate nell'incremento 0.3.1; la verifica con dati reali rimane aperta.

## Controlli prima delle scritture partite

1. In un database isolato, creare/modificare/eliminare partite con almeno un partecipante, score null/zero/negativi/decimali, più vincitori, score uguali e cooperative con risultato true/false/null.
2. Verificare JWT ordinario/admin via HTTP, ownership commenti, rifiuto di ID score di un'altra partita e rollback integrale per payload o FK invalidi.
3. Simulare timeout dopo commit, retry con stesso request_id, riuso della chiave con payload diverso e aggiornamenti simultanei su revisione vecchia. Verificare receipt e conflitti.
4. Verificare conservazione di color, role, location, campi importati e owner storico, comportamento del trigger score e cascade dell'eliminazione. Nessun percorso deve avviare ELO.
5. Eseguire i flussi web dopo l'adozione delle stesse RPC. Misurare tempi e richieste su dispositivo soltanto quando esiste una build Android; nessun benchmark mobile è stato eseguito in fase 1.
