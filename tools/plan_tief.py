#!/usr/bin/env python3
"""Plan „so tief wie möglich“ vom Start zu einem Ziel und zurück (Regel 5 in plaene/MISSIONSARTEN.md):
Höhe je Abschnitt = max(Mindesthöhe, höchste LiDAR-Oberfläche im 16-m-Korridor + 8 m, Gebäude näher als 8 m → Dach + 8 m),
alles relativ zum Startboden (bergab negativ). Zwischenpunkte alle ~45 m, Kamera −20°, am Ziel −45° mit Foto + Schweben.

Aufruf: python3 tools/plan_tief.py NAME startLat startLon zielLat zielLon [mindestHoeheM=12] [beschreibung] > plaene/x.json
"""
import array, json, math, struct, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent)); from plan_artifact import seg_building_dist

ROOT = Path(__file__).resolve().parent.parent
CORR = 8.0; CLEAR = 8.0; STEP = 45.0


def main(name, s_lat, s_lon, z_lat, z_lon, min_h="12", beschreibung=""):
    H = (float(s_lat), float(s_lon)); Z = (float(z_lat), float(z_lon)); min_h = float(min_h)
    b = (ROOT / "app/src/main/assets/hoehen.bin").read_bytes(); lat0, lon0, dlat, dlon, rows, cols = struct.unpack("<ddddii", b[4:44]); n = rows * cols
    G = array.array("h"); G.frombytes(b[44:44 + 2 * n]); S = array.array("h"); S.frombytes(b[44 + 2 * n:44 + 4 * n])
    def smp(a, lat, lon):
        r = int((lat0 - lat) / dlat); c = int((lon - lon0) / dlon)
        if r < 0 or c < 0 or r >= rows or c >= cols: return None
        v = a[r * cols + c]; return None if v == -32768 else v / 10
    g0 = smp(G, *H)
    if g0 is None: sys.exit("Start außerhalb des Höhenmodells")
    cx = math.cos(math.radians(H[0]))
    def xy(p): return ((p[1] - H[1]) * 111320 * cx, (p[0] - H[0]) * 110540)
    def ll(x, y): return (round(H[0] + y / 110540, 6), round(H[1] + x / (111320 * cx), 6))
    zx, zy = xy(Z); L = math.hypot(zx, zy)
    # Gebäude im Korridor der Strecke (Dach relativ zum Startboden)
    bl = []
    for ft in json.load(open(ROOT / "plaene/daten/gebaeude-lisboa.geojson", encoding="utf-8"))["features"]:
        gm = ft["geometry"]; h = float(ft["properties"].get("h") or 0)
        for r in ([gm["coordinates"][0]] if gm["type"] == "Polygon" else [p[0] for p in gm["coordinates"]]):
            poly = [xy((p[1], p[0])) for p in r]; mx = sum(p[0] for p in poly) / len(poly); my = sum(p[1] for p in poly) / len(poly)
            if seg_building_dist((0, 0), (zx, zy), [(mx, my)]) < 120:
                gb = smp(G, *ll(poly[0][0], poly[0][1])); bl.append((h + ((gb - g0) if gb is not None else 0), poly))
    def need(a, b):
        ax, ay = a; bx, by = b; l = math.hypot(bx - ax, by - ay); k = max(1, int(l / 3)); nx, ny = (-(by - ay) / l, (bx - ax) / l) if l else (0, 0); m = min_h
        for i in range(k + 1):
            for o in (-CORR, -CORR / 2, 0, CORR / 2, CORR):
                v = smp(S, *ll(ax + (bx - ax) * i / k + nx * o, ay + (by - ay) * i / k + ny * o))
                if v is not None: m = max(m, v - g0 + CLEAR)
        for h, poly in bl:
            if seg_building_dist(a, b, poly) < CLEAR + 0.5: m = max(m, h + CLEAR)
        return m
    k = max(1, round(L / STEP)); path = [(zx * i / k, zy * i / k) for i in range(k + 1)]
    needs = [need(path[i], path[i + 1]) for i in range(k)]
    wps = []
    for i in range(1, k + 1):
        hmin = math.ceil(max(needs[i - 1], needs[min(i, k - 1)]))
        p = ll(*path[i]); last = i == k
        wps.append({"lat": p[0], "lon": p[1], "h": hmin, "photo": last, "hover": 5 if last else 0, "pitch": -45 if last else -20})
    back = [dict(w, photo=False, hover=0, pitch=-20) for w in reversed(wps[:-1])]
    wps += back
    gz = smp(G, *Z)
    plan = {"name": name, "beschreibung": beschreibung or f"So tief wie möglich (LiDAR + 8 m, Gebäude + 8 m): {L:.0f} m hin und zurück, Ziel liegt {(gz - g0) if gz is not None else 0:+.0f} m zum Start.",
            "speed": 4, "rcLost": "continue", "start": {"lat": H[0], "lon": H[1]}, "ziel": {"lat": Z[0], "lon": Z[1]}, "waypoints": wps}
    json.dump(plan, sys.stdout, ensure_ascii=False, indent=2); print()
    print(f"# {name}: {len(wps)} Punkte, Höhen {min(w['h'] for w in wps)}…{max(w['h'] for w in wps)} m, {L:.0f} m", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 6: sys.exit(__doc__)
    main(*sys.argv[1:])
