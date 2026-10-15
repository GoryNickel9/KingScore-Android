# Contratto di lettura delle classifiche globali

Checkpoint locale del 15 ottobre 2026. La classifica pubblica comprende tutte le partite di tutti gli account, come richiesto dall'utente. Formula e ranking ELO restano invariati; il simulatore riceve l'intero storico ordinato, senza combinare classifiche separate.

## Comportamento implementato nel checkout web locale

1. La risposta dichiara ambito `global`, algoritmo, revisione sorgente, revisione pubblicata e UUID della generazione. `current` contiene il risultato completo; `stale` e `missing` contengono `result:null`.
2. Il decoder controlla completezza e coerenza fra mappe, storici e contatori; conserva esattamente le revisioni bigint. Rifiuta ambiti diversi, algoritmi sconosciuti e payload parziali. Non ricalcola ELO e non scrive cache.
3. Generale, classifiche per gioco e relativi grafici derivano dallo stesso snapshot immutabile. I filtri conservano l'ordine storico; i nomi arrivano dalla stessa generazione. Un risultato attuale vuoto resta distinto da un aggiornamento in attesa.
4. La prova PostgreSQL offre letture e snapshot globali; gli scope proprietario rimangono disponibili per diagnostica. Le letture funzionano in transazioni read-only e non modificano la pubblicazione.

Il codice è in `.work/King-Score-atomic/src/utils/elo/readModel.ts` e `projections.ts`; il contratto tecnico e la mappa dei caller sono in `.work/King-Score-atomic/docs/ELO_READ_CONTRACT.md`. Le tabelle e le funzioni della prova restano in `local-tests`: non sono una migrazione Supabase.

## Risultati verificati

Passano 323 test web in 15 file, inclusi 37 nuovi test di contratto, decoder e proiezioni. Typecheck, lint mirato e build passano. PostgreSQL 17.4 supera tutti i 38 casi locali, inclusi dieci nuovi casi su ambito globale e letture. Gli hash di `simulation.ts` e `positions.ts` sono invariati.

Il [report](evidence/elo-read-validation.json) conserva esiti e limiti. Run `529a5896a55849e5a2f1073a5fa2e05b`: `serverStopped=true`, `remoteContacted=false`. Le risposte SQL reali sono decodificate prima di proiettare le quattro viste; le letture funzionano in transazioni read-only. Le 1.200 partite restano complete, senza troncamento a 1.000. Questa è una verifica di correttezza, non un benchmark di capacità massima.

Commit web soltanto locale: `e31bb88ca22599ad9e51d061370b75fbaf8eb814`, branch `codex/atomic-play-writers`, senza push. La documentazione e una copia del report sono conservate nella repo Android.

## Limiti prima dell'integrazione

Le pagine legacy del sito e il client Android non sono ancora collegati a questo contratto. I nuovi moduli non cambiano il sito distribuito. Login obbligatorio e blocco senza Internet dell'app rimangono invariati; uno snapshot in memoria non autorizza uso offline o consultazione dopo cambio account.

Restano da collegare worker riavviabile, invalidazione dei writer reali e letture coordinate delle schermate; vanno ritirati i fallback che simulano o scrivono cache durante le letture. I bucket HU/3/4/5+ richiedono generazioni sul proprio sottoinsieme, perché filtrare l'output globale non produce gli stessi rating. Ordine persistito dei partecipanti, trasporto e limiti di risposta, Auth/RLS e ripristino richiedono ancora verifiche dedicate.

Nessun deploy, push web, modifica a Supabase/Vercel o esecuzione della CI GitHub. Il prossimo intervento è collegare il worker e l'invalidazione sul PC.
