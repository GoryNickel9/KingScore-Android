# Modifiche Supabase proposte, da approvare prima dell'applicazione

Aggiornato il 15 ottobre 2026. Autorizzati codice web locale e test sul PC. Nessun deploy o cambiamento Supabase eseguito. Le istruzioni seguenti descrivono la proposta; non costituiscono un'autorizzazione ad applicarla.

## 1. Installazione additiva già preparata

Nel checkout web locale `.work/King-Score-atomic`, il file `scripts/atomic-play-migrations/01-install-rpcs.sql` ha SHA-256 `f0b4e4a28ba4bcc89c164627de8e9db2275b9aaaf1c51a083cfdbaebf8305047`.

| Oggetto | Modifica proposta | Effetto |
| --- | --- | --- |
| API editor | `get_play_editor_v1` | Snapshot con revisione, punteggi come testo e motivi di non modificabilità. |
| API salvataggio | `create_play_v1`, `update_play_v1`, `delete_play_v1`, `import_bga_play_v1` | Partita, punteggi e ricevuta nella stessa transazione; rollback completo se una parte fallisce. |
| Ricevute | Schema privato `kingscore_atomic`, tabella `receipts`, helper privati | UUID per attore e richiesta immutabile; recupero senza duplicati dopo una risposta persa. |
| Colonne opzionali | `plays.location`, `plays.bga_table_id`, `play_scores.color`, `play_scores.role`, se assenti | Colonne `text NULL`; nessuna conversione arbitraria dei tipi esistenti. |
| Compatibilità vincoli | UNIQUE partecipante supportati resi `DEFERRABLE INITIALLY IMMEDIATE`; rimozione del solo trigger timestamp orfano riconosciuto | Permette scambi fra partecipanti nella stessa modifica. Configurazioni sconosciute fanno fallire l'installazione. |

Update e delete bloccano le righe e controllano la revisione prima di cambiare dati. Un admin può gestire tutte le partite; l'update mantiene il proprietario storico e i metadati non modificati. Il ranking mantiene il comportamento del sito, incluso il confronto dei punteggi assenti. L'import conserva le posizioni esterne e deduplica per attore/tavolo; duplicati storici ambigui bloccano l'installazione anziché essere cancellati.

Il file non riscrive lo storico e non avvia ricalcoli ELO. La creazione separata di giochi e giocatori non rientra nella transazione della partita.

## 2. Permessi

Le cinque funzioni pubbliche usano `SECURITY DEFINER` e `search_path=''`. Revocano EXECUTE ai ruoli generici e concedono EXECUTE solo ad `authenticated`; ogni funzione verifica internamente utente e claim JWT `app_metadata.role = admin`. Il solo login non autorizza un salvataggio.

Schema, tabella delle ricevute e helper non sono accessibili ai client. Le ricevute hanno RLS abilitata senza policy client e una FK verso `auth.users`. Nessuna password, chiave service role o nuova credenziale viene aggiunta al client. La revoca del ruolo admin non annulla istantaneamente un JWT già emesso: la politica di scadenza/refresh deve essere verificata prima della pubblicazione.

**Questa installazione lascia invariati RLS e permessi DML delle tabelle applicative.** Non basta per impedire che un vecchio sito o un servizio continui a salvare con il percorso precedente.

## 3. Modifiche ancora da definire prima del passaggio completo

