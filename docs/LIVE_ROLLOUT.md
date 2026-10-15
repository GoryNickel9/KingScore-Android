# Prima stabile privata 0.5.0 — 15 ottobre 2026

L'utente ha autorizzato modifiche Supabase/Vercel, CI Android e collaudo limitato con account e partite temporanei. La 0.5.0/code 15 supera il collaudo completo dell'APK firmato con MainActivity e account reali su emulatore API 35. La fonte immutabile è `df39f5b3bc1fad0e11991784864421193c983225`.

## Risultati completati

La transazione Supabase installa RPC atomiche, revisione globale, pubblicazioni ELO e ordine persistito dei partecipanti. I controlli conservano 483 partite e 1.359 partecipazioni, inclusi identificativi, punteggi esatti e metadati. Il calcolo sullo storico ordinato coincide con quello catturato prima dell'installazione; la formula rimane invariata. Vecchio DML e RPC legacy sono revocati. [Verifica della lettura online](evidence/live-backend-read-validation.json).

Il sito è pubblicato su `kingscore.baldeddu.cc` con `VITE_ATOMIC_PLAY_WRITES=true`: usa salvataggi atomici e una sola pubblicazione globale per classifiche e storici. Il caricamento Vercel iniziale è fallito; il successivo esclude esplicitamente directory di lavoro, credenziali e materiale di prova. Il controllo browser resta non eseguito perché non ci sono superfici CUA abilitate; sono passati build e otto controlli HTTP locali con SDK reale.

Il client Android legge lo stesso contratto globale senza ricalcolare. Distingue risultati mancanti e superati, conserva una sola generazione per tabella e storico, limita le risposte RPC e controlla il ruolo admin aggiornato prima degli invii, anche dopo il refresh. Passano 256 test JVM; lint ha 0 errori e 24 avvisi preesistenti. L'editor è abilitato nella 0.5.0/code 15; il factory rimane assente se manca la configurazione Auth. La [CI funzionale della fonte dell'APK](https://github.com/GoryNickel9/KingScore-Android/actions/runs/38725386747) è passata.

## APK e percorso reale verificati

L'APK `.work/releases/KingScore-0.5.0-15.apk` ha SHA-256 `fd196d0e75f452c24a3cb6e0223732a8ab1f5f02e598f2a808dbfe0a269ff509`. La firma usa lo stesso certificato della RC4 e l'aggiornamento code 12 → 15 conserva i dati cifrati. È verificata l'esclusione di componenti di test e debug: [build](evidence/private-050-build.json), [aggiornamento](evidence/private-050-upgrade-validation.json).

La prova UI completa restituisce `PASS_FULL` in 38,037 secondi: login admin, classifica globale e storico, ricerca e dettaglio, modifica atomica di una partita altrui, logout e login ordinario senza editor, creazione/modifica/eliminazione di un commento proprio, blocco senza Internet, riconnessione e logout finale. I crash TLS dei pool RPC e immagini e la race del login successivo al logout sono corretti; `AccountImagesCloseTest` fallisce sull'APK code 14 e passa sulla code 15. Prove: [UI reale](evidence/private-050-ui-validation.json), [rilascio](evidence/private-050-release-validation.json).

## Cleanup e chiusura

I cinque casi SDK con account temporanei sono passati. La regola originale delle registrazioni è stata ripristinata esattamente e le registrazioni sono chiuse. Il cleanup SDK termina il 15 ottobre alle 11:18:58 UTC; la verifica Node successiva conferma il risultato ELO originale e i conteggi iniziali: 483 partite, 1.359 partecipazioni, 18 giochi, 6 giocatori e 7 commenti. La revisione è 41 e la pubblicazione corrente è `7039e306-ee0e-4536-b33a-adef9303ff89`; questi identificatori avanzano normalmente e non vengono riportati indietro. [Cleanup](evidence/live-backend-cleanup-validation.json).

La pulizia elimina anche quattro pubblicazioni intermedie del collaudo e conserva quelle iniziale e corrente. Sono verificati zero account, ricevute e pubblicazioni temporanei, la regola originale delle registrazioni e il funzionamento HTTPS dello scheduler. La CI funzionale già passata identifica la fonte immutabile dell'APK; lo stesso workflow verifica i commit successivi dei report. Non sono stati verificati telefoni fisici o l'interfaccia browser del sito.

I test automatici distruttivi restano esclusivamente sul PC. Chiave di firma, credenziali, copie applicative e SQL contenente dati rimangono nelle directory private escluse da Git e dal deploy.
