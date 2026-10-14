# Ricreazione dell'Activity e accesso

La navigazione autenticata usa `AuthenticatedResources`, un ViewModel dell'Activity. La sua sessione contiene i repository e il ViewModelStore dei percorsi di navigazione. Una modifica di configurazione conserva questa sessione, le pagine caricate, la bozza del commento e l'eventuale invio in corso. Non crea un secondo writer e non ripete la richiesta.

Quando la composizione autenticata viene rimossa per Checking, blocco, logout o cambio account, la sessione viene rilasciata. Il rilascio cancella i ViewModel figli e chiude il writer commenti. La distruzione definitiva dell'Activity esegue la stessa pulizia. La rimozione tardiva di una vecchia composizione non può chiudere la sessione del nuovo account.

Dal 15 ottobre la stessa proprietà comprende i ViewModel dell'editor e i loro writer. La sessione viene sostituita anche quando cambiano ruolo admin o disponibilità del factory. La navigazione ricreata conserva la bozza soltanto se l'accesso rimane valido; Checking, blocco e logout chiudono il vecchio editor. Un successivo accesso carica uno snapshot nuovo o ripristina l'operazione pendente per il relativo account, senza riusare la bozza privata della vecchia sessione.

Al primo STARTED di un'Activity ricreata, `MainActivity` richiede comunque la verifica online dell'account. Per un account già verificato e conservato nel ViewModel evita il passaggio preventivo a Checking, come il controllo periodico esistente. Un rifiuto del server, un account diverso o la perdita di Internet rimuove la navigazione. I successivi rientri dal background mantengono il passaggio a Checking. Un nuovo processo parte da Checking e non mostra dati prima della verifica iniziale.

Le bozze restano soltanto in memoria: non sopravvivono alla morte del processo e non diventano una coda offline. Il recupero degli esiti di scrittura incerti usa invece il [registro cifrato dei commenti](PHASE4_COMMENTS.md).

## Prove sul PC

`ActivityRecreationTest` esegue AppContent e la navigazione di produzione in un'Activity del solo source set debug. Usa ActivityScenario.recreate e una rotazione portrait → landscape che deve produrre un'altra istanza dell'Activity. I casi verificano bozza, pagine e dettaglio conservati, un solo invio durante la rotazione, pulizia su blocco e cambio account, logout e rientro da Checking.

Le fixture non avviano MainActivity né contattano GoTrue o Supabase remoto. I test JVM verificano separatamente la revalidazione dell'AppViewModel e il rilascio delle risorse; il collegamento in MainActivity viene compilato e analizzato dal lint. Il punto di ingresso della fixture è dichiarato soltanto nel manifest debug e non è esportato.

Risultati effettivi: [TESTING.md](TESTING.md). Non dichiarare superata una versione Android finché la sua suite completa e la prova di recupero dopo arresto forzato non sono terminate con successo.

PlayEditorNavigationTest aggiunge dieci casi attraverso AppContent e la navigazione: creazione con filtro conservato, modifica di una partita di un altro proprietario, cancellazione, rotazione durante invio, ricreazione con bozza, chiusura e riapertura, ruolo revocato, rete persa, Checking/logout, account separati e testo ingrandito. Il writer è sintetico e il pendente viene mantenuto in memoria nella fixture; queste prove non dimostrano ancora il percorso HTTP/cifrato dopo morte del processo. Il factory di produzione rimane assente. Report: [editor-navigation-validation.json](evidence/editor-navigation-validation.json).
