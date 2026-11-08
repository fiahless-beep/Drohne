#!/usr/bin/env python3
"""Foto-Mission um ein hohes Objekt (Turm, Denkmal): Zuhause → gerade Linie zum Objekt (so tief wie möglich: LiDAR + 8 m, Gebäude + 8 m),
tiefer Ring auf Objektmitte (Abstand ≈ 1,3 × Höhe, 8 Fotos), hoher Ring über der Spitze (8 Fotos, Blick nach unten), gleicher Weg zurück.
Aufruf: python3 tools/plan_objekt.py NAME lat lon hoeheM [radiusM=1.3*H] [HH:MM=15:00] [--start lat,lon] [--beschreibung "…"] > plaene/NAME.json
Das Objekt muss in der Gebäudedatenbank stehen (sonst --hindernis-r METER für einen Ersatzumriss).
"""
import array, json, math, struct, sys
sys.path.insert(0, 'tools'); from plan_artifact import seg_building_dist
opts = {sys.argv[i][2:]: sys.argv[i + 1] for i in range(1, len(sys.argv) - 1) if sys.argv[i].startswith('--')}
args = [sys.argv[i] for i in range(1, len(sys.argv)) if not sys.argv[i].startswith('--') and not sys.argv[i - 1].startswith('--')]
NAME = args[0]; C = (float(args[1]), float(args[2])); OBJ_H = float(args[3]); R = float(args[4]) if len(args) > 4 else round(1.3 * OBJ_H)
hhmm = args[5] if len(args) > 5 else '15:00'
H = tuple(map(float, opts['start'].split(','))) if 'start' in opts else (38.72783, -9.15727)
CORR = 8.0; CLEAR = 8.0; NEAR = 30.0

b = open('app/src/main/assets/hoehen.bin', 'rb').read(); lat0, lon0, dlat, dlon, rows, cols = struct.unpack('<ddddii', b[4:44]); n = rows * cols
G = array.array('h'); G.frombytes(b[44:44 + 2 * n]); S = array.array('h'); S.frombytes(b[44 + 2 * n:44 + 4 * n])
def smp(a, lat, lon):
    r = int((lat0 - lat) / dlat); c = int((lon - lon0) / dlon)
    if r < 0 or c < 0 or r >= rows or c >= cols: return None
    v = a[r * cols + c]; return None if v == -32768 else v / 10
g0 = smp(G, *H); cx = math.cos(math.radians(H[0]))
def xy(p): return ((p[1] - H[1]) * 111320 * cx, (p[0] - H[0]) * 110540)
def ll(x, y): return (round(H[0] + y / 110540, 6), round(H[1] + x / (111320 * cx), 6))
gj = json.load(open('plaene/daten/gebaeude-lisboa.geojson', encoding='utf-8')); bl = []
for ft in gj['features']:
    gm = ft['geometry']; h = float(ft['properties'].get('h') or 0)
    for r in ([gm['coordinates'][0]] if gm['type'] == 'Polygon' else [p[0] for p in gm['coordinates']]):
        poly = [xy((p[1], p[0])) for p in r]; mx = sum(p[0] for p in poly) / len(poly); my = sum(p[1] for p in poly) / len(poly)
        if seg_building_dist((0, 0), xy(C), [(mx, my)]) < 300:
            gb = smp(G, *ll(poly[0][0], poly[0][1])); bl.append((h + ((gb - g0) if gb is not None else 0), poly))  # Dachhöhe relativ zum Start
def need(a, b):
    ax, ay = xy(a); bx, by = xy(b); L = math.hypot(bx - ax, by - ay); k = max(1, int(L / 3)); nx, ny = (-(by - ay) / L, (bx - ax) / L) if L else (0, 0); m = -1e9
    for i in range(k + 1):
        for o in (-CORR, -CORR / 2, 0, CORR / 2, CORR):
            v = smp(S, *ll(ax + (bx - ax) * i / k + nx * o, ay + (by - ay) * i / k + ny * o))
            if v is not None: m = max(m, v - g0 + CLEAR)
    for h, poly in bl:
        if seg_building_dist((ax, ay), (bx, by), poly) < NEAR + 0.5: m = max(m, h + CLEAR)  # Gebäude im 30-m-Umkreis unter sich lassen → volles Transit-Tempo statt Drosselung
    return m
def offset(p, d, brg): x, y = xy(p); return ll(x + d * math.sin(math.radians(brg)), y + d * math.cos(math.radians(brg)))
def bearing(a, b): ax, ay = xy(a); bx, by = xy(b); return (math.degrees(math.atan2(bx - ax, by - ay)) + 360) % 360
def densify(path, step=45):
    out = [path[0]]
    for a, b in zip(path, path[1:]):
        ax, ay = xy(a); bx, by = xy(b); L = math.hypot(bx - ax, by - ay); k = max(1, round(L / step))
        for i in range(1, k + 1): out.append(ll(ax + (bx - ax) * i / k, ay + (by - ay) * i / k))
    return out
