# Riepilogo e Statistiche — Android 0.6.0

La Home usa `get_app_summary_v1`: non legge più tutte le partite per ricavare i totali. Statistiche riprende le tre sezioni della web app, nell'ordine Win Rate per Gioco, ELO Generale, Statistiche per Gioco. La lettura di partite recenti, immagini e la verifica della sessione restano richieste distinte; non promettiamo visualizzazione istantanea su qualsiasi rete.

## Backend applicato

Il 15 ottobre 2026 l'utente ha approvato l'applicazione del [SQL verificato localmente](../local-tests/summary-api/01-install.sql). Progetto controllato tramite elenco progetti e URL dell'app: ScoreKing, `rixivmeipbizoxyvrvaf`.

Il preflight passa sul database reale. Dopo l'installazione restano identici conteggi e impronta dei dati, revisione 41, pubblicazione ELO, policy, grant e definizioni delle funzioni precedenti. Sono aggiunte cinque RPC e gli helper nello schema non esposto `kingscore_summary`. Vercel e il codice web non sono modificati. [Evidenza](evidence/summary-api-live-validation.json).

Le cinque RPC rifiutano richieste HTTP anonime con 401. Le letture complete passano nel database con ruolo authenticated e identità di un account collegato; le risposte reali sono decodificate sul PC dalle classi Android. Non sono stati creati account, partite o commenti di prova remoti e non è stato eseguito un nuovo login GoTrue.

## Letture Android

1. `SupabaseSummaryRepository` carica la Home con una RPC e conserva sei mesi e tre preferiti. `SupabaseStatisticsRepository` resta esclusivamente come riferimento per le prove della vecchia scansione: la factory di produzione non lo usa e non esiste un fallback automatico alla scansione completa.
2. Statistiche carica panoramica e grafico generale indipendentemente. Le schede gioco mostrano subito medie e copertura; giocatori e relativo grafico si caricano all'espansione. I giochi sono paginati a 20, i giocatori a 50.
3. Il grafico mostra finestre di 100 partite, dichiarando intervallo e totale. Le finestre non spezzano i partecipanti della stessa partita; i valori precedenti inizializzano soltanto giocatori già presenti. Partite dello stesso giorno e giocatori omonimi restano distinti per ID.
4. La nuova revisione osservata dal repository invalida la cache di sessione delle altre schermate. Risposte tardive, cambio account e logout non possono ripristinare vecchi dati. Un conflitto di revisione permette un solo riavvio automatico, poi richiede aggiornamento manuale.
5. Ogni richiesta verifica account e rete prima e dopo il trasporto. Le risorse si chiudono quando si perde l'accesso. Login obbligatorio, blocco senza Internet, aggiornamento trascinando dalla cima e divieto di ricalcolo ELO/registrazione/modifica credenziali o avatar sono conservati.

`get_app_data_version_v1` è usata internamente dalle RPC per associare versione e dati allo stesso snapshot SQL; il telefono non aggiunge un round trip iniziale solo per leggere la versione. La cache non usa polling permanente o modalità offline. La pagina Rating ELO mantiene il lettore della pubblicazione esistente, condivisa nella sua sessione: questa versione non sostituisce quel contratto con i grafici paginati.

## Contenuti e interazioni

Win rate = vittorie / partecipazioni del proprio giocatore. Due vittorie su quattro partecipazioni danno 50%, anche con dieci partite globali. NULL è escluso dalle medie e la copertura è visibile; uno zero registrato conta. Punteggi, somme e medie usano BigDecimal, mai Double. I conteggi di Statistiche sono Long; la presentazione Home conserva i precedenti Int e rifiuta esplicitamente overflow, senza troncamenti.

La grafica riprende slate scuro, schede con copertina, verde per punteggi e vittorie, blu per durate e schede giocatore espandibili. Il grafico usa colori del giocatore, schiariti soltanto se non raggiungono contrasto 3:1 sul fondo, e tratteggi distinti. La legenda attiva/disattiva le serie. Tocco e slider accessibile consultano partite e valori esatti; testo ingrandito dispone metriche e navigazione su più righe.

Oltre 64 serie occorre scegliere esplicitamente un sottoinsieme; la RPC non esclude giocatori silenziosamente. Le opzioni note del grafico vengono riusate; l'eventuale catalogo aggiuntivo è letto a pagine fino al completamento. Stati ELO stale/missing non mostrano punti come correnti e non bloccano i riepiloghi numerici.

## Prove e ripristino

Le prove SQL locali e i benchmark sono in [SUMMARY_API_VALIDATION.md](proposals/SUMMARY_API_VALIDATION.md). Sono passati 323 test JVM debug, lint senza errori e 41 controlli Node. I nuovi test verificano contratti, valori decimali, errori, sessione/rete, revisioni concorrenti, lazy loading e paginazione. Il run nativo completo chiude 111 successi e due integrazioni esplicitamente escluse, senza fallimenti; comprende sezioni, grafici, espansione, navigazione Home/Statistiche e testo 2×. [Evidenza UI](evidence/private-060-ui-validation.json). Le evidenze della consegna firmata vengono registrate dopo il collaudo dell'APK.

Il [rollback SQL](../local-tests/summary-api/02-rollback.sql) rimuove soltanto gli oggetti nuovi, senza dati, trigger o funzioni ELO esistenti. Prima di rimuovere le RPC occorre distribuire un client che usi il lettore precedente, con un versionCode maggiore: Android non permette un normale aggiornamento a una versione con codice inferiore. La 0.5.2 continua a funzionare con le nuove RPC installate.
