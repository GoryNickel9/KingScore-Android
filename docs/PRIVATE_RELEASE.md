# APK privato firmato

La stabile privata corrente è la 0.5.1/code 16: `.work/releases/KingScore-0.5.1-16.apk`, SHA-256 `90306da33c1a75ed24607c7828e7f59c5c645fcbf127164a71c61aad4586c960`. Aggiornamento dalla 0.5.0 verificato con lo stesso certificato, UID e dati cifrati conservati. Login, blocco offline e riconnessione sono verificati sulla release; i test delle nuove schermate usano fixture locali. Fonte e limiti: [STABILITY.md](STABILITY.md) e [qualifica](evidence/private-051-release-validation.json). La 0.5.0 resta il precedente checkpoint con collaudo remoto autorizzato.

Le RC precedenti restano storiche: la RC4 aveva l'editor disabilitato, la RC1 era stata ritirata per un difetto del monitor rete, la RC2 lo correggeva e la RC3 migliorava logout e navigazione con font grandi.

## Build e firma sul PC Windows

Servono PowerShell 7, JDK 17 o successivo, Android SDK 36 e build-tools 35.0.0. URL HTTPS e chiave pubblica Supabase devono essere già configurati nelle variabili SUPABASE_URL/SUPABASE_PUBLIC_KEY o in supabase.properties, escluso da Git. Lo script non legge né modifica altre repo e non esegue chiamate al backend.

```powershell
# Prima firma: crea una sola chiave locale, protetta dai permessi Windows.
pwsh -NoProfile -File scripts/build-private-apk.ps1 -InitializeSigningKey

# Build successive: riusano obbligatoriamente la stessa chiave.
pwsh -NoProfile -File scripts/build-private-apk.ps1 -VersionCode 16 -VersionName 0.5.1
```

Usare -Offline soltanto dopo che le dipendenze release sono state scaricate. Il workaround Java locale, quando necessario su questo PC, si configura nell'ambiente del comando come descritto in TESTING.md; non entra nell'APK o nella firma.

Lo script esegue testReleaseUnitTest, lintRelease e assembleRelease. Verifica package, versione, flag debuggable e assenza di host/probe di test nel manifest e nei DEX. Controlla il booleano cleartext nel formato aapt e rifiuta il manifest o la risorsa dell'eccezione HTTP locale di debug. Firma l'APK con il tool Android, confronta il certificato con quello conservato localmente e verifica firma v2 e allineamento ZIP a 16 KiB. Non sovrascrive un APK già prodotto.

L'output è in .work/releases: APK, SHA-256, metadati, log di build e verifica. La release non abilita la minificazione: questo incremento privilegia il comportamento già collaudato. Non contiene chiavi amministrative del server; la chiave pubblica del client è inclusa come negli altri client Supabase.

## Conservare la chiave

La chiave è .work/signing/kingscore.p12; password.txt contiene la password e certificate.der il certificato pubblico. La directory consente accesso all'utente Windows, SYSTEM e amministratori. Tutto il materiale resta escluso da Git. Lo script rifiuta di rigenerare o sostituire materiale esistente.

Conservare una copia protetta di questa directory: perdere chiave o password impedisce di firmare gli aggiornamenti della stessa installazione. Non distribuire keystore, password o APK della probe insieme all'app. L'APK e il suo SHA-256 sono i file destinati agli utenti.

## Installazione e aggiornamento

Un aggiornamento deve avere lo stesso applicationId e certificato, con versionCode maggiore. Android rifiuta una firma diversa. Le precedenti anteprime erano firmate con la chiave debug: non possono essere aggiornate direttamente con la nuova firma privata. La prima installazione privata richiede la rimozione dell'anteprima; questa elimina i suoi dati locali. Prima verificare eventuali commenti dall'esito incerto. Non disinstallare automaticamente l'app su un dispositivo dell'utente.

Installare l'APK da una fonte privata autorizzata e verificare SHA-256. Per gli aggiornamenti successivi, installare il nuovo APK sopra quello precedente, senza cancellare i dati.

## Collaudo locale

scripts/test-private-apk.ps1 rifiuta telefoni fisici ed emulatori con l'app già installata. Non cancella dati preesistenti. Il collaudo usa un APK baseline con versionCode 8 firmato con la nuova chiave, la candidata, la firma debug e una probe del solo APK di test firmata con la stessa chiave privata.

La probe crea dati sintetici cifrati nel contesto dell'app. Il collaudo verifica il rifiuto della firma debug, la conservazione dei dati dopo quel rifiuto, l'aggiornamento dalla baseline con UID invariato e il recupero di entrambi i registri cifrati. Controlla il rifiuto di run-as, avvia MainActivity senza Internet e poi verifica il modulo login a freddo con sessione vuota. Spegne e riaccende le connessioni con l'app aperta, verificando blocco e ripresa senza riavvio. Non inserisce credenziali e non autentica un account remoto: il percorso con archivio sessione vuoto termina prima dell'HTTP Auth.

Con la chiave già inizializzata e un emulatore nuovo avviato:

```powershell
pwsh -NoProfile -File scripts/build-private-apk.ps1 -VersionCode 8 -VersionName 0.4.0-upgrade-fixture
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -NoProfile -File scripts/sign-private-apk.ps1 -InputApk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk -OutputApk .work/releases/qa-probe.apk
pwsh -NoProfile -File scripts/test-private-apk.ps1 -BaselineApk .work/releases/KingScore-0.4.0-upgrade-fixture-8.apk -CandidateApk .work/releases/KingScore-0.5.0-rc3-11.apk -ProbeApk .work/releases/qa-probe.apk -WrongSignerApk app/build/outputs/apk/debug/app-debug.apk -Serial emulator-5576
```

