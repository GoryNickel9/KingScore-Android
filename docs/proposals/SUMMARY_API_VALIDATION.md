# Summary API V1 — validazione sul PC

Stato: PASS, 15 ottobre 2026. Nessuna applicazione a Supabase, nessun deploy Vercel e nessuna modifica all'APK. Il [report completo](../evidence/summary-api-local-validation.json) registra 45 gruppi di verifiche riusciti e tre dimensioni del benchmark. PostgreSQL e PostgREST temporanei sono stati arrestati al termine.

## SQL completo verificato

- [01-install.sql](../../local-tests/summary-api/01-install.sql), SHA256 `1725065de23a85b3c357c5650482fb46adf0096ecbd87084026ddf5cc4e3c9a0`.
- [02-rollback.sql](../../local-tests/summary-api/02-rollback.sql), SHA256 `50056d12e1915a058e8c3a56e037a2eaead33958982ba85f6fb198843b88981b`.
- [Runner e prerequisiti](../../local-tests/summary-api/README.md). Comando: `node scripts/test-local-summary-api.mjs`.

Gli input SQL sono congelati all'avvio e confrontati con i file originali alla fine. Gli hash dei file SQL sono preservati da Git senza conversione delle terminazioni di riga. Il file `elo-baseline.sql` riproduce esclusivamente il backend ELO già esistente nel database di prova: non è parte della nuova installazione cloud.

## Verifiche riuscite

1. Accesso anonimo, JWT scaduto/firma errata, soggetto assente e ruolo service esclusi dalle nuove RPC. Ruoli utente/admin, account diversi e policy RLS restrittive provati su partite, giocatori, giochi e score. Nessun ID account selezionabile dal client.
2. Win rate 2/4 = 50%, NULL esclusi dalle medie, zero incluso, punteggi negativi, decimali oltre il limite JavaScript e HALF_UP. Copertura, nessun campione, mesi vuoti e limiti del calendario. Riferimenti NULL e cancellazioni secondo i vincoli CASCADE esistenti.
3. Pagine senza duplicati, ordinamento stabile, cursori invalidi/di altri account o viste rifiutati e HTTP 409 dopo una revisione diversa. Letture concorrenti a transazioni partita+score, commit e rollback: nessun dato parziale o revisione non commessa.
4. Grafici dalla pubblicazione fornita: omonimi distinti, partite nello stesso giorno separate, rating precedenti per forward-fill, pagine complete per partita, selezione esplicita oltre 64 serie e nessun punto corrente per ELO stale/missing. Non viene eseguito alcun calcolo ELO.
5. Letture senza mutazioni, revisioni oltre 2^53, versione assente e numeri invalidi con errori espliciti. Installazione ripetuta rifiutata, preflight su dati storici invalidi e trigger di revisione disabilitato, rollback senza perdita di dati e reinstallazione riuscita. Il test attende soltanto l'aggiornamento asincrono del catalogo PostgREST, senza ritentare errori sui dati.

Passati anche i 40 test Node esistenti di contratti/evidenze. Non sono stati ricostruiti o collaudati Android/web, che non sono stati modificati.

## Misure su loopback

| Partite | Byte riepilogo | Byte scansione attuale | Richieste riepilogo / scansione | Mediana riepilogo ms | Scansione ms |
| --- | --- | --- | --- | --- | --- |
| 1.000 | 779 | 351.454 | 1 / 3 | 14.3 | 78.1 |
| 10.000 | 795 | 3.514.618 | 1 / 21 | 44.9 | 4120.5 |
| 100.000 | 808 | 35.146.258 | 1 / 201 | 615.7 | 292714.0 |

Ogni dimensione ha due righe score per partita, un gioco e due giocatori, con NULL e valori numerici deterministici. Il riferimento JS indipendente verifica Home, mesi, win rate e medie gioco/giocatore anche sulle fixture grandi. Il riepilogo è misurato tre volte dopo warm-up; la scansione HTTP ha una sola misura, a pagine da 500 fino alla pagina vuota. Nessun indice aggiunto. Piani EXPLAIN ANALYZE e query sono conservati nella directory locale dell'esecuzione.

Hardware: Intel(R) Core(TM) i3-10100F CPU @ 3.60GHz, 8 CPU logiche, 31.9 GiB RAM; Windows_NT 10.0.26200 x64. Versioni Node, PostgreSQL e PostgREST nel report.

Sono misure sul PC, non stime della latenza mobile o cloud. Non includono Auth, immagini o partite recenti. Il benchmark grande riguarda aggregati numerici e scansione delle partite; i grafici ELO sono verificati con pubblicazioni piccole, non con uno storico ELO di 100.000 partite. I piani del table function mostrano anche il costo totale della chiamata, senza esporre automaticamente tutti i piani interni.

## Stato e prossimo passaggio

I primi tentativi hanno richiesto l'adattamento della fixture storica, il congelamento congiunto del SQL di installazione/rollback e l'attesa del catalogo PostgREST dopo reinstallazione. I risultati sopra appartengono all'ultimo giro completo riuscito sullo stesso SQL identificato dagli hash.

Restano non verificati GoTrue/login reale, schema e policy cloud attuali, prestazioni su dati di produzione e integrazione Android. Prima dell'applicazione a Supabase occorrono revisione del SQL e autorizzazione dell'utente, poi preflight sul progetto corretto. Questa consegna si ferma ai file candidati collaudati localmente.
