# Decisioni tecniche KingScore Android

## Client nativo

Kotlin e Jetpack Compose sono la scelta confermata dall'utente. Riutilizzare Supabase; la UI React e i servizi TypeScript non vengono inclusi nell'APK. Il calcolo ELO rimane fuori dal client. Capacitor e una riscrittura del backend non sono previsti.

## Connessione e account

Login e Internet sono obbligatori. Registrazione, recupero password e modifica credenziali/avatar restano esterni all'app. Nessun database locale o sync offline; preservare lo stato transitorio di un modulo non abilita invii senza rete. Gli admin gestiscono partite, gli utenti commenti propri. La moderazione admin dei commenti altrui è esclusa perché non è consentita dalle policy correnti.

## Contratto guidato dallo schema remoto

Usare il catalogo reale, non il replay incompleto delle migrazioni, per definire i DTO. `played_at` è LocalDate, ELO è Int, score è BigDecimal nullable. La revisione per aggiornamenti concorrenti sarà opaca e calcolata server-side; non inventare un `updated_at` su plays. Le nuove mutazioni richiedono RPC complete con idempotenza. Non usare la RPC score esistente come sostituto del salvataggio intero.

## Strumenti e target

| Voce | Decisione o verifica |
| --- | --- |
| Package ID | `app.kingscore.android` nella build 0.3.0 |
| Android minimo | API 26, per java.time senza desugaring; dispositivo minimo ancora da provare |
| Target e compile SDK | API 36; build, lint e installazione su emulatore API 31 verificati |
| Contratti Kotlin | Compilati con Kotlin JVM 1.9.24, Java 21, bytecode target 17; senza dipendenze Android o librerie JSON |
| Toolchain dell'app | Kotlin/Compose plugin 2.1.20, Compose BOM 2025.04.01, AGP 8.13.2, Gradle 8.13; Supabase 3.1.4 e Ktor 3.1.2 |

La combinazione fissata compila l'APK e i test Android. La verifica su un telefono destinatario rimane da eseguire. Sessione cifrata con Android Keystore AES-256/GCM e preferenze private, escluse da backup e trasferimento; nessuna dipendenza di persistenza aggiuntiva serve per la fase 2.

## Distribuzione

Prima versione come APK privato firmato. Conservare package ID e chiave di firma per consentire gli aggiornamenti; chiave e password di firma restano fuori dal repository. Il repository deve contenere procedura di build, versione, checksum e istruzioni per l'installazione. AAB, Google Play, push e aggiornamenti automatici non fanno parte del primo rilascio.
