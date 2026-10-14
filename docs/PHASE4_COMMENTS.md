# Commenti propri, build 0.4.0

Nel dettaglio partita, Scrivi un commento apre il modulo. Modifica ed Elimina compaiono soltanto sui commenti con user_id uguale all'account autenticato, anche per gli admin. Un account non collegato a un giocatore può commentare. L'eliminazione richiede conferma; Annulla non invia richieste.

## Contratto e validazione

1. POST invia soltanto play_id e content. PATCH invia soltanto content; PATCH e DELETE filtrano id, user_id e play_id. Il server assegna autore e timestamp. Nessuna modifica a RLS, schema o dati remoti è stata eseguita per questo incremento.
2. Ogni richiesta verifica online l'account e usa il suo Bearer. Il trasporto delle scritture è distinto da immagini e SDK, accetta soltanto la destinazione Supabase configurata e non segue redirect. La risposta richiesta è return=representation.
3. Una risposta valida contiene una sola riga con partita/autore/ID attesi e, per creazione/modifica, contenuto corrispondente. Zero righe non significa successo. Errori di permesso e input non provocano logout; i messaggi non espongono body o header remoti.
4. Il testo viene normalizzato con trim e deve contenere da 1 a 1.000 code point Unicode. Multilinea ed emoji sono ammessi; NUL e surrogate isolate sono rifiutati. Il contatore usa la stessa convenzione di char_length PostgreSQL e non tronca il testo.
5. Salvataggio e cancellazione disabilitano gli invii ripetuti. Solo una risposta confermata produce il messaggio di successo; la successiva rilettura può fallire senza trasformare il salvataggio già confermato in un fallimento.

## Esiti incerti

Timeout, 5xx, cancellazione dopo dispatch e risposte 2xx non interpretabili possono seguire un commit. Il trasporto non ripete queste scritture, compreso un 503 Retry-After. Un 401 esplicito consente al solo gestore Auth un refresh e un secondo tentativo; non esiste un loop di retry.

L'interfaccia richiede una rilettura completa e la conferma Ho controllato i commenti prima di nuove scritture. La riconciliazione attraversa tutte le pagine, perché un commento appena creato può trovarsi dopo i primi 50. Refresh del dettaglio e retry di una lettura fallita non devono abbreviare questa verifica. La rilettura non reinvia, deduplica per testo o dichiara riuscita una mutazione incerta. Anche un elenco vuoto lascia il blocco attivo: l'utente controlla i commenti prima di decidere se scrivere di nuovo.

CommentOutcomes conserva soltanto gli UUID delle partite con un esito pendente, separati per account. L'Application inietta lo store cifrato: la persistenza precede l'HTTP e sopravvive alla chiusura del processo. Non conserva testo, command o una coda di invio. Logout, sessione invalidata, chiusura del writer e cambio account eliminano la copia in memoria; il record cifrato rimane per il suo account. Il recupero e la conferma richiedono prima rete e verifica online dell'identità. Il trasporto chiuso viene cancellato; le generation dei ViewModel impediscono a risposte tardive di ripristinare dati o editor.

Il backend non offre receipt o idempotenza per i commenti: una rilettura mostra lo stato osservato e non prova che una richiesta cancellata non possa terminare sul server più tardi. L'app non promette consegna exactly-once e non ripete una richiesta incerta. La conferma dell'utente rimuove il blocco locale dopo la rilettura; non attesta il fallimento dell'operazione originale e non trasmette alcun commento.

Il registro usa AES-GCM, chiavi Android Keystore e file atomici esclusi dal backup. Un account può avere al massimo 1.024 indicatori; il file cifrato è limitato a 64 KiB. File danneggiati, chiavi mancanti o salvataggi non riusciti bloccano le mutazioni con STORAGE e non vengono cancellati automaticamente. Le letture rimangono disponibili. La persistenza delle partite riusa lo stesso componente, mantenendo formato, directory e alias precedenti.

## Paginazione e concorrenza

Dopo ogni mutazione confermata, svuotare commenti e cursore e rileggere la prima pagina. Non appendere una riga nuova dopo una pagina incompleta: l'ordine resta created_at,id crescente. Con più di 50 commenti, il nuovo commento può richiedere Carica altri commenti; il messaggio Commento inviato conferma separatamente il risultato.

Le modifiche usano l'ultimo salvataggio autorizzato, come le policy esistenti; non c'è optimistic locking. L'editor dichiara questo comportamento. Partite admin, moderazione dei commenti altrui, calcolo ELO e operazioni account restano fuori da questo incremento.

## Verifica

Test Auth e trasporto usano sessioni e risposte sintetiche; la prova TCP del 503 è locale. I test Compose verificano creazione, modifica, conferma/annullamento eliminazione, ownership admin, Unicode, esito incerto, rete, logout, account e Checking durante dispatch. Risultati effettivi e screenshot: [TESTING.md](TESTING.md).

Le prove consentite rimangono sul PC, con fixture, emulatore e servizi loopback. Non usare Supabase remoto come ambiente di test. Le fixture non certificano il comportamento attuale del server con JWT reali; questa limitazione va mantenuta nella documentazione di rilascio.
