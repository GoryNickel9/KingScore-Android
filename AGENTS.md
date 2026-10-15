# Ambito del lavoro

Il lavoro riguarda la prima versione stabile di KingScore-Android. Il 15 ottobre 2026 l'utente ha autorizzato anche le modifiche necessarie a King-Score, Vercel e Supabase, chiedendo chiarimenti prima delle scelte incerte.

- Usare il checkout King-Score separato e preservare i file non committati preesistenti. Sono autorizzati deploy web e modifiche Supabase necessari alla stabile, dopo prove locali e verifica del progetto di destinazione.
- I test automatici distruttivi restano sul PC. È autorizzato un collaudo remoto limitato con account e partite temporanei, da rimuovere al termine.
- La classifica è unica globale. Conservare e verificare l'ordine dei partecipanti restituito attualmente dal sito; non cambiare la formula ELO.
- Se una funzionalità richiede modifiche fuori da questa repo o non può funzionare entro i vincoli, discuterne con l'utente prima dell'implementazione.
- La CI Android è riattivata con conferma esplicita del 15 ottobre 2026: Node, test JVM, lint e build APK.
- Mantenere login obbligatorio e blocco senza Internet; escludere ricalcolo ELO, registrazione e modifica credenziali/avatar.
- I nuovi salvataggi autorizzano gli amministratori su tutte le partite, come nel contratto locale confermato dall'utente.
- Le preparazioni backend delle fasi 4.1 e 4.2 restano annullate. La nuova attivazione del 15 ottobre usa le RPC verificate e la pubblicazione ELO globale. La 0.5.0/code 15 ha superato il collaudo firmato sul PC; account, dati e pubblicazioni temporanei sono stati rimossi e la regola originale delle registrazioni è ripristinata.
