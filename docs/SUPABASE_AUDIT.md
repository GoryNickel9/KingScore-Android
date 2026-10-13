# Verifica Supabase per KingScore Android

Il 13 ottobre 2026 è stato verificato il progetto collegato `rixivmeipbizoxyvrvaf`, nome `ScoreKing`, tramite Supabase CLI 2.115.0. Il catalogo remoto conferma PostgreSQL 17, dieci tabelle `public` con RLS attivo e ventiquattro policy pubbliche. Il progetto collegato non è stato certificato come ambiente di sviluppo: tutte le verifiche remote hanno evitato modifiche a schema e dati.

## Evidenze e metodo

| Evidenza | Controllo |
| --- | --- |
| `evidence/supabase-audit.json` | Colonne, tipi SQL, vincoli, indici, grant, policy, funzioni e trigger; transazione `READ ONLY` con rollback |
| `evidence/schema.remote.ts` | Tipi rigenerati dal progetto remoto per lo schema public |
| `evidence/schema-comparison.json` | Confronto dei campi Row e nullabilità TypeScript di dieci tabelle; zero differenze con `King-Score/src/types/db.ts` |
| `evidence/permission-probes.json` | Quattro piani EXPLAIN senza ANALYZE e due dinieghi RPC SQLSTATE 42501, con ruoli e identità SQL sintetici |
| `evidence/http-probes.json` | Otto GET PostgREST anonimi con `limit=0`, HTTP 200 e zero righe; GET Auth senza sessione, HTTP 401 |

Non sono stati esportati account, partite, commenti, password, token o chiavi. Le probe SQL verificano i ruoli PostgreSQL e i controlli della funzione; non sostituiscono login Auth reali o test HTTP di scrittura.

## Schema confermato

1. `games` è un catalogo condiviso: `id` e `name` obbligatori, `bga_id` testuale opzionale e univoco; nessuna colonna `user_id`.
2. `plays.played_at` è SQL `date`, non timestamp. `plays` non ha `updated_at`; `game_id`, `user_id`, campi cooperativi e metadati possono essere null nei dati storici.
3. `play_scores.score` è `numeric` nullable senza precisione fissa: preservare zero, valori negativi e decimali. `winner`, `position`, `color` e `role` sono nullable. Non esiste un vincolo UNIQUE su `(play_id, player_id)`.
4. Rating e variazioni ELO sono interi. `elo_history.date` è `timestamptz`. `players.user_id` è il proprietario storico; `account_user_id` è il collegamento con l'account.
5. I commenti hanno campi obbligatori, contenuto tra 1 e 1.000 caratteri SQL dopo `btrim`, e un trigger che aggiorna nome autore e data modifica.

La rigenerazione TypeScript coincide semanticamente con i tipi web esistenti; non è stato necessario modificarli. Il replay delle migrazioni storiche rimane diverso dallo schema remoto: la migrazione iniziale contiene `games.user_id`, ulteriori colonne e timestamp che oggi non esistono. Non eseguire automaticamente `db push`, `migration repair` o `db pull` per correggere questo storico.

## Permessi verificati

| Risorsa | Utente ordinario | Admin | Regola Android |
| --- | --- | --- | --- |
| Giochi, partite, punteggi e giocatori | Lettura; avatar del player collegato aggiornabile sul backend | CRUD globale | Lettura e CRUD partite admin; avatar esclusi |
| Commenti | Lettura, creazione, modifica/eliminazione propri | Stessi limiti di ownership | Nessuna moderazione dei commenti altrui |
| Rating e storico ELO | Lettura | Il backend consente anche scritture | App esclusivamente in lettura per tutti |
| `elo_recalc_runs` | RLS senza policy cliente | RLS senza policy cliente | Non leggerlo per mostrare lo stato job |
| `replace_play_scores` | EXECUTE concesso, ma funzione rifiuta il ruolo non-admin | Sostituzione dei punteggi di qualsiasi partita esistente | Non sufficiente per il salvataggio completo |

Letture pubbliche del sito confermate anche via HTTP senza sessione. Il login obbligatorio è un vincolo del client Android e non rende privati i dati del sito. I grant di tabella, da soli, non indicano quali scritture RLS ammetta. Il ruolo admin è letto da `app_metadata`, non da metadati modificabili dall'utente.

La RPC corrente è SECURITY DEFINER, ha `search_path` vuoto, revoca EXECUTE ad anon e controlla admin prima di DELETE/INSERT. Le probe hanno confermato il diniego sia per anon sia per authenticated non-admin. `get_play_user_ids` non è eseguibile da authenticated. La funzione notturna ELO resta esclusa dall'app.

## Difetti e dipendenze per le scritture

| Riscontro | Impatto | Azione necessaria |
| --- | --- | --- |
| Create web salva partita e score in richieste separate; edit salva metadati prima degli score | Possibili partite incomplete o aggiornamenti parziali | RPC transazionali complete prima dell'editor Android |
| `replace_play_scores` non conserva `color` e `role` e accetta un array vuoto | Perdita di metadati o rimozione di tutti i partecipanti | Nuovo contratto completo e validazione server |
| Nessun `plays.updated_at`, idempotenza o vincolo partecipante unico | Conflitti e retry non rilevabili con il contratto attuale | Revisione opaca e receipt delle mutazioni nelle RPC proposte |
| Trigger `update_play_scores_updated_at` attivo, ma `play_scores.updated_at` assente | Il corpo del trigger assegna un campo inesistente durante UPDATE | Correggere la migrazione prima di usare UPDATE sugli score; non è stato eseguito UPDATE remoto |
| Alcuni servizi web usano ancora `games.user_id` | Query incompatibili col catalogo condiviso | Non replicarle nei repository Kotlin; correzione web separata |

Il codice ELO locale non passa i flag cooperativi alla funzione che riassegna le posizioni; è un rischio del calcolo esistente, non un risultato verificato su partite remote. Android non correggerà o ricalcolerà i rating. Una modifica partita può lasciare temporaneamente rating precedenti fino al successivo aggiornamento server; lo schedule effettivo non è stato ispezionato.

## Esito della fase 1

Schema reale, policy e contratti di lettura verificati; contratti Kotlin compilati e testati. Le scritture complete restano una dipendenza della fase 4. Test Auth con account di prova, scritture e concorrenza devono essere eseguiti in un ambiente isolato prima di abilitarle. Nessun deploy o modifica al database è stato effettuato.
