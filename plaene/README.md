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
