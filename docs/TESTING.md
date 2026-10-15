# Verifica KingScore Android

## 0.6.2/code 20 — Statistiche numeriche

APK: `.work/releases/KingScore-0.6.2-20.apk`, 14.560.738 byte. SHA-256 `6bc382d954ed9a7d051cba527253574e9cb28d60f38141715354a3a1056c4f9f`. Fonte pulita `45193cfc5e5d53f3e1b0c58c46b972d32cc67b85`; stesso certificato delle versioni precedenti. Installare sopra la 0.6.0 senza disinstallarla; aggiornamento 18 → 20 verificato.

Statistiche mostra schede dei giochi, partite totali, medie, copertura e dettagli paginati dei giocatori. Win Rate per Gioco e tutti i grafici sono rimossi, insieme alle richieste ai grafici e ai loro cataloghi. Rating ELO conserva la classifica e la compatibilità v1/v2; login e Internet restano obbligatori. [Perimetro](STATISTICS_NUMERIC.md).

| Controllo | Risultato |
| --- | --- |
| JVM debug e release | 333 test per variante, zero fallimenti |
| Lint | Zero errori; 16 avvisi debug e 16 release |
| Contratti Node | 41 passati |
| Richieste ai grafici | Zero in apertura, refresh, espansione, riapertura e paginazione numerica |
| UI interessata | Singolo run verde: 25 successi, inclusi Statistiche, testo 2×, pareggi e accesso |
| Aggiornamento 18 → 20 | Stesso UID, registri cifrati conservati, firma estranea rifiutata, run-as negato |
| Login/rete release | Login con sessione vuota, blocco offline e disconnessione/riconnessione verificati |
| CI della fonte | [PASS sul commit 45193cf](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38816371224) |

Le prove UI usano un emulatore PC API 35; aggiornamento e gate della release sono verificati su un emulatore pulito API 31. Entrambi usano ART precompilato; il collaudo non misura l'avvio a freddo. La suite nativa integrale della 0.6.0 non è stata ripetuta. Nessun nuovo login remoto o telefono fisico verificato. Probe e dati sintetici rimossi, impostazioni ripristinate ed emulatori dedicati arrestati. Aggiornamenti APK manuali.

Il primo tentativo firmato su API 35 ha verificato firma e registri, ma la finestra Android «System UI isn't responding» ha coperto lo stato offline. Il log di quel boot contiene anche crash di Google Play Services; il riavvio non ha risolto il blocco del sistema. Il collaudo completo è passato sullo stesso APK in un emulatore pulito API 31; il report fallito resta referenziato nella qualifica.

Nessuna modifica web, Vercel o Supabase. Il riepilogo restituisce ancora i campi del win rate, che questa schermata non visualizza. I conteggi server e i writer atomici/web restano dipendenze separate: [ELO v2](ELO_V2_ANDROID.md).

Evidenze: [build](evidence/private-062-build.json), [UI](evidence/private-062-ui-validation.json), [aggiornamento](evidence/private-062-upgrade-validation.json), [qualifica](evidence/private-062-release-validation.json). Il manifest iniziale di build resta candidato; la qualifica promuove lo stesso checksum.

## 0.6.1/code 19 — compatibilità ELO v2

APK: `.work/releases/KingScore-0.6.1-19.apk`, 14.593.506 byte. SHA-256 `941950bb49ec1ec7b2d2679530fe7ce0f306c1c20f76565bb28c15eb70e28254`. Fonte pulita `03d1fa576df4bf19fa11d626aec847fd274937d7`; stesso certificato della 0.6.0. Installare sopra la versione precedente senza disinstallarla.

Classifiche e grafici leggono v2 e conservano v1 come compatibilità di lettura. Versioni sconosciute e dati incoerenti restano rifiutati. Storico, dettaglio e Home distinguono il primo posto condiviso dalle vittorie cooperative; nessun calcolo ELO nel client. Login e blocco senza Internet restano obbligatori.

| Controllo | Risultato |
| --- | --- |
| JVM debug e release | 333 test per variante, zero fallimenti |
| Lint | Zero errori; 16 avvisi debug e 16 release |
| Contratti Node | 41 passati |
| Risposte reali sul PC | Sette prove passate: v2, compatibilità v1, rifiuto di versioni sconosciute e storico alterato |
| UI nativa interessata | 25 successi; quattro nuovi casi, testo 2× e blocco d'accesso inclusi |
| Aggiornamento 18 → 19 | Stesso UID, registri cifrati conservati, firma estranea rifiutata, run-as negato |
| Login/rete release | Sessione vuota mostra login; avvio offline, disconnessione e riconnessione verificati |
| CI della fonte | [PASS sul commit 03d1fa5](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38813479934) |

