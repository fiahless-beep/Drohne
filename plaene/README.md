# Flugpläne (Claude → App)

Die App lädt `plaene/aktuell.json` („Plan laden“), zeigt eine Zusammenfassung, Felix bestätigt, dann „Auf Drohne“ → „Start“.
Nach dem Flug lädt die App Fotos + `protokoll.txt` nach `fluege/<datum>_<name>/` hoch (braucht GitHub-Token in der App).

## Format

```json
{
  "name": "haus-azeitao",
  "beschreibung": "Haus von allen Seiten, 8 Fotos, Sonne im Rücken",
  "speed": 4,
  "rcLost": "continue",
  "licht": false,
  "ziel":  { "lat": 38.5170, "lon": -9.0130, "h": 6 },
  "kreis": { "radius": 30, "h": 40, "punkte": 8, "start": 200, "uhrzeigersinn": true }
}
```

- `ziel` (optional): Objekt, auf das Nase und Kamera zeigen. `h` = Höhe des Ziels über dem Boden (Dachfirst ≈ 6–10 m).
- `kreis` (optional, ersetzt `waypoints`): Kreisflug ums Ziel. `radius` 5–200 m, `h` Flughöhe 2–120 m, `punkte` 3–36, `fotos` = Anzahl Fotos (Standard = punkte; weniger Fotos = flüssiger, Punkte ohne Foto werden im Kurvenflug durchflogen), `start` = Richtung vom Ziel zum ersten Punkt in Grad (Sonne im Rücken: Richtung der Sonne wählen, z. B. mittags in Portugal ≈ 180).
- `waypoints` (alternativ): Liste `{lat, lon, h, pitch, photo, hover, yaw}`. Mit `ziel` werden `yaw` (Blickrichtung) und `pitch` (Kameraneigung) automatisch aufs Ziel gerechnet, wenn sie fehlen.
- `rcLost`: `continue` = Mission zu Ende fliegen, dann heim (Standard); `goHome` = sofort heim.
- `licht`: Landelampe unten während der Mission an (kleine LED, hilft nur aus wenigen Metern).
- Grenzen: max. 200 Punkte, 1–15 m/s, Höhe 2–120 m, Punkte max. 1000 m vom Startpunkt, Akku-Reserve wird in der App geprüft.

Startpunkt (Home) ist dort, wo die Drohne steht; Höhen sind relativ dazu. Hindernisse (Bäume, Leitungen) kennt der Plan nicht: Flughöhe über dem höchsten Hindernis im Umkreis wählen.

- `relativ` (optional, mit `ziel`, ersetzt `waypoints`): Punkte als `richtung` (Grad, vom Ziel aus gesehen: 180 = die Drohne steht südlich vom Ziel und schaut nach Norden) und `abstand` (m), `h` Flughöhe. Kamera und Nase zeigen aufs Ziel. Für Fassadenfotos: `ziel.h` = halbe Gebäudehöhe, `abstand` ≈ 1,5× Gebäudebreite, `h` ≈ Gebäudehöhe.

## Sicherheitsregeln (seit dem Absturz 7.11.2026)
- Die App legt den Anflug und den Rückweg automatisch auf eine Transithöhe von mindestens 30 m (oder die höchste Missionshöhe): erst über dem ersten Punkt absteigen, nach dem letzten Punkt wieder hoch, dann über den Startpunkt. Die Drohne steigt beim Start nur auf die Höhe des ersten Punkts und fliegt dann gerade; ohne Transithöhe fliegt sie in jedes Gebäude auf der Linie.
- Punkte unter 25 m sind nur bei Tageslicht und mit arbeitender Hinderniserkennung erlaubt (die Sensoren sind Kameras, nachts blind). Die App verweigert sonst den Start.
- Beim Planen immer den Startpunkt berücksichtigen und die Linie zum ersten Punkt gegen Gebäudeumrisse prüfen. In engen Straßen keine Punkte unter Dachhöhe der Nachbarn.
