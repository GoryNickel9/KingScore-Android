# Storico e dettaglio partita Android

Questo incremento della fase 3 aggiunge lo storico condiviso del gruppo e il dettaglio di una partita, con commenti in lettura. Home, Statistiche ed ELO rimangono segnaposto; le scritture appartengono alla fase 4. La build è 0.3.0, versionCode 2.

## Consultazione

La scheda Partite carica 50 righe alla volta, ordinate per `played_at DESC, id DESC`. Il cursore usa data e UUID, con confronto lessicografico: pagina successiva significa data minore oppure stessa data e ID minore. Il filtro Tutte/Dal vivo/Online viene applicato dal server prima della paginazione. Il conteggio mostrato indica soltanto le partite caricate, non il totale del database.

Carica altre partite prosegue dal cursore dell'ultima pagina riuscita. Un errore conserva righe e cursore per un nuovo tentativo; un refresh o un cambio filtro riparte dalla prima pagina. Una richiesta precedente non può sovrascrivere il nuovo filtro. Tornare dal dettaglio conserva pagine, filtro e posizione di scorrimento finché la navigazione autenticata rimane attiva.

Le pagine non sono uno snapshot del database. Un inserimento più recente comparirà al refresh; modificare la data di una partita può spostarla fra pagine. La UI deduplica gli ID e il repository rifiuta risposte fuori ordine, oltre il limite richiesto o incompatibili col cursore.

Il dettaglio mostra gioco, data, modalità, durata, luogo, note e partecipanti. Punteggio zero, decimale o negativo resta un valore; null mostra Punteggio non registrato. Le posizioni e i vincitori provengono dalle righe salvate. Il client non ricava vincitori dal punteggio e non inventa posizioni quando mancano. Le cooperative distinguono vittoria, sconfitta ed esito non indicato. Sei tu usa `players.account_user_id`, non `is_me`.

I commenti sono paginati per `created_at ASC, id ASC`, con pagine da 50 e un cursore distinto. Errori e assenza di commenti hanno stati separati; nessun controllo permette di scrivere o modificare commenti in questo incremento. Gioco e giocatore non disponibili producono etichette leggibili.

## Accesso e memoria

Ogni GET dati richiede una sessione in memoria e una rete validata da Android. `SupabaseAuthRepository.withVerifiedSession` verifica l'utente attraverso Auth, controlla che corrisponda all'account della navigazione e invia il JWT utente. Non carica una sessione da disco né ripiega sulla chiave anon per una lettura dati.

Un 401 ammette un solo refresh nell'intera operazione di verifica e lettura; un ulteriore rifiuto cancella la sessione. Un 403 dati rimane un errore di permesso della risorsa e non esegue logout. Gli errori di rete o backend bloccano la navigazione e le risposte sensibili non vengono esposte nei messaggi o nei log.

La navigazione autenticata possiede uno store di ViewModel per account. Rimuovere questo albero per logout, perdita di rete, revoca o verifica al ritorno nell'app cancella le richieste e svuota storico, dettaglio e commenti. Alla riconnessione si verifica la sessione e si rilegge il server. Lo stato salvato della navigazione conserva soltanto route, filtro e scorrimento: nessuna riga applicativa è persistita.

Il controllo periodico della sessione conserva la schermata durante una verifica riuscita; il rifiuto la rimuove. La verifica al ritorno dal background mantiene il blocco già previsto nella fase 2. Le richieste restano serializzate con login/logout, così una lettura non può passare a un account diverso durante l'operazione.

## File e verifiche

| Area | File principali |
| --- | --- |
| Richieste autenticate | `core/auth/SupabaseAuthRepository.kt`, `data/remote/AuthenticatedReader.kt` |
| Query e cursori | `data/repository/PlaysRepository.kt`, `data/remote/SupabasePlaysRepository.kt` |
| Stato e UI | `feature/plays/HistoryViewModel.kt`, `PlayDetailViewModel.kt`, `PlaysScreens.kt`, `PlayPresentation.kt` |
| Navigazione | `feature/navigation/AuthenticatedNavigation.kt`, `ui/AppContent.kt`, `MainActivity.kt` |
| Test | `SupabasePlaysRepositoryTest.kt`, `SupabaseReaderTest.kt`, `PlaysViewModelTest.kt`, `PlaysUiTest.kt` |

Build debug, 48 test JVM, 10 test Android API 31 e lint sono passati. Il lint mantiene zero errori e 22 warning di versioni e suggerimenti KTX. Il percorso storico → dettaglio → storico è passato anche su una larghezza di circa 376 dp con testo al 130%, in verticale e orizzontale. Le schermate usano liste virtualizzate, chiavi stabili, controlli Material e label accessibili; TalkBack e telefono fisico restano da provare.

I risultati sono registrati in `docs/evidence/phase3-validation.json`. I test usano record e account sintetici; non sostituiscono una verifica con account reale. Due probe HTTP esterne al client Android, con chiave pubblica e `limit=0`, hanno validato sintassi dei join e cursori sul backend senza leggere righe applicative. Queste probe non certificano Auth o RLS di un utente reale.

La pipeline del commit precedente si era fermata prima della build perché `setup-android@v3` richiedeva il pacchetto SDK `tools`, non disponibile. Il workflow ora specifica platform-tools, SDK 36 e build-tools 35.0.0 tramite l'input [packages dell'azione](https://github.com/android-actions/setup-android/blob/v3/action.yml). La correzione è stata verificata su GitHub: [CI del commit 2d14b4f](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38414918651), conclusa con successo.

Durante il primo avvio dell'AVD, System UI ha mostrato un ANR prima dei test. Dopo il riavvio del processo di sistema dell'emulatore, la suite e le acquisizioni delle schermate sono state rieseguite senza quel dialogo. Questo non costituisce un benchmark dell'app.

Nessuno schema, permesso o dato remoto è stato modificato. Le nuove RPC partite non sono invocate e non esiste alcun percorso di ricalcolo ELO.
