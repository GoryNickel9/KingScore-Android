# Ricerca e filtri nello storico

La build 0.3.2 aggiunge ricerca in nome gioco o note e filtri combinabili per gioco, partecipante, modalità e intervallo date. Cerca e il comando Search della tastiera applicano il testo; digitare non invia una richiesta per ogni carattere. Il dialogo conserva una bozza fino ad Applica filtri; Annulla lascia la query applicata invariata. Rimuovi filtri azzera anche testo e modalità.

Le date usano `gg/mm/aaaa`, con controllo delle date inesistenti e dell'ordine da/a. Gli estremi sono inclusivi e uno può restare vuoto. I filtri identificano giochi e giocatori tramite UUID; i cataloghi sono ricercabili e gli omonimi rimangono distinti. I valori storici source null o diversi da offline/online rimangono visibili in Tutte.

## Query e paginazione

`HistoryFilters` contiene query, source, gameId, playerId, from e to. UUID e date sono tipizzati; il testo accetta una riga di massimo 120 caratteri. I filtri vengono applicati da PostgREST prima di limitare il risultato a 50 righe. Il client non filtra soltanto le righe della prima pagina.

La ricerca è una sottostringa letterale, senza distinzione fra maiuscole e minuscole secondo le regole del database. Non è una ricerca fuzzy e non rimuove gli accenti. `imatch` usa un pattern con tutti i metacaratteri POSIX escapati, poi quotato per PostgREST; percento, underscore e asterisco non diventano wildcard. Ktor applica l'encoding URL una sola volta. Il testo non può aggiungere operatori, modificare la proiezione o cambiare il cursore.

Il join `games(*)` rimane completo. L'alias vuoto `matched_game:games()` serve alla ricerca sul nome; l'OR con `notes` mantiene anche partite con gioco mancante e nota corrispondente. Il partecipante usa `matching_scores:play_scores!inner(player_id)` e un filtro sull'alias; `play_scores(*,players(*))` conserva tutti i punteggi. Questa separazione segue la [documentazione PostgREST sull'embedding](https://docs.postgrest.org/en/stable/references/api/resource_embedding.html#or-filtering-across-embedded-resources). Operatori, gruppi logici e quoting sono descritti nella [documentazione delle letture](https://docs.postgrest.org/en/stable/references/api/tables_views.html).

Ricerca e cursore vengono combinati in un unico `and=(or(...ricerca...),or(...cursore...))`. I confronti data `gte`/`lte` restano entrambi presenti. L'ordine è `played_at DESC,id DESC` con confronto UUID coerente con PostgreSQL. Il server può cambiare dati durante una scansione: il cursore non rappresenta uno snapshot immutabile.

Cambiare filtri rimuove le vecchie righe e riparte dalla prima pagina. Pagina successiva, retry, refresh e ritorno dal dettaglio riusano la query applicata. Una pagina fallita conserva il cursore per il retry; risposte obsolete non sostituiscono risultati di una ricerca più recente. Home e Statistiche usano filtri vuoti e mantengono il proprio perimetro.

Il ripristino della schermata conserva i sei input del filtro come stringhe nello stato UI Android, separati dalla bozza del campo ricerca. Un nuovo ViewModel rilegge il server con questi input; righe e cataloghi non vengono salvati nel Bundle. Valori salvati non validi ripartono senza filtri. La perdita di accesso rimuove anche questo stato UI.

## Cataloghi e accesso

Giochi e giocatori vengono letti quando si apre il selettore, con pagine da 50 e cursore `id ASC`. Il caricamento completo viene riusato in memoria finché l'accesso è valido. Un errore di catalogo resta distinto da uno storico vuoto; il dialogo permette di riprovare o impostare soltanto le date.

Ogni lettura passa dal controllo online dell'utente e dal Bearer della sessione. Perdita di connessione, logout e cambio account rimuovono righe, filtri, bozze, cataloghi e dialoghi. Le risposte tardive vengono scartate anche quando non possono essere cancellate. Non sono stati aggiunti cache locali delle righe, code offline, scritture o ricalcoli ELO.

## Verifica e limiti

Tre probe remote con `limit=0` hanno verificato la sintassi delle query, incluso l'insieme ricerca/cursore/partecipante/date e punteggiatura letterale. Sono richieste esterne al client Android con chiave pubblica: non certificano il comportamento con un account reale e non leggono righe applicative.

Le evidenze della build, dei test e del layout sono in [evidence/phase32-validation.json](evidence/phase32-validation.json): 87 test JVM, 18 Android e 10 controlli Node superati; lint senza errori. Il percorso completo è passato anche su finestra di circa 376 dp e in orizzontale, con testo al 130% e animazioni disattivate. Le immagini mostrano [storico filtrato](evidence/phase32-filtered-history.png) e [dialogo](evidence/phase32-filter-dialog.png), oltre alle varianti di layout elencate nell'evidenza.

I test usano fixture sintetiche. Confronto con dati reali, telefono fisico, API minima 26, TalkBack e benchmark mobili restano da eseguire prima della release stabile. La stima del lavoro residuo per la 1.0 è nella [roadmap](../ROADMAP.md#lavoro-residuo-per-la-versione-10-stabile).
