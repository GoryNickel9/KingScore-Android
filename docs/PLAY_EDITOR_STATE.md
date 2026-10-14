# Controller dell'editor partite

`PlayEditorViewModel` gestisce il flusso dell'editor con repository e writer iniettati. Le destinazioni di creazione e gestione sono ora collegate alla navigazione, con accesso admin e disponibilità del writer obbligatori. L'Application prepara il factory con journal cifrato condiviso per account; ATOMIC_PLAY_EDITOR_ENABLED resta false, quindi MainActivity riceve un factory assente. Soltanto i test rendono raggiungibile l'editor con fixture.

## Bozza e validazione

`PlayDraft` conserva i valori digitati, anche se incompleti. Data e durata passano alla validazione soltanto al salvataggio; un punteggio vuoto diventa null, mentre zero resta zero. I decimali non usano Double. Il controller verifica gli ID nel catalogo caricato e applica anche i limiti del payload JSON prima di chiamare il writer.

Una modifica conserva ID dei punteggi, colore e ruolo. Proprietario, location e metadati di importazione restano nella snapshot di lettura; non vengono aggiunti al payload. Il salvataggio usa la revisione originale. Un secondo caricamento non scarta una bozza già aperta o riapre una creazione appena confermata.

La snapshot storica viene conservata per intero. Se il server dichiara la partita incompatibile, il controller non crea una bozza modificabile: non tronca i partecipanti e non converte winner o source null. La cancellazione rimane un'operazione distinta, con conferma e revisione, subordinata ai permessi server.

## Invarianti del flusso

| Condizione | Comportamento |
| --- | --- |
| Controller disabilitato o account non admin | Nessuna lettura dei cataloghi, del journal o delle RPC |
| Internet perso | Bozza e snapshot rimosse dalla memoria; accesso bloccato, nessuna ripresa automatica |
| Invio attivo | Secondo invio, cambiamenti alla bozza e cancellazione bloccati |
| Conflitto | Bozza conservata; nuova snapshot letta su richiesta esplicita |
| Accettazione della nuova snapshot | Scarta esplicitamente la bozza; nessuna fusione automatica dei vecchi ID con la nuova revisione |
| Esito incerto | Blocca nuove scritture e cambiamenti; recupera soltanto l'UUID pendente richiesto esplicitamente |
| Richiesta pendente di un'altra partita | Mantiene tipo e destinazione visibili; blocca anche una nuova creazione |
| Conferma recuperata dal journal | Distinta dal risultato del nuovo modulo; nessuna nuova mutazione |
| Logout o distruzione del controller | Chiude il writer e impedisce a risposte tardive di ripopolare lo stato |

Il controllo locale del ruolo serve a evitare operazioni UI improprie; non sostituisce l'autorizzazione verificata dal client e dal database. Il chiamante dovrà distruggere il controller anche in caso di revoca del ruolo o cambio account.

## Journal per account

`AccountPlayJournals` conserva un solo journal e lock per UUID di account. Svuotare la memoria non rimuove il lock dal registro mentre un client lo usa. Il factory preparato nell'Application inietta EncryptedPendingPlayStore e usa lo stesso registro per ogni nuovo editor; il registro viene creato soltanto quando il factory è disponibile. Questo collegamento non attiva le API reali.

La lettura della conferma persistita mantiene il lock per account fino alla decodifica del risultato. Anche uno svuotamento della cache richiesto durante la lettura non elimina la conferma prima che il client possa restituirla.

## Limiti e verifica

`PlayEditorRoute` presenta il controller con componenti Material dell'app. Le destinazioni play/new e play/edit/{id} usano questa schermata quando ruolo, repository, rete e factory lo consentono. Mostra gioco, modalità, data, durata, note e partecipanti; permette di aggiungere o rimuovere partecipanti e modificare punteggio, vincitore, colore e ruolo. Il catalogo è ricercabile e gli omonimi mostrano un identificativo.

Gli errori compaiono nei campi e in un messaggio riepilogativo. I controlli dei risultati hanno etichette accessibili e area interattiva di almeno 48 dp. L'elenco scorre sopra la tastiera; dialoghi e selettori si chiudono quando l'accesso è bloccato. Il successo chiude il modulo di scrittura e distingue un nuovo salvataggio dal recupero di un'operazione precedente.

La schermata chiede conferma prima di uscire da una bozza modificata. I callback applicano le modifiche alla bozza attuale, senza riutilizzare valori di un frame precedente. Il confronto con il server mostra note, risultati e tutti i partecipanti; accettarlo scarta esplicitamente la bozza locale. I dati storici non modificabili restano leggibili e la cancellazione richiede conferma.

La destinazione della navigazione possiede il ViewModel. La sola ricreazione dell'Activity conserva bozza, richiesta in corso e writer. Uscita esplicita, distruzione del ViewModel, cambio account, Checking, blocco o revoca del ruolo rimuovono le risorse precedenti. clearMemory è idempotente: uscita e successiva rimozione della destinazione chiudono il writer una sola volta. La barra delle sezioni è nascosta nell'editor; il tasto visibile e il back di sistema passano dalla conferma della bozza.

Alla chiusura di un salvataggio o recupero confermato, la sessione incrementa una revisione locale di lettura. Storico, dettaglio, home, giochi, giocatori, statistiche e letture ELO aggiornano il rispettivo modello quando tornano visibili. I filtri dello storico rimangono applicati. Il segnale non ricalcola ELO e non rende coerenti risultati derivati che il backend non ha ancora aggiornato. Una sessione già chiusa ignora il segnale.

Le prove JVM usano cataloghi, Auth e writer simulati; il trasporto esistente ha test TCP loopback separati. Il controller non conserva una bozza non inviata dopo morte del processo: il journal riguarda la richiesta già preparata e il suo esito, non una coda offline.

Il collaudo del 15 ottobre passa con 231 test JVM, 39 controlli Node e 87 test Android API 35, incluse dieci nuove prove della navigazione editor. Le prove native usano writer e repository sintetici: non inviano RPC e il loro recupero usa dati in memoria per account. Le prove separate dello store cifrato non dimostrano ancora un percorso completo RPC, cifratura, navigazione e arresto del processo. Tale integrazione resta da verificare sul PC.

La candidata firmata 0.5.0-rc3 conserva il codice del checkpoint precedente; questo incremento produce soltanto APK debug e test. Non certifica una nuova release firmata, GoTrue o le API reali. Risultati: [editor-navigation-validation.json](evidence/editor-navigation-validation.json). Stato e condizioni: [STABILITY.md](STABILITY.md) e [WRITER_INTEGRATION.md](WRITER_INTEGRATION.md).
