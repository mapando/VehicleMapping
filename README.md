# VehicleMapping

App Android che, con il telefono montato in auto, scatta foto della strada con posizione GPS.
Le foto serviranno a estrarre targhe, marca, modello e anno dei veicoli e a costruire una mappa.

## Stato

- **App** (`app/`): scatto, GPS, salvataggio locale e upload verso il server.
- **Server** (`server/`): riceve le foto con i dati GPS e le mette in coda. Analisi e mappa arrivano nelle fasi successive.

## Uso

1. Monta il telefono con la fotocamera posteriore verso la strada.
2. Scegli **Ogni N metri** (predefinito 15 m) oppure **Ogni N secondi**.
3. Premi **Avvia** e concedi i permessi di fotocamera, posizione e notifiche.
4. L'anteprima compare dopo l'avvio; l'acquisizione continua anche a schermo spento.
5. Premi **Ferma** nell'app o nella notifica.

Non viene scattata nessuna foto finché non c'è una posizione GPS recente.

## Regolazioni contro il mosso

- **Tempo di esposizione** (Automatico, 1/500, 1/1000, 1/2000; predefinito 1/1000): tempo fisso con ISO automatici.
  Richiede Android 16 e una fotocamera che supporti la priorità ai tempi; altrimenti l'app ripiega sulla modalità sport,
  se disponibile. Sotto lo stato l'app indica quale regolazione è stata applicata davvero.
- **Fuoco fisso all'infinito**: evita che l'autofocus agganci parabrezza o cruscotto.

Tempo di esposizione e ISO effettivi di ogni foto sono nei dati EXIF.

## Dove finiscono i dati

Ogni sessione salva nella memoria condivisa del telefono, visibile dal file manager e dalla galleria:

```
Pictures/VehicleMapping/<aaaammgg_hhmmss>/img_00001.jpg    (con posizione GPS nell'EXIF)
Documents/VehicleMapping/<aaaammgg_hhmmss>/index.csv       (una riga per foto: orario, lat, lon, accuratezza, velocità, direzione, quota)
```

Richiede Android 10 o successivo.

## Upload

1. Premi **Server** e inserisci indirizzo (`https://…`) e token.
2. Premi **Carica foto (N)**. In Wi-Fi parte subito; su rete a consumo chiede conferma indicando numero e peso delle foto.
3. Se l'upload è partito in Wi-Fi e il Wi-Fi cade, si mette in pausa invece di passare alla rete dati.

L'upload riprende sempre dalla prima foto non caricata; ricaricare una foto già ricevuta non crea doppioni.
Vengono caricate solo le foto scattate dalla versione 0.4 in poi.

## Server

Serve una macchina con Docker e le porte 80 e 443 aperte.

```
cd server
cp .env.example .env      # imposta VM_API_TOKEN e VM_DOMAIN
docker compose up -d --build
```

Caddy ottiene da solo il certificato HTTPS per `VM_DOMAIN`. Verifica con `https://<VM_DOMAIN>/api/health`.
Le foto ricevute sono in `server/data/inbox/`, il database in `server/data/vehiclemapping.db`.

Test: `pip install -r requirements-dev.txt && python -m pytest`

## Compilare

Serve l'SDK Android (piattaforma 36). Da Android Studio apri la cartella, oppure:

```
./gradlew assembleDebug testDebugUnitTest
```

L'APK è in `app/build/outputs/apk/debug/app-debug.apk`.
