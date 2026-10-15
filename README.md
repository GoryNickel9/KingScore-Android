# KingScore Android

App Android nativa in Kotlin e Jetpack Compose, con il backend Supabase di KingScore. La **stabile privata corrente è la 0.6.0/code 18**. Comprende Home, storico paginato con ricerca e filtri, dettagli partita/gioco/giocatore, immagini in lettura, commenti propri e gestione atomica di tutte le partite per gli amministratori. Login e Internet sono obbligatori.

La Home riceve il riepilogo con una RPC senza scaricare tutto lo storico. Statistiche segue le tre sezioni web: win rate personale per gioco, ELO generale e schede per gioco con medie, copertura e giocatori espandibili. I grafici caricano finestre di 100 partite; tocco, slider e legenda consentono di consultarli. NULL è escluso dalle medie, zero resta un campione e il win rate considera le proprie partecipazioni.

Le cache restano in RAM nella sessione online e si invalidano dopo nuove revisioni osservate o salvataggi. Dettagli e grafici si caricano separatamente. Prima apertura e refresh richiedono ancora il server; queste modifiche non introducono uso offline né garantiscono tempi istantanei su ogni rete. Rating ELO conserva il lettore della pubblicazione esistente e la formula rimane invariata.

La code 18 supera 323 test JVM sia debug sia release, lint senza errori, 41 controlli Node e un singolo run nativo completo con 111 successi e due integrazioni escluse. Aggiornamento dalla 0.5.2 con stessa firma, UID e dati cifrati conservati. [CI della fonte](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38794486985) passata. APK, checksum e limiti: [STABILITY.md](docs/STABILITY.md).

Il SQL approvato è applicato al progetto ScoreKing verificato. Dati, pubblicazione ELO, policy e funzioni precedenti non cambiano; nessuna modifica web o Vercel. Letture reali con ruolo authenticated e decoder Android passati; non è stato ripetuto un login GoTrue. Telefoni fisici non verificati. Gli aggiornamenti APK restano manuali.

## Perimetro confermato

1. Login con account esistente e connessione Internet obbligatori.
2. Consultazione di partite, statistiche e rating ELO; nessun calcolo o comando di ricalcolo ELO.
3. Commenti propri per gli utenti; creazione, modifica ed eliminazione di tutte le partite per gli amministratori.
4. Nessuna registrazione, recupero password, modifica email/password/avatar o modalità offline.
5. Distribuzione iniziale tramite APK privato firmato.

## Documentazione

