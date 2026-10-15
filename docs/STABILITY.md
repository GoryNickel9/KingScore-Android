# Stabile privata corrente 0.6.3/code 21

## 0.6.3/code 21 — Campi obbligatori

APK: `.work/releases/KingScore-0.6.3-21.apk`, 14.560.738 byte. SHA-256 `e65be7b90316d44b98f83e9720bf7a02dfccf4ec3cdba445f552f68e4b6e70b8`. Fonte pulita `6f5f908b5f99a110c14f0762747019925860540b`; stesso certificato. Installare sopra la 0.6.0 senza disinstallarla; aggiornamento 18 → 21 verificato.

Creazione e modifica richiedono durata intera di almeno un minuto e almeno un vincitore nelle competitive. Più vincitori restano validi; le cooperative perse non richiedono vincitori. Vecchie partite con campi mancanti restano leggibili e, se modificabili secondo l'API, completabili prima del salvataggio. Eliminazione e recupero delle richieste pregresse conservano le protezioni esistenti. [Regole](PLAY_EDITOR_REQUIREMENTS.md).

| Controllo | Risultato |
| --- | --- |
| JVM debug/release | 339 test per variante, zero fallimenti |
| Lint | Zero errori; 16 avvisi debug e 16 release |
| Contratti Node | 41 passati |
| UI interessata | Singolo run verde di 57 casi su API 31; editor, navigazione, Statistiche, pareggi e accesso |
| Aggiornamento 18 → 21 | Stesso UID e registri cifrati conservati; firma estranea rifiutata; run-as negato |
| Gate release | Login con sessione vuota, blocco offline e disconnessione/riconnessione verificati |
| CI della fonte | [PASS sul commit 6f5f908](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38821149282) |

Sono due emulatori PC API 31 distinti, con ART precompilato; nessuna misura d'avvio a freddo. Le prove API 35 della 0.6.2 restano un checkpoint precedente. Non sono ripetute la suite UI integrale, le integrazioni HTTP/morte del processo sul PC o un nuovo login remoto. Telefoni fisici non verificati. Dati sintetici e probe rimossi, impostazioni ripristinate ed emulatori arrestati.

Statistiche conserva la rimozione di Win Rate per Gioco e di tutti i grafici. Login, Internet e compatibilità ELO v2 restano attivi. Questa patch non modifica web, Vercel o Supabase: anche le RPC devono applicare le regole obbligatorie. I DTO V1 restano compatibili con dati storici e registri cifrati, senza riscrivere richieste già inviate. Aggiornamenti APK manuali.

Evidenze: [build](evidence/private-063-build.json), [UI](evidence/private-063-ui-validation.json), [aggiornamento](evidence/private-063-upgrade-validation.json), [qualifica](evidence/private-063-release-validation.json). Il manifest iniziale di build resta candidato; la qualifica promuove lo stesso checksum.

# Checkpoint precedente 0.6.2/code 20

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

# Checkpoint precedente 0.6.1/code 19

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

# Checkpoint precedente 0.6.0/code 18

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

# Checkpoint precedente 0.5.2/code 17

La 0.5.2 è la baseline dell'aggiornamento alla 0.6.0. APK: `.work/releases/KingScore-0.5.2-17.apk`, 14.527.970 byte. SHA-256: `20a6a4867119ea43a3aca00ddb9c9b3ceb830fe1d6b93de568d3168efe720205`. Fonte pulita: `ca4908f16f2400a87c7276907dedc61ee47f01a4`. Il certificato SHA-256 resta `b00fd08085ba373e9dde52066b3e8fd85b17b94f4b87dc5cd50ddc55d06a294b`: quella versione si installava sopra la 0.5.1 senza disinstallarla.

L'implementazione comprende le tre schermate Partite, Statistiche e Giocatore nello stile approvato, card con tutti i partecipanti/punti e badge commenti, menu ricercabili e pannello Filtri. Home mette i preferiti prima delle ultime partite e rimuove Classifiche ELO; Rating ELO rimuove il dialogo dello storico punteggio. L'aggiornamento dei dati avviene trascinando verso il basso dalla cima, oppure con l'azione accessibile; restano i comandi di retry e recupero degli esiti incerti.