Il primo run nativo aveva due errori nei selettori dei nuovi test, corretti senza nuove modifiche produttive. La ripetizione completa delle sei classi interessate passa 25/25; non è ripetuta la suite integrale della 0.6.0. L'emulatore API 35 usa ART precompilato; nessuna misura di avvio a freddo o prova su telefono fisico. Nessun nuovo login GoTrue, deploy web o cambiamento Supabase.

Il primo collaudo firmato ha verificato firma e recupero dei registri, ma la finestra Android «System UI isn't responding» ha coperto lo stato offline. Dopo il riavvio dell'emulatore dedicato, il collaudo completo è stato ripetuto sullo stesso APK; il report fallito resta conservato e referenziato nella qualifica.

Questa patch non modifica le RPC statistiche: i conteggi server basati su `winner=true` possono includere ancora i primi posti condivisi fra le vittorie. Il ranking dei writer atomici e il collegamento dei writer web restano interventi separati. [Perimetro e dipendenze](ELO_V2_ANDROID.md).

Evidenze: [build](evidence/private-061-build.json), [UI](evidence/private-061-ui-validation.json), [aggiornamento](evidence/private-061-upgrade-validation.json), [qualifica](evidence/private-061-release-validation.json), [letture v2](evidence/elo-v2-reader-validation.json). Il manifest iniziale di build rimane candidato; la qualifica promuove lo stesso checksum. Probe e dati sintetici rimossi, impostazioni ripristinate, emulatore dedicato arrestato. Aggiornamenti APK manuali.

## Stabile privata 0.6.0/code 18 — qualifica PC

APK: `.work/releases/KingScore-0.6.0-18.apk`, 14.593.506 byte. SHA-256: `e820ff8a0622384dd618d1ad71ec876d8c289590237aa116c4790d4e258e9acf`. Fonte pulita: `b5d5e112c41cd585b81eab03c3e521b5de9c1fb9`. Certificato invariato: `b00fd08085ba373e9dde52066b3e8fd85b17b94f4b87dc5cd50ddc55d06a294b`. Installare sopra la 0.5.2 senza disinstallarla; aggiornamento 17 → 18 verificato con UID e registri cifrati conservati.

La Home legge i totali con una RPC. Statistiche riprende Win Rate per Gioco, ELO Generale e Statistiche per Gioco del web, con schede giocatore espandibili, medie e copertura dei valori disponibili. I grafici leggono finestre di 100 partite e consentono selezione tramite tocco, slider e legenda; il testo 2× mantiene metriche e comandi leggibili.

| Verifica della code 18 | Risultato |
| --- | --- |
| JVM e lint | 323 test sia debug sia release, zero fallimenti; lint senza errori, 24 avvisi debug e 16 release |
| Contratti Node | 41 passati |
| UI nativa | Singolo run completo verde: 111 successi e due integrazioni escluse su 113 casi |
| CI della fonte | [PASS sul commit b5d5e11](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38794486985) |
| Aggiornamento 17 → 18 | Stesso UID, registri cifrati conservati, firma diversa rifiutata, run-as negato |
| Login e rete release | Login a sessione vuota, avvio offline e disconnessione bloccati, riconnessione verificata |
| API reali | Progetto ScoreKing verificato; cinque RPC applicate, letture con ruolo authenticated e decoder Android passati, richieste HTTP anonime rifiutate |

Dati sorgente, formula e pubblicazione ELO, policy, grant e funzioni precedenti sono invariati. Nessuna modifica web o Vercel. Le nuove RPC sono applicate su autorizzazione dell'utente; il telefono non esegue ricalcoli. Login e connessione restano obbligatori, senza modalità offline.

Evidenze: [build](evidence/private-060-build.json), [UI](evidence/private-060-ui-validation.json), [aggiornamento](evidence/private-060-upgrade-validation.json), [qualifica](evidence/private-060-release-validation.json), [rollout Supabase](evidence/summary-api-live-validation.json). Il manifest di build resta candidato; il report di qualifica promuove lo stesso checksum.

Il collaudo usa l'emulatore API 35 sul PC con ART precompilato. Telefono fisico e nuovo login GoTrue non sono verificati. Le risposte reali sono lette con ruolo SQL authenticated e decodificate dalle classi Android sul PC; non è un nuovo percorso autenticato completo nell'APK. Le misure SQL locali non stimano la latenza mobile. Le due integrazioni escluse richiedono account remoti e driver HTTP dedicato. Impostazioni ripristinate, fixture e probe rimosse, AVD dedicato arrestato. Gli aggiornamenti APK restano manuali.

## Stabile privata 0.5.2/code 17 — 15 ottobre 2026

La code 17 deriva dalla fonte pulita `ca4908f16f2400a87c7276907dedc61ee47f01a4`. APK SHA-256: `20a6a4867119ea43a3aca00ddb9c9b3ceb830fe1d6b93de568d3168efe720205`. La qualifica della 0.5.2 è distinta dai risultati storici sotto, che restano attribuiti ai rispettivi APK.

