# VehicleMapping

App Android che, con il telefono montato in auto, scatta foto della strada con posizione GPS.
Le foto serviranno a estrarre targhe, marca, modello e anno dei veicoli e a costruire una mappa.

## Stato

Fase 1 (questa): scatto, GPS e salvataggio locale. Upload, analisi sul server e mappa arrivano nelle fasi successive.

## Uso

1. Monta il telefono con la fotocamera posteriore verso la strada.
2. Scegli **Ogni N metri** (predefinito 15 m) oppure **Ogni N secondi**.
3. Premi **Avvia** e concedi i permessi di fotocamera, posizione e notifiche.
4. L'anteprima compare dopo l'avvio; l'acquisizione continua anche a schermo spento.
5. Premi **Ferma** nell'app o nella notifica.

Non viene scattata nessuna foto finché non c'è una posizione GPS recente.

## Dove finiscono i dati

Ogni sessione crea una cartella sul telefono:

```
Android/data/eu.mapando.vehiclemapping/files/captures/<aaaammgg_hhmmss>/
  img_00001.jpg   (con posizione GPS nell'EXIF)
  index.csv       (una riga per foto: orario, lat, lon, accuratezza, velocità, direzione, quota)
```

Per copiarle sul PC: `adb pull /sdcard/Android/data/eu.mapando.vehiclemapping/files/captures`

## Compilare

Serve l'SDK Android (piattaforma 35). Da Android Studio apri la cartella, oppure:

```
./gradlew assembleDebug testDebugUnitTest
```

L'APK è in `app/build/outputs/apk/debug/app-debug.apk`.
