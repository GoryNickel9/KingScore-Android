# Prima versione stabile: stato e criteri

Obiettivo operativo dal 14 ottobre 2026: completare la prima versione stabile privata dell'app, procedendo senza richiedere conferma a ogni incremento. Commit e push accompagnano i passaggi principali; la CI è sospesa secondo le ultime istruzioni dell'utente. Le modifiche restano in KingScore-Android; altre repo, Vercel e Supabase rimangono esclusi.

La stabile mantiene il perimetro concordato: login e Internet obbligatori, consultazione, commenti propri e gestione partite admin atomica. Non comprende ricalcolo ELO, registrazione, modifica credenziali/avatar o uso offline. È disponibile la candidata privata firmata 0.5.0-rc3; la stabile completa non è ancora dichiarata raggiunta.

| Requisito | Stato | Verifica necessaria per chiuderlo |
| --- | --- | --- |
| Consultazione, login e blocco rete | Suite funzionali con fixture su API 31/35; 77 casi finali API 35, font grandi, tastiera e storico da 1.200 partite verificati | Nessuna certificazione Auth/RLS remota; misure PC con limiti espliciti in BENCHMARKS.md |
| Commenti propri | Implementati, registro cifrato nell'Application; recupero dopo arresto del processo e percorsi di sessione verificati | Rilettura completa e conferma manuale prima di nuove scritture; nessuna promessa di idempotenza server |
| Contratti e salvataggi atomici | Candidata locale con 21 casi superati | API reali e writer web/server coerenti, oggi indisponibili |
| Client RPC Android | Preparato, disabilitato per default | Integrazione nell'editor dopo le condizioni backend |
| Recupero salvataggi | Journal cifrato preparato; account verificato prima del recupero | Collegamento al ciclo di vita dell'editor e collaudo dei percorsi completi |
| Editor partite | Controller, bozza e schermata Compose preparati con fixture | Factory, navigazione/sessione, rotazione dell'Activity e recupero completo dopo arresto del processo |
| APK privato e aggiornamenti | RC3 firmata, non debuggabile; aggiornamento 8 → 11, registri cifrati e rete verificati su API 31; sette avvii release misurati su API 35 | Conservare la chiave privata; la RC2 ha anche il collaudo aggiornamento/rete API 35; nessuna verifica Auth remota |
| CI GitHub | Sospesa su istruzione dell'utente | Nuova autorizzazione e controlli sull'implementazione finale; nessuna esecuzione automatica |

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

Il registro `AccountPlayJournals`, il [controller con fixture](PLAY_EDITOR_STATE.md) e la schermata Compose preparano questa integrazione. Il controller gestisce caricamento, validazione della bozza, conferma della cancellazione, conflitti e recupero esplicito. Restano da collegare factory, navigazione e ciclo di vita della sessione; i percorsi reali non sono abilitati.

## Lavoro restante

Firma privata, installazione, aggiornamento, checksum e documentazione sono descritti in [PRIVATE_RELEASE.md](PRIVATE_RELEASE.md). La rc1 è stata ritirata dopo il difetto di perdita rete su Android 15; la rc2 corregge il monitor e supera le prove live su API 31 e 35. La rc3 aggiunge le correzioni per font grandi; il [collaudo finale con fixture](FINAL_PC_QA.md) passa su API 35. Le misure non certificano prestazioni su telefoni o autenticazione remota. Restano aperti i criteri esterni.

La versione completa con gestione partite admin richiede prima una decisione sul backend, escluso dal perimetro attuale. Finché queste API mancano, l'editor resta disabilitato. La CI sospesa mantiene aperto il relativo criterio. Queste condizioni impediscono di assegnare una data definitiva alla stabile completa.

La [gestione della ricreazione](ACTIVITY_LIFECYCLE.md) conserva ora repository, ViewModel figli e bozze dei commenti attraverso le modifiche di configurazione. Le verifiche di accesso e le transizioni a Checking, blocco, logout o altro account continuano a rimuovere lo stato della sessione precedente. Le prove native eseguono la navigazione di produzione in un host debug con account sintetici; non certificano GoTrue remoto o MainActivity con credenziali reali.
