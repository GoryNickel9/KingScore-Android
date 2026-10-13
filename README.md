# KingScore Android

App Android nativa in Kotlin e Jetpack Compose, con il backend Supabase di King-Score. La build 0.4.0 comprende Home, storico paginato con ricerca e filtri, dettagli partita/gioco/giocatore, copertine e avatar in lettura, creazione/modifica/eliminazione dei commenti propri, statistiche essenziali e classifiche/storico ELO. Login e Internet rimangono obbligatori. L'editor partite admin richiede ancora le RPC atomiche della fase 4.

## Perimetro confermato

1. Login con account esistente e connessione Internet obbligatori.
2. Consultazione di partite, statistiche e rating ELO; nessun calcolo o comando di ricalcolo ELO.
3. Commenti propri per gli utenti; aggiunta, modifica ed eliminazione partite per gli admin secondo i permessi del sito.
4. Nessuna registrazione, recupero password, modifica email/password/avatar o modalità offline.
5. Distribuzione iniziale tramite APK privato firmato.

## Documentazione

| File | Contenuto |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | Fasi, stime e stato corrente |
| [docs/SUPABASE_AUDIT.md](docs/SUPABASE_AUDIT.md) | Schema reale, RLS e limiti delle verifiche |
| [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md) | API esistenti, DTO e RPC proposte |
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
| [docs/PHASE4_ADMIN_RPC.md](docs/PHASE4_ADMIN_RPC.md) | RPC admin preparate, test locali e requisiti di rollout |
| [docs/PHASE4_WEB_WRITERS.md](docs/PHASE4_WEB_WRITERS.md) | Writer web e import BGA adeguati, precisione e attivazione controllata |

I modelli in `contracts/v1/Rows.kt` derivano dai tipi SQL e dalla nullabilità del catalogo remoto. `Commands.kt` definisce input e invarianti. Gli adattatori JSON compilati nell'app conservano NUMERIC, date e campi nullable; il client Auth verifica la sessione online.

## Build locale

Con Android SDK 36 e JDK 17 o successivo disponibili:

```powershell
./scripts/configure-local.ps1
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Lo script riusa URL e chiave pubblica del repository web; le configurazioni locali sono escluse da Git. Per una configurazione manuale vedere [docs/PHASE2.md](docs/PHASE2.md). APK: `app/build/outputs/apk/debug/app-debug.apk`, firmato con la chiave di debug. La release privata con firma definitiva appartiene alla fase 5.

Per verificare le evidenze salvate: `node --test scripts/check-evidence.test.mjs`. Per compilare ed eseguire i test Kotlin: `./scripts/check-contracts.ps1` con Kotlin e Java 17 o successivo disponibili.

Le nuove RPC per le partite sono specificate ma non esistono ancora sul backend. Non implementare un ripiego con scritture separate di partita e punteggi.
