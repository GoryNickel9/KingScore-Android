# Ricreazione dell'Activity e accesso

La navigazione autenticata usa `AuthenticatedResources`, un ViewModel dell'Activity. La sua sessione contiene i repository e il ViewModelStore dei percorsi di navigazione. Una modifica di configurazione conserva questa sessione, le pagine caricate, la bozza del commento e l'eventuale invio in corso. Non crea un secondo writer e non ripete la richiesta.

Quando la composizione autenticata viene rimossa per Checking, blocco, logout o cambio account, la sessione viene rilasciata. Il rilascio cancella i ViewModel figli e chiude il writer commenti. La distruzione definitiva dell'Activity esegue la stessa pulizia. La rimozione tardiva di una vecchia composizione non può chiudere la sessione del nuovo account.

Al primo STARTED di un'Activity ricreata, `MainActivity` richiede comunque la verifica online dell'account. Per un account già verificato e conservato nel ViewModel evita il passaggio preventivo a Checking, come il controllo periodico esistente. Un rifiuto del server, un account diverso o la perdita di Internet rimuove la navigazione. I successivi rientri dal background mantengono il passaggio a Checking. Un nuovo processo parte da Checking e non mostra dati prima della verifica iniziale.

Le bozze restano soltanto in memoria: non sopravvivono alla morte del processo e non diventano una coda offline. Il recupero degli esiti di scrittura incerti usa invece il [registro cifrato dei commenti](PHASE4_COMMENTS.md).

## Prove sul PC

`ActivityRecreationTest` esegue AppContent e la navigazione di produzione in un'Activity del solo source set debug. Usa ActivityScenario.recreate e una rotazione portrait → landscape che deve produrre un'altra istanza dell'Activity. I casi verificano bozza, pagine e dettaglio conservati, un solo invio durante la rotazione, pulizia su blocco e cambio account, logout e rientro da Checking.

Le fixture non avviano MainActivity né contattano GoTrue o Supabase remoto. I test JVM verificano separatamente la revalidazione dell'AppViewModel e il rilascio delle risorse; il collegamento in MainActivity viene compilato e analizzato dal lint. Il punto di ingresso della fixture è dichiarato soltanto nel manifest debug e non è esportato.

Risultati effettivi: [TESTING.md](TESTING.md). Non dichiarare superata una versione Android finché la sua suite completa e la prova di recupero dopo arresto forzato non sono terminate con successo.