1. **Chiudere i percorsi legacy.** Inventariare i permessi reali, migrare o ritirare `replace_play_scores` e il DML diretto, coordinare i servizi e le vecchie schede browser. Le revoche definitive non sono ancora contenute in uno script di cutover verificato.
2. **Gestire le cascade dei cataloghi.** Eliminare giochi o giocatori può cancellare partite o partecipanti fuori dalle RPC; le eliminazioni delle partite possono coinvolgere anche commenti. Revocare il solo DML su `plays` e `play_scores` non blocca le cascade delle FK.
3. **Integrare l'aggiornamento ELO.** Il protocollo è ora definito e verificato sul PC: revisione sorgente, generazione completa, pubblicazione atomica e rifiuto dei calcoli superati, mantenendo la formula. Passano 36 nuovi test e 28 casi PostgreSQL. È un prototipo separato, non una migrazione o un worker attivato. Le nuove RPC partite non pubblicano ancora la classifica; restano ambito dei reader, ordine dei partecipanti, collegamento dei writer e ritiro delle cache legacy. Dettagli in [ELO_PUBLICATION_REVIEW.md](ELO_PUBLICATION_REVIEW.md).
4. **Verificare lo schema effettivo e il ripristino.** Il preflight read-only preparato sul PC non certifica schema, Auth, RLS e grant oggi installati. Un inventario remoto richiederà autorizzazione. Vanno controllati anche eventuali consumer esterni con `ON CONFLICT`, incompatibile con l'UNIQUE reso deferrable.

Queste dipendenze bloccano il deploy dell'integrazione completa. Il flag web `VITE_ATOMIC_PLAY_WRITES` resta disabilitato per impostazione predefinita; l'editor Android di produzione resta disabilitato.

## 4. Installazione e ripristino

L'installazione proposta richiede PostgreSQL almeno 15 e un proprietario affidabile con bypass RLS. Usa una transazione e lock `ACCESS EXCLUSIVE` su `plays`/`play_scores`: può bloccare temporaneamente anche letture. Attende al massimo 5 secondi per i lock e imposta un limite di 120 secondi per gli statement; va pianificata una finestra adatta e verificata la configurazione effettiva di PostgREST/ruoli.

Prima del COMMIT, un errore annulla l'intera installazione. Dopo il COMMIT non esiste ancora uno script di rollback operativo collaudato. Rimuovere le funzioni non annulla partite già create, aggiornate o eliminate; eliminare colonne o ricevute può perdere dati e garanzie di replay.

Il piano da preparare prevede backup coerente con procedura di restore provata, definizioni originali di vincoli/trigger e finestra di riconciliazione delle richieste incerte. Non usare `DROP SCHEMA ... CASCADE` come ripristino. Disabilitare i moduli web blocca anche il recupero da quel client: occorre mantenere un percorso di replay per le richieste precedenti prima di revocare le RPC. Dopo un futuro cutover dei grant, tornare al vecchio frontend da solo non ripristinerà i salvataggi.

## 5. Verifiche locali del 15 ottobre

La migrazione con l'hash indicato ha superato 16 profili di compatibilità e 21 casi di accettazione su PostgreSQL/PostgREST loopback con dati sintetici. Report: `.work/local-play-tests/9305f263d6f84c14bfb0bff615999463/report.json`; `serverStopped=true`, `remoteContacted=false`, `githubCiExecuted=false`.

Il checkpoint web locale è `10110365aa161518c69ee3af6c17f6a95c42dd80`, branch `codex/atomic-play-writers`, senza push. Passano 250 test web in 12 file, typecheck, lint (0 errori, 16 warning) e build con flag abilitato/disabilitato.

Le pagine web sono state verificate separatamente nel browser con SDK reale e ricevitore HTTP sintetico: creazione, modifica di partita altrui, conflitto, eliminazione, import e recupero dopo reload. Non è una verifica Auth/RLS remota né un collaudo browser contro PostgreSQL. Risultati e comandi sono descritti in `.work/King-Score-atomic/docs/ATOMIC_PLAY_WEB.md`.

La successiva definizione ELO è verificata nel checkpoint [ELO_PUBLICATION_REVIEW.md](ELO_PUBLICATION_REVIEW.md): 286 test web complessivi, 28 casi PostgreSQL, typecheck, lint mirato e build passati. Nessuna modifica alla formula o ai servizi online.

Il successivo [contratto di lettura](ELO_READ_REVIEW.md) fissa una classifica unica globale: passano 323 test web e 38 casi PostgreSQL locali, con formula invariata. Decoder e proiezioni non sono ancora collegati alle pagine distribuite. Il prossimo intervento è preparare sul PC il collegamento del worker e dell'invalidazione dei writer prima di proporre l'applicazione online.
