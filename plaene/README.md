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

## Sicherheitsregeln (seit dem Absturz 7.11.2026, verschärft 8.11.)
Was schiefging: Plan mit erstem Punkt auf 12 m, 7 m vor einer Fassade, nachts, ohne Startpunkt und ohne Gebäudedaten. Die Drohne steigt beim Start nur bis zur Höhe des ersten Punkts und fliegt dann gerade – mitten in das Gebäude auf der Linie. Die DJI-Hinderniserkennung ist nachts blind.
Stufen in der App (jede für sich reicht, um diesen Absturz zu verhindern):
1. **Transit**: erster Wegpunkt senkrecht über dem Startpunkt auf mindestens 30 m (unabhängig von DJIs takeOffSecurityHeight), dann über den ersten Missionspunkt, erst dort absteigen; nach dem letzten Punkt wieder hoch, dann über den Startpunkt. Der Plan muss mit dem echten Startpunkt übernommen worden sein („Übernehmen + auf Drohne“ erst, wenn die Drohne Home hat); sonst verweigert die App den Start.
2. **Gebäudedaten Pflicht**: Punkte unter 30 m nur, wenn der Plan `hindernisse` (Umrisse + Höhen aus den Kartendaten) enthält oder ausdrücklich `"umgebung": "frei"` sagt (nichts über 2 m im Umkreis von 50 m). Der Gebäude-Wächter prüft jede Strecke inkl. Start: 8 m waagerechter Abstand und 8 m über Dachhöhe.
3. **Geplanter Start** `"start": {lat, lon}`: die Prüfung gilt für diesen Startpunkt; steht die Drohne mehr als 50 m weg, verweigert die App den Start.
4. **Nachts/ohne Sensoren**: Punkte unter 25 m nur bei Tageslicht (Sonne über −3°) und wenn die Hinderniserkennung „aktiv“ meldet. Gilt auch für „Folgen“.
5. **Tempo** (Felix 8.11.): unter 30 m höchstens 4 m/s, im Transit 5 m/s, egal was `speed` sagt. Nahe bekannten Gebäuden drosselt die App je Abschnitt: 30 m Abstand = volles Tempo, 8 m Abstand = 1,5 m/s.
6. **Heimflug**: Heimflughöhe = Transithöhe bzw. höchstes Gebäude + 8 m; der Wächter prüft von jedem Punkt die gerade Linie zum Start (so fliegt DJI bei Akku knapp oder Funkverlust). Liegt das DJI-Höhenlimit unter der Flughöhe oder meldet die Drohne Wind, verweigert die App den Start.
7. **Gebäudedatenbank**: alle Gebäude der Stadt Lissabon mit gemessener Höhe (Câmara Municipal, Layer Edifícios Lisboa 3D) liegen gepackt in der App (plaene/daten/*.geojson → tools/gebaeude_pack.py → assets/gebaeude.bin, automatisch beim Build). Die App nimmt für jeden Plan alle Gebäude im Umkreis von 300 m dazu; `hindernisse` im Plan braucht es in Lissabon nicht mehr, außerhalb weiterhin.
8. **Hinderniswächter im Livebild**: hält Mission oder Folgen an, wenn eine Fläche schnell näher kommt (Standard an; reiner Abstieg wird ausgeblendet). Abschalten unter Mehr ….
Für den Planer (Claude): Startpunkt und Gebäude immer aus den Kartendaten holen, Linie Start→erster Punkt prüfen, in engen Straßen keine Punkte unter Dachhöhe der Nachbarn, nachts nie unter 25 m, Testflüge nur bei Tageslicht.
