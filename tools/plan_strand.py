#!/usr/bin/env python3
"""Strandplan für eine Westküste ohne Höhendaten: Start oben auf der Klippe, alles seewärts und mindestens 30 m über dem Start.
Aufruf: plan_strand.py <start_lat> <start_lon> <name> [seerichtung=270] [ziel_lat ziel_lon]   (Ziel = Strandmitte, auf die die Kamera zeigt)
Clip 1: Bogen über dem Wasser (150 m um den Strandpunkt vor dem Start), Kamera zurück auf Strand und Klippen.
Clip 2: Rückzug aufs Meer hinaus mit Steigen auf 60 m."""
import json, math, sys
slat, slon = float(sys.argv[1]), float(sys.argv[2]); name = sys.argv[3]; sea = float(sys.argv[4]) if len(sys.argv) > 4 else 270.0
ky = 111200; kx = 111320 * math.cos(math.radians(slat))
def off(p, d, brg): r = math.radians(brg); return (p[0] + d * math.cos(r) / ky, p[1] + d * math.sin(r) / kx)
S = (slat, slon)
T = (float(sys.argv[5]), float(sys.argv[6])) if len(sys.argv) > 6 else off(S, 80, sea)      # Strandpunkt: darauf zeigt die Kamera
wps = []
def wp(p, h, **kw): wps.append(dict(lat=round(p[0], 6), lon=round(p[1], 6), h=h, photo=False, **kw))
for i, a in enumerate(range(55, -56, -22)):   # Bogen von rechts nach links, vom Strand aus gesehen; bleibt über dem Wasser
    kw = dict(video="start", hover=2) if i == 0 else {}
    wp(off(T, 150, sea + a), 35, **kw)
wps[-1].update(video="stop", hover=1)
wp(off(T, 110, sea), 32, video="start", hover=1)
wp(off(T, 200, sea), 45)
wp(off(T, 300, sea), 60, video="stop", hover=2)
plan = {"name": name,
        "beschreibung": "Strandflug von der Klippe: Bogen über dem Wasser 150 m vor dem Strand in 35 m Höhe über dem Start, Kamera zurück auf Strand und Klippen, danach Rückzug aufs Meer hinaus mit Steigen auf 60 m. Zwei Clips. Keine Höhendaten für diesen Ort: alles seewärts und mindestens 30 m über dem Startplatz. Weiteste Entfernung vom Ziel 300 m. Nur bei Tageslicht, Wind an der Küste beachten.",
        "speed": 4, "rcLost": "goHome", "transitHoehe": 40, "start": {"lat": slat, "lon": slon},
        "ziel": {"lat": round(T[0], 6), "lon": round(T[1], 6), "h": -20}, "waypoints": wps}
json.dump(plan, sys.stdout, ensure_ascii=False, indent=1)
