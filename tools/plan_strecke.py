#!/usr/bin/env python3
"""Plan „hin und zurück“: vom Start zu einem Ziel, dort Foto + Schweben, zurück zum Start.

Aufruf: python3 tools/plan_strecke.py NAME startLat startLon zielLat zielLon hoeheM [beschreibung] > plaene/aktuell.json
Zwischenpunkte alle ~80 m auf der geraden Linie (damit der Gebäude-Wächter und die Tempo-Drosselung je Abschnitt greifen).
Kamera unterwegs leicht nach vorn-unten (-20°), am Ziel -45° (Felix 8.11.: kein Senkrechtblick an jedem Punkt).
Die App setzt Transit (senkrecht über dem Start) und Heimflug selbst dazu.
"""
import json
import math
import sys


def offset(lat, lon, dist_m, bearing_deg):
    r = 6371008.8
    b = math.radians(bearing_deg); la1 = math.radians(lat); lo1 = math.radians(lon)
    la2 = math.asin(math.sin(la1) * math.cos(dist_m / r) + math.cos(la1) * math.sin(dist_m / r) * math.cos(b))
    lo2 = lo1 + math.atan2(math.sin(b) * math.sin(dist_m / r) * math.cos(la1), math.cos(dist_m / r) - math.sin(la1) * math.sin(la2))
    return math.degrees(la2), math.degrees(lo2)


def dist_bearing(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    dla = la2 - la1; dlo = lo2 - lo1
    h = math.sin(dla / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlo / 2) ** 2
    d = 2 * 6371008.8 * math.asin(math.sqrt(h))
    y = math.sin(dlo) * math.cos(la2); x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(dlo)
    return d, (math.degrees(math.atan2(y, x)) + 360) % 360


def main(name, s_lat, s_lon, z_lat, z_lon, h, beschreibung=""):
    start = (float(s_lat), float(s_lon)); ziel = (float(z_lat), float(z_lon)); h = float(h)
    d, brg = dist_bearing(start, ziel)
    n = max(1, int(d / 80))
    hin = [offset(start[0], start[1], d * k / n, brg) for k in range(1, n + 1)]
    pts = []
    for i, (la, lo) in enumerate(hin):
        last = i == len(hin) - 1
        pts.append({"lat": round(la, 6), "lon": round(lo, 6), "h": h, "photo": last, "hover": 5 if last else 0, "pitch": -45 if last else -20})
    for la, lo in reversed(hin[:-1]):
        pts.append({"lat": round(la, 6), "lon": round(lo, 6), "h": h, "photo": False, "hover": 0, "pitch": -20})
    plan = {
        "name": name,
        "beschreibung": beschreibung or f"Vom Start zum Ziel ({d:.0f} m) in {h:.0f} m Höhe, dort Foto und 5 s Schweben, zurück zum Start.",
        "speed": 5,
        "rcLost": "continue",
        "start": {"lat": start[0], "lon": start[1]},
        "ziel": {"lat": ziel[0], "lon": ziel[1], "h": 10},
        "waypoints": pts,
    }
    json.dump(plan, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    if len(sys.argv) < 7:
        sys.exit(__doc__)
    main(*sys.argv[1:])
