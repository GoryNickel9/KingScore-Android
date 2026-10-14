# Misure sul PC

La candidata firmata 0.5.0-rc2 è collaudata su emulatori x86_64 API 31 e 35. Host: Windows, Intel Core i3-10100F, 8 processori logici; ogni emulatore usa quattro core, 1.536 MiB di RAM su API 31 e 2.048 MiB su API 35, renderer SwiftShader. Durante le prove erano presenti altri processi del PC: queste misure non rappresentano prestazioni garantite su telefoni.

## Diagnostica di avvio

| Versione | MainActivity offline, processo nuovo | Login online, processo nuovo e sessione vuota |
| --- | --- | --- |
| Android 12 / API 31 | 1.737 ms | 2.311 ms |
| Android 15 / API 35 | 1.569 ms | 1.538 ms |

Sono i valori TotalTime di am start -W nel collaudo della release, un solo campione per stato. Non misurano il completamento dell'Auth remota, il caricamento dati o la prima pagina dello storico. La verifica UI avviene separatamente dopo l'avvio. Fonte: [private-release-validation.json](evidence/private-release-validation.json).

## Avvio ripetuto della rc3, API 35

APK firmato code 11, SHA-256 `7f54b60fdf96d4bf63ca20d653d1d030b2d27a9f799203c5034248edb64e7a31`. Sette avvii con processo arrestato, un riscaldamento escluso, sessione vuota e login verificato separatamente. Nessuna credenziale o autenticazione remota. Nessuna build concorrente durante i sette campioni; sul PC restavano altri processi e un altro emulatore, che non è stato modificato.

| Misura | Risultato |
| --- | --- |
| TotalTime, sette campioni | 3.046; 3.654; 3.288; 3.678; 4.439; 2.841; 2.716 ms |
| Mediana | 3.288 ms |
| Percentile 95, nearest rank | 4.439 ms; con sette campioni coincide con il massimo |
| PSS dopo verifica login | 61.068–63.343 KiB |

Il primo tentativo si è fermato sul timeout di connessione di UiAutomation, dopo un TotalTime di 9.656 ms. Il processo dell'app era presente; una lettura successiva mostrava il login. Il protocollo è stato ripreso sulla stessa installazione appena creata, senza dati o credenziali aggiunti, con un nuovo riscaldamento e sette campioni completi. Report: `.work/startup-measurements/rc3-resumed-empty-install/report.json`, collegato al tentativo iniziale. Lo script permanente conserva il requisito dell'emulatore nuovo e ora ritenta la lettura UI fino a quattro volte con file distinti; la ripresa di questo run è stata eseguita con un driver locale separato, non con un'opzione che riusi installazioni generiche.

Questi numeri misurano la visualizzazione iniziale dell'Activity sul PC, non Auth, prima pagina dati o tempo di risposta del server. Non sono una garanzia di avvio sotto una soglia su telefoni. Il numero di campioni limita l'interpretazione del percentile.

## Storico con fixture: rc3, API 35

Run mirato `.work/final-pc-qa/fcb5a4f9eba14ca68d8f2924766ea166`: tre casi superati, prima della build release. Display 320×640 px, 160 dpi, quattro core e 2.048 MiB di RAM, SwiftShader. Fixture: 1.200 partite già allocate, punteggi vuoti, un gioco, niente immagini, JSON o HTTP. Il protocollo è in [FINAL_PC_QA.md](FINAL_PC_QA.md).

| Misura | Risultato | Interpretazione |
| --- | --- | --- |
| Aggiornamento: cinque campioni del driver | 759,73; 139,82; 142,13; 162,01; 150,56 ms | Include sincronizzazione Compose e controlli UI; nessuna latenza di rete |
| Paginazione | 24 pagine da 50; 30 richieste totali | Home, prima pagina, cinque aggiornamenti e 23 caricamenti; lo scroll non aggiunge richieste |
| PSS prima/dopo caricamento | 204.855 / 206.318 KiB | Intero processo debug con instrumentation e fixture condivise |
| Heap Java prima/dopo | 11.135.280 / 10.274.800 byte | La raccolta dei rifiuti può ridurlo; non dimostra assenza di leak |
| FrameMetrics: 12 swipe Android da 350 ms | 156 callback; mediana 31,31 ms; p95 94,94 ms | Durata totale dei frame osservati in debug con renderer software |
| Frame osservati oltre 16,67 ms | 146 su 156 | Il run non sostiene una promessa di 60 fps |

Non conteggiamo le callback perse dal listener come frame misurati. Non si tratta di una certificazione di fluidità della release: instrumentation, renderer e altri processi del PC incidono sui tempi. Il run precedente, con 251 callback, aveva mediana 17,48 ms e p95 27,38 ms; la variazione impedisce di attribuire i tempi a una regressione senza un profilo controllato. Nessuna ottimizzazione del codice viene motivata da questo confronto.

I sette rapporti di contrasto calcolati dai colori espliciti del tema vanno da 5,71 a 17,06; il valore minimo riguarda il bordo, non il testo. Non coprono tutti gli stati disabilitati o tutti i colori Material derivati. Font grandi, logout e dialogo sono controllati dalle prove native; TalkBack completo e hardware fisico rimangono fuori dalle prove consentite sul PC.

Le prove non usano Supabase remoto. I tempi delle suite, i refresh del driver e i quattro avvii singoli della rc2 non sono benchmark delle richieste reali dell'app.
