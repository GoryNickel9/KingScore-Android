# Blocco della rete predefinita

Il collaudo della candidata firmata rc1 su Android 15 ha riprodotto un difetto: con Active default network: none, MainActivity restava sul login dopo la perdita di Internet. Il monitor rileggeva activeNetwork e getNetworkCapabilities nelle callback; queste letture possono osservare uno stato obsoleto. Un avvio con processo nuovo mostrava invece il blocco corretto.

La rc2 parte con accesso bloccato finché la callback della rete predefinita non fornisce INTERNET e VALIDATED. onAvailable conserva il nuovo identificatore e attende le capacità; onCapabilitiesChanged aggiorna soltanto la rete corrente. onLost della rete corrente imposta subito false. Gli eventi di una rete precedente non possono ripristinare o togliere l'accesso a quella nuova.

Le callback vengono eseguite in sequenza sul thread di connettività Android. La scelta segue la [documentazione Android](https://developer.android.com/develop/connectivity/network-ops/reading-network-state), che richiede di usare le capacità ricevute anziché interrogare lo stato sincrono durante le callback. La validazione Android resta un prerequisito: Auth e richieste dati verificano separatamente l'account e la raggiungibilità del backend.

ValidatedDefaultNetworkTest verifica perdita immediata, callback tardive, cambio di rete e nuova validazione dopo riconnessione. Il collaudo firmato spegne Wi-Fi e dati mobili con MainActivity aperta, osserva il blocco e verifica il ritorno al login dopo la riconnessione, senza riavviare il processo e senza inviare credenziali.

Risultati effettivi: [TESTING.md](TESTING.md). La rc1 resta un artefatto di diagnosi locale e non va installata dagli utenti.
