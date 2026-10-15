# Stato della candidata 0.5.2/code 17

La 0.5.2 è in verifica. La stabile consegnata resta la 0.5.1/code 16, descritta nella sezione seguente. Non sono ancora attribuiti alla candidata esiti positivi di test, CI, build firmata o aggiornamento.

L'implementazione comprende le tre schermate Partite, Statistiche e Giocatore nello stile approvato, card con tutti i partecipanti/punti e badge commenti, menu ricercabili e pannello Filtri. Home mette i preferiti prima delle ultime partite e rimuove Classifiche ELO; Rating ELO rimuove il dialogo dello storico punteggio. L'aggiornamento dei dati avviene trascinando verso il basso dalla cima, oppure con l'azione accessibile; restano i comandi di retry e recupero degli esiti incerti.

Home e Statistiche condividono il riepilogo della sessione in RAM. La lettura statistica richiede pagine da 500 con i soli campi necessari, fino alla pagina vuota; ELO riusa la pubblicazione letta mantenendo il controllo online della sessione. Nessuna misura su telefono dimostra ancora il tempo percepito di questo aggiornamento. Primo accesso, refresh e dati non disponibili richiedono comunque il server.

| Verifica richiesta per code 17 | Stato |
| --- | --- |
| JVM, lint e build | Da qualificare sul codice finale |
| UI nativa, gesture, menu, font grandi e commenti | Da qualificare con fixture sul PC |
| CI GitHub del commit di consegna | Da verificare |
| Firma e aggiornamento dalla 0.5.1/code 16 | Da verificare con dati cifrati conservati |
| Telefono fisico e nuovo collaudo remoto | Non verificati |

Login obbligatorio, blocco senza Internet, commenti propri e permessi admin restano nel perimetro. Nessun ricalcolo ELO, modifica cloud o aggiornamento automatico dell'APK è introdotto dalla candidata. La promozione dovrà identificare il checksum e la fonte dell'APK effettivamente collaudato.

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
