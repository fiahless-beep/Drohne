#!/usr/bin/env python3
"""Plan „Foto vom Gebäude“ (Regel Felix 8.11.): in der Straßenmitte vor der Front so tief wie das höchste
Nachbarhaus + 3 m (bzw. + 8 m, wenn ein Haus näher als 8 m ist – Gebäude-Wächter), Kamera auf die Fassadenmitte,
drei Blickwinkel (links, mittig, rechts entlang der Straße). An- und Rückflug macht die App auf Transithöhe.

Aufruf: python3 tools/plan_gebaeudefoto.py NAME zielLat zielLon [startLat startLon] [--geojson plaene/daten/gebaeude-lisboa.geojson] [--hoehen hoehen.bin] > plaene/aktuell.json
zielLat/zielLon = ein Punkt im oder direkt am Gebäude (Google-Pin der Hausnummer).
"""
import array
import json
import math
import struct
import sys

MARGIN = 8.0     # Gebäude-Wächter: waagerechter Abstand und Höhe über Dach
NEAR_PLUS = 3.0  # Felix: über dem höchsten Nachbarhaus (nicht näher als MARGIN) 3 m Sicherheitsabstand
NEAR_R = 30.0    # Umkreis für „Nachbarhaus“
MIN_H = 12.0


def load_buildings(path):
    d = json.load(open(path, encoding="utf-8"))
    out = []
    for f in d["features"]:
        g = f["geometry"]; h = float(f["properties"].get("h") or 0)
        rings = [g["coordinates"][0]] if g["type"] == "Polygon" else [p[0] for p in g["coordinates"]]
        for r in rings:
            out.append((h, [(p[1], p[0]) for p in r], f["properties"].get("n", "")))  # (lat, lon)
    return out


class Local:
    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0; self.cx = math.cos(math.radians(lat0))
    def xy(self, p): return ((p[1] - self.lon0) * 111320 * self.cx, (p[0] - self.lat0) * 110540)
    def ll(self, x, y): return (self.lat0 + y / 110540, self.lon0 + x / (111320 * self.cx))


def inside(pt, poly):
    x, y = pt; c = False
    for i in range(len(poly)):
        x1, y1 = poly[i]; x2, y2 = poly[(i + 1) % len(poly)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1 + 1e-12) + x1: c = not c
    return c


