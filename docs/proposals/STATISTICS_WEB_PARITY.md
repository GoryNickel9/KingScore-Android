# Statistiche Android: allineamento alla web app

Obiettivo confermato: la pagina deve essere il più possibile simile al web, nei contenuti e nell'ordine, oltre che nello stile. Questa proposta è implementata nella 0.6.0; integrazione, interazioni e limiti sono descritti in [SUMMARY_STATISTICS.md](../SUMMARY_STATISTICS.md). Le sezioni seguenti conservano il confronto con la precedente 0.5.2.

## Perché la 0.5.2 appare diversa

L'Android conserva il riepilogo della precedente implementazione: quattro contatori, andamento mensile delle partite, durate, partecipazioni personali e conteggi per gioco. Il redesign 0.5.2 ne ha cambiato la presentazione, mantenendo quelle informazioni.

La web app ha un'altra struttura: win rate personale per gioco, grafico ELO generale e schede gioco con medie, giocatori e grafici ELO. Il riepilogo Android deriva dalla Home e non copre queste sezioni. La differenza dipende da quella semplificazione implementata, non da un limite di Kotlin o Android. Il precedente progetto non rendeva sufficientemente esplicita la riduzione dei contenuti.

Riferimenti verificati: web HEAD `78918e57e44e3fa385d0a0108ec3fa73b86edd1b`, [Stats.tsx](https://github.com/GoryNickel9/KingScore/blob/78918e57e44e3fa385d0a0108ec3fa73b86edd1b/src/pages/Stats.tsx); Android 0.5.2, [StatisticsRoute.kt](../../app/src/main/java/app/kingscore/android/feature/stats/StatisticsRoute.kt).

## Corrispondenza da implementare

| Ordine web | Android 0.5.2 | Nuovo obiettivo Android |
| --- | --- | --- |
| Win Rate per Gioco — Top 10 | Assente | Stesso blocco e grafico a barre, percentuale e partecipazioni visibili |
| ELO Generale | Grafico del numero di partite per mese | Grafico ELO con più giocatori, legenda e valori consultabili |
| Statistiche per Gioco | Conteggi e barre di popolarità | Copertina, nome, partite, punteggio medio, durata media |
| Statistiche giocatori, espandibili | Assenti | Schede espandibili con avatar, nome, partecipazioni e media dei punteggi |
| Evoluzione ELO del gioco | Assente | Grafico dei giocatori nel gioco aperto |

I contatori globali e il grafico mensile rimangono nella Home. Non devono sostituire i contenuti di Statistiche.

## Aspetto e interazioni

1. Conservare fondo slate scuro, pannelli, copertine e gerarchia del web. Titolo «Statistiche», sottotitolo «Analisi dettagliata delle partite». Stessi tre blocchi principali e stesso ordine.
2. Su telefono disporre copertina, metriche e pulsante «Statistiche giocatori» su più righe, senza cancellare informazioni. I dettagli restano espandibili e conservano lo stato al ritorno dalla partita/giocatore.
3. Grafici ELO per ID giocatore; nomi, colori e legenda coerenti. Asse delle partite, senza fondere incontri dello stesso giorno. Tocco per consultare il valore al posto del passaggio del mouse; legenda utilizzabile anche con TalkBack e testo ingrandito.
4. Caricamento indipendente delle sezioni e dei giochi aperti. Finestre/pagine dichiarate, senza rappresentare una pagina come intero archivio. Pull-to-refresh dalla cima; conservare login obbligatorio e blocco senza Internet.
5. Lo storico individuale rimosso da Rating ELO resta rimosso. I grafici ELO nella pagina Statistiche corrispondono a quelli della pagina web richiesta.

## Calcoli approvati per il nuovo contratto

Il web attuale incrementa il denominatore del win rate anche per partite a cui il proprio giocatore non ha partecipato (`Stats.tsx`, righe 176–188). Usa inoltre zero per punteggi/durate mancanti e divide la durata per tutte le partite (righe 240–266).

L'utente ha scelto esplicitamente:

- Due vittorie su quattro partecipazioni devono dare 50%, anche con dieci partite globali di quel gioco.
- Le medie escludono NULL e mostrano la copertura; gli zeri registrati contano.

La somiglianza visiva non richiede di conservare quei calcoli del web. Le nuove regole sono fissate in [SUMMARY_API_V1.md](SUMMARY_API_V1.md); il web rimane invariato finché non verrà autorizzata e pubblicata una modifica separata. Il giocatore personale segue il collegamento account_user_id già usato dall'app.

## Accettazione del prossimo intervento UI

Confronto affiancato con gli stessi dati e lo stesso account: tre sezioni nello stesso ordine; ogni gioco con medie, copertura e dettaglio giocatori; ELO letto dalla stessa pubblicazione. Verificare anche nessun dato disponibile, account non collegato, ELO stale/missing, molti giochi, testo 2×, connessione persa e logout.

I valori del win rate e delle medie saranno confrontati con le due nuove regole approvate, senza pretendere uguaglianza con i risultati errati del web attuale. Nessuna promessa di parità funzionale completa finché questi controlli e il confronto visivo non passano.
