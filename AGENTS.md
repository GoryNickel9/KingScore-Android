# Ambito del lavoro

Il lavoro è limitato a questo repository, KingScore-Android.

- Non modificare altri repository, inclusa la web app King-Score.
- Non modificare o distribuire configurazioni, schema o dati su Vercel e Supabase.
- Le prove dei salvataggi sono consentite soltanto sul PC, con PostgreSQL/PostgREST loopback e dati sintetici; non usare Supabase remoto come ambiente di test.
- Se una funzionalità richiede modifiche fuori da questa repo o non può funzionare entro i vincoli, discuterne con l'utente prima dell'implementazione.
- La CI Android è riattivata su autorizzazione esplicita dell'utente del 14 ottobre 2026: sono consentiti Node, test JVM, lint e build APK nel workflow di questa repo. Non autorizza deploy, operazioni su Vercel/Supabase o test backend remoti.
- Mantenere login obbligatorio e blocco senza Internet; escludere ricalcolo ELO, registrazione e modifica credenziali/avatar.
- Le preparazioni backend descritte nei documenti delle fasi 4.1 e 4.2 sono state annullate. Le RPC proposte non sono disponibili; l'editor partite resta disabilitato.
