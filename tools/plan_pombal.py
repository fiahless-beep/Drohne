#!/usr/bin/env python3
"""Plan „Marquês de Pombal, tief durch den Park“ (Felix 8.11.): Zuhause → Rua Castilho → Parque Eduardo VII (Rasenachse) → Praça,
Fotos der Statue (Achsen-Standpunkte vom Park, tiefer Ring auf Statuenmitte, hoher Ring über der Spitze), gleicher Weg zurück.
Höhen: je Abschnitt 8 m über der höchsten LiDAR-Oberfläche im 16-m-Korridor UND Gebäude-Wächter der App (Gebäude < 8 m → Höhe + 8).
Aufruf: python3 tools/plan_pombal.py [HH:MM] > plaene/marques-de-pombal-park-tief.json
"""
import array, json, math, struct, sys
sys.path.insert(0, 'tools'); from plan_artifact import seg_building_dist
H = (38.72783, -9.15727); C = (38.72506, -9.14976)   # Zuhause, Statue (Mitte des Hausrings „Praça do Marquês de Pombal“ aus der Gebäudedatenbank)
STATUE_H = 36.0; CORR = 8.0; CLEAR = 8.0

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
        if math.hypot(mx - xy(C)[0] * 0.5, my - xy(C)[1] * 0.5) < 900:
            gb = smp(G, *ll(poly[0][0], poly[0][1])); bl.append((h + ((gb - g0) if gb is not None else 0), poly))  # Dachhöhe relativ zum Start
def need(a, b):
    ax, ay = xy(a); bx, by = xy(b); L = math.hypot(bx - ax, by - ay); k = max(1, int(L / 3)); nx, ny = (-(by - ay) / L, (bx - ax) / L) if L else (0, 0); m = -1e9
    for i in range(k + 1):
        for o in (-CORR, -CORR / 2, 0, CORR / 2, CORR):
            v = smp(S, *ll(ax + (bx - ax) * i / k + nx * o, ay + (by - ay) * i / k + ny * o))
            if v is not None: m = max(m, v - g0 + CLEAR)
    for h, poly in bl:
        if seg_building_dist((ax, ay), (bx, by), poly) < CLEAR + 0.5: m = max(m, h + CLEAR)  # Gebäude-Wächter der App (Dach relativ zum Start + 8)
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
hhmm = sys.argv[1] if len(sys.argv) > 1 else '15:00'; hh, mm = map(int, hhmm.split(':')); doy = 312
decl = math.radians(23.44) * math.sin(math.radians(360 / 365 * (doy - 81))); lat = math.radians(C[0])
eot = 9.87 * math.sin(math.radians(2 * 360 / 365 * (doy - 81))) - 7.53 * math.cos(math.radians(360 / 365 * (doy - 81))) - 1.5 * math.sin(math.radians(360 / 365 * (doy - 81)))
solar = hh + mm / 60 + (C[1] * 4 + eot) / 60; ha = math.radians(15 * (solar - 12))
el = math.asin(math.sin(lat) * math.sin(decl) + math.cos(lat) * math.cos(decl) * math.cos(ha))
az = math.degrees(math.atan2(math.sin(ha), math.cos(ha) * math.sin(lat) - math.tan(decl) * math.cos(lat))) + 180
sun_az = az % 360; sun_el = math.degrees(el)
# Geländebezug an der Praça; Denkmal (Sockel + Säule, ca. 36 m) als eigenes Hindernis, es fehlt in der Gebäudedatenbank
gP = smp(G, *C) - g0; top = gP + STATUE_H; mid = gP + STATUE_H * 0.55
def offset(p, d, brg): x, y = xy(p); return ll(x + d * math.sin(math.radians(brg)), y + d * math.cos(math.radians(brg)))
denkmal = [offset(C, 7, a) for a in range(0, 360, 45)]
bl.append((gP + STATUE_H, [xy(q) for q in denkmal]))
# Weg hin: Zuhause → Rua Castilho → Parkrand → Rasenachse → Südende des Parks
corners = [H, offset(C, 60, bearing(C, H))]  # Felix 8.11.: direkte Linie, kein Umweg; die Linie streift die Südwestecke des Parks (Bäume über LiDAR)
hin = densify(corners)
# Fotostandpunkte: 2 Achsen-Punkte vom Park (Blick nach Süden über Statue und Avenida), dann tiefer Ring (Abstand 50 m, Kamera auf Statuenmitte), dann hoher Ring
shots = []
for d, h in ((130, top + 12), (90, top + 4)):
    p = offset(C, d, 0); shots.append((p, h, 180, round(math.degrees(math.atan2(mid - h, d))), 'Achse Park → Avenida'))
