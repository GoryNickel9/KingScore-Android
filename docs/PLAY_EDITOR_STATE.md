# Controller dell'editor partite

`PlayEditorViewModel` prepara il flusso dell'editor con repository e writer iniettati. È disabilitato per default e non è collegato all'Application o alla navigazione. Soltanto i test lo attivano con fixture: le RPC reali restano indisponibili.

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

`AccountPlayJournals` conserva un solo journal e lock per UUID di account. Svuotare la memoria non rimuove il lock dal registro mentre un client lo usa. Lo store resta iniettato: il factory Android dovrà usare `EncryptedPendingPlayStore`, senza attivare per questo le API reali.

La lettura della conferma persistita mantiene il lock per account fino alla decodifica del risultato. Anche uno svuotamento della cache richiesto durante la lettura non elimina la conferma prima che il client possa restituirla.

## Limiti e verifica

`PlayEditorRoute` presenta ora il controller con componenti Material dell'app. Non è una destinazione della navigazione reale. Mostra gioco, modalità, data, durata, note e partecipanti; permette di aggiungere o rimuovere partecipanti e modificare punteggio, vincitore, colore e ruolo. Il catalogo è ricercabile e gli omonimi mostrano un identificativo.

Gli errori compaiono nei campi e in un messaggio riepilogativo. I controlli dei risultati hanno etichette accessibili e area interattiva di almeno 48 dp. L'elenco scorre sopra la tastiera; dialoghi e selettori si chiudono quando l'accesso è bloccato. Il successo chiude il modulo di scrittura e distingue un nuovo salvataggio dal recupero di un'operazione precedente.

La schermata chiede conferma prima di uscire da una bozza modificata. I callback applicano le modifiche alla bozza attuale, senza riutilizzare valori di un frame precedente. Il confronto con il server mostra note, risultati e tutti i partecipanti; accettarlo scarta esplicitamente la bozza locale. I dati storici non modificabili restano leggibili e la cancellazione richiede conferma.

Il chiamante possiede il ViewModel. La sola ricreazione della composizione non chiude il writer; uscita esplicita, distruzione del ViewModel, cambio account o revoca del ruolo devono invece chiuderlo. Il ripristino della composizione nelle fixture non certifica ancora la ricreazione dell'Activity attraverso la navigazione reale.

Le prove JVM usano cataloghi, Auth e writer simulati; il trasporto esistente ha test TCP loopback separati. Il controller non conserva una bozza non inviata dopo morte del processo: il journal riguarda la richiesta già preparata e il suo esito, non una coda offline.

Mancano factory e integrazione con navigazione/sessione. Rotazione dell'Activity e percorsi completi dell'editor dopo arresto del processo richiedono il collaudo dell'integrazione, con store cifrato e writer controllato. Le fixture della schermata non inviano HTTP e non rendono disponibili le RPC reali. L'APK attuale è la candidata firmata 0.5.0-rc3: i 77 test della suite e le prove di ricreazione della consultazione non certificano l'integrazione dell'editor admin. Stato e condizioni: [STABILITY.md](STABILITY.md) e [WRITER_INTEGRATION.md](WRITER_INTEGRATION.md).
