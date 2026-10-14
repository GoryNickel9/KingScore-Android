# Ambito del lavoro

Il lavoro riguarda KingScore-Android e, su autorizzazione esplicita dell'utente del 14 ottobre 2026, il codice della web app King-Score e la preparazione della migrazione, con test soltanto sul PC.

- È consentito modificare il codice di King-Score in un checkout locale separato. Preservare i file non committati preesistenti. Non pubblicare le modifiche web o avviare deploy in questa fase.
- Non modificare o distribuire configurazioni, schema o dati su Vercel e Supabase.
- Le prove dei salvataggi sono consentite soltanto sul PC, con PostgreSQL/PostgREST loopback e dati sintetici; non usare Supabase remoto come ambiente di test.
- Se una funzionalità richiede modifiche fuori da questa repo o non può funzionare entro i vincoli, discuterne con l'utente prima dell'implementazione.
- La CI Android è riattivata su autorizzazione esplicita dell'utente del 14 ottobre 2026: sono consentiti Node, test JVM, lint e build APK nel workflow di questa repo. Non autorizza deploy, operazioni su Vercel/Supabase o test backend remoti.
- Mantenere login obbligatorio e blocco senza Internet; escludere ricalcolo ELO, registrazione e modifica credenziali/avatar.
- I nuovi salvataggi autorizzano gli amministratori su tutte le partite, come nel contratto locale confermato dall'utente.
- Le preparazioni backend descritte nei documenti delle fasi 4.1 e 4.2 sono state annullate. Le RPC proposte non sono disponibili; l'editor partite resta disabilitato.
