# Home, statistiche ed ELO in lettura

La build 0.3.1 aggiunge Home, statistiche essenziali, classifiche ELO generali e per gioco e storico dei rating. Riusa autenticazione, cancellazione delle richieste e store dei ViewModel per account. Registrazione, modifica account/avatar, scritture partite/commenti e ricalcolo ELO restano esclusi da questo incremento.

## Home e statistiche

Home mostra le ultime cinque partite della prima pagina verificata online e collegamenti a storico e classifiche. Non usa quella pagina per stimare il totale del gruppo e non scarica l'intero archivio a ogni apertura.

Statistiche legge tutte le pagine dello storico, con progresso visibile. I totali compaiono soltanto dopo una scansione riuscita; un errore intermedio non diventa un risultato parziale. La scansione conserva ID e aggregati, senza trattenere tutti i dettagli delle partite. Tornare alla scheda riusa il risultato in memoria finché l'accesso rimane valido; Aggiorna statistiche riparte da zero.

Le metriche comprendono partite totali e del mese corrente, giochi e giocatori identificati, minuti registrati, durata media con copertura e partite per gioco. Zero minuti è una durata registrata; null e valori negativi vengono esclusi dalla media e dalla copertura. I nomi uguali non uniscono giochi o giocatori con ID diversi. Il mese deriva dalla LocalDate della partita, senza conversioni di fuso.

Le tue partite usa il giocatore collegato tramite `players.account_user_id`. Il proprietario storico `user_id` non identifica chi sta usando l'app. Una partecipazione conta soltanto se il giocatore compare nella partita; una vittoria richiede `winner=true`. Più righe score dello stesso giocatore non duplicano partecipazioni o vittorie. Senza collegamento, la UI mostra Profilo giocatore non collegato.

I cursori non garantiscono uno snapshot immutabile: modifiche concorrenti possono spostare partite fra pagine. Gli ID vengono deduplicati, e il refresh rilegge il dataset. Per archivi molto grandi o aggiornamenti frequenti, un'aggregazione server verificata potrà sostituire la scansione; non è stata introdotta in questo incremento.

## Classifiche e storico ELO

Le letture ammesse sono `player_general_ratings`, `player_ratings` ed `elo_history`. Il client non invoca helper del sito che ricostruiscono o scrivono cache, RPC, job o funzioni di ricalcolo. Ogni richiesta passa da SupabaseReader, con verifica online dell'utente e Bearer della sessione.

La classifica generale usa `user_id` del giocatore collegato come archivio storico; se manca, usa l'ID dell'account. Questo evita di mescolare rating dello stesso player pubblicati da proprietari diversi: il vincolo SQL è `(player_id,user_id)`. La classifica per gioco è condivisa, filtrata soltanto per `game_id`; il suo vincolo è `(player_id,game_id)`. La UI non sceglie un rating arbitrario né media rating distinti.

Catalogo giochi e classifica attraversano tutte le pagine da 50 con cursori ID crescenti. Soltanto alla fine il client ordina i valori pubblicati, rating decrescente e ID come ordine stabile. I pari merito ricevono posizioni 1,1,3; rating null resta non disponibile e non riceve una posizione. I contatori null e la data di aggiornamento mancante restano espliciti. Il momento di lettura non viene presentato come data di aggiornamento dei rating.

Aprire un giocatore mostra lo storico pubblicato, paginato per `date DESC,id DESC`. I filtri includono player, tipo generale/per gioco e, per il generale, proprietario dell'archivio. Variazioni e rating sono quelli restituiti dal backend; uno storico vuoto mostra Nessun dato storico pubblicato. Cambiare classifica o chiudere lo storico cancella il caricamento precedente e scarta risposte obsolete.

## Accesso e verifiche

Perdita di rete, logout, revoca e cambio account rimuovono anche totali, catalogo, classifiche e dialogo storico. La riconnessione convalida la sessione prima di rileggere il server. Nessun dato applicativo è salvato su disco o inviato in una coda offline.

La fase 3 precedente è stata pubblicata nel commit `2d14b4f` e la [CI è passata](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38414918651). Le evidenze locali della build 0.3.1 sono in [evidence/phase31-validation.json](evidence/phase31-validation.json), con checksum APK e limiti della verifica. Sono passati 75 test JVM, 13 test Android API 31 e 10 controlli Node; lint non riporta errori. I test usano fixture sintetiche; account reale, telefono fisico e TalkBack restano da verificare.

Le schermate catturate dal test Compose mostrano [Home](evidence/phase31-dashboard.png), [Statistiche](evidence/phase31-statistics.png) ed [ELO](evidence/phase31-ratings.png). La regressione V/P/S usa contatori diversi per vittorie, pareggi e sconfitte, così uno scambio fra campi non passa inosservato.

Cinque probe remote con `limit=0` hanno verificato la sintassi di profilo, catalogo, classifiche e storico senza leggere righe applicative. Sono probe esterne al client Android con chiave pubblica, non prove di accesso con un utente reale. Schema, permessi, dati e processi ELO non sono stati modificati.
