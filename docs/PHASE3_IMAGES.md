# Immagini e avatar in lettura, 0.3.5

Copertine nelle partite, Home, dettaglio gioco e classifiche per gioco; avatar nei partecipanti, dettaglio giocatore e classifiche. Nome, punteggi e metadati restano leggibili anche senza immagine. Le immagini sono decorative per l'accessibilità; il comando Riprova immagine è separato dall'apertura della scheda.

## Accesso e trasporto

1. Creare il loader soltanto per l'account Connected. Verificare rete e sessione prima di ogni fetch; bloccare l'app se la verifica di accesso fallisce.
2. Accettare HTTPS, senza credenziali nell'URL. Risolvere i percorsi avatar sul bucket player-images già usato dal sito; codificare i segmenti senza consentire attraversamento di directory.
3. Usare un client HTTP separato da Supabase Auth; rimuovere Authorization, apikey e Cookie. Non inoltrare il JWT al CDN. Nessun upload, modifica avatar o mutazione backend.
4. Disabilitare cache memoria/disco e chiudere richieste e loader quando termina l'accesso. Ricontrollare il guard durante la lettura dello stream e prima di pubblicare il risultato; una risposta vecchia non appartiene alla sessione nuova.
5. Limitare il payload decodificato a 8 MiB, timeout a 15 secondi e bitmap richiesta a 1024×1024. Errori CDN o immagini corrotte mostrano un fallback con retry manuale. Non aggiungere retry automatici o consultazione offline.

La verifica Auth termina prima del trasporto dell'immagine: un CDN lento non trattiene il mutex SDK. Il monitor di rete condiviso continua a bloccare le schermate. I limiti di payload non rappresentano un benchmark RAM; i decoder possono usare memoria transitoria e file temporanei. Non viene conservata una cache di consultazione.

## Dipendenza

Coil 3.1.0 è fissata nel catalogo versioni e usa AsyncImage con le dimensioni del layout. Il loader esplicito evita un singleton che sopravviva all'account. API di riferimento: [Compose](https://coil-kt.github.io/coil/compose/), [ImageLoader 3.1.0](https://raw.githubusercontent.com/coil-kt/coil/3.1.0/coil-core/src/commonMain/kotlin/coil3/ImageLoader.kt), [trasporto OkHttp 3.1.0](https://raw.githubusercontent.com/coil-kt/coil/3.1.0/coil-network-okhttp/src/commonMain/kotlin/coil3/network/okhttp/OkHttpNetworkFetcher.kt). Non sono aggiunti decoder SVG o GIF animate; formati non supportati seguono lo stesso fallback.

## Validazione e limiti

ImagePolicyTest verifica URL, percorso Storage, Unicode, rete, account e risposte tardive. ImagesUiTest usa il decoder reale con PNG sintetici e un trasporto HTTP simulato: verifica pixel, header, cache, retry, revoca, cambio account, logout, immagini corrotte e limite payload. Nessuna immagine personale o credenziale reale è usata nelle fixture.

Risultati eseguiti, checksum APK e catture sono riportati in [TESTING.md](TESTING.md) e nelle evidenze della 0.3.5. CDN reali, API 26, telefono fisico e TalkBack restano da verificare prima della stabile. La barra inferiore permette due righe per le etichette con caratteri grandi.