| Area | Risultati verificati |
| --- | --- |
| JVM, lint e contratti | 301 test sia debug sia release, zero fallimenti; lint con zero errori, 24 avvisi debug e 16 release; 40 controlli Node passati |
| Letture dati | Statistiche con proiezione ridotta, pagine richieste da 500 e completamento a pagina vuota; fixture da 483 partite in due richieste dati invece di dieci, escluse Auth/cataloghi. Riepilogo condiviso, cache ELO con verifica online, invalidazione e risposte tardive coperti dai test |
| UI nativa | 108 casi distinti: 106 riusciti e due integrazioni escluse, dopo la ripetizione da 31 casi delle classi coinvolte nei cinque fallimenti iniziali. Menu 4/4 e nuove schermate 4/4 passano anche con font di sistema a 2× |
| Firma e aggiornamento | Release non debuggabile, hook di test esclusi; aggiornamento 16 → 17 con stesso UID e registri cifrati conservati, firma estranea rifiutata e run-as negato |
| Accesso release e CI | Login a sessione vuota, avvio offline, disconnessione e riconnessione verificati; [CI della fonte](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38774226906) passata |

Le prove mirate comprendono `CommentCountsRepositoryTest`, `HistoryCommentCountsTest`, `CommentChangeCallbackTest` e `RemainingScreensUiTest`. Verificano conteggi in batch con cap server inferiore a 50, errori distinti da zero, notifiche solo dopo esiti confermati e risposte tardive scartate. Il retry di un refresh fallito rilegge la prima pagina con gli stessi filtri; FORBIDDEN elimina righe, conteggi e risposte catalogo pendenti. Le card conservano tutte le cifre, compresi decimali con virgola; il dettaglio Giocatore non produce totali dalla sola pagina caricata.

Il run completo iniziale sulla fonte `6601729c922b5a984aa4358c7e2a90605473c07b` esegue 106 casi: 99 successi, cinque fallimenti e due assunzioni. La fonte finale ripete integralmente le classi coinvolte: 31 successi, inclusi due nuovi casi, senza fallimenti rimasti aperti. L'aggregato è 106 successi e due assunzioni su 108 casi distinti; non è un singolo run completo verde. L'ultima modifica produttiva è la guardia di caricamento del dettaglio Gioco, ricollaudata con la sua classe completa e `MetadataRefreshUiTest`. Sono corretti anche la separazione dei tag nel wrapper refresh e i driver dei conteggi/card.

Le due assunzioni sono le integrazioni `LiveBackendSmokeTest` e `PlayEditorRpcRecoveryTest`, che richiedono account remoti e il driver HTTP dedicato; non sono contate come successi. Le nuove catture hanno sfondo e colore testo del tema applicativo e fixture sintetiche. Menu e nuove schermate sono ripetuti con font di sistema a 2×, oltre alla prova Compose del punteggio lungo.

Evidenze: [build](evidence/private-052-build.json), [UI per caso](evidence/private-052-ui-validation.json), [aggiornamento](evidence/private-052-upgrade-validation.json), [rilascio](evidence/private-052-release-validation.json), [letture dati](evidence/private-052-data-reads-validation.json) e [misure PC](evidence/private-052-pc-performance.json). Le misure usano l'APK debug con 1.200 partite sintetiche, scorrimenti Android e nessuna latenza di rete. L'ambiente è l'AVD API 35 dedicato, 384×832 a 160 dpi, GPU host e ART precompilato. Non costituiscono misure di produzione, release o avvio a freddo su telefono.

La prova firmata usa la stabile 0.5.1/code 16 come baseline, conserva i registri cifrati e verifica login/blocco/ripresa con sessione vuota. Non inserisce credenziali né autentica account remoti. Nessuna modifica web, Vercel o Supabase per la 0.5.2; il percorso reale della 0.5.0 resta storico. Impostazioni dell'emulatore ripristinate, dati sintetici cancellati, pacchetto test rimosso e AVD dedicato arrestato. Telefono fisico e nuovo collaudo remoto non verificati.

## Prima stabile privata 0.5.0 — 15 ottobre 2026

L'APK firmato code 15 deriva dalla fonte pulita `df39f5b3bc1fad0e11991784864421193c983225`.

1. Build release: 256 test JVM passati, lint con zero errori e 24 avvisi preesistenti. La CI della fonte passa anche i 40 controlli Node.
2. Aggiornamento RC4/code 12 → 15: stesso certificato e UID, dati cifrati conservati, firma estranea rifiutata e blocco senza Internet verificato.
3. Test nativi sullo stesso APK: regressione immagini `OK (1 test)`; percorso con MainActivity e backend reali `PASS_FULL`, `OK (1 test)`. Verifica classifica/storico, modifica admin di una partita altrui, cambio account, commenti propri e disconnessione/riconnessione. Le letture usano account reali temporanei; il percorso dura 38,037 secondi, senza costituire un benchmark.
4. Cinque casi SDK online passati. Cleanup verificato: zero account, ricevute e pubblicazioni di prova; conteggi applicativi iniziali e risultato ELO originale conservati; regola delle registrazioni ripristinata esattamente.

