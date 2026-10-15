# Compatibilità Android con ELO v2 — 0.6.1/code 19

Il backend ScoreKing restituisce `kingscore-elo-rules-v2` tramite le RPC di lettura già usate dall'app. Il suffisso `_v1` nel nome di una RPC identifica il contratto di trasporto; non impone l'algoritmo ELO v1.

## Letture

Classifiche e grafici ammettono gli identificativi `kingscore-elo-legacy-v1` e `kingscore-elo-rules-v2`. V1 resta una compatibilità di lettura per un eventuale rollback del backend; l'app non sceglie né esegue la formula. Le versioni sconosciute restano rifiutate. Restano attivi i controlli su scope globale, revisione, pubblicazione, interi, contatori e continuità dello storico da 1000.

## Pareggi

Storico, dettaglio e riepilogo Home condividono la presentazione dell'esito. Un primo posto condiviso in una partita competitiva è un pareggio; un piazzamento condiviso fra sconfitti resta una sconfitta. Un esito cooperativo esplicito prevale sui flag individuali: vittoria per tutti se la squadra ha vinto, sconfitta per tutti altrimenti. Un esito mancante non genera un vincitore.

Il dettaglio e le card dello storico mostrano il testo «Pareggio», oltre al colore, e conservano posizioni e punteggi ricevuti dal server. Non ricostruiscono il ranking e non correggono dati in scrittura.

## Confini e dipendenze

Login e Internet restano obbligatori. Auth, gate di rete, writer, registrazione, credenziali/avatar e ricalcoli non cambiano. Questa versione modifica soltanto KingScore-Android.

La verifica precedente ha rilevato due dipendenze backend ancora separate: le API statistiche contano `winner=true` anche nei primi posti condivisi, mentre il ranking dei salvataggi atomici usa le regole precedenti. Questo aggiornamento non cambia quelle RPC e non ricava un win rate corretto da una pagina parziale. Il valore restituito dal server in Statistiche può quindi includere ancora i pareggi fra le vittorie, finché verrà allineata l'API. Anche il collegamento dei writer web alle RPC resta esterno a questa patch.

## Verifica

Sono passati 333 test JVM debug, lint senza errori e 41 controlli Node. Sette [prove sulle risposte reali](evidence/elo-v2-reader-validation.json), eseguite sul PC senza rete, verificano pubblicazione/grafico v2, compatibilità v1 e rifiuto di versioni sconosciute o storico incoerente.

Le [25 prove native interessate](evidence/private-061-ui-validation.json) passano sul sorgente finale, incluse quattro nuove prove di pareggi e cooperativi. Coprono testo 2×, card, dettaglio, Home, login, blocco offline e rimozione dei dati dopo perdita d'accesso. Il primo run aveva due errori nei selettori dei nuovi test, corretti senza ulteriori modifiche produttive. Non è stata ripetuta la suite integrale della 0.6.0. Anche la release supera 333 test JVM, aggiornamento 18 → 19, login e blocco offline. [Qualifica APK](evidence/private-061-release-validation.json) e [CI della fonte](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38813479934).
