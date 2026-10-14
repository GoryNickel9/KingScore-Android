# APK privato firmato

La versione 0.5.0-rc3 è una candidata privata. Mantiene login e Internet obbligatori, consultazione e commenti propri. L'editor partite admin resta disabilitato perché le API atomiche reali non sono disponibili. Non dichiarare questa candidata la stabile completa. La rc1 è stata ritirata dopo un difetto del monitor rete riprodotto su Android 15; la rc2 lo corregge. La rc3 rende raggiungibile il logout con font grandi e adatta l'altezza delle etichette di navigazione.

## Build e firma sul PC Windows

Servono PowerShell 7, JDK 17 o successivo, Android SDK 36 e build-tools 35.0.0. URL HTTPS e chiave pubblica Supabase devono essere già configurati nelle variabili SUPABASE_URL/SUPABASE_PUBLIC_KEY o in supabase.properties, escluso da Git. Lo script non legge né modifica altre repo e non esegue chiamate al backend.

```powershell
# Prima firma: crea una sola chiave locale, protetta dai permessi Windows.
pwsh -NoProfile -File scripts/build-private-apk.ps1 -InitializeSigningKey

# Build successive: riusano obbligatoriamente la stessa chiave.
pwsh -NoProfile -File scripts/build-private-apk.ps1 -VersionCode 11 -VersionName 0.5.0-rc3
```

Usare -Offline soltanto dopo che le dipendenze release sono state scaricate. Il workaround Java locale, quando necessario su questo PC, si configura nell'ambiente del comando come descritto in TESTING.md; non entra nell'APK o nella firma.

Lo script esegue testReleaseUnitTest, lintRelease e assembleRelease. Verifica package, versione, flag debuggable e assenza di host/probe di test nel manifest e nei DEX. Firma l'APK con il tool Android, confronta il certificato con quello conservato localmente e verifica firma v2 e allineamento ZIP a 16 KiB. Non sovrascrive un APK già prodotto.

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

Le prove di aggiornamento non sostituiscono le suite funzionali né verificano un salvataggio completo sul server. I risultati effettivi e i limiti sono in [TESTING.md](TESTING.md). La CI Android è riattivata dal 14 ottobre 2026 su autorizzazione dell'utente; le prove locali della RC3 precedenti alla riattivazione restano documentate con CI non eseguita.
