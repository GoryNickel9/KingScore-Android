# Summary API V1 — candidata locale

Questa directory contiene il SQL completo da revisionare prima di qualsiasi applicazione cloud:

1. [01-install.sql](01-install.sql): preflight, schema di helper, cinque RPC, privilegi e notifica di aggiornamento del catalogo PostgREST, in una sola transazione.
2. [02-rollback.sql](02-rollback.sql): rimozione delle sole funzioni candidate e del loro schema, senza `CASCADE` e senza toccare dati o pubblicazione ELO esistente.
3. [acceptance.mjs](acceptance.mjs): controlli HTTP/SQL, concorrenza e confronto prestazioni su dati sintetici.

Nessuno di questi file è eseguito automaticamente contro Supabase. Non ci sono comandi Supabase CLI, credenziali reali, workflow di deploy o modifiche all'APK.

## Eseguire sul PC

```powershell
node scripts/test-local-summary-api.mjs
```

Il runner richiede Node, PostgreSQL 17 in `C:\Program Files\PostgreSQL\17\bin` e il binario PostgREST 14.1 già verificato in `.work/undone-external-files/postgrest-14.1/postgrest.exe`. Un percorso alternativo dei binari PostgreSQL è l'unico argomento opzionale. Non scarica dipendenze.

Ogni esecuzione inizializza un cluster in `.work/summary-api-tests/<UUID>`, sceglie porte libere e collega PostgreSQL e PostgREST solo a `127.0.0.1`. I processi non ereditano configurazione PG/PGRST o proxy. Il ruolo gateway e la chiave JWT casuale sono locali; nessuna chiave compare nel report. Il `finally` arresta esclusivamente i processi creati dal runner. Report e piani restano sul PC.

## Base riprodotta

Lo schema pubblico, le policy e gli indici derivano dall'audit già salvato in `docs/evidence/supabase-audit.json`, senza interrogazioni remote. [baseline-adapter.sql](baseline-adapter.sql) riproduce la rimozione del trigger timestamp obsoleto su `play_scores` già presente nella migrazione web `01-install-rpcs.sql:183`. Crea soltanto il namespace richiesto dal preflight ELO: non sostituisce né collauda i writer atomici.

[elo-baseline.sql](elo-baseline.sql) è una copia immutata della migrazione ELO esistente, dal checkout web `78918e57e44e3fa385d0a0108ec3fa73b86edd1b`, file `scripts/atomic-play-migrations/02-elo-publication.sql`. SHA256: `55cc801aa6674fac50c317754e16db8e92e81dcbf998afe5fb404b4ab45dcfbb`. Serve solo per riprodurre contatore, trigger e funzioni ELO sul cluster locale. Non è parte del nuovo SQL da applicare al cloud.

La fixture Auth verifica JWT e ruoli attraverso PostgREST, ma non replica GoTrue né il login Supabase reale. Le pubblicazioni ELO sintetiche verificano la proiezione dei valori forniti, senza certificare o ricalcolare la formula ELO, già fuori dal perimetro di questo cambiamento.

## Permessi e contratti

Le cinque RPC pubbliche concedono EXECUTE solo ad `authenticated` e richiedono soggetto e ruolo JWT. Solo la lettura del contatore usa SECURITY DEFINER. Gli aggregati e gli helper usano SECURITY INVOKER con `search_path` vuoto e rispettano RLS. Gli helper sono nello schema dedicato `kingscore_summary`: USAGE/EXECUTE ad authenticated, nessun accesso diretto al privato `kingscore_elo`, nessuna nuova tabella. Lo schema helper non va aggiunto agli schemi esposti da PostgREST.

I cursori sono base64 di un oggetto JSON validato, legato a account, revisione, vista e selezione. Non sono firme o credenziali: una posizione modificata può cambiare la pagina richiesta, ma non i dati visibili sotto RLS. Una revisione differente produce HTTP 409. Le modifiche alle policy richiedono invece revisione del contratto/scope, perché il contatore dati non le versiona.

Le medie di punteggio, le somme e le revisioni sono stringhe decimali; i conteggi sono interi entro il limite esatto JSON/JavaScript. NULL non è zero. Il win rate personale usa partecipazioni e vittorie distinte per partita. Le finestre ELO non dividono i partecipanti di una partita e restituiscono i valori precedenti per il forward-fill. Oltre 64 serie la RPC richiede una selezione esplicita tramite `p_player_ids` (massimo 64), senza omettere giocatori silenziosamente.

## Misure e limiti

Il benchmark usa 1.000, 10.000 e 100.000 partite, con due righe punteggio per partita. Confronta il riepilogo con la proiezione HTTP leggera attuale a pagine da 500, inclusa l'ultima pagina vuota. Verifica gli aggregati con un ciclo JS indipendente; registra byte, richieste, tre tempi HTTP dopo warm-up, hardware e piani EXPLAIN ANALYZE. Il confronto della scansione ha una sola ripetizione per dimensione. I risultati su loopback non stimano la latenza di Supabase o della rete mobile. Auth, immagini e partite recenti non sono incluse.

Non sono introdotti indici preventivi. Il riepilogo deve comunque leggere le righe necessarie agli aggregati esatti; la proiezione ELO deve leggere il JSON pubblicato. Una prima risposta più piccola non rende gratuito il lavoro del database.

Prima di qualsiasi applicazione cloud restano obbligatori: revisione del SQL con l'utente, verifica dello schema/owner/policy/trigger effettivi sul progetto di destinazione, approvazione dell'applicazione e piano di rollback. Questa attività non modifica Android o la web app.
