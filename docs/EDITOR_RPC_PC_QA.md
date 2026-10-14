# Collaudo editor, HTTP locale e journal cifrato

Il protocollo collega la navigazione Android, SupabaseReader, SupabasePlayWrites, il trasporto senza retry e EncryptedPendingPlayStore alle RPC di un cluster PostgreSQL/PostgREST isolato sul PC. Auth è simulata: il bridge emette JWT sintetici e verifica la firma per il suo endpoint utente. PostgREST verifica gli stessi JWT e le RPC verificano il ruolo nei dati sintetici. Il protocollo non prova password, GoTrue, TLS o permessi del progetto remoto.

## Perimetro e prerequisiti

Il cluster si chiama kingscore_local_tests e deve avere il marker isolated-fixture-v1. PostgreSQL, PostgREST e bridge ascoltano soltanto su 127.0.0.1. Il percorso del SQL preparato deve essere nella directory .work della repo; il runner ne congela la copia e controlla l'hash a fine prova. Questo modo non ripete i 16 profili o i 21 casi di accettazione e non installa l'adattatore sintetico delle posizioni.

Usare un emulatore dedicato chiamato KingScoreStable35, avviato dalla definizione locale .work/avd-definitions, con avvio terminato. Il driver controlla il nome prima di installare APK o fermare processi. Non usare l'emulatore personale già aperto o un telefono. Sono richiesti PostgreSQL, il binario PostgREST verificato dal runner, Node, ADB e i due APK debug compilati.

Il solo manifest debug consente HTTP per 127.0.0.1, senza sottodomini. La configurazione predefinita continua a rifiutare cleartext e il source set release non contiene l'eccezione. ADB reverse inoltra una sola porta locale e la rimuove a fine prova. L'Activity usata dalla prova è debug e non esportata. ATOMIC_PLAY_EDITOR_ENABLED rimane false; la prova inietta i client nella navigazione e non rende disponibile il factory di produzione.

## Esecuzione

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-daemon
pwsh -NoProfile -File scripts/test-local-play-saves.ps1 -Atomic -AndroidEditor -WebMigrationDirectory .work/King-Score-atomic/scripts/atomic-play-migrations -Serial emulator-5578
```

Il PC può richiedere il workaround Java locale già documentato nei log; non fa parte dell'APK. L'emulatore va avviato prima del secondo comando. Il runner crea una nuova directory sotto .work/local-play-tests e conserva log, query e report. Le chiavi dei JWT sono generate per il cluster; non usare credenziali Supabase. I token del client test restano in MemorySessionManager, così non possono essere caricati dal client di produzione al successivo avvio.

## Tre processi Android

1. Il primo processo crea una partita dalla UI con il punteggio esatto 9007199254740993.125. Il server locale committa partita, punteggio e receipt, ma trattiene la risposta. Android deve avere salvato il comando nel journal cifrato e impedire nuovi invii. Il PC controlla PID vivo e connessione HTTP ancora pendente, quindi esegue force-stop.
2. Il secondo processo deve caricare il comando cifrato per lo stesso account. Un altro amministratore non può ripristinarlo o inviarlo. Il recupero esplicito reinvia corpo e UUID originali, riceve replayed=true e conserva la conferma cifrata. Il PC verifica un solo insieme di righe e ferma anche questo PID dopo la conferma.
3. Il terzo processo deve mostrare la conferma conservata. Recuperarla non produce un'altra mutazione HTTP. Dalla UI modifica poi il punteggio e le note, legge il dettaglio aggiornato ed elimina la partita con conferma. Il database deve rimanere senza partita/punteggio e con tre receipt: creazione, modifica ed eliminazione.

I primi due comandi instrumentation terminano volutamente quando il PC ferma il processo. Non sono test JUnit superati: il driver accetta quelle terminazioni soltanto dopo i rispettivi checkpoint e la verifica dei PID. Il terzo comando deve terminare con OK. Un errore precedente al checkpoint resta FAIL, anche se il database ha già committato.

## Evidenza e limiti

Il collaudo del 15 ottobre 2026 termina PASS nella directory .work/local-play-tests/9f8193086180497b92c546f25d3f66e6. I tre PID sono 4672, 4761 e 4839. Creazione e replay conservano UUID e hash del corpo; modifica e cancellazione lasciano zero partite, zero punteggi e tre receipt. Entrambi i report confermano PASS e l'arresto dei servizi propri. I tentativi precedenti restano conservati come FAIL, anche quelli che avevano raggiunto solo alcuni checkpoint. Risultati: [editor-rpc-pc-validation.json](evidence/editor-rpc-pc-validation.json).

La successiva suite generale ha due fallimenti: nessun frame raccolto durante i gesti reali e timeout della tastiera. Anche la ripetizione mirata conserva quei fallimenti. La diagnosi registra mCurrentFocus su «Application Not Responding: com.android.systemui» e la schermata .work/editor-rpc-emulator-screen.png: una finestra del sistema copre l'app. Il riavvio riguarda soltanto KingScoreStable35. Questi tentativi rimangono FAIL e non sostituiscono l'esito del protocollo HTTP locale.

Dopo il riavvio, lo stesso APK supera la suite completa: 87 casi passati e un caso condizionale saltato, quello del protocollo HTTP già eseguito separatamente. Passano anche le probe dei registri partite/commenti con PID diversi e le impostazioni vengono ripristinate. Report PASS: .work/pending-recovery/b14fa328306948b5b4cd964f938ddb89/report.json. La correzione riguarda lo stato dell'emulatore; non sono state indebolite le asserzioni o modificate le schermate.

android-editor-report.json registra PID, terminazioni attese, hash dei corpi, risposte RPC, letture, contatori e chiusura dei servizi. report.json verifica anche lo spegnimento del cluster PostgreSQL. I log non sono una prova di successo finché entrambi i report non terminano PASS. Il driver rifiuta scritture REST dirette e RPC estranee al percorso Android.

Il monitor di rete della prova è iniettato; non certifica Internet reale o captive portal. Auth simulata e database locale non certificano la disponibilità delle API remote o il passaggio dei writer web/server. La prova non crea una release firmata e non abilita l'editor nell'app distribuita. Una migrazione reale e la chiusura degli accessi legacy richiedono ancora la decisione concordata prima di interventi esterni.
