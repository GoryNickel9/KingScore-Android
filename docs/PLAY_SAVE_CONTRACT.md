# Contratto dei salvataggi partite, proposta V1

Definizione del 14 ottobre 2026. Questa proposta e le prove sono contenute soltanto in KingScore-Android. Le nuove RPC non sono implementate o deployate; l'editor partite Android resta disabilitato. King-Score, Vercel e Supabase remoto non vengono modificati. Il catalogo di riferimento è l'audit già salvato del 13 ottobre, senza nuove letture remote.

## Richieste

Il contratto leggibile dalle macchine è [play-saves.json](../contracts/v1/play-saves.json). Tutte le operazioni richiedono un JWT valido, un'identità non nulla e `app_metadata.role=admin`, verificati sul server prima di lookup o replay. Il permesso admin resta globale, come nel sito attuale. `user_metadata` non concede permessi.

| RPC proposta | Argomenti | Risposta di successo |
| --- | --- | --- |
| get_play_editor_v1 | p_play_id | detail e revision dallo stesso snapshot |
| create_play_v1 | p_request_id, p_payload | play_id, revision, replayed |
| update_play_v1 | p_request_id, p_play_id, p_expected_revision, p_payload | play_id, revision, replayed |
| delete_play_v1 | p_request_id, p_play_id, p_expected_revision | play_id, deleted=true, replayed |
| import_bga_play_v1 | p_request_id, p_payload, p_metadata, p_positions | play_id, revision, replayed, skipped |

`play_id` è UUID; revision è una stringa opaca; replayed, deleted e skipped sono booleani. Un 200 con campi mancanti, tipi errati o un ID inatteso non è un successo confermato.

Il payload contiene esattamente game_id, played_at, duration_minutes, source, notes, is_cooperative, team_won e scores. Gioco e partecipanti devono esistere; nessuna creazione implicita dei cataloghi. Date di calendario tra 0001-01-01 e 9999-12-31, senza conversione timezone. Duration è null o un intero non negativo entro int32; source è offline oppure online. Offline descrive il luogo della partita, non una modalità offline dell'app.

Ogni score contiene esattamente id, player_id, score, winner, color e role. In creazione id è null; in modifica gli ID esistenti devono appartenere alla partita. ID null indica un partecipante nuovo. Da uno a 63 partecipanti, senza duplicati di player o score ID. I partecipanti omessi in un update vengono rimossi nella stessa transazione; quelli mantenuti conservano gli ID. Color e role devono essere reinviati per ciascun partecipante mantenuto, anche quando null.

Le letture storiche restano più permissive degli input V1: source manual/null, gioco assente, winner null o quantità fuori limite possono comparire nei dati. Il client non deve convertirli o troncarli silenziosamente per salvare una modifica. La gestione editor di queste partite richiederà una scelta esplicita prima dell'implementazione; questa proposta non garantisce già la modifica di ogni riga storica.

Notes, color, role e location accettano testo Unicode valido o null; NUL e surrogate isolate sono rifiutati. Il limite del payload è 1 MiB di UTF-8 nella rappresentazione PostgreSQL jsonb::text. Il server deve verificare questo limite prima di modificare dati. La verifica JS prepara il controllo della stessa rappresentazione; il confronto esatto col SQL resta un test di accettazione della futura implementazione.

## Punteggi senza perdita di precisione

Il wire format comune a web e Android usa score come **stringa decimale oppure null**, per esempio `"9007199254740993.125"`. Nessun passaggio da Number per salvare il valore. Separatore punto, segno meno facoltativo, niente esponenti o zeri iniziali superflui. Android conserva BigDecimal nel dominio e serializzerà con toPlainString. Questa definizione sostituisce l'esempio JSON numerico della proposta precedente, che non è mai stata pubblicata.

Il getter editor restituisce anche gli score come stringhe decimali o null; il client li decodifica nei propri tipi di dominio. Le letture delle tabelle esistenti restano invariate e continuano a restituire NUMERIC come token JSON numerici.

Il ranking mantiene il confronto Float64 del sito, separato dal NUMERIC salvato. Sono rifiutati valori che diventano Infinity o uno zero per underflow, salvo lo zero reale. NULL resta distinto da zero. Winner, pareggi e cooperative mantengono il comportamento concordato del sito: nessuna nuova regola NULLS LAST. Una modifica soltanto di note, color o role conserva le posizioni se partecipanti, ID, score, winner, modalità ed esito team sono invariati. La verifica dell'algoritmo di ranking resta da eseguire sulla nuova implementazione.