Prove: [build](evidence/private-050-build.json), [aggiornamento](evidence/private-050-upgrade-validation.json), [UI](evidence/private-050-ui-validation.json), [cleanup](evidence/live-backend-cleanup-validation.json) e [rilascio](evidence/private-050-release-validation.json). Le prove native riguardano emulatori del PC; il browser del sito dopo il deploy e telefoni fisici non sono stati verificati. Le sezioni seguenti conservano i checkpoint storici e i relativi limiti.

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

## Ricreazione e rotazione della navigazione, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| JVM | 221 test superati, zero errori | Cinque nuovi casi per risorse conservate, rilascio e revalidazione dell'accesso |
| Android 12, API 31 | 74 test superati | Suite completa, quattro nuovi casi con ricreazione effettiva dell'Activity |
| Android 15, API 35 | 74 test superati | Stesso APK e suite completa su display 320×640 px, densità 160 dpi |
| Recupero dopo arresto forzato | Superato su entrambe le versioni | Partite e commenti letti da processi diversi; impostazioni ripristinate |
| Node, lint e build | 39 controlli superati; zero errori lint, 16 warning preesistenti | APK debug e test compilati |

La prova nativa esegue AppContent e la navigazione di produzione in un host del source set debug, non esportato. ActivityScenario.recreate e la rotazione portrait → landscape devono produrre un'altra istanza dell'Activity. I test conservano bozza, dettaglio e pagine senza un nuovo repository o caricamento; un invio in corso rimane singolo. Blocco rete, cambio account, logout e Checking continuano a chiudere il writer e scartare le bozze precedenti. Il test ripristina portrait prima di chiudere l'host.

Il primo run completo API 31 esegue 74 test con un errore No compose hierarchies nel dialogo di cancellazione, CommentsUiTest.kt:113; i quattro nuovi casi di ricreazione passano. Dopo la correzione della pulizia dell'orientamento, il run completo API 31 passa in 188,586 secondi. La causa del primo errore non è stata dimostrata in modo conclusivo. API 35 passa in 316,468 secondi. Sono tempi di collaudo, non benchmark.

Le probe di recupero scrivono/leggono nei PID 8281/8325 su API 31 e 7601/7680 su API 35. Non verificano un invio remoto completo dopo morte del processo. MainActivity viene compilata e analizzata dal lint; la revalidazione del suo AppViewModel è testata separatamente con Auth sintetica. Nessuna credenziale reale, richiesta al backend remoto, firma di rilascio o modifica fuori repo. CI sospesa; editor admin disabilitato. Dettagli: [ACTIVITY_LIFECYCLE.md](ACTIVITY_LIFECYCLE.md); risultati e hash: [activity-recreation-validation.json](evidence/activity-recreation-validation.json).

## Candidata privata firmata e rete reale dell'emulatore, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| JVM release | 225 test superati, zero errori | Quattro nuovi casi per callback rete, perdita e ordine degli eventi |
| Lint release e build | Zero errori, 24 warning; build in 3 minuti e 3 secondi | Raccomandazioni di aggiornamento dipendenze/plugin e quattro UseKtx; nessun errore di correttezza segnalato |
| Contratti Node | 39 controlli superati | Evidenze salvate e payload V1 |
| Firma e APK | RSA 3072, v2/v3, certificato corrispondente, allineamento ZIP a 16 KiB | APK non debuggabile; host/probe di test assenti da manifest e DEX |
| Aggiornamento API 31 e 35 | Superato su entrambe | Baseline privata sintetica 8 → candidata 10, UID invariato, registri partite/commenti cifrati conservati |
| Controlli negativi | Superati | Firma debug rifiutata senza cambiare app/dati; run-as negato; DEX alterato rifiutato; rigenerazione implicita della chiave rifiutata |
| MainActivity release | Superata su entrambe le versioni | Avvio offline, login a freddo senza credenziali, disconnessione/riconnessione live senza riavvio |

La rc1 superava firma e aggiornamento, ma il collaudo API 35 ha riprodotto un difetto reale: rete predefinita assente mentre il login restava visibile dopo disconnessione. Il monitor rileggeva uno snapshot sincrono dentro le callback. La rc2 usa la rete e le capacità ricevute, blocca subito sulla perdita e ignora gli eventi precedenti. Quattro test JVM e il collaudo live sulle due versioni verificano la correzione. La rc1 non è distribuita. Dettagli: [NETWORK_MONITOR.md](NETWORK_MONITOR.md).