La baseline non è la vecchia anteprima debug: è un APK di collaudo, firmato con la nuova chiave privata. La probe e la baseline non vanno distribuite. Il runner conserva report JSON, output ADB e screenshot in .work/private-apk; legge l'XML salvato da uiautomator e accetta userId/appId secondo la versione Android.

La RC3 supera aggiornamento 8 → 11, conservazione dei registri, rifiuto della firma debug, avvio offline e disconnessione/riconnessione live su API 31. Su API 35 sono misurati sette avvii della RC3 con login a sessione vuota; il collaudo completo di aggiornamento/rete della RC2 resta documentato separatamente. Non attribuire alla RC3 prove di aggiornamento API 35 eseguite solo sulla RC2.

APK: `.work/releases/KingScore-0.5.0-rc3-11.apk`, 14.446.050 byte. SHA-256: `7f54b60fdf96d4bf63ca20d653d1d030b2d27a9f799203c5034248edb64e7a31`. Certificato SHA-256: `b00fd08085ba373e9dde52066b3e8fd85b17b94f4b87dc5cd50ddc55d06a294b`.

Le prove di aggiornamento non sostituiscono le suite funzionali né verificano un salvataggio completo sul server. I risultati effettivi e i limiti sono in [TESTING.md](TESTING.md). La CI Android è attualmente sospesa; le precedenti esecuzioni autorizzate restano documentate sui rispettivi commit. I checkpoint RC4 usano verifiche locali e skip ci.

## Candidata RC4

APK: `.work/releases/KingScore-0.5.0-rc4-12.apk`, 14.462.434 byte, versionCode 12. SHA-256: `247dcf22d6f091ce5e283d1c13b548cbae4ae1da06e06775dbeee7f4d6dabd58`. Il certificato conserva il SHA-256 della RC3. La fonte è il commit `bc6789607e5b9e3eb310a07411f4f7917308a897`, con working tree pulito al momento della firma.

La build release esegue 231 test JVM senza errori; lint riporta zero errori e 24 avvisi. Il primo controllo del manifest non riconosceva il formato booleano tipizzato di aapt: il verificatore è stato corretto e la procedura completata. Entrambi i log restano in .work. Il manifesto vieta cleartext, l'eccezione HTTP debug non è nei resources e host/probe non sono nel manifest o nei DEX. Firma privata e allineamento a 16 KiB sono verificati. [Metadati della build](evidence/private-rc4-build.json).

Il collaudo di aggiornamento della RC4 usa la RC3 come baseline, conserva i dati del suo account Android e non autentica account Supabase. Le prove precedenti della RC3 restano riferite alla RC3; i risultati RC4 vengono registrati separatamente.

La RC4 supera il collaudo completo su API 31: aggiornamento 11 → 12 con UID 10145 invariato, registri cifrati conservati, firma debug rifiutata, run-as negato, avvio offline bloccato, login con sessione vuota e disconnessione/riconnessione live. Le impostazioni sono ripristinate e l'emulatore dedicato viene arrestato. [Report API 31](evidence/private-rc4-api31-validation.json).

Il [primo tentativo API 31](evidence/private-rc4-api31-first-attempt.json) resta FAIL sul controllo offline. Il runner disabilitava i radio soltanto prima degli install/probe e non verificava la rete del sistema al momento dell'avvio dell'app. La diagnosi separata mostra il blocco corretto dopo l'assenza della rete predefinita. Il nuovo runner disabilita nuovamente i radio prima dell'avvio offline e attende «Active default network: none» anche per la disconnessione live; la prova completa passa su un altro emulatore nuovo, con lo stesso APK. Non sono state modificate schermate o asserzioni.

Il collaudo firmato API 35 della stessa RC4 passa il 15 ottobre dopo la ripresa del lavoro: aggiornamento 11 → 12 con UID 10209 invariato, registri cifrati conservati, firma debug rifiutata, run-as negato, blocco offline, login a sessione vuota e disconnessione/riconnessione live. Impostazioni ripristinate ed emulatore dedicato arrestato. [Report API 35](evidence/private-rc4-api35-validation.json). Le prove precedenti rimangono riferite ai loro APK; il checksum della RC4 è lo stesso su entrambe le piattaforme.

La sospensione richiesta al checkpoint 76008a4 è stata revocata dall'utente con la richiesta di continuare. I collaudi firmati previsti su API 31 e 35 sono chiusi. Auth remoto, CI sospesa e dipendenze backend restano aperti; la candidata non viene dichiarata stabile completa.

## Aggiornamento UI 0.5.1

La qualifica usa la stabile 0.5.0/code 15 come baseline e il runner `test-private-apk.ps1` con `-CompileEmulatorPackages`. Il flag prepara ART sul solo emulatore esplicito: non cambia i byte degli APK e non rappresenta una misura delle prestazioni di avvio. È stato aggiunto dopo timeout osservati nell'avvio dell'instrumentation su API 35. I controlli di firma, cifratura, login e radio restano invariati. La probe firmata è un artefatto di test e non va distribuita.

Build, aggiornamento 15 → 16 e CI della fonte sono passati. Non sono stati inseriti account reali né eseguite modifiche cloud per la 0.5.1. L'APK, il checksum e la promozione verificata sono in `.work/releases`; il manifest originale di build resta `candidate=true, stable=false`, mentre `.apk.release.json` registra la successiva qualifica. Le prove precedenti nelle sezioni RC restano riferite ai rispettivi artefatti.
