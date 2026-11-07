# Testplan – Schritt für Schritt

Jede Stufe einzeln testen. Erst weiter, wenn die vorige klappt. Ergebnis (Screenshot) an Claude.
DJI Fly vorher immer komplett schließen.

| Stufe | Was | Wie | Erwartet | Drohne fliegt? |
|---|---|---|---|---|
| 0 | App startet | App öffnen | Kein Absturz, Status-Text sichtbar | nein |
| 1 | DJI-Registrierung | Handy mit Internet, App öffnen | „SDK: registriert“ (braucht App-Key) | nein |
| 2 | Verbindung | Kabel Handy ↔ Controller (oberer Anschluss), Controller + Drohne an | „Drohne: verbunden“ | nein |
| 3 | Telemetrie | wie 2, draußen | Akku %, GPS ≥ 10 Satelliten, Position, „Home: gesetzt“ | nein |
| 4 | Live-Bild | wie 2 | Kamerabild links | nein |
| 5 | Plan laden | „Plan laden“ | Beispielplan wird angezeigt (nur Ansicht) | nein |
| 6 | KMZ + DJI-Prüfung | „Testflug“ → Schwebetest → Übernehmen | Log: „DJI-Prüfung: in Ordnung“ | nein |
| 7 | Hochladen | „Hochladen“ | Log: „Mission ist auf der Drohne“ | nein |
| 8 | Startsperre | „Start“ mit wenig Akku/GPS | Dialog „Start nicht möglich“ mit Grund | nein |
| 9 | Schwebetest | freies Feld, „Start“ | 10 m hoch, 5 m vor, 1 Foto, zurück, landet | **ja, minimal** |
| 10 | Not-Knopf | während Stufe 9 „Abbrechen + Heimflug“ | Mission stoppt, Drohne kommt zurück | ja |
| 11 | Testquadrat | „Testflug“ → Testquadrat | 4 Fotos an den Ecken, Heimflug | ja |
| 12 | Cloud-Plan | Claude legt echten Plan in `plaene/aktuell.json` | wie 11, mit Felix' Freigabe | ja |

Bei jedem Flug: Daumen an den Sticks. Sticks bewegen übersteuert die Mission, Pause-Taste am Controller stoppt sie.
