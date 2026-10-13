# Writer web e import atomico

Creazione, modifica, eliminazione e import BGA del sito sono adeguati ai contratti transazionali. Il flusso è pronto ma non attivo: `VITE_ATOMIC_PLAY_WRITES` resta false fino al collaudo e al deploy. Android resta alla 0.4.0, con editor partite disabilitato, login obbligatorio e blocco senza Internet. Nessuna migrazione è stata applicata al Supabase remoto; la CI GitHub rimane sospesa.

## Comportamento preparato

| Percorso web | Cambiamento |
| --- | --- |
| AddPlay | Un'unica RPC per partita e score, retry esplicito con richiesta invariata, UUID stabili dei giocatori temporanei |
| EditPlay | Snapshot e revisione, score ID/color/role conservati, null e decimali accettati, conflitto con bozza visibile e rilettura esplicita |
| PlayDetail | Revisione acquisita alla conferma delete, doppio invio bloccato, replay dopo timeout |
| Import BGA | Location/table_id e posizioni esterne preservati, deduplica per owner/table, stop dopo esito incerto |
| playWrites | Utente verificato online, JWT legato alla richiesta, errori e risposta controllati senza fallback multi-write |

Con il flag false il sito conserva i percorsi precedenti. Con il flag true un errore RPC non riattiva le scritture dirette. Il cutover SQL resta fuori dalle migrazioni automatiche e va applicato dopo tutti i writer: revoca DML diretto e replace_play_scores ai vecchi client, mantenendo RPC e letture.

## Precisione e recupero

Il browser trasferisce i punteggi come stringhe decimali, per esempio `9007199254740993.125`. Le nuove RPC web convertono in NUMERIC dopo receipt e lock; il getter web restituisce testo. Il getter Android continua a restituire token JSON numerici per BigDecimal. Entrambi condividono revisione, lock e transazione; i contratti Android originali restano invariati.

La richiesta usa il token verificato con getUser anche se la sessione SDK cambia nel frattempo. Moduli e operazioni pendenti restano legati ad account e partita originali: il cambio di contesto nasconde la bozza precedente e blocca nuove richieste. Letture obsolete vengono scartate; un errore di sessione durante un retry non cancella il request_id dall'esito ancora incerto.

Il recupero vale nella pagina aperta. Reload, logout che smonta la pagina e chiusura del browser perdono la richiesta pendente: dopo un create manuale incerto verificare lo storico prima di un nuovo invio. Non è un recupero dopo riavvio. BGA aggiunge la deduplica server per owner/table_id; il riepilogo distingue partita incerta e righe non inviate, senza dichiarare un successo completo.

Il preload dell'import attraversa tutte le pagine da 1.000, senza il filtro games.user_id inesistente nel catalogo. Risolve tutti i partecipanti prima della RPC; errori non scartano una persona silenziosamente. Cataloghi e partita restano operazioni separate; partita e score costituiscono una transazione.

I flussi ELO del sito dopo successo restano separati dalle RPC. Delete usa l'owner storico; l'invalidazione dopo edit filtra lo storico per owner anche per admin globali. L'algoritmo ELO non cambia. Android non richiama questi flussi e legge soltanto i rating pubblicati.

## Verifiche e limiti

Passano 96 test web, incluse sette prove dei callback React; sei prove SQL concorrenti, con collisioni BGA e web/Android; 1.616 confronti di ranking. Lint passa con zero errori e 17 warning; typecheck e build default/flag true passano. Le tre migrazioni, rollback, permessi e cutover sono verificati su un cluster PostgreSQL 17.4 locale separato, arrestato dal runner.

Passano anche 43 verifiche HTTP sul gateway PostgREST 14.1 locale: firma JWT errata e token scaduti rifiutati, permessi ordinario/admin, errori 400/404/409, decimali esatti, replay, import BGA e blocco delle scritture dirette dopo cutover. Le tabelle ELO sentinella restano invariate. Il runner arresta sia PostgreSQL sia il gateway.

I callback usano hook controllati: non verificano DOM, focus, accessibilità o browser reali. Le claim SQL e l'emissione dei JWT HS256 sono sintetiche; PostgREST verifica realmente firma e scadenza. Queste prove non sostituiscono login GoTrue, account reali e configurazione Supabase cloud. Nessuna prova modifica il database del sito.

Evidenze e hash: [phase42-web-writers-validation.json](evidence/phase42-web-writers-validation.json). Il piano completo è in `King-Score/ATOMIC_PLAY_ROLLOUT.md`. I commit usano `[skip ci]`.

## Prossimo passo

Definire il rollout remoto controllato e il collaudo con account Supabase reali prima di abilitare l'editor Android. Non è disponibile un progetto Supabase di sviluppo; le prove HTTP locali sono completate, mentre il servizio Auth cloud resta da verificare.
