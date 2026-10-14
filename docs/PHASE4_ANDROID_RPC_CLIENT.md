# Client RPC Android, preparazione locale

Il client implementa getter editor, creazione, modifica ed eliminazione secondo il [contratto V1](PLAY_SAVE_CONTRACT.md). Non è collegato alla navigazione, a PlaysRepository o all'Application. `SupabasePlayWrites` è disabilitato per default: senza attivazione esplicita restituisce UNAVAILABLE prima di verificare la sessione o inviare richieste. Soltanto i test lo attivano con Auth simulata e trasporto controllato.

L'APK resta 0.4.0, con editor partite disabilitato. Non sono modificati King-Score, Vercel o Supabase. Nessuna richiesta di questa preparazione raggiunge il backend reale; non vengono introdotti import BGA, ricalcolo ELO, registrazione, modifica credenziali/avatar o invii offline.

## Codice e contratti

| Componente | Responsabilità |
| --- | --- |
| contracts/v1/Commands.kt | Unicode PostgreSQL valido per note/color/role, revisione ASCII opaca limitata, editability con flag e motivazioni coerenti |
| PlaySaveJson | Argomenti delle quattro RPC; punteggi testuali esatti, byte JSONB, decoder editor e risultati con controllo degli ID |
| PlayWrites | Interfaccia tipizzata, errori distinti, indicatore della richiesta incerta e ripetizione manuale |
| SupabasePlayWrites | Protezione disabilitata per default, verifica online dell'account, Bearer verificato, serializzazione degli invii e nessun fallback REST |
| NoRetryPlayRpcTransport | Riuso del trasporto HTTP esistente senza redirect o reinvii automatici, anche per 503 Retry-After |

Il trasporto commenti conserva il comportamento predefinito. Il solo client RPC attiva la lettura di un codice errore da un corpo limitato a 8 KiB: messaggi e dettagli server vengono scartati. Questo consente di distinguere una RPC mancante, PGRST202, da una partita assente. Le eccezioni non contengono corpi, token o richieste.

I punteggi sono BigDecimal nel dominio e stringhe decimali sul wire. Prima di toPlainString si verifica la lunghezza: anche uno zero con scala enorme deve essere rifiutato senza allocare una stringa di miliardi di caratteri. La dimensione dell'intero payload include spazi ed escape di jsonb::text e deve restare entro 1 MiB. Le collezioni dei partecipanti sono ricopiate e validate durante la serializzazione.

Il decoder editor riusa RowDecoders dopo aver convertito il solo punteggio testuale nel token numerico esatto. Le letture REST esistenti continuano a richiedere token numerici. Nullable storici e più di 63 partecipanti restano leggibili senza troncamento; il flag editability viene dal server. Valori NUMERIC non finiti non sono rappresentabili dai DTO BigDecimal e producono INVALID_RESPONSE, senza convertirli in zero: la loro eventuale presentazione richiede una decisione successiva.

## Invio e recupero

Una mutazione conserva una copia JSON immutabile prima dell'invio. Il token viene acquisito tramite withVerifiedSession per l'account atteso. La revisione di update/delete resta quella della richiesta preparata. Dopo un successo valido si azzera lo stato pendente; ID inattesi o risposte incomplete mantengono l'incertezza.

| Situazione | Comportamento |
| --- | --- |
| Client disabilitato o chiuso | Nessuna verifica Auth o richiesta RPC |
| Rete assente / account differente / sessione mancante | Invio bloccato |
| Mutazione già in corso | BUSY; nessun secondo invio accodato |
| 401 esplicito | Un solo rinnovo e ripetizione della stessa richiesta con il nuovo Bearer |
| 400, 403, 404 o 409 alla prima richiesta | Errore distinto e nessun esito incerto nuovo |
| Timeout, errore 5xx, redirect o successo non interpretabile | Richiesta incerta; nuovi salvataggi bloccati |
| Perdita rete, cancellazione o chiusura dopo l'invio | Risposta non pubblicata e richiesta mantenuta come incerta |
| retryPending esplicito | Reinvia esattamente il JSON e l'UUID conservati; valida anche un replay dopo delete |
| Ripetizione incerta rifiutata per sessione o permesso | La vecchia incertezza rimane: il rifiuto attuale non prova che il primo invio non abbia committato |
| Getter editor | Non crea e non cancella l'incertezza di una mutazione |

La memoria pendente appartiene a questa istanza del client. Non esiste persistenza su disco, outbox o recupero dopo morte del processo. Una nuova istanza non eredita automaticamente la richiesta; prima della UI occorre decidere durata, recupero e ciclo di vita per account. Nessuna riconnessione invia automaticamente dati. Un replay conferma l'esito della vecchia operazione; la UI futura dovrà rileggere lo stato corrente della partita.

## Validazione

I test JVM usano Auth SDK con MockEngine, risposte RPC controllate e socket TCP su 127.0.0.1. Verificano encoder/decoder, precisione, limiti, Unicode, getter storico, permessi, RPC assente, conflitti, rinnovo 401, concorrenza, annullamento, chiusura e recupero immutabile. Il TCP reale verifica 503, redirect, perdita connessione e filtraggio del codice errore. Le prove native SQL precedenti non vengono rieseguite: candidata e cutover non sono modificati.

Risultati della suite completa, lint, APK e hash: [android-rpc-client-validation.json](evidence/android-rpc-client-validation.json). Il workaround Java locale resta ignorato e non entra in APK o CI. Le prove non certificano Supabase Auth reale, integrazione SQL attraverso questo client, schermate Compose, telefono o recupero dopo riavvio.

## Prossima attività

Definire il ciclo di vita e il recupero delle richieste incerte prima di implementare il ViewModel editor, mantenendo disabilitate navigazione e scritture reali.
