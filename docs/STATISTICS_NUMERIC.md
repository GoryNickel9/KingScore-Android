# Statistiche numeriche — 0.6.2/code 20

Su richiesta dell'utente, Statistiche mostra soltanto le schede dei giochi con partite totali, punteggio medio, durata media e copertura dei dati. Le schede espandibili conservano partecipazioni, punteggio medio e copertura per giocatore, con paginazione.

Sono rimossi Win Rate per Gioco, ELO Generale e i grafici dei singoli giochi, compresi selezione giocatori, legenda e comandi delle finestre. Il controller non richiede più grafici né cataloghi per i grafici durante apertura, refresh, espansione o paginazione. Rimangono il gesto di aggiornamento, gli errori con Riprova e i controlli sulle revisioni.

La schermata Rating ELO mantiene la classifica e la compatibilità di lettura v1/v2. Login e Internet restano obbligatori; la perdita d'accesso cancella lo stato privato.

Questa modifica riguarda soltanto Android. Le API esistenti non cambiano: il riepilogo statistico restituisce ancora i campi del win rate, che la schermata non visualizza. I contratti e i decoder delle API restano disponibili; le funzioni SQL non vengono modificate o eliminate. Anche le dipendenze dei writer e dei conteggi server descritte in [ELO_V2_ANDROID.md](ELO_V2_ANDROID.md) restano separate.

I test del controller verificano assenza di richieste ai grafici, paginazione numerica, riuso della sessione, aggiornamento delle schede espanse, conflitti di revisione e rimozione dei dati alla perdita d'accesso. I test nativi verificano assenza delle sezioni rimosse, schede e medie ancora consultabili, errori, profilo non collegato e testo al 200%.

Sono passati 333 test JVM debug, lint senza errori e 41 controlli Node. Un singolo run delle sei classi native interessate passa 25/25, comprese le cinque prove di Statistiche, i pareggi e i gate d'accesso. [Report UI](evidence/private-062-ui-validation.json). La suite integrale della 0.6.0 non è stata ripetuta; anche la release supera 333 test JVM e il collaudo 18 → 20. [Qualifica](evidence/private-062-release-validation.json) e [CI della fonte](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38816371224).
