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
| `feature/dashboard`, `plays`, `ratings`, `stats` | Consultazione online e letture ELO |
| `feature/comments`, `playeditor`, `profile` | Commenti propri, editor admin e profilo in lettura |

Home legge soltanto la prima pagina e mostra le cinque partite più recenti. Statistiche attraversa l'archivio su richiesta, conserva aggregati e ID e pubblica un risultato soltanto dopo la lettura completa. Classifiche e cataloghi usano cursori ID; lo storico ELO usa data e ID. Le viste condividono SupabaseReader e la stessa barriera di accesso, senza nuove dipendenze.

`HistoryFilters` viene applicato sul server prima della paginazione. Ricerca e cursore condividono il gruppo AND; il partecipante usa un alias di selezione separato dai punteggi restituiti. `ReadAllById` riusa la traversata dei cataloghi e rating. Lo stato UI salva soltanto gli input dei filtri per il ripristino della schermata, senza righe o cataloghi; `Blocked` rimuove anche questo stato dell'account.

## Stati dell'app

Senza sessione mostrare il login. Una sessione salvata deve essere verificata online prima di mostrare dati. Una perdita di rete o un backend irraggiungibile mostra lo stato di connessione e sospende l'uso delle schermate funzionali. Mantenere separati errore di rete, permesso negato, risorsa assente e insieme vuoto.

Nell'incremento storico/dettaglio, la perdita di accesso elimina lo store dei ViewModel e cancella richieste e righe in memoria. Alla riconnessione si rilegge il server. Il modulo può mantenere i valori in memoria fino alla chiusura del processo; non esistono outbox, invii differiti o salvataggi automatici alla riconnessione. Logout e cambio account eliminano lo stato privato.

Persistono soltanto sessione protetta e preferenze necessarie. Il progetto non richiede Room, WorkManager, un database locale o un server Android separato. I token non devono comparire in log, backup ordinari o fixture; la password non viene memorizzata.

## ELO e mutazioni

Il client non contiene un calcolatore ELO. Legge rating e storico già pubblicati, senza invocare job o scrivere valori derivati. Il salvataggio delle partite userà soltanto RPC transazionali dopo il loro deploy e la verifica isolata. Aggiornare la schermata significa effettuare letture, non richiedere ricalcoli.

I DTO di trasporto sono distinti dai command: la lettura conserva la nullabilità storica; gli input nuovi richiedono identificatori validi, partecipanti distinti e risultati cooperativi coerenti. `data/remote/RowDecoders.kt` implementa la decodifica stretta di otto modelli, con BigDecimal, LocalDate e Instant, verificata da test JVM.

`AppViewModel` controlla l'accesso attraverso Checking, SignedOut, Connected e Blocked. Il login salvato viene verificato online; in primo piano la sessione attiva viene ricontrollata ogni 60 secondi e al ritorno nell'app. Una schermata login già aperta rimane montata durante questi controlli. Il ritorno della rete avvia una nuova verifica; le richieste Auth hanno timeout di 15 secondi. L'assenza di rete validata blocca subito la navigazione; il backend indisponibile viene rilevato alla successiva richiesta.

## Compatibilità del sito

Non cambiare SELECT pubbliche, struttura account o processi ELO per imporre i vincoli del client. Le future RPC si aggiungeranno al backend e richiederanno un adeguamento dei flussi web di scrittura per garantire transazioni e concorrenza coerenti. Migrazioni e funzioni backend rimangono nel repository King-Score.
