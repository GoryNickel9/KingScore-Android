# Passaggio alla prima stabile — 15 ottobre 2026

L'utente ha autorizzato modifiche Supabase/Vercel, CI Android e collaudo limitato con account e partite temporanei. La consegna rimane in verifica: non dichiarare stabile l'APK prima del collaudo finale.

## Risultati completati

La transazione Supabase installa RPC atomiche, revisione globale, pubblicazioni ELO e ordine persistito dei partecipanti. I controlli conservano 483 partite e 1.359 partecipazioni, inclusi identificativi, punteggi esatti e metadati. Il calcolo sullo storico ordinato coincide con quello catturato prima dell'installazione; la formula rimane invariata. Vecchio DML e RPC legacy sono revocati. [Verifica della lettura online](evidence/live-backend-read-validation.json).

Il sito è pubblicato su `kingscore.baldeddu.cc` con `VITE_ATOMIC_PLAY_WRITES=true`: usa salvataggi atomici e una sola pubblicazione globale per classifiche e storici. Il caricamento Vercel iniziale è fallito; il successivo esclude esplicitamente directory di lavoro, credenziali e materiale di prova. Il controllo browser resta non eseguito perché non ci sono superfici CUA abilitate; sono passati build e otto controlli HTTP locali con SDK reale.

Il nuovo client Android legge lo stesso contratto globale senza ricalcolare. Distingue risultati mancanti e superati, conserva una sola generazione per tabella e storico, limita le risposte RPC e controlla il ruolo admin aggiornato prima degli invii, anche dopo il refresh. Passano 254 test JVM e i 40 controlli Node. L'editor è abilitato sulla candidata 0.5.0, versionCode 13; il factory rimane assente se manca la configurazione Auth.

## Verifiche ancora necessarie per dichiarare la stabile

1. Completare il collaudo Auth/permessi con i tre account temporanei autorizzati; ripristinare esattamente la regola originale delle registrazioni dopo la loro creazione.
2. Verificare la candidata firmata e l'aggiornamento dalla RC4 sul PC, inclusi accesso reale, editor, classifiche e blocco Internet.
3. Rimuovere account e dati di prova, verificare il ritorno del risultato ELO originale e chiudere CI, commit e push di rilascio.

I test automatici distruttivi restano esclusivamente sul PC. Chiave di firma, credenziali, copie applicative e SQL contenente dati rimangono nelle directory private escluse da Git e dal deploy.
