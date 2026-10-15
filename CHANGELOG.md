# Changelog

## 0.5.0 — 15 ottobre 2026 (collaudo finale)

- Abilitata la gestione di tutte le partite per gli amministratori: creazione, modifica ed eliminazione atomiche, conflitti e recupero esplicito degli esiti incerti.
- Classifiche globali e storici letti dalla stessa pubblicazione. Formula ELO invariata; stati mancanti o in attesa di aggiornamento indicati senza mescolare generazioni diverse.
- Ruolo admin verificato online prima degli invii e dopo il refresh della sessione. Risposte RPC limitate e UTF-8 verificato; richieste incerte conservate quando l'accesso viene negato.
- Corrette le chiusure TLS sul thread UI dei pool RPC e immagini: svuotamento sul dispatcher HTTP e chiusura idempotente. Corretto il login mostrato prima della conclusione del logout, che poteva scartare un nuovo tentativo di accesso.
- VersionCode 15 in preparazione. Login e Internet restano obbligatori; restano esclusi registrazione, modifica credenziali/avatar, uso offline e ricalcolo ELO dall'app.

Checkpoint precedente code 14: 255 test JVM passati, lint con 0 errori e 24 avvisi preesistenti; firma e aggiornamento RC4/code 12 → 14 verificati, compresi dati cifrati e blocco offline. Il percorso UI reale conferma login, ELO, ricerca, dettaglio e modifica admin, poi incontra il crash del pool immagini al logout. Cinque casi SDK remoti passati; regola originale delle registrazioni ripristinata e registrazioni chiuse.

Build e verifiche dell'APK code 15, UI reale completa, cleanup e CI finale ancora aperti. La versione non è ancora dichiarata stabile: [STABILITY.md](docs/STABILITY.md).

## 0.5.0-rc4 — 15 ottobre 2026

- Risorse della navigazione conservate durante la rotazione e rimosse al cambio account, alla perdita di accesso o alla revoca del ruolo.
- Editor partite collegato alla navigazione, con conferma prima di uscire dalla bozza e aggiornamento delle letture dopo un esito confermato. Rimane disabilitato in produzione.
- Richieste partite dall'esito ambiguo conservate; il collaudo HTTP locale verifica replay senza duplicati, journal cifrato e recupero dopo due arresti forzati.
- Release verificata senza componenti o eccezione HTTP dei test. Stessa chiave privata; versionCode 12. CI sospesa e dipendenze backend ancora aperte.

## 0.5.0-rc3 — 14 ottobre 2026

- Profilo scorrevole: logout raggiungibile con font a 1,8× su display da 320 dp.
- Altezza della barra di navigazione proporzionata al font e fino a tre righe per le etichette.
- Collaudo locale di storico da 1.200 partite, paginazione senza richieste duplicate e commenti con tastiera visibile; protocollo ripetibile per avvio e memoria della release.
- Test del login porta il pulsante in vista con la tastiera aperta e verifica il contenuto inviato e la cancellazione della password.
- Stessa chiave privata; versionCode 11. Editor admin disabilitato e CI sospesa.

## 0.5.0-rc2 — 14 ottobre 2026

- Corretto il blocco senza Internet: le callback della rete predefinita usano le capacità ricevute e la perdita esplicita della rete, senza rileggere uno snapshot sincrono obsoleto.
- Quattro test di regressione coprono perdita, validazione, cambio di rete ed eventi tardivi. La prova nativa verifica anche disconnessione e riconnessione con MainActivity aperta.
- Stessa chiave privata; versionCode incrementato a 10.

## 0.5.0-rc1 — 14 ottobre 2026

Candidata locale ritirata dopo il difetto del monitor rete riprodotto nel collaudo Android 15. Non distribuita.

- Firma privata separata dal debug, APK non debuggabile e checksum SHA-256.
- Pipeline locale per build release, verifica del certificato, esclusione dei componenti di test e collaudo dell'aggiornamento senza cancellare dati.
- Registro cifrato dei commenti incerti iniettato nell'Application; rilettura completa e conferma manuale prima di nuove scritture.
- Repository, pagine, bozza commento e invio in corso conservati durante ricreazione e rotazione dell'Activity. Logout, blocco, Checking e cambio account scartano lo stato precedente.
- Login e Internet obbligatori; nessuna registrazione, modifica credenziali/avatar o ricalcolo ELO.

Nota storica della RC1: l'editor partite admin era disabilitato per l'assenza delle API atomiche reali. Le prove di quella candidata erano limitate al PC, senza modifiche a King-Score, Vercel o Supabase.
