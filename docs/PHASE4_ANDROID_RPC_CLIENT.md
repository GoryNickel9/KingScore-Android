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

### Risposte di errore ambigue, 14 ottobre 2026

Il client ora richiede un codice macchina coerente con lo stato HTTP prima di cancellare una richiesta nuova o rinnovare la sessione. La tabella precedente descriveva il primo incremento; un semplice stato HTTP non basta più.

| Stato HTTP e codice | Prima richiesta | Recupero di una richiesta già incerta |
| --- | --- | --- |
| 400/PT400, 403/PT403 o 42501, 404/PT404, 409/PT409 | Errore del contratto; la transazione corrente è rifiutata e la richiesta nuova viene rimossa | La richiesta originale resta conservata |
| 404/PGRST202 | RPC indisponibile; nessun fallback | La richiesta originale resta conservata |
| 401/PT401 o PGRST301 | Un solo rinnovo Auth, con lo stesso UUID e corpo | La richiesta originale resta conservata anche se il rinnovo non risolve il rifiuto |
| Corpo vuoto, HTML, codice assente/non testuale/incoerente, 422 o codice sconosciuto | UNCERTAIN; nessun rinnovo o reinvio automatico della mutazione | La richiesta originale resta conservata |

La stessa risposta ambigua sul getter produce INVALID_RESPONSE e non modifica il journal. Le risposte reali di errore attraversano il filtro già presente del trasporto, che conserva soltanto il codice macchina e limita la lettura a 8 KiB. Le nuove prove verificano 27 combinazioni di stato/corpo e il recupero dopo ricreazione del client, usando uno store in memoria condiviso, Auth simulata e trasporto controllato. Non certificano l'integrazione con lo store Android cifrato o con le RPC reali. La produzione resta disabilitata.

Questo primo incremento conservava la richiesta nella sola istanza del client. Il successivo journal prepara persistenza cifrata, ripristino verificato per account e conservazione della conferma dopo risposta: [STABILITY.md](STABILITY.md). Il client predefinito resta disabilitato e con store in memoria; il factory dell'editor dovrà iniettare lo store cifrato e condividere il journal. Nessuna riconnessione invia automaticamente dati. Un replay o una conferma recuperata riguardano la vecchia operazione; la UI futura dovrà rileggere lo stato corrente della partita.

## Validazione

I test JVM usano Auth SDK con MockEngine, risposte RPC controllate e socket TCP su 127.0.0.1. Verificano encoder/decoder, precisione, limiti, Unicode, getter storico, permessi, RPC assente, conflitti, rinnovo 401, concorrenza, annullamento, chiusura e recupero immutabile. Il TCP reale verifica 503, redirect, perdita connessione e filtraggio del codice errore. Le prove native SQL precedenti non vengono rieseguite: candidata e cutover non sono modificati.

Risultati della suite completa, lint, APK e hash: [android-rpc-client-validation.json](evidence/android-rpc-client-validation.json). Il workaround Java locale resta ignorato e non entra in APK o CI. Le prove non certificano Supabase Auth reale, integrazione SQL attraverso questo client, schermate Compose, telefono o recupero dopo riavvio.

## Prossima attività

Il [controller e la schermata editor](PLAY_EDITOR_STATE.md) preparano ora bozze, conflitti, conferma della cancellazione e recupero con fixture. Factory, navigazione reale e ciclo di vita completo restano da integrare; le scritture reali sono disabilitate.
