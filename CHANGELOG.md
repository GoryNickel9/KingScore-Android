# Changelog

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

L'editor partite admin resta disabilitato finché le API atomiche reali mancano. Questa versione è una candidata privata, non la stabile completa. Le prove rimangono sul PC; nessuna modifica a King-Score, Vercel o Supabase.
