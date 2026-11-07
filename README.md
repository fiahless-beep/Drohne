# Felix Drohne

Android-App für die **DJI Mini 4 Pro** (mit RC-N2), gebaut auf dem DJI Mobile SDK v5.

Idee: Eine KI plant Foto-Flüge (Grundstück, Gebäude, Park) in der Cloud. Ein festes Programm
rechnet daraus eine DJI-Wegpunktdatei (KMZ/WPML), Felix gibt den Plan frei, die Drohne fliegt ihn ab.

## Stand: Meilenstein 1

- Verbindung zur Drohne über den Controller (USB), Registrierung beim DJI SDK
- Live-Bild der Kamera
- Akku, GPS-Satelliten, Position, Höhe, Startpunkt, Flugstatus
- Testflug: Quadrat 30 × 30 m in 30 m Höhe um die aktuelle Position, Foto an jeder Ecke,
  danach Rückkehr zum Startpunkt
- Startsperre: Akku < 60 %, < 10 Satelliten, kein Startpunkt, keine Verbindung
- Not-Knopf: Mission abbrechen + Heimflug

## Aufbau

| Datei | Aufgabe |
|---|---|
| `DroneManager.kt` | SDK starten, registrieren, Telemetrie |
| `MissionController.kt` | KMZ prüfen, hochladen, starten, pausieren, abbrechen |
| `plan/Mission.kt` | Mission, Wegpunkte, Testquadrat |
| `plan/Wpml.kt` | Festes Programm: Mission → KMZ (WPML 1.0.6), ohne KI |
| `plan/Geo.kt` | Geo-Rechnung (Versatz, Entfernung) |
| `MainActivity.kt` | Oberfläche |

## Bauen

Die APK wird bei jedem Push automatisch von GitHub Actions gebaut und unter
**Releases** veröffentlicht. Auf dem Handy die Releases-Seite öffnen und die `.apk` antippen.

Lokal: `./gradlew :app:assembleRelease` (Android SDK 35 nötig).

Der DJI-App-Key kommt **nicht** ins Repo. Er wird als GitHub-Secret `DJI_API_KEY` oder lokal in
`~/.gradle/gradle.properties` (`DJI_API_KEY=…`) gesetzt. Paketname: `com.felixos.drohne`.

## Am Gerät noch zu prüfen

- `droneEnumValue` 68 für die Mini 4 Pro in der KMZ (DJIs Prüfer meldet sonst einen Fehler im Log)
- Verhalten bei Funkabriss (aktuell: Mission zu Ende fliegen, dann Rückkehr zum Startpunkt)
- Kameraneigung −90° an allen Fotopunkten