La prima build offline non aveva tre dipendenze release in cache; sono state scaricate le versioni già fissate, senza aggiornamenti. La prima firma consumava due volte il file password: ora PKCS12 riusa la password del keystore e la chiave originale è conservata. Il runner legge l'XML salvato da uiautomator, accetta userId/appId e avvia un processo nuovo dopo le probe prima del collaudo UI. Un'istanza API 35 aveva ADB bloccato con porta console non disponibile; la riconnessione mirata falliva. Solo quell'istanza di prova è stata chiusa e riavviata su una porta supportata. Nessun tentativo fallito viene conteggiato come successo.

La candidata finale è .work/releases/KingScore-0.5.0-rc2-10.apk, versionCode 10, 14.446.050 byte, SHA-256 9aee9c8245e0583f10f475069b15c7e150261cca55847dc756be708fa4c89afa. Il certificato SHA-256 è b00fd08085ba373e9dde52066b3e8fd85b17b94f4b87dc5cd50ddc55d06a294b. Le sei schermate finali sono state controllate visivamente. I segmenti ELF delle librerie grafiche dei quattro ABI hanno allineamento 16.384 byte; non è una prova runtime su hardware ARM o pagine da 16 KiB.

Le probe sono firmate con la stessa chiave soltanto per questo collaudo; non entrano nell'APK prodotto e non vanno distribuite. Usano record sintetici e non verificano sessioni con JWT reali o un salvataggio HTTP/RPC completo. Il login usa un archivio sessione vuoto e non invia credenziali: il percorso Auth termina prima dell'HTTP. Nessun test su Supabase remoto, modifica fuori repo o esecuzione CI. I 74 test Compose del checkpoint precedente restano evidenza della navigazione con fixture; questo incremento esegue le prove release su MainActivity. Risultati, hash e log: [private-release-validation.json](evidence/private-release-validation.json).

## RC3: collaudo finale sul PC, 14 ottobre 2026

| Controllo | Esito | Ambito |
| --- | --- | --- |
| JVM release, lint e APK | 225 test superati; zero errori lint, 24 warning; build 3m 29s | Release code 11, stesso certificato privato, host di test esclusi |
| Contratti Node | 39 controlli superati | Contratti ed evidenze locali; nessuna verifica RLS remota aggiunta |
| Suite Android API 35 | 77 test superati in 211,612 s | Include i tre nuovi casi di storico/font/tastiera; recupero partite/commenti fra PID 12837 e 12879, impostazioni ripristinate |
| Font Android a 1,8× | Tre casi mirati superati in 101,615 s | Rafforzamento successivo della stessa classe: configurazione di sistema verificata, dialogo e logout raggiungibili, valore 1,0 ripristinato |
| Aggiornamento RC3 API 31 | Superato | Baseline 8 → 11, UID 10145 invariato, registri cifrati, firma debug rifiutata, run-as negato, blocco/ripresa rete live |
| Avvio RC3 API 35 | Sette campioni validi | Mediana 3.288 ms, p95 4.439 ms; PSS 61.068–63.343 KiB; login senza credenziali |

Il primo run da 77 casi fallisce soltanto in AccessUiTest: il driver cliccava il login senza portare il pulsante in vista con l'IME aperto. Il test originale fallisce anche nel run mirato. Dopo scorrimento e sincronizzazione del callback, i tre casi AccessUiTest e la suite completa passano; il test verifica email/password inviate e rimozione della password. Un controllo intermedio sul testo del campo includeva l'etichetta Email e falliva pur con EditableText corretto: è stato corretto con assertTextContains.

Le prime prove mirate terminavano durante l'avvio dell'instrumentation; in una è osservato un ANR, nell'altra la causa non è conclusiva. Un run successivo viene fermato esplicitamente dopo l'errore del Profilo non scorrevole e il costo del driver di paginazione. Il driver ora salta direttamente all'indice atteso e la suite mirata passa. Il controllo del font è poi rafforzato con l'impostazione Android reale, perché il dialogo ha un owner separato dall'host; questi tre casi vengono rieseguiti, senza attribuire alla suite precedente il controllo aggiunto successivamente. Le due immagini finali sono state controllate visivamente: la cattura del dialogo mostra l'invio; annullamento viene verificato dopo un altro scorrimento.

Il primo protocollo di avvio release fallisce nella connessione UiAutomation. Il processo dell'app rimane presente e una nuova lettura mostra il login. I sette campioni vengono raccolti sulla stessa installazione nuova e vuota con un driver locale di ripresa; il tentativo iniziale resta registrato. Lo script permanente aggiunge letture UI con retry limitati e conserva il rifiuto di installazioni preesistenti. Una nuova istanza dell'emulatore resta offline senza porta console aperta e fallisce anche la riconnessione mirata: vengono verificati e chiusi soltanto i suoi PID, poi il controllo font passa su una porta libera. L'altro emulatore presente sul PC non viene modificato.

