# Collaudo finale sul PC

La rc3 corregge due problemi riprodotti con font a 1,8× su display da 320 dp: il Profilo non consentiva di scorrere fino al logout e le etichette della barra di navigazione venivano tagliate. Il Profilo ora scorre; la barra conserva almeno 80 dp a scala normale e aumenta l'altezza con il font, con etichette fino a tre righe.

## Protocollo con fixture

`FinalPcQaTest` esegue AppContent e la navigazione di produzione nell'host debug. Usa account sintetici e 1.200 partite preallocate, un gioco, punteggi vuoti e nessuna immagine o richiesta HTTP. Il valore storico `offline` nelle partite descrive la modalità della partita; l'accesso all'app resta connesso.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -NoProfile -File scripts/test-final-pc-qa.ps1 -Serial emulator-5578
```

I tre casi verificano:

1. Tutte le 24 pagine da 50 partite, ordine dei cursori e ultima partita. Home, prima pagina, cinque aggiornamenti e 23 pagine successive producono esattamente 30 richieste; lo scorrimento non ne aggiunge.
2. Profilo con font a 1,8×: logout visibile dopo scorrimento, bersaglio alto almeno 48 dp e uscita dalla navigazione autenticata.
3. Commento con font a 1,8×, campo a fuoco e IME realmente visibile: invio e annullamento raggiungibili. L'annullamento non esegue scritture.

I casi con font grandi salvano anche l'impostazione Android `font_scale`, la portano a 1,8 e verificano la configurazione dell'Activity. Il solo override Compose dell'host non basta a certificare il dialogo, che ha una finestra e un owner separati. La pulizia del test ripristina il valore originale e attende il ritorno della configurazione; il runner controlla il valore prima/dopo e conserva una seconda protezione nel finally.

Il runner salva report, metriche e immagini in `.work/final-pc-qa` e ripristina le impostazioni delle animazioni. Le immagini del Profilo e del dialogo sono controllate visivamente. La cattura del dialogo esclude la tastiera: la sua presenza è verificata dagli inset IME, non dall'immagine.

Il test storico registra cinque tempi del driver di aggiornamento, PSS e heap prima/dopo il caricamento e FrameMetrics durante 12 gesti Android reali da 350 ms. Queste misure includono instrumentation e fixture condivise; non misurano rete, decodifica JSON, immagini o prestazioni di un telefono. Non certificano 60 fps o assenza di perdite di memoria. Dati e limiti: [BENCHMARKS.md](BENCHMARKS.md).

## Login e affidabilità del collaudo

La suite completa ha riprodotto un errore in `AccessUiTest.loginExposesOnlyExistingAccountSignIn`: il callback non riceveva l'email. Anche il run mirato originale falliva. Il driver cliccava senza portare il pulsante in vista dopo l'apertura della tastiera sul display piccolo. Il test ora scorre, verifica visibilità e abilitazione, clicca e legge il risultato sul thread UI dopo il completamento. Controlla entrambi i valori inviati e la cancellazione della password. I tre test mirati passano; il codice del login non è stato modificato.

Un primo controllo aggiunto confrontava l'intera semantica del campo, inclusa l'etichetta Email, con il solo valore. Falliva nonostante `EditableText` fosse corretto; ora verifica la presenza del valore. I tentativi falliti restano nei log e non sono conteggiati come successi.

## Avvio della release

```powershell
pwsh -NoProfile -File scripts/measure-private-startup.ps1 -Serial emulator-5576
```

Il runner richiede un emulatore nuovo senza l'app, installa la release e rifiuta il flag debuggable. Misura un riscaldamento escluso dai risultati e sette avvii con processo arrestato. Verifica separatamente il modulo login con sessione vuota e registra TotalTime di `am start -W` e PSS. Riporta mediana e percentile 95 con metodo nearest rank. Non inserisce credenziali: il ripristino della sessione vuota termina prima dell'HTTP Auth. Non cancella dati preesistenti.

Le prove di aggiornamento della candidata sono descritte in [PRIVATE_RELEASE.md](PRIVATE_RELEASE.md). CI e integrazione delle API admin reali restano aperte secondo [STABILITY.md](STABILITY.md); nessuna prova locale le sostituisce.
