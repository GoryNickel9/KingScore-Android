# Verifica KingScore Android

## Risultati della fase 1

| Controllo eseguito | Esito | Limite |
| --- | --- | --- |
| Catalogo remoto in READ ONLY | Dieci tabelle e RLS catturate | Nessuna mutazione o replay migrazioni |
| Confronto Row web e remoto | Dieci tabelle, zero differenze di campi/nullabilità TS | I tipi TS non distinguono date da timestamp o numeric da integer |
| Probe SQL | Quattro piani EXPLAIN e due dinieghi 42501 | Identità SQL sintetiche, nessun login Auth reale e nessuna DML eseguita |
| Probe HTTP | Otto letture con limit=0 e un diniego Auth 401 | Nessun JWT di un utente ordinario/admin |
| Contratti Kotlin | Dieci gruppi di controlli superati, modelli compilati | Nessun serializer JSON, client Android o test UI |

La suite Node comprende dieci test, tutti superati. Sono state verificate anche la sintassi dei moduli Node, quella dei quattro script PowerShell e l'assenza di credenziali nelle evidenze esportate.

Le verifiche Node aggiuntive controllano consistenza delle evidenze, tipi, policy, trigger autore, vincoli del perimetro e limiti delle RPC. I risultati dettagliati vengono prodotti dai comandi seguenti.

## Comandi locali ripetibili

```powershell
node --test scripts/check-evidence.test.mjs
./scripts/check-contracts.ps1
node scripts/compare-schema.mjs
```

`check-contracts.ps1` accetta `-KotlinCompiler` e `-JavaExecutable` se Kotlin/Java non sono nei percorsi rilevati. Richiede Kotlin JVM e Java 17 o successivo; il JAR temporaneo resta in `.work`, esclusa da Git. Non richiede un dispositivo, rete o credenziali.

Per rigenerare i contratti dopo aver acquisito una nuova evidenza: `node scripts/generate-contracts.mjs`. Esaminare le differenze prima di accettarle: la generazione non certifica un nuovo schema come compatibile.

## Verifiche remote ripetibili

Questi comandi usano le credenziali già conservate dalla CLI e la configurazione pubblica web senza esportarle. Richiedono accesso al progetto corretto.

```powershell
./scripts/audit-supabase.ps1
./scripts/probe-permissions.ps1
./scripts/probe-http.ps1
```

L'audit e le probe SQL usano READ ONLY. EXPLAIN non usa ANALYZE. Le sole chiamate alla RPC score sono eseguite con anon/non-admin e devono fallire prima della DML. Le probe HTTP richiedono zero righe applicative. Il progetto collegato deve rimanere quello indicato; lo script HTTP rifiuta host inattesi.

Per aggiornare i tipi remoti, catturare lo stdout della CLI in memoria, controllare l'exit code e scrivere `docs/evidence/schema.remote.ts` soltanto in caso di successo. Non sovrascrivere i tipi web senza un confronto semantico.

## Risultati della fase 2

| Controllo eseguito | Esito | Ambito |
| --- | --- | --- |
| Build debug e APK test | Superati | Gradle 8.13, AGP 8.13.2, JVM target 17, SDK 36 |
| Test JVM | 23 superati, zero errori | 8 Auth SDK con MockEngine, 10 ViewModel, 5 decodifica JSON |
| Lint debug | Zero errori, 22 warning | Warning di aggiornamento strumenti/librerie e suggerimenti KTX; versioni fissate |
| Test Android API 31 | 5 superati | 3 Compose per login/blocco/ruoli; 2 Keystore per cifratura, ripristino e corruzione |
| Test evidenze Node | 10 superati | Contratti e audit remoto conservati dalla fase 1 |
| Perdita rete reale su AVD | Blocco e ripresa verificati | Wi-Fi/dati disattivati e modalità aereo; al ritorno della rete ricompare il login |

Auth è verificato con richieste simulate, inclusi refresh, revoca, backend indisponibile, logout locale prima della revoca remota e cancellazione durante il cleanup. Il ViewModel copre connessione persa, invalidazione SDK e mancata rimozione locale. Due regressioni verificano che rientro e timer preservino il login già aperto e ricontrollino una sessione attiva.

I decoder verificano gli otto DTO, join nullable, data senza fuso, timestamp con offset, UUID, score zero/null/negativo e numeri oltre la precisione Double. Campi mancanti e valori non finiti devono produrre un errore.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

In questa sessione i cinque test Android sono stati eseguiti con il runner direttamente via ADB, su un AVD dedicato API 31. I report JVM/lint si trovano in `app/build/reports`; risultati, checksum APK e limiti sono salvati in [evidence/phase2-validation.json](evidence/phase2-validation.json). La build locale ha richiesto il workaround JVM descritto in [PHASE2.md](PHASE2.md).

## Controlli ancora aperti

1. Login con account reale ordinario/admin, revoca remota effettiva e riavvio del processo con sessione reale. Nessuna credenziale utente è stata usata nei test.
2. Installazione e aggiornamento su telefono fisico, API minima 26, TalkBack e caratteri grandi. I risultati dell'emulatore API 31 non coprono questi dispositivi.
3. Esecuzione CI su GitHub. Il workflow è presente ma non è stato avviato remotamente.
4. Nella fase 3: paginazione oltre 1.000 fixture, risultati coerenti col web, dettaglio ed ELO in sola lettura. Nella fase 4: commenti e scritture isolate secondo i controlli sotto.

Non sono stati eseguiti benchmark mobili. Le schermate dati rimangono segnaposto nella fase 2; i test UI non certificano funzioni ancora assenti.

## Controlli prima delle scritture partite

1. In un database isolato, creare/modificare/eliminare partite con almeno un partecipante, score null/zero/negativi/decimali, più vincitori, score uguali e cooperative con risultato true/false/null.
2. Verificare JWT ordinario/admin via HTTP, ownership commenti, rifiuto di ID score di un'altra partita e rollback integrale per payload o FK invalidi.
3. Simulare timeout dopo commit, retry con stesso request_id, riuso della chiave con payload diverso e aggiornamenti simultanei su revisione vecchia. Verificare receipt e conflitti.
4. Verificare conservazione di color, role, location, campi importati e owner storico, comportamento del trigger score e cascade dell'eliminazione. Nessun percorso deve avviare ELO.
5. Eseguire i flussi web dopo l'adozione delle stesse RPC. Misurare tempi e richieste su dispositivo soltanto quando esiste una build Android; nessun benchmark mobile è stato eseguito in fase 1.