Home e Statistiche condividono il riepilogo della sessione in RAM. La lettura statistica richiede pagine da 500 con i soli campi necessari, fino alla pagina vuota; ELO riusa la pubblicazione letta mantenendo il controllo online della sessione. La fixture da 483 partite richiede due letture dati invece delle dieci precedenti, escluse Auth e cataloghi. Primo accesso e refresh richiedono comunque il server; la latenza di produzione e le prestazioni su telefono non sono misurate.

| Verifica della code 17 | Risultato |
| --- | --- |
| JVM, lint e build | 301 test sia debug sia release, zero fallimenti; lint: zero errori, 24 avvisi debug e 16 release; 40 controlli Node passati |
| UI nativa | 106 casi distinti riusciti e due integrazioni escluse su 108 casi; cinque fallimenti iniziali chiusi con 31 prove delle classi coinvolte, inclusi due nuovi casi |
| Font di sistema a 2× | Quattro prove menu e quattro prove delle nuove schermate passate |
| CI della fonte dell'APK | [PASS sul commit ca4908f](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38774226906) |
| Aggiornamento 16 → 17 | PASS: stesso UID, registri cifrati conservati, firma diversa rifiutata, run-as negato |
| Login e rete della release | PASS: login a sessione vuota, avvio offline e disconnessione bloccati, riconnessione verificata |
| Telefono fisico e nuovo collaudo remoto | Non verificati |

La qualifica nativa combina il run completo iniziale da 106 casi, con 99 successi, cinque fallimenti e due assunzioni, e la ripetizione da 31 casi delle classi coinvolte sulla fonte finale. Non è un singolo run completo verde. L'ultima modifica produttiva riguarda il blocco del refresh del dettaglio Gioco durante il caricamento; la sua classe completa e `MetadataRefreshUiTest` sono ripetute con successo. Le altre correzioni riguardano il wrapper dei gesti e i selettori dei driver. Dettagli per caso nel report UI.

Evidenze: [build](evidence/private-052-build.json), [UI](evidence/private-052-ui-validation.json), [aggiornamento](evidence/private-052-upgrade-validation.json), [qualifica](evidence/private-052-release-validation.json), [letture dati](evidence/private-052-data-reads-validation.json) e [misure PC](evidence/private-052-pc-performance.json). Il manifest iniziale di build resta candidato; il report di qualifica registra la promozione dello stesso APK.

Le prove usano fixture ed emulatore API 35 sul PC, con ART precompilato. Le misure di scorrimento e memoria riguardano l'APK debug e repository sintetici, senza latenza di rete. Le due integrazioni escluse richiedono account remoti e il driver HTTP locale dedicato. Non è stato ripetuto il percorso autenticato sul backend reale della 0.5.0. Impostazioni dell'AVD ripristinate, dati sintetici cancellati, pacchetto test rimosso ed emulatore dedicato arrestato.

Login obbligatorio, blocco senza Internet, commenti propri e permessi admin restano nel perimetro. Formula ELO, sito e cloud non sono modificati. Gli aggiornamenti dell'APK restano manuali.

# Stabile privata 0.5.1: aggiornamento delle tre schermate

La 0.5.1, versionCode 16, implementa le proposte approvate per Home, classifica e dettaglio partita. Login e Internet restano obbligatori; gestione admin e commenti propri conservano i permessi precedenti. Formula ELO, web, Vercel e Supabase non cambiano in questo aggiornamento.

APK: `.work/releases/KingScore-0.5.1-16.apk`, 14.511.586 byte. SHA-256: `90306da33c1a75ed24607c7828e7f59c5c645fcbf127164a71c61aad4586c960`. Fonte pulita: `971e9237e7590bca6baaea8defc9544b43744f3b`. Il certificato è quello della 0.5.0; installare sopra la versione precedente senza disinstallarla. Gli aggiornamenti restano manuali.