# Sonne (NOAA-Näherung) für Lissabon, 8.11., Uhrzeit aus argv (WET = UTC)
hh, mm = map(int, hhmm.split(':')); doy = 312
decl = math.radians(23.44) * math.sin(math.radians(360 / 365 * (doy - 81))); lat = math.radians(C[0])
eot = 9.87 * math.sin(math.radians(2 * 360 / 365 * (doy - 81))) - 7.53 * math.cos(math.radians(360 / 365 * (doy - 81))) - 1.5 * math.sin(math.radians(360 / 365 * (doy - 81)))
solar = hh + mm / 60 + (C[1] * 4 + eot) / 60; ha = math.radians(15 * (solar - 12))
el = math.asin(math.sin(lat) * math.sin(decl) + math.cos(lat) * math.cos(decl) * math.cos(ha))
az = math.degrees(math.atan2(math.sin(ha), math.cos(ha) * math.sin(lat) - math.tan(decl) * math.cos(lat))) + 180
sun_az = az % 360; sun_el = math.degrees(el)
# Geländebezug an der Praça; Denkmal (Sockel + Säule, ca. 36 m) als eigenes Hindernis, es fehlt in der Gebäudedatenbank
gP = smp(G, *C) - g0; top = gP + OBJ_H; mid = gP + OBJ_H * 0.55
def offset(p, d, brg): x, y = xy(p); return ll(x + d * math.sin(math.radians(brg)), y + d * math.cos(math.radians(brg)))
denkmal = [offset(C, float(opts['hindernis-r']), a) for a in range(0, 360, 45)] if 'hindernis-r' in opts else []
if denkmal: bl.append((gP + OBJ_H, [xy(q) for q in denkmal]))
# Weg hin: Zuhause → Rua Castilho → Parkrand → Rasenachse → Südende des Parks
a0 = round(bearing(C, H) / 45) * 45 % 360  # Ringeinstieg auf der Seite von Zuhause
corners = [H, offset(C, R, a0)]
hin = densify(corners)
# Fotostandpunkte: 2 Achsen-Punkte vom Park (Blick nach Süden über Statue und Avenida), dann tiefer Ring (Abstand 50 m, Kamera auf Statuenmitte), dann hoher Ring
shots = []
angles = [(a0 + k) % 360 for k in range(0, 360, 45)]
ring_lo = [(offset(C, R, a), mid, (a + 180) % 360, 0, 'Ring tief') for a in angles]
def hi_h(p):  # 8 m über der Spitze, aber nie mehr als 118 m über dem Boden am Standpunkt (120-m-Regel)
    gq = (smp(G, *p) or (g0 + gP)) - g0; return min(top + 8, gq + 118)
ring_hi = [(offset(C, R, a), hi_h(offset(C, R, a)), (a + 180) % 360, round(math.degrees(math.atan2(gP + OBJ_H * 0.75 - hi_h(offset(C, R, a)), R))), 'Ring hoch') for a in reversed(angles)]
# Reihenfolge: tiefer Ring beginnt im Norden (vom Park kommend), hoher Ring zurück zum Norden
shots += ring_lo + [ring_lo[0]] + ring_hi + [ring_hi[0]]
path = hin + [s[0] for s in shots] + list(reversed(hin[:-1]))
needs = [need(path[i], path[i + 1]) for i in range(len(path) - 1)]
wps = []
for i in range(1, len(path)):
    hmin = math.ceil(max(needs[i - 1], needs[min(i, len(needs) - 1)]))
    k = i - len(hin)
    if 0 <= k < len(shots):
        p, h, yaw, pitch, what = shots[k]; h = max(h, hmin); last_of_ring = k in (len(shots) - 1, len(ring_lo))
        # Licht: Kamera-Blickrichtung vs. Sonne – Sonne im Rücken (Differenz < 60°) = gut, Gegenlicht (> 120°) = schlecht
        diff = abs((yaw - sun_az + 180) % 360 - 180); licht = 'Sonne im Rücken' if diff < 60 else ('Seitenlicht' if diff < 120 else 'Gegenlicht')
        wps.append({"lat": p[0], "lon": p[1], "h": round(h), "photo": not last_of_ring, "hover": 0 if last_of_ring else 2, "yaw": int(round(yaw)) % 360, "pitch": int(max(-60, min(30, pitch))), "notiz": f"{what}, {licht}"})
    else:
        wps.append({"lat": path[i][0], "lon": path[i][1], "h": hmin, "photo": False, "hover": 0, "pitch": -20})
gut = sum(1 for w in wps if w.get('notiz', '').endswith('Rücken')); gegen = sum(1 for w in wps if w.get('notiz', '').endswith('Gegenlicht'))
dist = math.hypot(*xy(C)); laenge = 2 * dist + 2 * 2 * math.pi * R
plan = {"name": NAME,
        "beschreibung": opts.get('beschreibung', '') + f" Start → gerade Linie ({dist:.0f} m) zum Objekt, so tief wie möglich (LiDAR-Oberfläche + 8 m, Gebäude + 8 m); tiefer Ring {R:.0f} m um die Mitte in halber Objekthöhe ({len(ring_lo)} Fotos, Kamera waagerecht auf die Mitte), hoher Ring 8 m über der Spitze ({len(ring_hi)} Fotos, Blick nach unten), gleicher Weg zurück, Strecke ca. {laenge / 1000:.1f} km. Boden am Objekt liegt {gP:+.0f} m zum Start. Sonne um {hhmm}: Azimut {sun_az:.0f}°, Höhe {sun_el:.0f}° → {gut} Fotos mit Sonne im Rücken, {gegen} im Gegenlicht. Nur tagsüber mit Sensoren.",
        "speed": 5, "rcLost": "continue", "transitHoehe": max(30, wps[0]["h"]), "start": {"lat": H[0], "lon": H[1]}, "ziel": {"lat": C[0], "lon": C[1], "h": OBJ_H},
        "hindernisse": ([{"name": NAME, "h": OBJ_H, "umriss": [[q[0], q[1]] for q in denkmal]}] if denkmal else []), "waypoints": wps}
json.dump(plan, sys.stdout, ensure_ascii=False, indent=2); print()
print(f"# {len(wps)} Punkte, Höhen {min(w['h'] for w in wps)}…{max(w['h'] for w in wps)} m, Strecke {laenge:.0f} m, Sonne {sun_az:.0f}°/{sun_el:.0f}°", file=sys.stderr)
