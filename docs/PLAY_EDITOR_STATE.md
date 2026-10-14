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

Le prove JVM usano cataloghi, Auth e writer simulati; il trasporto esistente ha test TCP loopback separati. Il controller non conserva una bozza non inviata dopo morte del processo: il journal riguarda la richiesta già preparata e il suo esito, non una coda offline.

Mancano schermata Compose, factory e integrazione con navigazione/sessione. Rotazione, confronto visivo dei conflitti e percorsi dell'editor dopo arresto del processo richiedono test Android quando questi componenti saranno presenti. L'APK rimane un'anteprima 0.4.0.