## Transazioni, duplicati e concorrenza

Creazione e modifica devono salvare metadati, score e ricevuta in una transazione. Un input invalido lascia il database nello stato precedente. Delete comprende figli eliminati dalle cascade esistenti e ricevuta. Non applicare una compensazione client che elimini automaticamente una partita dopo un timeout.

La chiave della ricevuta è actor_id verificato più request_id. La richiesta registrata comprende operazione, target, revisione, payload e dati importati. Una ripetizione identica restituisce il risultato registrato con replayed=true, anche se il target è stato poi eliminato. Stessa chiave con contenuto differente produce 409; per esempio score `"1"` e `"1.0"` sono due payload distinti. Le ricevute V1 non vengono eliminate automaticamente: un'eventuale retention richiede un limite di idempotenza esplicito.

Update e delete ricevono la revisione letta dall'editor. La revisione include tutte le colonne della partita e degli score, con serializzazione e ordine deterministici; esclude nomi dei cataloghi, immagini, commenti e rating. Il server acquisisce lock coerenti e confronta la revisione prima di scrivere. Due aggiornamenti diversi della stessa revisione devono produrre un solo successo e un conflitto 409.

Non basta introdurre le RPC: import, altri client e processi backend che scrivono punteggi devono rispettare la stessa disciplina. La chiusura dei vecchi percorsi di scrittura è un'attività successiva, da provare localmente. Nessun cutover è eseguito ora.

## Import BGA e rating

L'import usa gli stessi campi del payload e aggiunge metadata `{location,bga_table_id}` e positions, nell'ordine degli score. Ogni posizione è null oppure un intero positivo int32. Il table ID è testo non vuoto, senza spazi, lungo al massimo 256 code point. Le posizioni esterne sono conservate, senza riclassificazione manuale.

Actor e table ID identificano la duplicazione dell'import. Un'altra richiesta per la stessa coppia restituisce skipped=true e la partita già presente, senza riscriverla; il riuso della stessa richiesta restituisce invece il replay. Il campo skipped deve distinguere chiaramente un nuovo import da uno già presente. Partita, score, metadata e ricevuta devono restare nella stessa transazione.

Nessuna RPC calcola ELO, modifica rating o avvia job. Delete mantiene le cascade esistenti, comprese quelle sullo storico associato alla partita. I flussi ELO del sito saranno verificati separatamente nella futura integrazione; Android legge soltanto i rating pubblicati.

## Errori e recupero client

| Caso | Risposta / comportamento |
| --- | --- |
| Sessione mancante o invalida | 401; nessuna scrittura anonima |
| Ruolo non admin | 403; controllo prima di target o ricevuta |
| Input, formato o riferimento non valido | 400; nessuna modifica parziale |
| Target assente senza ricevuta valida | 404 |
| Revisione obsoleta o chiave riusata diversamente | 409 |
| Timeout, 5xx, risposta 2xx invalida | Esito incerto: bloccare nuovi invii, conservare richiesta e UUID |

Un eventuale retry è manuale e usa esattamente la richiesta pendente. Nessun reinvio automatico dopo il ritorno della rete. Dopo un conflitto mostrare la bozza e chiedere una rilettura esplicita. La futura integrazione dovrà dichiarare cosa succede alla richiesta pendente dopo reload o chiusura del processo: questo contratto non promette persistenza client già implementata.

La verifica locale usa JWT HS256 sintetici. Non valida il servizio di login reale, refresh delle sessioni o revoca remota effettiva. Il controllo ruolo della RPC usa le claim del token valido; non promette revoca immediata di JWT già emessi. Questi limiti vanno mantenuti visibili nei risultati.

## Criterio di completamento

La definizione è verificata dai test di payload. La preparazione dell'ambiente è verificata dal runner locale e dalla riproduzione dei difetti attuali. I [21 casi di accettazione](../local-tests/acceptance-cases.json) sono **pending**, finché non esiste una nuova implementazione atomica e non vengono eseguiti. Nessuna di queste prove abilita automaticamente le scritture Android o autorizza un deploy.
