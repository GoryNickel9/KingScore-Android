# Misure sul PC e benchmark aperti

La candidata firmata 0.5.0-rc2 è collaudata su emulatori x86_64 API 31 e 35. Host: Windows, Intel Core i3-10100F, 8 processori logici; ogni emulatore usa quattro core, 1.536 MiB di RAM su API 31 e 2.048 MiB su API 35, renderer SwiftShader. Durante le prove erano presenti altri processi del PC: queste misure non rappresentano prestazioni garantite su telefoni.

## Diagnostica di avvio

| Versione | MainActivity offline, processo nuovo | Login online, processo nuovo e sessione vuota |
| --- | --- | --- |
| Android 12 / API 31 | 1.737 ms | 2.311 ms |
| Android 15 / API 35 | 1.569 ms | 1.538 ms |

Sono i valori TotalTime di am start -W nel collaudo della release, un solo campione per stato. Non misurano il completamento dell'Auth remota, il caricamento dati o la prima pagina dello storico. La verifica UI avviene separatamente dopo l'avvio. Fonte: [private-release-validation.json](evidence/private-release-validation.json).

## Prove ancora da completare

Prima di fissare soglie, eseguire un protocollo ripetuto sul PC con configurazione stabile. Restano da misurare prima pagina con fixture dichiarate, fluidità di scorrimento, memoria e numero di richieste. Usare servizi loopback e account sintetici; Supabase remoto e telefoni restano esclusi. Non usare i tempi delle suite o questi quattro avvii singoli come benchmark dell'app.
