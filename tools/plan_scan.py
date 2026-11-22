"""Vermessungsflug (Felix 22.11.2026: an einem neuen Ort erst vermessen, dann filmen).

Raster senkrecht von oben über dem Motiv, danach zwei schräge Ringe. Aus den Fotos wird ein 3D-Modell gerechnet,
auf dem der eigentliche Foto-/Videoplan gebaut wird. Alles auf sicherer Höhe; jede Strecke samt Steigflug, Rückweg
und geradem Heimflug von jedem Punkt wird gegen das Höhenmodell geprüft.

Zwei Flüge, weil 100 Fotostopps mit Bremsen und Anfahren für einen Akku mit 20 % Reserve zu knapp sind (Gegenprüfung 22.11.2026):

  python3 tools/plan_scan.py raster plaene/muehle-scan-v2-raster.json
  python3 tools/plan_scan.py ringe  plaene/muehle-scan-v2-ringe.json
"""
import json, math, struct, sys
import numpy as np

# ---- Ort: Mühle (Koordinaten wie tools/plan_muehle_film.py) ----
MODE = sys.argv[1]; assert MODE in ("raster", "ringe")
NAME = "muehle-scan-v2-" + MODE
GRID = "app/src/main/assets/hoehen_muehle.bin"
START = (38.901381, -9.388113)          # Parkplatz vor dem Tor
CENTER = (38.901680, -9.388320)         # Mitte zwischen Pool, Rundbau und Haupthaus
HALF_NS, HALF_EW = 50.0, 48.0           # halbe Kantenlängen des Rasters in m
H_GRID = 45.0                           # Flughöhe Raster über Start
LINE_M, SHOT_M = 13.0, 10.0             # Linienabstand, Fotoabstand (an 13 m hohen Kronen noch gut 70 % Überlappung)
RINGS = [(48.0, 50.0, 20), (28.0, 32.0, 16)]   # Radius, Höhe, Fotos: etwa 45° Neigung; der innere Ring bleibt 10 m neben dem Startplatz
TARGET_H = 4.0                          # Blickpunkt der schrägen Fotos über Boden
SPEED = 3.0
MIN_CLEAR = 15.0                        # verlangter Abstand zur Oberfläche (App verlangt 8)
PHOTO_S = 6.0                           # Annahme: Bremsen, Foto, Anfahren je Punkt
BATTERY_MIN = 25.0                      # Annahme: nutzbare Flugzeit eines vollen Akkus bei wenig Wind

b = open(GRID, "rb").read()
lat0, lon0, dlat, dlon, rows, cols = struct.unpack("<ddddii", b[4:44]); n = rows * cols
T = np.frombuffer(b[44:44 + 2 * n], "<i2").reshape(rows, cols) / 10      # Gelände
S = np.frombuffer(b[44 + 2 * n:44 + 4 * n], "<i2").reshape(rows, cols) / 10  # Oberfläche (Dächer, Bäume)
ky = 111200.0; kx = 111320.0 * math.cos(math.radians(CENTER[0]))

def cell(p): return int((lat0 - p[0]) / dlat), int((p[1] - lon0) / dlon)
def inside(p, k=0):
    r, c = cell(p); return k <= r < rows - k and k <= c < cols - k
r0, c0 = cell(START); G0 = float(T[r0, c0])
def surf(p, rad=8.0):
    """Höchste Oberfläche im Umkreis rad, relativ zum Start. Außerhalb des Rasters: Fehler statt Annahme."""
    k = int(rad / 2.2) + 1
    assert inside(p, k), f"Punkt {p} liegt außerhalb des Höhenmodells"
    r, c = cell(p); return float(S[r - k:r + k + 1, c - k:c + k + 1].max()) - G0
def off(p, north, east): return (p[0] + north / ky, p[1] + east / kx)
def polar(p, d, brg): r = math.radians(brg); return off(p, d * math.cos(r), d * math.sin(r))
def dist(a, b_): return math.hypot((a[0] - b_[0]) * ky, (a[1] - b_[1]) * kx)
def bearing(a, b_): return math.degrees(math.atan2((b_[1] - a[1]) * kx, (b_[0] - a[0]) * ky)) % 360
def clearance(a, ha, b_, hb):
    m = 1e9; nn = max(1, int(dist(a, b_) / 2))
    for i in range(nn + 1):
        t = i / nn; p = (a[0] + (b_[0] - a[0]) * t, a[1] + (b_[1] - a[1]) * t)
        m = min(m, ha + (hb - ha) * t - surf(p))
    return m

wps = []
def wp(p, h, pitch, yaw):
    wps.append(dict(lat=round(p[0], 6), lon=round(p[1], 6), h=h, pitch=round(pitch, 1), yaw=round(yaw, 1), photo=True, hover=0))

