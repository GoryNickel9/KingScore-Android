# Dettaglio gioco

La build 0.3.3 aggiunge Apri gioco al dettaglio partita quando esiste un `game_id`. La schermata legge nome, minimo/massimo giocatori e riferimento BGA dal DTO esistente. I campi null non diventano valori inventati; il riferimento BGA è testo, non un collegamento costruito dal client.

## Letture e navigazione

`PlaysRepository.game(UUID)` richiede una sola riga `games` per ID, attraverso `AuthenticatedReader`. La risposta deve contenere zero o una riga dello stesso UUID; JSON invalido, UUID diverso e righe duplicate producono un errore recuperabile. Un array vuoto significa gioco eliminato o non visibile, mentre un 403 conserva il messaggio di permesso negato.

Lo storico parte soltanto dopo una lettura valida del gioco. Riusa `HistoryViewModel` con `HistoryFilters(gameId = id)`, pagine da 50 e ordine `played_at DESC,id DESC`. Punteggi, vincitori, cooperative e partecipanti usano le stesse schede dello storico generale. Il conteggio indica le partite caricate, senza presentare una pagina come totale del gioco.

Toccare una scheda apre la partita. Riaprire il suo gioco torna alla destinazione esistente con le pagine già lette; Indietro torna alla schermata precedente. Aggiorna rimuove i dati della schermata e rilegge metadati e prima pagina. Una revisione transitoria assicura la rilettura dello storico anche quando il nome e gli altri metadati restano identici. Un errore nella pagina successiva mantiene righe e cursore per Riprova.

## Accesso

Ogni richiesta verifica rete, sessione e account tramite il lettore condiviso. Perdita di accesso, logout e cambio account distruggono i ViewModel della navigazione autenticata. Le richieste vengono cancellate e le risposte tardive non ripristinano dati cancellati. Alla riconnessione la navigazione riparte dalla Home e il gioco viene riletto quando si riapre.

Non sono state aggiunte mutazioni, cache delle righe o richieste di ricalcolo ELO. Lo schema Supabase rimane invariato. Immagini e statistiche complete dedicate al gioco non fanno parte di questo incremento: una scansione parziale non basta a produrre aggregati completi.

## Verifiche

I test del repository coprono query, account, nullabilità, cardinalità, parsing e permesso negato. I test del ViewModel coprono ritorno, refresh identico, missing/403, rete persa e risposte non cancellabili obsolete. I test Compose attraversano gioco, paginazione, partita e ritorno, refresh, retry e rimozione dei dati senza accesso.

La suite completa ha superato 95 test JVM, 23 Android API 31 e 10 controlli Node; lint ha zero errori e 22 warning preesistenti. Il percorso gioco/pagina/partita/ritorno/refresh passa anche con testo al 130% in verticale su circa 376 dp e in orizzontale, con animazioni disattivate. Risultati e checksum: [evidence/phase33-validation.json](evidence/phase33-validation.json). Le catture mostrano il layout [standard](evidence/phase33-game.png), [con testo ingrandito](evidence/phase33-game-large-font.png) e [orizzontale](evidence/phase33-game-landscape.png). La barra inferiore esistente tronca Statistiche nella cattura verticale ingrandita; la rifinitura delle etichette resta fra i controlli prima della release stabile.

Le fixture sono sintetiche. Confronto con un account reale, telefono fisico, API minima 26, TalkBack e misure delle prestazioni restano da eseguire prima della release stabile. La versione è ancora un APK con firma debug; scritture e firma definitiva appartengono alle fasi successive.