R = 50
ring_lo = [(offset(C, R, a), mid, (a + 180) % 360, 0, 'Ring tief') for a in range(0, 360, 45)]
ring_hi = [(offset(C, R + 10, a), top + 6, (a + 180) % 360, round(math.degrees(math.atan2(mid - (top + 6), R + 10))), 'Ring hoch') for a in range(0, 360, 45)]
# Reihenfolge: tiefer Ring beginnt im Norden (vom Park kommend), hoher Ring zurück zum Norden
shots += ring_lo + [ring_lo[0]] + ring_hi + [ring_hi[0]]
path = hin + [s[0] for s in shots] + list(reversed(hin[:-1]))
needs = [need(path[i], path[i + 1]) for i in range(len(path) - 1)]
wps = []
for i in range(1, len(path)):
    hmin = math.ceil(max(needs[i - 1], needs[min(i, len(needs) - 1)]))
    k = i - len(hin)
    if 0 <= k < len(shots):
        p, h, yaw, pitch, what = shots[k]; h = max(h, hmin); last_of_ring = k in (len(shots) - 1, 2 + len(ring_lo))
        # Licht: Kamera-Blickrichtung vs. Sonne – Sonne im Rücken (Differenz < 60°) = gut, Gegenlicht (> 120°) = schlecht
        diff = abs((yaw - sun_az + 180) % 360 - 180); licht = 'Sonne im Rücken' if diff < 60 else ('Seitenlicht' if diff < 120 else 'Gegenlicht')
        wps.append({"lat": p[0], "lon": p[1], "h": round(h), "photo": not last_of_ring, "hover": 0 if last_of_ring else 2, "yaw": int(round(yaw)) % 360, "pitch": int(max(-60, min(30, pitch))), "notiz": f"{what}, {licht}"})
    else:
        wps.append({"lat": path[i][0], "lon": path[i][1], "h": hmin, "photo": False, "hover": 0, "pitch": -20})
gut = sum(1 for w in wps if w.get('notiz', '').endswith('Rücken')); gegen = sum(1 for w in wps if w.get('notiz', '').endswith('Gegenlicht'))
plan = {"name": "marques-de-pombal-park-tief",
        "beschreibung": f"Zuhause → gerade Linie zur Praça Marquês de Pombal (über die Dächer der Rua Castilho, dann über die Südwestecke des Parque Eduardo VII, 8 m über Baumkronen und Dächern): 2 Achsen-Fotos vom Park nach Süden (Statue, Avenida, Tejo), tiefer Ring (50 m Abstand, Kamera auf Statuenmitte, {len(ring_lo)} Fotos) und hoher Ring ({len(ring_hi)} Fotos, Blick 20° nach unten), gleicher Weg zurück. Praça liegt {-gP:.0f} m tiefer als der Start, daher negative Höhen. Sonne um {hhmm}: Azimut {sun_az:.0f}°, Höhe {sun_el:.0f}° → {gut} Fotos mit Sonne im Rücken, {gegen} im Gegenlicht (Auswahl danach). Nur tagsüber mit Sensoren.",
        "speed": 4, "rcLost": "continue", "start": {"lat": H[0], "lon": H[1]}, "ziel": {"lat": C[0], "lon": C[1], "h": STATUE_H},
        "hindernisse": [{"name": "Denkmal Marquês de Pombal", "h": STATUE_H, "umriss": [[q[0], q[1]] for q in denkmal]}], "waypoints": wps}
json.dump(plan, sys.stdout, ensure_ascii=False, indent=2); print()
print(f"# {len(wps)} Punkte, Höhen {min(w['h'] for w in wps)}…{max(w['h'] for w in wps)} m, Sonne {sun_az:.0f}°/{sun_el:.0f}°", file=sys.stderr)