La RC3 non ha un nuovo collaudo completo di aggiornamento API 35: quello della RC2 rimane evidenza separata. I tempi dello storico includono instrumentation e fixture preallocate, senza HTTP, JSON o immagini; il renderer software non consente una promessa di 60 fps. Limiti e protocollo: [FINAL_PC_QA.md](FINAL_PC_QA.md) e [BENCHMARKS.md](BENCHMARKS.md). Risultati e hash: [final-pc-qa-validation.json](evidence/final-pc-qa-validation.json).

CI sospesa, editor admin disabilitato, nessun account o database remoto usato per i test. Questa evidenza chiude il checkpoint locale della candidata; non dichiara completati i requisiti esterni della stabile.

## Riattivazione CI, 14 ottobre 2026

Su autorizzazione esplicita dell'utente, Android CI è stato riattivato. Il run [38624823497](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38624823497), sul commit abaf16c17f25ec70f000488ceeb126ea79ba976c, termina con successo: 39 controlli Node, 225 test JVM debug senza failure o ignored, lint e APK debug. Il report lint contiene 25 warning e nessun errore: target SDK, aggiornamenti plugin/dipendenze e quattro UseKtx. La build termina in due minuti; è tempo di build, non un benchmark dell'app.

I report e l'APK CI sono stati scaricati in `.work/ci-resume/38624823497` e controllati. La CI non esegue i 21 casi PostgreSQL/PostgREST, i test Android nativi o la firma privata: queste restano prove locali separate. Nessun deploy, test su Supabase remoto o chiave privata inviata a GitHub. La CI sospesa nei report RC3 precedenti è uno stato storico, non la configurazione attuale. Metadati: [ci-resume-validation.json](evidence/ci-resume-validation.json).

## Migrazione web predisposta: collaudo sul PC

La modalità aggiuntiva esegue la migrazione del checkout web locale, senza usare Supabase o modificare l'APK:

```powershell
pwsh -NoProfile -File scripts/test-local-play-saves.ps1 -Atomic -WebMigrationDirectory .work/King-Score-atomic/scripts/atomic-play-migrations
```

Il percorso SQL deve trovarsi nella directory .work di questa repo; il runner richiede Atomic, copia la fonte in una directory di run univoca e confronta il suo SHA256 fra profili, installazione e sorgente finale. Il cluster PostgreSQL e PostgREST sono propri, vincolati a loopback e con ruoli/utenti/dati sintetici. La fonte web contiene soltanto l'installazione V1: Auth fake, adattatore posizioni e cutover rimangono fixture locali distinte.

Il comando finale passa con 16 profili di compatibilità e i 21 casi di accettazione, 1.592 confronti ranking e 170 richieste HTTP. I profili verificano anche scambio partecipanti sotto UNIQUE nei due ordini, preservazione di ID/created_at/metadati, ON CONFLICT non utilizzabile come arbiter deferrable, Auth senza PK, FK dipendenti, rollback su schema/tipi/trigger non supportati e duplicati BGA introdotti dopo l'installazione. Ogni clone viene rimosso dopo il caso, conservando query e report; gateway e server vengono arrestati. Le fixture non verificano GoTrue, il database reale, il browser web o i job reali.

I primi tentativi hanno rilevato due errori del test: confronto inet con suffisso /32 e ID BGA con spazio, respinto prima del controllo duplicati. Sono stati corretti nelle fixture. Due run successivi passavano le prove funzionali ma segnalavano FAIL nella chiusura perché il flush dei 16 clone superava 30 e poi 60 secondi. Le letture successive di pg_ctl status e dei log hanno confermato lo spegnimento; nessun timeout è stato interpretato come arresto immediato. Il runner finale rimuove i soli clone creati nel cluster isolato dopo ogni caso e il comando completo termina PASS. I tentativi precedenti rimangono nei rispettivi run .work e non vengono conteggiati come comandi riusciti.

Evidenze: [run](evidence/prepared-web-migration-run.json), [profili](evidence/prepared-web-migration-profiles.json), [accettazione](evidence/prepared-web-migration-acceptance.json). I test nativi restano locali; la CI Android continua a verificare Node/JVM/lint/APK e non installa questa migrazione.

## Rifiuti RPC Android e recupero, 14 ottobre 2026

Due nuovi test falliscono sul client precedente: una risposta HTTP ambigua cancellava il comando salvato. Dopo la correzione passano tutti i 227 test JVM, inclusi i 31 del writer partite, i 39 controlli Node, lintDebug e assembleDebug. La build completa termina in 4 minuti e 42 secondi; questo è il tempo del comando sul PC, non un benchmark dell'app. I tentativi Java 17 si fermano prima dei test per connessione loopback e compatibilità dell'agente; il comando riuscito usa Java 21 con il workaround locale già presente e ignorato.