# 1 Raster: Linien Nord–Süd, im Osten (nahe am Start) beginnen, Schlangenlinie nach Westen
n_lines = int(round(2 * HALF_EW / LINE_M)) + 1
n_shots = int(round(2 * HALF_NS / SHOT_M)) + 1
for i in range(n_lines):
    east = HALF_EW - i * LINE_M
    ys = [-HALF_NS + k * SHOT_M for k in range(n_shots)]
    if i % 2: ys.reverse()
    for y in ys: wp(off(CENTER, y, east), H_GRID, -90.0, 0.0 if i % 2 == 0 else 180.0)
if MODE == "ringe": wps.clear()
n_grid = len(wps)

# 2 Schräge Ringe, Kamera auf die Mitte; jeder Ring beginnt dort, wo der vorige Abschnitt endet
for radius, h, count in (RINGS if MODE == "ringe" else []):
    first = bearing(CENTER, (wps[-1]["lat"], wps[-1]["lon"]) if wps else START)
    for k in range(count):
        p = polar(CENTER, radius, first + k * 360.0 / count)
        wp(p, h, -math.degrees(math.atan2(h - TARGET_H, radius)), bearing(p, CENTER))

TR = max(H_GRID, max(w["h"] for w in wps))
pts = [(START, TR)] + [((w["lat"], w["lon"]), w["h"]) for w in wps] + [(START, TR)]
tot = 0.0; worst = 1e9
for i, ((a, ha), (b_, hb)) in enumerate(zip(pts, pts[1:])):
    # Die App fliegt Anflug und Rückweg auf Transithöhe und steigt/sinkt erst über dem Punkt.
    first_leg, last_leg = i == 0, i == len(pts) - 2
    c = clearance(a, TR if (first_leg or last_leg) else ha, b_, TR if (first_leg or last_leg) else hb)
    tot += dist(a, b_); worst = min(worst, c)
# Gerader Heimflug von jedem Punkt auf Heimflughöhe (= Transithöhe), wie bei Funkabriss oder Akku-Umkehr;
# dazu der senkrechte Weg vom Punkt hinauf auf diese Höhe.
# Näher als 50 m am Start fliegt DJI auf der aktuellen Höhe heim, deshalb auch diese Linie.
home_worst = min(min(clearance(p, TR, START, TR), h - surf(p), clearance(p, h, START, h) if dist(p, START) < 50 else 1e9) for p, h in pts[1:-1])
far = max(dist(START, p) for p, _ in pts[1:-1])
secs = tot / SPEED + len(wps) * PHOTO_S + 2 * TR / 3.0
back = far / 8.0 + TR / 3.0             # Rückweg vom fernsten Punkt (App: 8 m/s) und Abstieg
used = (secs + back) / 60.0 / BATTERY_MIN * 100.0

print(f"{NAME}: {len(wps)} Fotos" + (f", Raster {n_lines} Linien x {n_shots}" if MODE == "raster" else f", Ringe {[r[2] for r in RINGS]}"), file=sys.stderr)
print(f"Strecke {tot:.0f} m, Flugzeit ~{secs / 60:.1f} min, weitester Punkt {far:.0f} m vom Start", file=sys.stderr)
print(f"Kleinster Abstand zur Oberfläche: Strecke {worst:.1f} m, Heimflug {home_worst:.1f} m", file=sys.stderr)
print(f"Akku: ~{used:.0f} % eines vollen Akkus bis zur Landung (Annahme {BATTERY_MIN:.0f} min nutzbar, kein Wind)", file=sys.stderr)
assert len(wps) <= 195, "zu viele Wegpunkte"
assert worst >= MIN_CLEAR and home_worst >= MIN_CLEAR, "zu tief"
assert used <= 80.0, "Akku reicht nicht mit 20 % Reserve"

text = (f"Vermessungsflug Mühle V2, Teil 1 von 2: {n_grid} Fotos senkrecht von oben im Raster auf {H_GRID:.0f} m." if MODE == "raster" else
        f"Vermessungsflug Mühle V2, Teil 2 von 2: zwei schräge Ringe um das Grundstück ({RINGS[0][2]} Fotos auf {RINGS[0][1]:.0f} m, {RINGS[1][2]} auf {RINGS[1][1]:.0f} m), Kamera etwa 45° auf die Mitte.")
plan = {"name": NAME,
        "beschreibung": text + f" Aus beiden Teilen entsteht das 3D-Modell für die späteren Film- und Fotopläne. Gegen DGT-LiDAR 2024 geprüft: überall mindestens "
                        f"{min(worst, home_worst):.0f} m über Bäumen und Dächern, auch auf dem Heimweg. Etwa {secs / 60:.0f} min, mit vollem Akku starten. "
                        f"Bei hohem Sonnenstand oder bedecktem Himmel fliegen, nicht in der Dämmerung.",
        "speed": SPEED, "rcLost": "goHome", "transitHoehe": TR, "start": {"lat": START[0], "lon": START[1]}, "waypoints": wps}
if MODE == "ringe": plan["ziel"] = {"lat": CENTER[0], "lon": CENTER[1], "h": TARGET_H}
json.dump(plan, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
