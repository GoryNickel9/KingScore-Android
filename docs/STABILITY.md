# Prima stabile privata 0.5.0: stato e criteri

La candidata 0.5.0, versionCode 15, è in preparazione, con editor admin abilitato e backend attivato il 15 ottobre 2026. Il sito pubblicato usa gli stessi salvataggi atomici e la stessa pubblicazione globale ELO. L'ultimo APK costruito è la code 14, non stabile; le sue prove non certificano la code 15. Stato del rollout: [LIVE_ROLLOUT.md](LIVE_ROLLOUT.md).

Il perimetro comprende consultazione, commenti propri e gestione atomica di tutte le partite per gli amministratori. Login e Internet sono obbligatori. L'app non offre registrazione, recupero o modifica credenziali, modifica avatar, uso offline o ricalcolo ELO.

| Area | Risultato verificato | Stato residuo |
| --- | --- | --- |
| Backend e sito | RPC atomiche installate, writer web attivati, vecchie scritture ritirate | Nessun blocco di disponibilità API ancora aperto |
| Classifiche ELO | Pubblicazione globale condivisa fra tabelle e storici; formula invariata e risultato iniziale verificato all'installazione | Verificare il ritorno del risultato iniziale dopo il cleanup |
| Client Android | La UI della code 14 conferma login, ELO, ricerca, dettaglio e modifica admin reale | Correzione del crash immagini al logout e della race login nelle sorgenti code 15; ripetere la UI completa |
| Verifiche automatiche | Checkpoint code 14: 255 test JVM passati; lint: 0 errori e 24 avvisi preesistenti. Ultima suite con fixture su API 35: `OK (88 tests)` | Verifiche complete e CI della code 15 da concludere |
| Auth e permessi remoti | Cinque casi SDK con account temporanei passati; regola originale delle registrazioni ripristinata esattamente e registrazioni chiuse | Rimuovere account e dati temporanei |
| APK e aggiornamento | Precedente code 14 firmata dalla fonte pulita `dd9dd44`; aggiornamento RC4/code 12 → 14 passato, con dati cifrati e blocco offline verificati | Build, firma, checksum e aggiornamento della code 15 ancora da verificare |

SHA-256 dell'APK precedente code 14, non stabile: `ee5ca0b96af47b26a556da0a0e26eeb95ec97dba8bcf7d66a4bb0036a9afcb3b`. Nessun checksum code 15 è ancora disponibile.

I report precedenti della [build firmata code 14](evidence/private-050-build.json) e dell'[aggiornamento RC4 → code 14](evidence/private-050-upgrade-validation.json) riportano fonte, certificato, esclusione dei componenti di test e dati cifrati conservati. Non sono prove della code 15; la prova di aggiornamento non usa account remoti.

Il collaudo della code 13 ha rilevato il crash di chiusura TLS nel trasporto RPC, corretto nella code 14. La code 14 conferma anche il salvataggio admin reale, poi rileva lo stesso problema nel pool immagini durante il logout. Le sorgenti code 15 correggono il pool immagini e la race che mostrava il login prima della conclusione del logout. La ripetizione completa del percorso UI è ancora aperta; il successo dei test SDK non la sostituisce.

## Gate finali

1. Costruire e verificare la code 15 firmata, il suo aggiornamento dalla RC4 e la prova UI completa con MainActivity e backend reali: classifica globale, storico, modifica admin di una partita altrui, logout/login, commenti propri e blocco Internet. Registrare il perimetro effettivamente eseguito.
2. Rimuovere account, partite, cataloghi e commenti temporanei e verificare il ritorno del risultato ELO iniziale. Revisione e UUID della pubblicazione possono avanzare: devono coincidere i dati del risultato, senza riavvolgere i contatori.
3. Chiudere CI, commit e push della consegna; collegare APK, checksum e prove definitive prima di dichiarare la prima stabile privata.

## Journal dei salvataggi

`PlayWriteJournal` conserva la richiesta esatta prima dell'invio e la conferma prima di mostrare il successo. `EncryptedPendingPlayStore` usa AES-GCM, Android Keystore e file atomici esclusi dai backup. Non memorizza endpoint o credenziali. Il factory di produzione è collegato all'Application e l'editor è abilitato nella candidata; ogni account ha il proprio journal.

La perdita della connessione non genera una nuova richiesta automatica. Il recupero esplicito verifica rete, identità e ruolo admin e usa UUID e corpo originali. Una conferma già salvata si ripristina senza ripetere il POST. Negazione del ruolo o dell'accesso non elimina una richiesta incerta. Record corrotti, incompatibili o non salvabili bloccano nuove scritture.

La chiusura del client rimuove la copia in memoria e conserva il record cifrato per il suo account. Rotazione e ricreazione conservano la bozza; perdita di accesso, logout e cambio account rimuovono la sessione precedente. Le prove locali comprendono arresti del processo e ripristino cifrato: [EDITOR_RPC_PC_QA.md](EDITOR_RPC_PC_QA.md). Queste garanzie non coprono perdita del dispositivo, cancellazione dei dati dell'app o guasti del filesystem.

## Recupero delle letture e limiti

Le risposte delle nuove RPC sono limitate a 32 KiB per le ricevute, 4 MiB per lo snapshot dell'editor e 16 MiB per la pubblicazione ELO. Il client rifiuta risposte troppo grandi o incoerenti. Classifica e storico condividono la stessa generazione; uno stato superato richiede una nuova lettura, senza ricalcoli dall'app. Il polling di aggiornamento si ferma quando la schermata perde il ciclo di vita attivo.

Le prove native e le misure riguardano emulatori sul PC. Non certificano prestazioni su telefoni fisici. Il precedente reader REST generico non è stato esteso con i nuovi limiti delle risposte RPC. Il client rifiuta rating oltre l'intervallo Int32; il dataset sintetico da 1.200 partite verifica paginazione e navigazione, non definisce una capienza massima.

Le prove RC e i documenti delle precedenti preparazioni annullate restano riferimenti storici. Le API e il codice distribuiti oggi sono descritti in [LIVE_ROLLOUT.md](LIVE_ROLLOUT.md).
