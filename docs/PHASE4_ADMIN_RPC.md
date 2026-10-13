# Preparazione RPC partite admin

Il backend locale contiene `get_play_editor_v1`, `create_play_v1`, `update_play_v1` e `delete_play_v1`, con test SQL e sessioni concorrenti. Nessuna migrazione è stata applicata a Supabase remoto. L'app resta alla 0.4.0: l'editor partite è disabilitato, login e Internet restano obbligatori. La CI GitHub rimane sospesa su richiesta dell'utente.

## File e responsabilità

Nel repository King-Score:

| File | Contenuto |
| --- | --- |
| `supabase/migrations/20261013000000_atomic_play_writes.sql` | Quattro RPC pubbliche, helper e ricevute private, correzione trigger score, revoca privilegi che eludono RLS |
| `supabase/tests/atomic_play_fixture.sql` | Schema sintetico dal catalogo auditato, policy, grant e trigger, senza dati reali |
| `supabase/tests/atomic_play_writes.sql` | Controlli di accesso, validazione, replay, rollback, revisioni e cascade |
| `supabase/tests/atomic_play_concurrency.mjs` | Richieste SQL concorrenti e confronto con `src/utils/elo/positions.ts` |
| `scripts/test-atomic-plays.ps1` | Cluster PostgreSQL locale separato, log, report e arresto del server nel finally |

Il runner richiede PowerShell 7, PostgreSQL 17 e Node 24. Dalla radice del repository web:

```powershell
pwsh -NoProfile -File scripts/test-atomic-plays.ps1 -ConcurrentScript "$PWD/supabase/tests/atomic_play_concurrency.mjs"
```

La suite usa esclusivamente `127.0.0.1`, ruoli e claim sintetici, con variabili PG* ripulite e file di configurazione psql esclusi. Non usa `.env`, password, SDK Supabase o connessioni remote. Il report distingue bootstrap da suite completa; conserva i log e verifica l'arresto del proprio cluster.

## Contratti implementati

Ogni scrittura verifica account e ruolo admin prima di leggere una ricevuta. Gli amministratori operano su tutte le partite consentite dalla RLS corrente. Una ricevuta privata per `(actor_id, request_id)` registra richiesta completa e risultato nella stessa transazione della mutazione: un retry identico restituisce `replayed=true`, anche dopo modifiche successive o eliminazione del target. Il riuso della chiave con un'altra richiesta è `PT409`. Non esiste un retry automatico Android o una coda offline.

Create assegna l'owner dalla sessione. Update conserva owner storico, location, bga_table_id, created_at e ID degli score mantenuti; il client deve reinviare color e role. Il server rifiuta score ID estranei. Se ID/player/score/winner, modalità ed esito team restano invariati, conserva le posizioni anche quando il payload cambia ordine: note, color e role non devono riclassificare i pareggi. V1 non offre un comando di riordino manuale. Negli altri casi deriva il ranking dal nuovo input.

Il lock segue richiesta, partita, score ordinati per ID; revisione diversa produce `PT409` prima della mutazione. Getter e revisione derivano dallo stesso snapshot SQL: l'hash comprende partita e score completi, esclude nomi, immagini, commenti e rating, e usa timestamp UTC. Il getter presenta gli score per posizione e ID, mentre l'hash usa l'ordine stabile degli ID.

Il database non possiede `play_scores.updated_at`: il vecchio trigger fallisce durante UPDATE. La migrazione rimuove quel solo trigger, preservando la funzione condivisa e i trigger validi delle altre tabelle. Revoca inoltre TRUNCATE/REFERENCES/TRIGGER ad anon/authenticated su plays e play_scores: TRUNCATE non è protetto dalle policy di riga. Mantiene SELECT e le scritture legacy del sito.

Nessuna RPC aggiorna rating o avvia ricalcoli. Delete conserva le cascade esistenti per score, commenti ed elo_history. I rating pubblicati possono quindi precedere la modifica: il client non deve promettere un ELO aggiornato.

## Ranking conservato e limiti V1

L'utente ha confermato il comportamento attuale del sito per punteggi mancanti. La funzione web assegna priorità ai winner; confronta i numeri in ordine decrescente solo quando entrambi sono presenti; un confronto con NULL vale zero. I pareggi numerici fra non vincitori mantengono posizioni distinte nell'ordine prodotto dal sort. Più vincitori hanno tutti posizione 1; con due vincitori il primo non vincitore è terzo. Le cooperative con esito noto hanno posizione 1 o 2 per tutti.

Il confronto con NULL non è transitivo, quindi un ORDER BY SQL con NULLS LAST cambierebbe il risultato. V1 riproduce natural run e binary insertion del percorso V8 per array piccoli. Il limite esplicito è 63 partecipanti e 1 MiB di payload, con rifiuto prima della scrittura; nessun dato viene troncato. Le letture storiche non cambiano. I punteggi restano NUMERIC esatti; il ranking usa Float64 come il sito e rifiuta overflow/underflow non rappresentabili. I contratti Kotlin anticipano i limiti di partecipanti, date e punteggi.

Il test differenziale importa la funzione web effettiva, con casi esaustivi ridotti e casi deterministici fino al limite. I risultati valgono per le versioni Node/V8 registrate nell'evidenza. Altri browser o un aumento del limite richiedono una nuova verifica; nessuna logica ELO o dato storico è stato modificato.

## Validazione eseguita

Il run completo del 13 ottobre 2026 passa su PostgreSQL 17.4 locale, Node 24.19.0 e V8 13.6.233.17-node.51. [L'evidenza JSON](evidence/phase41-admin-rpc-validation.json) registra versioni, codici di uscita, commit backend e hash dei sorgenti verificati.

| Verifica | Risultato |
| --- | --- |
| Suite SQL | PASS: permessi, validazione, rollback create/update/delete, receipt, revisioni, metadata, limite 63/64, UTC, cascade e rating/job invariati |
| Sessioni concorrenti | 3 prove PASS: create duplicata, update con revisione condivisa, delete concorrente e replay |
| Ranking SQL contro funzione web | 1.616 casi PASS, inclusi NULL, vincitori multipli, pareggi e cooperative |
| Contratti e caratterizzazione | 11 controlli Kotlin, 10 controlli Node dell'audit e 7 test web delle posizioni PASS |
| Lint e pulizia | Lint del nuovo script Node PASS; cluster locale arrestato, nessun postmaster.pid residuo nei cluster di questa attività |

## Requisiti prima del rollout

1. Adeguare `AddPlay`, `EditPlay`, `PlayDetail` e import BGA alle transazioni e al medesimo ordine dei lock. `replace_play_scores` e le scritture dirette attuali non garantiscono atomicità tra metadati e score; i test RPC contro RPC non risolvono quel limite.
2. Verificare schema, policy, grant e cron dell'ambiente Supabase isolato. Applicare la sola migrazione prevista dopo aver riconciliato la storia delle migrazioni: non eseguire tutte le vecchie migrazioni sul database auditato.
3. Eseguire test HTTP con JWT reali admin e ordinario, compresi revoca ruolo, status 404/409, timeout dopo commit e retry. Le claim SQL simulate non validano firma JWT, gateway o configurazione degli schemi esposti.
4. Collegare l'editor Android ai contratti verificati, gestendo conflitti ed esiti incerti senza fallback multi-write. Eseguire poi prove di rete, cambio account, accessibilità e telefono prima della release privata firmata.

La preparazione locale non completa il CRUD admin nell'app. Benchmark mobili e prestazioni in Supabase non sono stati misurati in questa attività.
