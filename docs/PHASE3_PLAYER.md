# Dettaglio giocatore

La build 0.3.4 aggiunge Apri giocatore ai partecipanti del dettaglio partita. Il comando usa `play_scores.player_id`; un ID nullo non permette la navigazione, mentre un join `players` nullo non impedisce una nuova lettura del profilo. Ogni comando ha un nome accessibile associato al partecipante.

La schermata mostra alias, oppure nome quando l'alias manca o contiene solo spazi. Quando differiscono mostra anche il nome. Sei tu richiede `account_user_id` uguale all'account autenticato: il flag storico `is_me` non identifica l'utente corrente. La data del profilo proviene da `updated_at`, senza sostituirla con l'ora della lettura. Non sono esposti ID account o comandi per modificare il profilo.

## Query e stato

`PlaysRepository.player(UUID)` legge `players?select=*&id=eq.<uuid>&limit=1` attraverso `AuthenticatedReader`. Valida cardinalità, UUID e DTO generato. Nessuna riga indica giocatore eliminato o non visibile; un 403 e un payload invalido producono errori recuperabili distinti.

Solo dopo la lettura valida del profilo parte lo storico con `HistoryFilters(playerId = id)`. Il server filtra sull'alias `matching_scores:play_scores!inner(player_id)` e restituisce separatamente tutti i punteggi tramite `play_scores(*,players(*))`. Ogni pagina conserva filtro partecipante e cursore `played_at DESC,id DESC`, con limite 50. Il conteggio riguarda le partite caricate, senza presentare una pagina come totale dell'archivio.

Il rendering dello storico è condiviso con il dettaglio gioco. Un errore nella pagina successiva mantiene righe e cursore per Riprova. Aprire una partita e riaprire lo stesso giocatore torna alla destinazione esistente; Indietro torna alla schermata precedente. Aggiorna cancella i dati della schermata e rilegge profilo e prima pagina. Una revisione in memoria forza il caricamento anche quando i metadati non cambiano.

## Login e Internet

La nuova destinazione appartiene alla navigazione autenticata. Ogni GET verifica rete, sessione e account attraverso il lettore condiviso. Perdita di accesso, logout e cambio account rimuovono ViewModel e stato privato. Le richieste vengono cancellate; risposte o errori tardivi non ripristinano dati e non bloccano una lettura più recente già accettata. Alla riconnessione la Home riparte con letture nuove.

Non sono state aggiunte cache delle righe, code offline, mutazioni o operazioni ELO. Lo schema e i dati Supabase rimangono invariati. Avatar e immagini appartengono all'incremento successivo; la loro modifica resta esclusa dall'app. Rating e storico ELO sono disponibili nella sezione ELO già implementata. Statistiche complete per giocatore richiederebbero una scansione o un'aggregazione completa, oltre alle pagine caricate.

## Verifiche e limiti

Le fixture del repository attraversano 1.051 partite in 22 richieste conservando entrambi i partecipanti. I test del profilo coprono campi null, alias vuoto, timestamp con offset, UUID errato, cardinalità, payload invalido e permesso negato. Il ViewModel verifica ritorno, refresh identico, dati mancanti, rete persa e risposte/errori obsoleti. Le prove Compose attraversano partecipante, paginazione, partita e ritorno, retry, refresh, logout, riconnessione e cambio account.

La suite completa ha superato 104 test JVM, 31 Android API 31 e 10 controlli Node; lint ha zero errori e 22 warning preesistenti. Il percorso giocatore/pagina/partita/ritorno/refresh passa con testo al 130% su circa 376 dp in verticale e in orizzontale, con animazioni disattivate. In orizzontale il test deve scorrere fino al pulsante del partecipante, non solo fino all'item che lo contiene: l'item può superare l'altezza della viewport. Risultati e checksum: [evidence/phase34-validation.json](evidence/phase34-validation.json). Catture [standard](evidence/phase34-player.png), [testo ingrandito](evidence/phase34-player-large-font.png) e [orizzontale](evidence/phase34-player-landscape.png).

Le verifiche usano fixture sintetiche. Account reali, telefono fisico, API minima 26, TalkBack e benchmark mobili restano da provare prima della release stabile. La barra inferiore esistente può troncare Statistiche su finestra stretta con testo al 130%; la rifinitura è documentata nella build 0.3.3. L'APK rimane un'anteprima con firma debug.
