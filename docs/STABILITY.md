# Prima versione stabile: stato e criteri

Obiettivo operativo dal 14 ottobre 2026: completare la prima versione stabile privata dell'app, procedendo senza richiedere conferma a ogni incremento. Commit e push accompagnano i passaggi principali. Il perimetro attuale limita le modifiche a KingScore-Android e sospende la CI GitHub. Le precedenti preparazioni web rimangono sul PC; la loro fonte SQL può essere letta dai test locali. Altri repository, Vercel e Supabase non vengono modificati.

La stabile mantiene il perimetro concordato: login e Internet obbligatori, consultazione, commenti propri e gestione partite admin atomica. Non comprende ricalcolo ELO, registrazione, modifica credenziali/avatar o uso offline. È disponibile la candidata privata firmata 0.5.0-rc3; la stabile completa non è ancora dichiarata raggiunta.

| Requisito | Stato | Verifica necessaria per chiuderlo |
| --- | --- | --- |
| Consultazione, login e blocco rete | Suite funzionali con fixture su API 31/35; 77 casi finali API 35, font grandi, tastiera e storico da 1.200 partite verificati | Nessuna certificazione Auth/RLS remota; misure PC con limiti espliciti in BENCHMARKS.md |
| Commenti propri | Implementati, registro cifrato nell'Application; recupero dopo arresto del processo e percorsi di sessione verificati | Rilettura completa e conferma manuale prima di nuove scritture; nessuna promessa di idempotenza server |
| Contratti e salvataggi atomici | Candidata e migrazione web predisposta: 21 casi superati anche sulla fonte install-only | API reali e writer web/server coerenti, oggi indisponibili |
| Client RPC Android | Factory e journal per account preparati; percorso HTTP locale, navigazione e cifratura verificati insieme | Disponibilità delle API e condizioni backend; factory di produzione assente |
| Writer web locale | Preparatore, trasporto mutazioni, journal con conferme e verifica account; 97 test web, typecheck/lint superati | Pagine, conflitti, collaudo browser e integrazione SQL ancora da completare; nessuna pubblicazione |
| Compatibilità SQL | Preflight read-only: 19 casi; migrazione predisposta: 16 profili e 21 casi HTTP passati su PostgreSQL 17.4 | Cutover, caller reali e verifica backend restano da completare; nessuna modifica cloud |
| Recupero salvataggi | Percorso locale integrato passato: due arresti forzati, replay senza duplicati e conferma cifrata recuperata senza POST | Auth remoto e backend effettivo restano non verificati |
| Editor partite | Navigazione collegata; creazione, modifica ed eliminazione passate contro RPC locali; disabilitato in produzione | Disponibilità delle API e nuova candidata firmata |
| APK privato e aggiornamenti | RC3 firmata, non debuggabile; aggiornamento 8 → 11, registri cifrati e rete verificati su API 31; sette avvii release misurati su API 35 | Conservare la chiave privata; la RC2 ha anche il collaudo aggiornamento/rete API 35; nessuna verifica Auth remota |
| CI GitHub | Ultimo checkpoint noto passato: aee9ac4, run 38640169702; esecuzione attualmente sospesa | I checkpoint successivi hanno soltanto verifiche locali e commit con skip ci |

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

Il client cancella una richiesta nuova soltanto dopo un rifiuto con codice macchina riconosciuto e coerente con lo stato HTTP. Un corpo vuoto, HTML o con codice inatteso conserva il comando originale e blocca nuovi salvataggi. Il checkpoint verifica 27 combinazioni ambigue e il replay dopo ricreazione del client con store in memoria: [android-play-rejection-validation.json](evidence/android-play-rejection-validation.json). Le prove non chiudono l'integrazione con l'editor, lo store cifrato o il backend reale.

Il factory preparato nell'Application condivide un journal per account e inietta lo store cifrato. La disponibilità del factory resta condizionata a ATOMIC_PLAY_EDITOR_ENABLED, oggi false; non viene creato alcun editor reale. Il costruttore standalone del client resta disabilitato per default e usa memoria. Le garanzie del file riguardano riavvio e arresto del processo; non certificano perdita del dispositivo, cancellazione dei dati dell'app o guasti del filesystem.

Il registro AccountPlayJournals, il [controller](PLAY_EDITOR_STATE.md) e la schermata Compose sono collegati alle destinazioni admin. Il controller gestisce caricamento, validazione della bozza, conferma della cancellazione, conflitti e recupero esplicito. Rotazione e ricreazione conservano la bozza; perdita di accesso o ruolo rimuove la sessione. Alle prove con fixture di navigazione si aggiunge il [collaudo HTTP/cifratura sul PC](EDITOR_RPC_PC_QA.md), passato attraverso tre processi Android distinti. Auth e rete sono simulati. I percorsi reali non sono abilitati.

## Lavoro restante

Firma privata, installazione, aggiornamento, checksum e documentazione sono descritti in [PRIVATE_RELEASE.md](PRIVATE_RELEASE.md). La rc1 è stata ritirata dopo il difetto di perdita rete su Android 15; la rc2 corregge il monitor e supera le prove live su API 31 e 35. La rc3 aggiunge le correzioni per font grandi; il [collaudo finale con fixture](FINAL_PC_QA.md) passa su API 35. Le misure non certificano prestazioni su telefoni o autenticazione remota. Restano aperti i criteri esterni.

Le preparazioni web precedenti restano locali e non vengono modificate nel perimetro attuale. Finché le API reali mancano, l'editor resta disabilitato. I checkpoint attuali usano verifiche sul PC; la CI è sospesa. Writer reali coerenti, migrazione compatibile e attivazione autorizzata del backend restano necessari prima della stabile completa. Stato e prove: [WRITER_INTEGRATION.md](WRITER_INTEGRATION.md).

La [gestione della ricreazione](ACTIVITY_LIFECYCLE.md) conserva ora repository, ViewModel figli e bozze dei commenti attraverso le modifiche di configurazione. Le verifiche di accesso e le transizioni a Checking, blocco, logout o altro account continuano a rimuovere lo stato della sessione precedente. Le prove native eseguono la navigazione di produzione in un host debug con account sintetici; non certificano GoTrue remoto o MainActivity con credenziali reali.