| Verifica | Risultato |
| --- | --- |
| Release | 258 test JVM riusciti; lint: 0 errori e 24 avvisi preesistenti; firma e pacchetto senza hook di test verificati |
| UI nativa | 92 casi distinti riusciti, due integrazioni condizionali escluse; font fino a 2×. Il run da 94 casi aveva tre fallimenti dei driver, risolti con una ripetizione da 27 casi delle classi editor |
| Aggiornamento 15 → 16 | PASS: UID e registri cifrati conservati, firma diversa rifiutata, run-as negato |
| Login e rete della release | PASS: sessione vuota mostra login; avvio offline e disconnessione bloccati; riconnessione ripristina login |
| CI della fonte | [PASS](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38747712245) |

Evidenze: [build](evidence/private-051-build.json), [UI](evidence/private-051-ui-validation.json), [aggiornamento](evidence/private-051-upgrade-validation.json), [qualifica](evidence/private-051-release-validation.json). Le prove usano l'emulatore API 35 sul PC e dati sintetici; nessun telefono fisico è verificato. I pacchetti ART sono precompilati per evitare il timeout di avvio dell'instrumentation sul PC: questi risultati non misurano l'avvio a freddo su un telefono. Il collaudo remoto della 0.5.0 resta storico e non viene attribuito alla 0.5.1.

# Prima stabile privata 0.5.0: prove storiche

La prima stabile privata 0.5.0, versionCode 15, è costruita e verificata dalla fonte pulita `df39f5b3bc1fad0e11991784864421193c983225`. L'editor admin è abilitato e usa il backend attivato il 15 ottobre 2026. Il sito pubblicato usa gli stessi salvataggi atomici e la stessa pubblicazione globale ELO. Stato del rollout: [LIVE_ROLLOUT.md](LIVE_ROLLOUT.md).

Il perimetro comprende consultazione, commenti propri e gestione atomica di tutte le partite per gli amministratori. Login e Internet sono obbligatori. L'app non offre registrazione, recupero o modifica credenziali, modifica avatar, uso offline o ricalcolo ELO.

| Area | Risultato verificato | Stato residuo |
| --- | --- | --- |
| Backend e sito | RPC atomiche installate, writer web attivati, vecchie scritture ritirate | Interfaccia browser del sito non verificata |
| Classifiche ELO | Pubblicazione globale condivisa fra tabelle e storici; formula invariata; risultato originale identico dopo cleanup | Quattro pubblicazioni temporanee eliminate; iniziale e corrente conservate |
| Client Android | APK firmato, MainActivity e account reali: `PASS_FULL` in 38,037 secondi su API 35 | Prove su emulatore del PC, senza certificazione di telefoni fisici |
| Verifiche automatiche | 256 test JVM passati; lint: 0 errori e 24 avvisi preesistenti. CI della fonte `df39f5b` passata | [Run dei report e della documentazione](https://github.com/GoryNickel9/KingScore-Android/actions/workflows/android-ci.yml) |
| Auth e permessi remoti | Cinque casi SDK passati; account e dati temporanei rimossi; regola originale delle registrazioni ripristinata esattamente e registrazioni chiuse | Collaudo remoto limitato al perimetro autorizzato |
| APK e aggiornamento | Code 15 firmata con la chiave RC4; aggiornamento code 12 → 15 passato, dati cifrati conservati e blocco offline verificato | Conservare la chiave privata per gli aggiornamenti |

APK: `.work/releases/KingScore-0.5.0-15.apk`. SHA-256: `fd196d0e75f452c24a3cb6e0223732a8ab1f5f02e598f2a808dbfe0a269ff509`. Certificato SHA-256: `b00fd08085ba373e9dde52066b3e8fd85b17b94f4b87dc5cd50ddc55d06a294b`.

Le prove della [build firmata code 15](evidence/private-050-build.json), dell'[aggiornamento RC4 → code 15](evidence/private-050-upgrade-validation.json), del [percorso UI reale](evidence/private-050-ui-validation.json) e del [rilascio](evidence/private-050-release-validation.json) identificano APK e fonte. Il test di aggiornamento usa dati sintetici; il percorso UI successivo usa account remoti temporanei. La [CI della fonte dell'APK](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38725386747) è passata; non è il run del successivo commit dei report.