def dseg(p, a, b):
    ax, ay = a; bx, by = b; px, py = p; dx, dy = bx - ax, by - ay; l = dx * dx + dy * dy
    t = 0 if l == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def dpoly(p, poly):
    if inside(p, poly): return 0.0
    return min(dseg(p, poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    opts = {argv[i][2:]: argv[i + 1] for i in range(len(argv) - 1) if argv[i].startswith("--")}
    name = args[0]; ziel = (float(args[1]), float(args[2]))
    start = (float(args[3]), float(args[4])) if len(args) >= 5 else None
    loc = Local(*ziel)
    bl = [(h, [loc.xy(p) for p in poly], n) for h, poly, n in load_buildings(opts.get("geojson", "plaene/daten/gebaeude-lisboa.geojson"))
          if abs(poly[0][0] - ziel[0]) < 0.004 and abs(poly[0][1] - ziel[1]) < 0.005]
    z = loc.xy(ziel)
    # Zielgebäude: enthält den Punkt, sonst das nächste innerhalb 30 m
    target = next((b for b in bl if inside(z, b[1])), None) or min(bl, key=lambda b: dpoly(z, b[1]))
    if dpoly(z, target[1]) > 30: sys.exit("kein Gebäude am Zielpunkt")
    H, poly, street = target
    street_name = street
    others = [b for b in bl if b is not target]
    # Front: längste Kante, vor der frei Platz ist; Straßenmitte = halber Weg bis zum gegenüberliegenden Haus
    best = None
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        if L < 4: continue
        mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        nx, ny = (b[1] - a[1]) / L, -(b[0] - a[0]) / L  # Normale; Richtung so wählen, dass sie nach außen zeigt
        if inside((mid[0] + nx * 1.0, mid[1] + ny * 1.0), poly): nx, ny = -nx, -ny
        # Freiraum vor der Kante abtasten
        W = None; opp = ""
        for d in range(2, 80):
            q = (mid[0] + nx * d, mid[1] + ny * d)
            hit = next((o for o in others if inside(q, o[1])), None)
            if hit: W = d; opp = hit[2]; break
        if W is None: W = 40
        if W < 6: continue  # Lichthof/Hinterhof
        # Straßenfront: gegenüber steht ein Haus derselben Straße in typischem Abstand (8–40 m)
        score = L * (3.0 if opp and opp == street_name and 8 <= W <= 40 else 1.0) * (1.0 if 8 <= W <= 40 else 0.4)
        if best is None or score > best[0]: best = (score, mid, (nx, ny), W, L)
    if best is None: sys.exit("keine freie Front gefunden")
    _, mid, (nx, ny), W, L = best
    P = (mid[0] + nx * W / 2, mid[1] + ny * W / 2)  # Straßenmitte vor der Front
    # Flughöhe nach Felix' Regel + Gebäude-Wächter
    near8 = [b[0] for b in [target] + others if dpoly(P, b[1]) < MARGIN]
    near30 = [b[0] for b in others if dpoly(P, b[1]) < NEAR_R]
    h = max(MIN_H, (max(near8) + MARGIN) if near8 else 0, (max(near30) + NEAR_PLUS) if near30 else 0)
    # Zusätzliche Blickwinkel entlang der Straße (±12 m), nur wenn dort frei
    tx, ty = -ny, nx
    pts = []
    for off in (-12.0, 0.0, 12.0):
        q = (P[0] + tx * off, P[1] + ty * off)
        n8 = [b[0] for b in [target] + others if dpoly(q, b[1]) < MARGIN]
        n30 = [b[0] for b in others if dpoly(q, b[1]) < NEAR_R]
        hq = max(MIN_H, (max(n8) + MARGIN) if n8 else 0, (max(n30) + NEAR_PLUS) if n30 else 0)
        pts.append((q, hq))
    hmax = max(hq for _, hq in pts)
    # Höhenmodell: Oberfläche + 8 relativ zum Startgelände (falls Datei da)
    note = ""; rise = 0.0
    if "hoehen" in opts and start:
        b = open(opts["hoehen"], "rb").read(); lat0, lon0, dlat, dlon, rows, cols = struct.unpack("<ddddii", b[4:44]); n = rows * cols
        g = array.array("h"); g.frombytes(b[44:44 + 2 * n]); s = array.array("h"); s.frombytes(b[44 + 2 * n:44 + 4 * n])
        def smp(a, p):
            r = int((lat0 - p[0]) / dlat); c = int((p[1] - lon0) / dlon); v = a[r * cols + c]; return None if v == -32768 else v / 10
        g0 = smp(g, start)
        need = 0
        for q, _ in pts:
            for dx in (-3, 0, 3):
                for dy in (-3, 0, 3):
                    v = smp(s, loc.ll(q[0] + dx, q[1] + dy))
                    if v is not None and g0 is not None: need = max(need, v - g0 + MARGIN)
        if need > hmax: note = f" Höhenmodell verlangt {need:.0f} m (Gelände/Bäume), angehoben."; hmax = math.ceil(need)
        rise = (smp(g, loc.ll(*P)) or 0) - (g0 or 0)
        note += f" Gelände am Ziel {rise:+.0f} m gegenüber Start."
    hmax = math.ceil(hmax)
    # Kamera auf die Fassadenmitte: Kurs = Peilung zur Front, Neigung = Winkel hinunter zur halben Gebäudehöhe
    wps = []
    for q, _ in pts:
        la, lo = loc.ll(*q); dx = mid[0] - q[0]; dy = mid[1] - q[1]; dist = max(1.0, math.hypot(dx, dy))
        yaw = round((math.degrees(math.atan2(dx, dy)) + 360) % 360); pitch = max(-80, min(-5, round(-math.degrees(math.atan2(hmax - (rise + H / 2), dist)))))
        wps.append({"lat": round(la, 6), "lon": round(lo, 6), "h": hmax, "photo": True, "hover": 2, "yaw": yaw, "pitch": pitch})
    fl, fo = loc.ll(*mid)
    plan = {
        "name": name,
        "beschreibung": f"Gebäudefoto {street}: Gebäude {H:.0f} m hoch, Front {L:.0f} m, Straße {W:.0f} m breit. Drei Fotos aus der Straßenmitte in {hmax} m Höhe (höchstes Nachbarhaus {max(near30) if near30 else 0:.0f} m, Regel +3 m bzw. +8 m bei <8 m Abstand), Kamera auf die Fassadenmitte.{note}",
        "speed": 4,
        "rcLost": "continue",
        "ziel": {"lat": round(fl, 6), "lon": round(fo, 6), "h": round(H / 2, 1)},
        "waypoints": wps,
    }
    if start: plan["start"] = {"lat": start[0], "lon": start[1]}
    json.dump(plan, sys.stdout, ensure_ascii=False, indent=2); print()
    print(f"# {plan['beschreibung']}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 4: sys.exit(__doc__)
    main(sys.argv[1:])
