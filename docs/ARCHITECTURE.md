# Architettura KingScore Android

Il client è un'app Kotlin con Jetpack Compose, in un solo modulo Gradle `app`, organizzato per funzionalità. Supabase resta il backend condiviso con il sito. La fase 2 implementa Auth, connessione e navigazione; la fase 3 aggiunge Home, storico, dettaglio, commenti in lettura, statistiche e rating pubblicati. I contratti di fase 1 sono indipendenti da Android e vengono compilati anche nell'app.

## Flusso dati

`Compose → ViewModel / StateFlow → Repository → Supabase`

La UI presenta dati e raccoglie azioni. Il ViewModel mantiene lo stato della schermata e gli errori. I repository costruiscono query tipizzate, traducono le risposte nei DTO e applicano il vincolo sessione/connessione prima di ogni operazione. La RLS e le RPC server verificano l'autorizzazione.

| Area prevista | Responsabilità |
| --- | --- |
| `core/auth`, `core/network` | Sessione protetta, rinnovo, raggiungibilità ed errori condivisi; implementati |
| `data/remote`, `data/repository` | Supabase, adattatori JSON e repository per risorsa |
| `feature/auth`, `feature/navigation` | Login obbligatorio, logout e destinazioni per ruolo |
| `feature/dashboard`, `plays`, `games`, `players`, `ratings`, `stats` | Consultazione online e letture ELO |
| `feature/comments`, `playeditor`, `profile` | Commenti propri, editor admin e profilo in lettura |

Home legge soltanto la prima pagina e mostra le cinque partite più recenti. Statistiche attraversa l'archivio su richiesta, conserva aggregati e ID e pubblica un risultato soltanto dopo la lettura completa. Classifiche e cataloghi usano cursori ID; lo storico ELO usa data e ID. Le viste condividono SupabaseReader e la stessa barriera di accesso, senza nuove dipendenze.

`HistoryFilters` viene applicato sul server prima della paginazione. Ricerca e cursore condividono il gruppo AND; il partecipante usa un alias di selezione separato dai punteggi restituiti. `ReadAllById` riusa la traversata dei cataloghi e rating. Lo stato UI salva soltanto gli input dei filtri per il ripristino della schermata, senza righe o cataloghi; `Blocked` rimuove anche questo stato dell'account.

Il dettaglio gioco legge una sola riga `games` per UUID attraverso lo stesso repository verificato. Solo dopo il caricamento dei metadati avvia `HistoryViewModel` con `HistoryFilters(gameId = id)`. Entrambi i ViewModel appartengono alla destinazione di navigazione dell'account; il ritorno dalla partita conserva le pagine, mentre Aggiorna rilegge metadati e prima pagina. Una revisione in memoria distingue anche refresh che restituiscono metadati identici. Riaprire il gioco dalla sua partita torna alla destinazione esistente, senza aggiungere copie allo stack.

Il dettaglio giocatore segue lo stesso flusso con `players` per UUID e `HistoryFilters(playerId = id)`. Il filtro sull'alias dei punteggi seleziona le partite del partecipante senza rimuovere gli altri punteggi. `DetailHistory` condivide il rendering di paginazione, errori e aggiornamento fra gioco e giocatore. Il comando nel dettaglio partita usa lo score.player_id, anche con join players null; senza ID non compare. Il confronto account_user_id/account autenticato determina Sei tu, senza usare il vecchio flag is_me.

## Stati dell'app

Senza sessione mostrare il login. Una sessione salvata deve essere verificata online prima di mostrare dati. Una perdita di rete o un backend irraggiungibile mostra lo stato di connessione e sospende l'uso delle schermate funzionali. Mantenere separati errore di rete, permesso negato, risorsa assente e insieme vuoto.

Nell'incremento storico/dettaglio, la perdita di accesso elimina lo store dei ViewModel e cancella richieste e righe in memoria. Alla riconnessione si rilegge il server. Il modulo può mantenere i valori in memoria fino alla chiusura del processo; non esistono outbox, invii differiti o salvataggi automatici alla riconnessione. Logout e cambio account eliminano lo stato privato.

Persistono soltanto sessione protetta e preferenze necessarie. Il progetto non richiede Room, WorkManager, un database locale o un server Android separato. I token non devono comparire in log, backup ordinari o fixture; la password non viene memorizzata.

## ELO e mutazioni

Il client non contiene un calcolatore ELO. Legge rating e storico già pubblicati, senza invocare job o scrivere valori derivati. Il salvataggio delle partite userà soltanto RPC transazionali dopo il loro deploy e la verifica isolata. Aggiornare la schermata significa effettuare letture, non richiedere ricalcoli.

I DTO di trasporto sono distinti dai command: la lettura conserva la nullabilità storica; gli input nuovi richiedono identificatori validi, partecipanti distinti e risultati cooperativi coerenti. `data/remote/RowDecoders.kt` implementa la decodifica stretta di otto modelli, con BigDecimal, LocalDate e Instant, verificata da test JVM.

`AppViewModel` controlla l'accesso attraverso Checking, SignedOut, Connected e Blocked. Il login salvato viene verificato online; in primo piano la sessione attiva viene ricontrollata ogni 60 secondi e al ritorno nell'app. Una schermata login già aperta rimane montata durante questi controlli. Il ritorno della rete avvia una nuova verifica; le richieste Auth hanno timeout di 15 secondi. L'assenza di rete validata blocca subito la navigazione; il backend indisponibile viene rilevato alla successiva richiesta.

## Immagini per account

`AccountImages` vive soltanto nel ramo Connected dell'account. Prima del trasporto verifica rete e sessione con `withVerifiedSession`; il caricamento usa poi un client OkHttp separato, senza header SDK, cookie o cache persistente. `ImageAccessGuard` controlla anche il completamento e la lettura dello stream. Alla rimozione del ramo Connected, close invalida il guard prima di cancellare loader e richieste. Una risposta tardiva non può pubblicare pixel né bloccare l'account successivo. Copertine e avatar sono decorativi: il nome resta nel testo e il retry è un comando distinto. Un errore del CDN lascia disponibili i dati, mentre un errore di accesso usa il blocco globale.

## Compatibilità del sito

Non cambiare SELECT pubbliche, struttura account o processi ELO per imporre i vincoli del client. Le future RPC si aggiungeranno al backend e richiederanno un adeguamento dei flussi web di scrittura per garantire transazioni e concorrenza coerenti. Migrazioni e funzioni backend rimangono nel repository King-Score.