La prova parametrica copre 27 risposte ambigue, la conservazione del corpo e dell'UUID e il blocco di nuovi invii. Un'altra prova ricrea il client sullo stesso store in memoria e recupera il risultato ripetendo solo il comando originale. I test dei rifiuti noti e del rinnovo Auth usano ora codici macchina espliciti, anziché corpi arbitrari. Il trasporto TCP esistente continua a verificare assenza di retry/redirect e filtraggio dei codici errore.

Nessun test Android su emulatore, backend SQL o servizio remoto viene rieseguito in questo checkpoint. L'integrazione del journal cifrato con l'editor resta aperta; le RPC di produzione rimangono disabilitate. La CI GitHub non viene avviata secondo le ultime istruzioni AGENTS.md fornite nella conversazione; il commit usa `[skip ci]`. Report e hash: [android-play-rejection-validation.json](evidence/android-play-rejection-validation.json).

## Navigazione e sessione dell'editor, 15 ottobre 2026

Il collegamento passa con 231 test JVM, 39 controlli Node, lintDebug e build dei due APK debug. Lint riporta zero errori e 24 avvisi. La suite Android completa passa con 87 test su API 35, in 265,412 secondi di instrumentation. Include dieci nuovi casi della navigazione editor e i percorsi precedenti di consultazione, commenti, immagini e storage. Il tempo non misura le prestazioni dell'app su un telefono.

Il successivo [collaudo integrato HTTP e cifratura](EDITOR_RPC_PC_QA.md) termina PASS il 15 ottobre: tre processi Android, due force-stop verificati, replay con UUID/corpo invariati, conferma recuperata senza nuova mutazione, modifica e cancellazione contro PostgreSQL/PostgREST locali. Auth e monitor rete sono sintetici. Il database finale ha zero partite, zero punteggi e tre receipt. Gateway, bridge, reverse ADB e cluster sono chiusi; nessun contatto remoto o CI GitHub. La build debug passa con due worker, 231 test JVM già aggiornati e lint senza errori; i controlli Node diventano 40. L'APK firmato resta la RC3 precedente. Evidenza: [editor-rpc-pc-validation.json](evidence/editor-rpc-pc-validation.json).

La regressione dello stesso APK passa su API 35 con 87 casi superati e un caso condizionale saltato, già eseguito dal driver HTTP. Passano le probe partite/commenti dopo force-stop, con PID diversi e impostazioni ripristinate. I due tentativi precedenti conservano i fallimenti di frame/tastiera: una finestra ANR di System UI copriva l'app. Il solo emulatore dedicato è stato riavviato; nessuna modifica al codice produttivo o alle asserzioni ha risolto quei fallimenti. Report e diagnosi sono nell'evidenza del collaudo integrato.

Le nuove prove usano AppContent e NavHost di produzione con repository e writer sintetici. Verificano gate admin/capability, creazione con filtro storico conservato e una sola rilettura, modifica di una partita di un altro proprietario con ID/metadati, eliminazione con dettaglio aggiornato, bozza conservata attraverso ricreazione, una sola richiesta durante rotazione, chiusura e riapertura, revoca del ruolo, rete persa, Checking/logout, separazione fra account, recupero esplicito e font scale 1,8 tramite LocalDensity. Il recupero usa una mappa in memoria per account, senza HTTP: non certifica il percorso completo attraverso il journal cifrato e le RPC.

Il primo tentativo non esegue test: ActivityManager registra un ANR di avvio del processo sotto pressione CPU/memoria. Il processo dell'emulatore resta vivo; una riconnessione ADB mirata permette di proseguire sulla stessa installazione. La ripresa esegue nove casi e fallisce in due selettori del test: root della schermata/dialogo e testo Online presente sia nel chip sia nel riepilogo. Vengono corretti i selettori, aggiunto il caso Checking/logout e ricompilato l'APK test. La suite completa successiva termina PASS; i tentativi precedenti restano conservati come falliti.

Le catture della bozza e del dialogo sono state ispezionate sul tema scuro dell'app. Il collaudo usa l'emulatore dedicato emulator-5578; l'istanza preesistente emulator-5584 non viene modificata. Non vengono applicate migrazioni, inviate richieste Supabase o avviati workflow GitHub. Il factory di produzione rimane assente e la candidata firmata RC3 resta un artefatto del checkpoint precedente. Report, hash dei sorgenti, APK e log: [editor-navigation-validation.json](evidence/editor-navigation-validation.json).

## RC4 firmata: aggiornamento e rete su API 31 e 35

