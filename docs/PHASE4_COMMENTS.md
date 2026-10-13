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

L'interfaccia richiede Rileggi commenti prima di un nuovo invio. La riconciliazione attraversa tutte le pagine, perché un commento appena creato può trovarsi dopo i primi 50. Refresh del dettaglio e retry di una lettura fallita non devono abbreviare questa verifica. La rilettura non reinvia, deduplica per testo o dichiara riuscita una mutazione incerta: l'utente controlla i commenti prima di decidere se ripeterla.

CommentOutcomes conserva soltanto gli UUID delle partite con un esito pendente nell'account attivo. Sopravvive alla ricreazione della schermata, Checking e blocco rete; la nuova schermata verifica tutte le pagine. Non conserva testo, command o una coda di invio. Logout, sessione invalidata e cambio account eliminano questo stato. Il trasporto chiuso viene cancellato; le generation dei ViewModel impediscono a risposte tardive di ripristinare dati o editor.

L'indicatore rimane in memoria, quindi non sopravvive alla chiusura del processo. Il backend non offre receipt o idempotenza per i commenti: una rilettura mostra lo stato osservato e non prova che una richiesta cancellata non possa terminare sul server più tardi. Non promettere consegna exactly-once. Al ritorno da un blocco, verificare prima di ripetere una modifica.

## Paginazione e concorrenza

Dopo ogni mutazione confermata, svuotare commenti e cursore e rileggere la prima pagina. Non appendere una riga nuova dopo una pagina incompleta: l'ordine resta created_at,id crescente. Con più di 50 commenti, il nuovo commento può richiedere Carica altri commenti; il messaggio Commento inviato conferma separatamente il risultato.

Le modifiche usano l'ultimo salvataggio autorizzato, come le policy esistenti; non c'è optimistic locking. L'editor dichiara questo comportamento. Partite admin, moderazione dei commenti altrui, calcolo ELO e operazioni account restano fuori da questo incremento.

## Verifica

Test Auth e trasporto usano sessioni e risposte sintetiche; la prova TCP del 503 è locale. I test Compose verificano creazione, modifica, conferma/annullamento eliminazione, ownership admin, Unicode, esito incerto, rete, logout, account e Checking durante dispatch. Risultati effettivi e screenshot: [TESTING.md](TESTING.md).

Prima della stabile servono prove di POST/PATCH/DELETE con account ordinario e admin in un ambiente isolato, più installazione e collaudo su telefono. Le fixture non certificano il comportamento attuale del server con JWT reali.