| File | Contenuto |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | Fasi, stime e stato corrente |
| [docs/STABILITY.md](docs/STABILITY.md) | Obiettivo stabile, criteri di uscita e lavoro autonomo residuo |
| [docs/LIVE_ROLLOUT.md](docs/LIVE_ROLLOUT.md) | Attivazione backend e sito del 15 ottobre, verifiche e collaudo finale |
| [docs/PRIVATE_RELEASE.md](docs/PRIVATE_RELEASE.md) | Firma privata, chiave locale, checksum, installazione e aggiornamento |
| [docs/ACTIVITY_LIFECYCLE.md](docs/ACTIVITY_LIFECYCLE.md) | Rotazione, ricreazione e pulizia della sessione |
| [CHANGELOG.md](CHANGELOG.md) | Modifiche e cronologia dei rilasci |
| [API riepilogo](docs/proposals/SUMMARY_API_V1.md) | SQL completo, permessi, contratti per Statistiche e collaudi locale/ScoreKing |
| [Riepilogo e Statistiche 0.6.0](docs/SUMMARY_STATISTICS.md) | API applicate a ScoreKing, integrazione Android e comportamento dei grafici |
| [docs/SUPABASE_AUDIT.md](docs/SUPABASE_AUDIT.md) | Schema reale, RLS e limiti delle verifiche |
| [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md) | API esistenti, DTO e RPC proposte |
| [docs/PLAY_SAVE_CONTRACT.md](docs/PLAY_SAVE_CONTRACT.md) | Contratti V1 e prove locali dei salvataggi |
| [docs/WRITER_INTEGRATION.md](docs/WRITER_INTEGRATION.md) | Piano dei writer web/server, test locali e dipendenze dell'editor Android |
| [docs/PHASE4_ANDROID_RPC_CLIENT.md](docs/PHASE4_ANDROID_RPC_CLIENT.md) | Checkpoint storico della preparazione del client RPC |
| [docs/PLAY_EDITOR_STATE.md](docs/PLAY_EDITOR_STATE.md) | Controller e schermata editor: bozze, conflitti e recupero; checkpoint locali |
| [local-tests/README.md](local-tests/README.md) | PostgreSQL e HTTP sul PC, difetti riprodotti e 21 casi atomici |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Moduli e flusso dati Android |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Decisioni tecniche e vincoli |
| [docs/TESTING.md](docs/TESTING.md) | Comandi di verifica e controlli ancora necessari |
| [docs/PHASE2.md](docs/PHASE2.md) | Implementazione, configurazione e limiti dell'APK di anteprima |
| [docs/PHASE3.md](docs/PHASE3.md) | Storico, dettaglio, cursori e cancellazione dei dati alla perdita di accesso |
| [docs/PHASE3_OVERVIEW.md](docs/PHASE3_OVERVIEW.md) | Home, metriche, archivio generale e rating pubblicati |
| [docs/PHASE3_SEARCH.md](docs/PHASE3_SEARCH.md) | Ricerca, filtri server, date inclusive e cataloghi paginati |
| [docs/PHASE3_GAME.md](docs/PHASE3_GAME.md) | Dettaglio gioco, storico dedicato e ritorno dalla partita |
| [docs/PHASE3_PLAYER.md](docs/PHASE3_PLAYER.md) | Dettaglio giocatore, identità account e storico del partecipante |
| [docs/PHASE3_IMAGES.md](docs/PHASE3_IMAGES.md) | Immagini, isolamento per account, limiti e retry |
| [docs/PHASE4_COMMENTS.md](docs/PHASE4_COMMENTS.md) | Commenti propri, ownership, errori e riconciliazione |
| [docs/PHASE4_ADMIN_RPC.md](docs/PHASE4_ADMIN_RPC.md) | Preparazione RPC annullata: risultati storici |
| [docs/PHASE4_WEB_WRITERS.md](docs/PHASE4_WEB_WRITERS.md) | Adeguamento web annullato: risultati storici |

I modelli in `contracts/v1/Rows.kt` derivano dai tipi SQL e dalla nullabilità del catalogo remoto. `Commands.kt` definisce input e invarianti. Gli adattatori JSON compilati nell'app conservano NUMERIC, date e campi nullable; il client Auth verifica la sessione online.

## Build locale

Con Android SDK 36 e JDK 17 o successivo disponibili:

```powershell
# Configurare prima local.properties e supabase.properties in questa repo.
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Lo script di configurazione iniziale legge URL e chiave pubblica dal repository web; le configurazioni locali sono escluse da Git. Nel perimetro corrente usare la configurazione già presente oppure impostare manualmente SUPABASE_URL e SUPABASE_PUBLIC_KEY: [docs/PHASE2.md](docs/PHASE2.md). APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

La build privata firmata usa `scripts/build-private-apk.ps1`; istruzioni e checkpoint precedenti sono in [docs/PRIVATE_RELEASE.md](docs/PRIVATE_RELEASE.md), lo stato corrente in [docs/STABILITY.md](docs/STABILITY.md). Gli APK sono in `.work/releases`, esclusi da Git. La chiave privata rimane in `.work/signing` e deve essere conservata per gli aggiornamenti. La CI Android è riattivata e verifica contratti Node, test JVM, lint e build APK; la [CI della fonte della 0.6.0](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38794486985) è passata.

Per verificare le evidenze salvate: `node --test scripts/check-evidence.test.mjs`. Per compilare ed eseguire i test Kotlin: `./scripts/check-contracts.ps1` con Kotlin e Java 17 o successivo disponibili.

Le RPC atomiche sono installate sul backend reale e l'editor Android è abilitato nella 0.5.0. Eseguire `pwsh -NoProfile -File scripts/test-local-play-saves.ps1 -Atomic` per riprodurre il collaudo locale. L'app non ripiega su scritture separate di partita e punteggi.
