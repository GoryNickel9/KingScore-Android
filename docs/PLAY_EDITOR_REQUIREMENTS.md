# Campi obbligatori nell'editor — 0.6.3/code 21

Le regole confermate dall'utente si applicano sia alla creazione sia alla modifica:

1. Durata obbligatoria: minuti interi, almeno 1. Un campo vuoto, zero, un numero negativo, decimale o fuori dal formato intero supportato impedisce l'invio.
2. Partite competitive: almeno un partecipante deve essere selezionato come vincitore. Sono ammessi più vincitori, anche tutti i partecipanti, per i pareggi.
3. Partite cooperative: nessun obbligo di vincitore individuale. L'esito esplicito della squadra continua a impostare tutti vincitori in caso di vittoria e nessuno in caso di sconfitta.

L'editor mostra l'obbligo nel campo durata e il messaggio accanto ai partecipanti. Non inserisce una durata predefinita e non sceglie automaticamente il vincitore dai punteggi. Il controller verifica le regole prima di creare un comando o chiamare il writer.

Le vecchie partite con durata assente o zero e senza vincitori restano leggibili. Se sono già modificabili secondo l'API, l'editor le apre e richiede di completare i campi prima di salvarle. L'eliminazione resta possibile con i permessi e la conferma esistenti.

I DTO e il trasporto V1 conservano la compatibilità con le letture storiche e con i registri cifrati delle operazioni dall'esito incerto. Il recupero mantiene UUID e payload originali: queste regole non riscrivono o eliminano richieste pregresse. Login, Internet, permessi admin e protezioni contro i doppi invii restano attivi.

Questa patch riguarda soltanto Android. La validazione autorevole deve essere presente anche nelle RPC del backend; rendere obbligatorio un campo nella sola pagina web non vincola gli altri client. Non sono state modificate RPC, schema, dati Supabase, web app o Vercel. La formula ELO non cambia.

Le prove aggiunte coprono blocco locale in creazione e modifica, vecchi dati da completare, eliminazione, minimo di un minuto, pareggi e cooperative perse. Sono passati 339 test JVM debug, lint senza errori, 41 controlli Node e un singolo run di 57 prove native interessate su API 31. [Report UI](evidence/private-063-ui-validation.json).

Il primo run nativo aveva 56 successi e un errore di scroll nel test della copertura giocatore in Statistiche a testo 2×. Il test ora porta in vista il nodo di testo; la ripetizione delle otto classi passa 57/57, senza nuove modifiche produttive dopo i test JVM. Anche la release supera 339 test JVM, lint senza errori e aggiornamento 18 → 21, con login e blocco offline verificati. [Qualifica](evidence/private-063-release-validation.json) e [CI della fonte](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38821149282).