La candidata RC4, versionCode 12, deriva dal commit bc6789607e5b9e3eb310a07411f4f7917308a897. La build release esegue 231 test JVM senza errori e lint con zero errori e 24 avvisi. Il certificato privato è lo stesso della RC3; manifest, DEX e risorse escludono host/probe e l'eccezione HTTP di debug. I [metadati](evidence/private-rc4-build.json) registrano checksum e fonte. I collaudi usano questo APK immutato e la RC3 come baseline.

Il [report API 31](evidence/private-rc4-api31-validation.json) e il [report API 35](evidence/private-rc4-api35-validation.json) terminano PASS: rifiuto della firma debug senza perdita dei dati, aggiornamento 11 → 12 con UID invariato, registri partite/commenti cifrati conservati, run-as negato, avvio senza rete bloccato, login con sessione vuota e blocco/ripresa dopo disconnessione live. Il runner verifica l'assenza della rete predefinita prima delle prove offline. Entrambi ripristinano i radio; gli emulatori dedicati vengono arrestati. Le catture offline e login API 35 sono state ispezionate senza riscontrare finestre che coprano l'app o controlli tagliati.

Il primo tentativo API 31 resta FAIL ed è conservato separatamente: mancava la verifica dello stato di rete prima dell'avvio offline. La correzione riguarda il runner; l'APK non cambia. Non sono inserite credenziali o contattati database remoti. Queste prove non certificano Auth/RLS reali, RPC disponibili in produzione o dispositivi fisici; l'editor reale resta disabilitato e la CI rimane sospesa.

## UI approvata e release 0.5.1, 15 ottobre 2026

La build debug e la release eseguono 258 test JVM senza errori; i due nuovi casi verificano aggregazione mensile, mesi vuoti, cambio anno e deduplicazione. I 40 controlli Node passano. Lint release conserva i 24 avvisi precedenti e non riporta errori. La build privata dalla fonte pulita `971e9237e7590bca6baaea8defc9544b43744f3b` verifica firma, HTTPS-only, manifest/DEX senza componenti di test e allineamento. La CI di questa fonte passa.

Quattro nuovi test nativi verificano Home/classifica/dettaglio, valori ELO e punteggi esatti, accesso obbligatorio e rimozione delle viste private quando bloccate, testo Compose a 2×, riepiloghi incompleti o falliti distinti da zero e azioni sui soli commenti propri. Le catture sono in `.work/ui-051-screenshots`; usano dati sintetici e copertine sostitutive nelle fixture, senza un nuovo login remoto.

Il run completo su API 35 esegue 94 casi: 89 successi, tre fallimenti e due assunzioni. I fallimenti riguardano i driver dell'editor: target lazy ricomposto durante il tocco con IME, lettura del writer prima della conferma e chiusura Activity in timeout. Il driver ora termina la sessione di input prima di portare il pulsante in vista, ne verifica la visibilità e attende la conferma prima di controllare il comando. Il cleanup chiude l'input prima dell'Activity. Le due classi coinvolte vengono ripetute integralmente: **27 successi in 474,047 secondi**. L'APK applicativo è identico nei due run; cambia soltanto l'APK test. Il totale distinto è **92 casi riusciti e due assunzioni**, senza casi falliti rimasti aperti. Non è un singolo run completo verde. Dettagli per caso, hash e log: [UI validation](evidence/private-051-ui-validation.json).

Le due assunzioni sono `LiveBackendSmokeTest` e `PlayEditorRpcRecoveryTest`, che richiedono rispettivamente credenziali remote esplicite e il driver HTTP locale dedicato. Non sono conteggiate come successi della 0.5.1. Le prove remote e HTTP delle versioni precedenti restano documentate nei rispettivi checkpoint.

Il PC ha inizialmente mostrato ANR di System UI/launcher, che coprivano tastiera e catture frame, e timeout del processo di instrumentation prima dei test. Il run finale usa il solo AVD dedicato `KingScoreUi05135`, API 35, 384×832 a 160 dpi, GPU host e pacchetti ART precompilati (`cmd package compile -m speed -f`). Il vecchio AVD dedicato è arrestato; l'emulatore preesistente dell'utente non viene modificato. Le prove frame/tastiera passano nel run completo finale. Questi tempi descrivono i comandi sul PC e non le prestazioni di un telefono.

L'[aggiornamento firmato](evidence/private-051-upgrade-validation.json) 15 → 16 passa sullo stesso AVD: firma diversa rifiutata, UID e registri partite/commenti cifrati conservati, run-as negato, avvio offline bloccato, sessione vuota al login, disconnessione e riconnessione live. Il runner usa `-CompileEmulatorPackages`, senza modificare gli APK o le asserzioni di sicurezza. Radio ripristinati, catture della release ispezionate. Non sono inserite credenziali o contattati database remoti dall'app in questa prova. Il telefono fisico e un nuovo collaudo remoto della 0.5.1 non sono verificati.
