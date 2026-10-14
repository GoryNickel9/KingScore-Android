# Prima versione stabile: stato e criteri

Obiettivo operativo dal 14 ottobre 2026: completare la prima versione stabile privata dell'app, procedendo senza richiedere conferma a ogni incremento. Commit, push e CI accompagnano i passaggi principali. Le modifiche restano in KingScore-Android; altre repo, Vercel e Supabase rimangono esclusi.

La stabile mantiene il perimetro concordato: login e Internet obbligatori, consultazione, commenti propri e gestione partite admin atomica. Non comprende ricalcolo ELO, registrazione, modifica credenziali/avatar o uso offline. L'APK 0.4.0 è ancora un'anteprima, non la 1.0 stabile.

| Requisito | Stato | Verifica necessaria per chiuderlo |
| --- | --- | --- |
| Consultazione, login e blocco rete | Implementati, con test JVM e Android | Collaudo finale, accessibilità e dispositivo reale |
| Commenti propri | Implementati | Rafforzare il recupero degli esiti incerti dopo morte del processo; nessuna promessa di idempotenza server |
| Contratti e salvataggi atomici | Candidata locale con 21 casi superati | API reali e writer web/server coerenti, oggi indisponibili |
| Client RPC Android | Preparato, disabilitato per default | Integrazione nell'editor dopo le condizioni backend |
| Recupero salvataggi | Journal cifrato preparato; account verificato prima del recupero | Collegamento al ciclo di vita dell'editor e collaudo dei percorsi completi |
| Editor partite | Controller e bozza preparati con fixture; schermata da implementare | Integrazione Compose, ciclo di vita, conflitti, rete, logout e rotazione |
| APK privato e aggiornamenti | Firma debug attuale | Firma di rilascio, checksum, installazione e aggiornamento verificati |

## Ordine del lavoro autonomo

1. Chiudere il recupero dei salvataggi e il ciclo di vita per account, conservando richiesta e risultato confermato in modo protetto.
2. Implementare ViewModel e schermate editor con fixture, mantenendo i percorsi reali disabilitati.
3. Rafforzare commenti, accessibilità e percorsi di rete/sessione; eseguire il collaudo completo sul PC.
4. Preparare build di rilascio, firma privata e aggiornamento APK, con documentazione e CI ripetibili.
5. Chiudere le dipendenze esterne prima di dichiarare completa la stabile e abilitare le scritture partite reali.

Le condizioni backend sono in [WRITER_INTEGRATION.md](WRITER_INTEGRATION.md). Il codice locale e i test non rendono disponibili le RPC sul sito. Non è consentito aggirarle con scritture multiple. Quando rimarranno soltanto attività esterne o verifiche che richiedono accesso dell'utente, servirà una decisione sull'ambito; nel frattempo il lavoro indipendente nella repo prosegue.

## Journal dei salvataggi

`PlayWriteJournal` gestisce una sola operazione per account e serializza i client che lo condividono. Il client conserva l'esatta richiesta prima dell'HTTP. `EncryptedPendingPlayStore` la salva con AES-GCM, chiavi Android Keystore e file atomico nella directory esclusa dai backup. Non memorizza endpoint o credenziali.

Il recupero richiede prima rete e verifica dell'identità. Non invia automaticamente una richiesta: l'operazione incerta può essere ripetuta soltanto su comando esplicito. Record danneggiati, incompatibili o non salvabili bloccano l'invio e non vengono eliminati come se l'operazione fosse fallita sul server. La chiusura del client elimina la copia in memoria; il record cifrato rimane disponibile soltanto per il suo account.

Una risposta valida viene conservata come conferma cifrata, anziché cancellare subito ogni traccia. Questo copre la chiusura dell'app fra risposta server e presentazione del risultato. Il recupero della conferma non esegue HTTP; restituisce un risultato marcato restored, distinto dal campo replayed del server. Una nuova operazione esplicita con un nuovo UUID sostituisce la conferma precedente. Riutilizzare il vecchio UUID con un contenuto diverso produce conflitto.

Il factory dell'editor dovrà condividere un journal per account e iniettare lo store cifrato. Il client predefinito resta disabilitato e usa memoria: non è ancora collegato all'Application. Le garanzie del file riguardano riavvio e arresto del processo; non certificano perdita del dispositivo, cancellazione dei dati dell'app o guasti del filesystem.

Il registro `AccountPlayJournals` e il [controller con fixture](PLAY_EDITOR_STATE.md) preparano ora questa integrazione. Il controller gestisce caricamento, validazione della bozza, conferma della cancellazione, conflitti e recupero esplicito. Restano da collegare le schermate Compose e il ciclo di vita della sessione; i percorsi reali non sono abilitati.
