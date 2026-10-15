# Prove dei salvataggi sul PC

Per le nuove funzioni di riepilogo e Statistiche, vedere [Summary API V1](summary-api/README.md): suite locale separata, SQL completo di installazione/rollback e benchmark. Non richiede o contatta Supabase remoto.

Questo ambiente usa un cluster PostgreSQL dedicato e un gateway PostgREST, entrambi su 127.0.0.1. Tutti i file nuovi restano in KingScore-Android. Non usa Supabase CLI, Docker, SDK collegati al cloud, .env o credenziali reali; non modifica il repository web, Vercel o Supabase remoto.

## Esecuzione

Prerequisiti già disponibili su questo PC: PowerShell 7, Node 24, PostgreSQL 17 e PostgREST 14.1 Windows x86-64. Il runner verifica l'hash del binario PostgREST conservato nella directory locale ignorata. Non installa o scarica programmi.

Dalla radice di KingScore-Android:

```powershell
node --test contracts/tests/play-save-contracts.test.mjs
pwsh -NoProfile -File scripts/test-local-play-saves.ps1
pwsh -NoProfile -File scripts/test-local-play-saves.ps1 -Atomic
```

PostgreSQL deve essere disponibile in `C:\Program Files\PostgreSQL\17\bin`, oppure indicato con `-PostgresBin`. PostgREST è in `.work/undone-external-files/postgrest-14.1/postgrest.exe`, oppure indicato con `-PostgrestBin` verso un file verificato dentro questa repo. SHA256 del binario: `13e575ad59a05df3f1554164883f5a756c8231e4e4dd495ee48053fb3acfad5c`.

Il runner crea una directory univoca in `.work/local-play-tests`, inizializza un nuovo cluster e sceglie porte loopback. Le variabili PG*/PGRST*/proxy ereditate non raggiungono i processi di prova; psql usa -X e una destinazione esplicita. La fixture richiede il nome database dedicato, un marcatore locale e la connessione loopback prima di creare oggetti. Il gateway ha un ruolo di connessione dedicato; i JWT firmati sono generati per questa esecuzione e non vengono stampati nei report.

## Base attuale e candidata atomica

I test JS controllano le richieste del [contratto proposto](../docs/PLAY_SAVE_CONTRACT.md). Il generatore riproduce tabelle, vincoli, indici, policy, funzioni e trigger pubblici dal catalogo già salvato. Auth è una piccola fixture SQL; Storage e GoTrue non sono replicati. Account e righe sono sintetici, con email example.test.

Le prove HTTP del backend attuale **devono riprodurre** creazioni e modifiche parziali, perdita di color/role e ID, trigger UPDATE incompatibile, assenza di controllo revisione, creazioni duplicate e cancellazione dopo una modifica concorrente. PASS significa che il comportamento attuale è riprodotto correttamente; non significa che i difetti siano risolti. Le nuove cinque RPC devono risultare assenti.

Senza `-Atomic` il runner si ferma dopo la caratterizzazione della base. Con `-Atomic`, nello stesso cluster nuovo, applica [candidate.sql](candidate.sql) ed esegue tutti i [21 casi di accettazione](acceptance-cases.json) via HTTP e SQL. Non ripristina i vecchi commit backend o le migrazioni annullate.

La candidata salva partita, score e ricevuta nella stessa transazione. Le prove provocano errori anche all'inserimento finale della ricevuta e confrontano lo stato completo prima e dopo. Verificano permessi, NUMERIC testuale, limiti JSONB, revisione, richieste concorrenti, replay dopo delete, ranking e import BGA. Il ranking confronta 1.592 vettori con la caratterizzazione JS del comportamento concordato, senza leggere un'altra repo.

Il caso 19 applica [candidate-cutover.sql](candidate-cutover.sql) soltanto alla fixture e verifica il rifiuto di scritture dirette e RPC legacy. Il caso 20 usa un adattatore server sintetico che applica posizioni già calcolate con controllo revisione, senza calcolare ELO. I job reali e il sito non sono integrati: il PASS locale non certifica un rollout. Il caso 21 legge dati storici incompatibili e ne impedisce la conversione silenziosa durante l'update.

## Risultati e arresto

Ogni esecuzione salva report.json, http-report.json, baseline.sql e log nella propria directory ignorata. La modalità atomica aggiunge acceptance-report.json e le query UTF-8 usate dai test. Il processo HTTP termina il proprio gateway; il runner arresta il proprio PostgreSQL nel finally. I file restano per la diagnosi. Non vengono cancellate directory ricorsivamente o arrestati server già presenti sul computer. L'evidenza versionata dell'ultimo collaudo è [local-atomic-validation.json](../docs/evidence/local-atomic-validation.json).

Non sono testati login Supabase reale, configurazione cloud, consumer web/Android o accessibilità. Non viene effettuata alcuna mutazione remota. Questi script sono per il PC; le suite native non vengono aggiunte alla CI GitHub.
