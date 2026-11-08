# Missionsarten und Planungsregeln (Felix, 8.11.2026)

Jeder Plan wird zuerst nach seiner Art eingeordnet. Die Art bestimmt Höhe, Kamera, Aufenthalt und Reihenfolge.
Sicherheitsregeln (Transit ≥ 30 m über dem Start, 8 m Abstand zu Gebäuden und Oberfläche, < 30 m nur mit Gebäudedaten,
< 25 m nur tagsüber mit Sensoren, Tempo < 30 m max. 4 m/s) gelten immer und gehen vor.

## 1. Foto / Video („mach Fotos von …“, „film …“)
Ziel: die ästhetisch besten Bilder. Vor dem Plan überlegen, wie das Motiv am besten aussieht.
- **Licht**: Sonnenstand zur Flugzeit rechnen (Datum, Uhrzeit; ohne Angabe 15:00). Standpunkte mit Sonne im Rücken oder
  Seitenlicht bevorzugen, Gegenlicht nur bewusst (Silhouette). Im Plan je Foto vermerken.
- **Abstand und Brennweite**: Mini 4 Pro 24 mm, Bildwinkel ca. 82° diagonal / 60° vertikal. Motivhöhe H soll etwa zwei
  Drittel des Bildes füllen → Abstand ≈ 1,3 × H. Dazu eine weitere Serie mit 2 × H (Umgebung) und eine nähere (Details).
- **Blickachse**: die bekannte Ansicht des Motivs finden (Sichtachse, Straße, Fluss im Hintergrund) und als eigene
  Standpunkte aufnehmen, leicht erhöht.
- **Höhen**: Kamera auf Motivmitte (Augenhöhe des Motivs), zweiter Ring über der Spitze mit 15–25° Blick nach unten.
  Nie senkrecht nach unten, außer es ist gewollt (Muster, Dach).
- **Viele Bilder**: 8 Standpunkte je Ring, 2 s Schweben je Foto; Auswahl danach.
- **Kamerafahrt** (Vorschau/Video): keine 90°-Kippungen, weiche Übergänge, Gimbal unterwegs −20°.

## 2. Fassade / Gebäude („Foto vom Gebäude …“)
tools/plan_gebaeudefoto.py: Straßenmitte vor der Front, Höhe = höchstes Nachbarhaus im Umkreis 30 m + 3 m (bzw. + 8 m, wenn
ein Haus näher als 8 m), Kamera auf Fassadenmitte, drei Blickwinkel (links, mittig, rechts). Sonnenseite wie bei 1.

## 3. Aufklärung („flieg hin und guck, ob …“, Parkplatz, Zustand, Zählen)
Ziel: sichere Auswertung, nicht Schönheit.
- Kamera **90° senkrecht nach unten**, Höhe so, dass das Zielgebiet ins Bild passt (Bildbreite ≈ 1,15 × Höhe über Grund).
- **Länger schweben** (≥ 6 s je Standpunkt), Standpunkte überlappend (50 %), bei Bedarf Raster.
- Die App wertet das Bild aus (Erkennung) und **wartet auf das Ergebnis**, bevor sie weiterfliegt; bei Unklarheit eine
  Stufe tiefer oder näher heran (innerhalb der Sicherheitsregeln).
- Ergebnis als Antwort (ja/nein, Anzahl, Bild) zurück in den Chat.

## 4. Transit / Strecke („flieg nach …“)
tools/plan_strecke.py: gerade Linie, Zwischenpunkte alle 80 m, Gimbal −20°, am Ziel −45°, Foto + 5 s Schweben am Ziel.

## 5. So tief wie möglich („unter den Bäumen durch“, „so niedrig wie möglich“)
Höhe je Abschnitt = höchste LiDAR-Oberfläche (Baumkronen, Dächer) im 16-m-Korridor + 8 m, zusätzlich Gebäude-Wächter der App
(Gebäude näher als 8 m → Gebäudehöhe + 8 m). Gelände beachten: Höhen sind relativ zum Startpunkt, können negativ werden.
Beispiel tools/plan_pombal.py.

## Ablauf (immer)
1. Art bestimmen, Motiv/Ziel und Uhrzeit klären (ohne Angabe: 15:00, Lissabon).
2. Plan bauen, Gebäude- und Oberflächen-Prüfung, Flugplaner-Artifact aktualisieren (tools/plan_artifact.py).
3. Felix sieht es im Planer, Änderungen auf Zuruf. Erst nach „übernehmen“ als plaene/aktuell.json fürs Handy.
