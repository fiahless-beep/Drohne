"""Fotoflug Mühle (Art 1 „Foto“ aus plaene/MISSIONSARTEN.md), noch auf dem LiDAR-Modell, vor dem 3D-Modell aus dem Scan.

Drei Serien, Kamera immer auf die Mitte des Grundstücks:
  A  naher Ring, so tief wie das Höhenmodell mit 10 m Abstand erlaubt
  B  drei Standpunkte mit der Sonne im Rücken (Nachmittag: Drohne im Südwesten)
  C  weiter Ring für die Umgebung

  python3 tools/plan_muehle_foto.py plaene/muehle-foto-v1.json
"""
import json, math, struct, sys
import numpy as np

NAME = "muehle-foto-v1"
GRID = "app/src/main/assets/hoehen_muehle.bin"
START = (38.901381, -9.388113)
CENTER = (38.901680, -9.388320)
TARGET_H = 3.0
CLEAR = 10.0            # Abstand zur Oberfläche im 8-m-Umkreis (App verlangt 8)
SUN_FROM = 215.0        # Sonnenrichtung am 22.11. gegen 14:30 bis 15:30 Uhr (Südwest, 15 bis 22° hoch)
TR = 35.0               # Anflug, Rückweg und Heimflug

b = open(GRID, "rb").read()
lat0, lon0, dlat, dlon, rows, cols = struct.unpack("<ddddii", b[4:44]); n = rows * cols
T = np.frombuffer(b[44:44 + 2 * n], "<i2").reshape(rows, cols) / 10
S = np.frombuffer(b[44 + 2 * n:44 + 4 * n], "<i2").reshape(rows, cols) / 10
ky = 111200.0; kx = 111320.0 * math.cos(math.radians(CENTER[0]))
def cell(p): return int((lat0 - p[0]) / dlat), int((p[1] - lon0) / dlon)
r0, c0 = cell(START); G0 = float(T[r0, c0])
def surf(p, rad=8.0):
    k = int(rad / 2.2) + 1; r, c = cell(p)
    assert k <= r < rows - k and k <= c < cols - k, "außerhalb des Höhenmodells"
    return float(S[r - k:r + k + 1, c - k:c + k + 1].max()) - G0
def polar(p, d, brg): r = math.radians(brg); return (p[0] + d * math.cos(r) / ky, p[1] + d * math.sin(r) / kx)
def dist(a, b_): return math.hypot((a[0] - b_[0]) * ky, (a[1] - b_[1]) * kx)
def seg_need(a, b_):
    """Höhe, die die ganze Strecke a→b mit CLEAR Abstand überfliegt."""
    nn = max(1, int(dist(a, b_) / 2)); return max(surf((a[0] + (b_[0] - a[0]) * i / nn, a[1] + (b_[1] - a[1]) * i / nn)) for i in range(nn + 1)) + CLEAR
def clearance(a, ha, b_, hb):
    nn = max(1, int(dist(a, b_) / 2))
    return min(ha + (hb - ha) * i / nn - surf((a[0] + (b_[0] - a[0]) * i / nn, a[1] + (b_[1] - a[1]) * i / nn)) for i in range(nn + 1))

pts = []   # (pos, Wunschhöhe, Serie)
for k in range(8): pts.append((polar(CENTER, 30.0, SUN_FROM + 45.0 * k), 15.0, "A"))            # naher Ring, beginnt auf der Sonnenseite
for brg, d in ((SUN_FROM + 20, 46.0), (SUN_FROM, 50.0), (SUN_FROM - 20, 46.0)): pts.append((polar(CENTER, d, brg), 18.0, "B"))
for k in range(8): pts.append((polar(CENTER, 62.0, SUN_FROM - 20 + 45.0 * k), 36.0, "C"))       # weiter Ring

# Höhe je Punkt: Wunsch, aber jede angrenzende Strecke muss mit CLEAR Abstand frei sein (Höhen ändern sich linear entlang der Strecke,
# deshalb beide Enden auf den Bedarf der Strecke heben).
h = [max(w, surf(p) + CLEAR) for p, w, _ in pts]
for i in range(len(pts) - 1):
    need = seg_need(pts[i][0], pts[i + 1][0]); h[i] = max(h[i], need); h[i + 1] = max(h[i + 1], need)
h = [math.ceil(x) for x in h]

wps = [dict(lat=round(p[0], 6), lon=round(p[1], 6), h=hh, photo=True, hover=2) for (p, _, _), hh in zip(pts, h)]
legs = [(START, TR, pts[0][0], TR)] + [(pts[i][0], h[i], pts[i + 1][0], h[i + 1]) for i in range(len(pts) - 1)] + [(pts[-1][0], TR, START, TR)]
worst = min(clearance(*l) for l in legs)
home = min(min(clearance(p, TR, START, TR), clearance(p, hh, START, hh) if dist(p, START) < 50 else 1e9, hh - surf(p)) for (p, _, _), hh in zip(pts, h))
tot = sum(dist(l[0], l[2]) for l in legs); secs = tot / 3.0 + len(wps) * 8.0 + 2 * TR / 3.0
for (p, w, s), hh in zip(pts, h):
    print(f"{s}  h {hh:>3} m (Wunsch {w:.0f})  Oberfläche darunter {surf(p):5.1f}  Abstand Mitte {dist(p, CENTER):3.0f} m  Start {dist(p, START):3.0f} m", file=sys.stderr)
print(f"{len(wps)} Fotos, Strecke {tot:.0f} m, ~{secs / 60:.1f} min, kleinster Abstand Strecke {worst:.1f} m, Heimweg {home:.1f} m, tiefster Punkt {min(h)} m", file=sys.stderr)
assert worst >= CLEAR - 0.01, "Strecke zu tief"
assert home >= CLEAR - 0.01, "Heimweg zu tief"
plan = {"name": NAME,
        "beschreibung": f"Fotoflug Mühle V1, {len(wps)} Fotos, Kamera immer auf die Mitte des Grundstücks: naher Ring mit 30 m Radius so tief wie möglich ({min(h[:8])} bis {max(h[:8])} m), "
                        f"drei Standpunkte aus Südwesten mit der Sonne im Rücken, weiter Ring mit 62 m Radius auf {max(h[11:])} m für die Umgebung. "
                        f"Gegen DGT-LiDAR 2024 geprüft: überall mindestens {min(worst, home):.0f} m über Bäumen und Dächern. Für den Nachmittag gerechnet (Sonne im Südwesten). Etwa {secs / 60:.0f} min.",
        "speed": 3, "rcLost": "continue", "transitHoehe": TR, "start": {"lat": START[0], "lon": START[1]},
        "ziel": {"lat": CENTER[0], "lon": CENTER[1], "h": TARGET_H}, "waypoints": wps}
json.dump(plan, open(sys.argv[1], "w"), ensure_ascii=False, indent=1)
