# Progetto Android e autenticazione KingScore

La fase 2 introduce un'app Android nativa con login tramite account esistente, ripristino della sessione verificato online, navigazione autenticata e blocco senza Internet. Le schermate Home, Partite, Statistiche ed ELO sono anteprime esplicite: le query e la consultazione dei risultati appartengono alla fase 3.

## Implementazione

| Componente | File |
| --- | --- |
| Progetto e dipendenze | `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` e Gradle Wrapper |
| Auth Supabase | `core/auth/SupabaseAuthRepository.kt`, interfaccia limitata in `AuthRepository.kt` |
| Sessione protetta | `core/auth/EncryptedSessionManager.kt`; Android Keystore AES-256/GCM, SharedPreferences cifrate, backup e trasferimento preferenze esclusi |
| Connessione e accesso | `core/network/NetworkMonitor.kt`, `feature/auth/AppViewModel.kt`, `MainActivity.kt` |
| Interfaccia | `LoginScreen.kt`, `AuthenticatedNavigation.kt`, `AppContent.kt`, `KingScoreTheme.kt` |

Tutti i percorsi Kotlin sono sotto `app/src/main/java/app/kingscore/android`. I DTO di fase 1 vengono compilati direttamente da `contracts/v1`; `data/remote/RowDecoders.kt` decodifica gli otto modelli senza perdere la precisione NUMERIC o confondere date e timestamp. La generazione resta in `scripts/generate-contracts.mjs`.

## Comportamento della sessione

Il ripristino carica i token cifrati e verifica l'utente con Auth prima di aprire la navigazione. Le informazioni account mostrate dalla UI provengono da questa risposta online, non dalla sola decodifica locale del JWT. L'admin deriva da `app_metadata`; la RLS rimane l'autorità per le future scritture.

In primo piano la sessione attiva viene verificata all'ingresso e ogni 60 secondi; viene verificata anche alla riconnessione. Il controllo periodico e il ritorno dal gestore password lasciano aperta una schermata login già attiva. Un token prossimo alla scadenza viene rinnovato, e un rifiuto Auth ammette un solo tentativo di refresh. Una revoca definitiva elimina la sessione; un errore temporaneo del backend blocca la UI conservando i token validi. Le invalidazioni osservate dal repository rimuovono la navigazione autenticata.

Il monitor richiede una rete che Android consideri validata. La raggiungibilità di Supabase viene accertata dalle richieste Auth, con timeout di 15 secondi: un'interruzione del backend non può essere rilevata prima di una richiesta o del controllo periodico. Le future richieste dati della fase 3 devono gestire lo stesso blocco e gli errori di rete.

Il logout rimuove sessione locale e chiave di cifratura prima di richiedere la revoca remota della sessione corrente. La cancellazione della coroutine non interrompe il cleanup locale. Un errore di rimozione locale viene mostrato e impedisce il ripristino automatico immediato. La perdita di rete non cancella l'operazione di logout.

La password resta nello stato transitorio della schermata e viene rimossa quando si invia il login. Non entra in SavedState, preferenze o log. Gli errori SDK non vengono stampati o mostrati: possono includere header sensibili. Registrazione, reset password, modifiche email/password/avatar e ricalcolo ELO non hanno comandi o implementazioni nell'app.

## Configurazione e build

Per usare il progetto Supabase già configurato in King-Score:

```powershell
./scripts/configure-local.ps1
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Lo script legge soltanto URL e chiave pubblica dalla configurazione web, scrive `local.properties` e `supabase.properties` esclusi da Git e non legge credenziali utente. In alternativa copiare `supabase.properties.example` in `supabase.properties` e compilare i due valori. Si possono fornire anche le variabili ambiente `SUPABASE_URL` e `SUPABASE_PUBLIC_KEY`.

La build rifiuta chiavi service_role/secret. Senza configurazione genera comunque l'APK per i controlli CI; quel pacchetto non permette il login fino alla ricompilazione con i parametri pubblici corretti. La chiave pubblica è inclusa nell'APK; i permessi dipendono dal JWT utente e dalla RLS.

APK di debug: `app/build/outputs/apk/debug/app-debug.apk`. Non è il pacchetto release firmato con la chiave definitiva: la firma e distribuzione stabile appartengono alla fase 5. La CI esegue test JVM, lint e build senza credenziali Supabase reali; non è stata ancora eseguita su GitHub.

## Strumenti fissati

Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin e plugin Compose/serialization 2.1.20, Compose BOM 2025.04.01, Supabase Kotlin 3.1.4 e Ktor 3.1.2. Min SDK 26, compile/target SDK 36, bytecode JVM 17, package `app.kingscore.android`.

AGP 8.13 supporta API 36 e richiede almeno Gradle 8.13/JDK 17. Il catalogo della versione Supabase selezionata usa Kotlin 2.1.20 e Ktor 3.1.2. Le versioni sono fissate per questa build, senza affermare che siano tutte le più recenti. [Compatibilità AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [catalogo Supabase 3.1.4](https://github.com/supabase-community/supabase-kt/blob/3.1.4/gradle/libs.versions.toml).

In questa sessione Windows la JVM ha fallito la creazione dei socket AF_UNIX usati da Gradle. Un helper temporaneo sotto `.work` forza il fallback TCP nei soli processi di build; non è incluso nell'APK, nelle dipendenze o nella configurazione del progetto. Il computer non è stato riconfigurato. La build standard e la CI non richiedono questo helper su ambienti in cui la JVM funziona normalmente.

## Interfaccia

Tema scuro con superfici slate e accento emerald, coerente con King-Score. Controlli Material 3, target tattili nativi, campi con etichette visibili, password oscurata e modulo scorrevole quando compare la tastiera. La navigazione ha cinque destinazioni; logout e perdita di accesso rimuovono l'albero autenticato. PartyScore si apre in un browser esterno.

## Validazione e limiti

I report dei test e del lint sono sotto `app/build/reports`. L'evidenza sintetica della verifica conclusiva è salvata in `docs/evidence/phase2-validation.json`. I test SDK usano MockEngine e identificatori sintetici; non certificano login con un account reale del gruppo. Il login reale e l'installazione su un telefono fisico restano da verificare con il proprietario dell'account.

Build e lint completati, 23 test JVM e 5 test Android superati su emulatore API 31. Lint: zero errori e 22 warning, relativi alle versioni fissate e a suggerimenti KTX. La perdita della connessione reale sull'AVD mostra il blocco senza schermate dati; ripristinare la rete riapre il login. [Schermata login verificata](evidence/phase2-login.png).

Le RPC per le partite non sono state deployate e nessun percorso dell'app le invoca. Non sono state modificate schema, policy, dati o processi ELO remoti.