Il percorso UI code 15 verifica login admin, ELO globale e storico, ricerca e dettaglio partita, modifica atomica di una partita altrui, logout, login ordinario senza editor, creazione/modifica/eliminazione di un commento proprio, blocco senza Internet, ripresa della connessione e logout finale. I crash TLS dei precedenti APK code 13/14 sono corretti nei pool RPC e immagini. `AccountImagesCloseTest` fallisce sulla code 14 e passa sulla code 15; è corretta anche la race che mostrava il login prima della conclusione del logout.

## Chiusura della consegna

Account e dati temporanei sono rimossi; il [report di cleanup](evidence/live-backend-cleanup-validation.json) verifica il risultato ELO originale e i conteggi iniziali: 483 partite, 1.359 partecipazioni, 18 giochi, 6 giocatori e 7 commenti. Le registrazioni sono chiuse. Revisione e UUID della pubblicazione avanzano normalmente, senza riavvolgere i contatori.

La pulizia elimina anche quattro pubblicazioni intermedie, conservando quelle iniziale e corrente. Le verifiche finali rilevano zero account, ricevute e pubblicazioni di prova. I commit successivi raccolgono report e documentazione e sono verificati dalla CI Android; l'APK resta quello costruito dalla fonte immutabile `df39f5b3bc1fad0e11991784864421193c983225`.

## Journal dei salvataggi

`PlayWriteJournal` conserva la richiesta esatta prima dell'invio e la conferma prima di mostrare il successo. `EncryptedPendingPlayStore` usa AES-GCM, Android Keystore e file atomici esclusi dai backup. Non memorizza endpoint o credenziali. Il factory di produzione è collegato all'Application e l'editor è abilitato nella 0.5.0; ogni account ha il proprio journal.

La perdita della connessione non genera una nuova richiesta automatica. Il recupero esplicito verifica rete, identità e ruolo admin e usa UUID e corpo originali. Una conferma già salvata si ripristina senza ripetere il POST. Negazione del ruolo o dell'accesso non elimina una richiesta incerta. Record corrotti, incompatibili o non salvabili bloccano nuove scritture.

La chiusura del client rimuove la copia in memoria e conserva il record cifrato per il suo account. Rotazione e ricreazione conservano la bozza; perdita di accesso, logout e cambio account rimuovono la sessione precedente. Le prove locali comprendono arresti del processo e ripristino cifrato: [EDITOR_RPC_PC_QA.md](EDITOR_RPC_PC_QA.md). Queste garanzie non coprono perdita del dispositivo, cancellazione dei dati dell'app o guasti del filesystem.

## Recupero delle letture e limiti

Le risposte delle nuove RPC sono limitate a 32 KiB per le ricevute, 4 MiB per lo snapshot dell'editor e 16 MiB per la pubblicazione ELO. Il client rifiuta risposte troppo grandi o incoerenti. Classifica e storico condividono la stessa generazione; uno stato superato richiede una nuova lettura, senza ricalcoli dall'app. Il polling di aggiornamento si ferma quando la schermata perde il ciclo di vita attivo.

Le prove native e le misure riguardano emulatori sul PC. Non certificano prestazioni su telefoni fisici. Il precedente reader REST generico non è stato esteso con i nuovi limiti delle risposte RPC. Il client rifiuta rating oltre l'intervallo Int32; il dataset sintetico da 1.200 partite verifica paginazione e navigazione, non definisce una capienza massima.

Le prove RC e i documenti delle precedenti preparazioni annullate restano riferimenti storici. Le API e il codice distribuiti oggi sono descritti in [LIVE_ROLLOUT.md](LIVE_ROLLOUT.md).
