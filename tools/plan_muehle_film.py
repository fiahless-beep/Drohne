import json, math, struct, sys
import numpy as np
b = open('muehle_dgt/hoehen_muehle.bin', 'rb').read()
lat0, lon0, dlat, dlon, rows, cols = struct.unpack('<ddddii', b[4:44]); n = rows * cols
S = np.frombuffer(b[44 + 2 * n:], '<i2').reshape(rows, cols) / 10
START = (38.901381, -9.388113); POOL = (38.901734, -9.388308); G0 = 88.2
ky = 111200; kx = 111320 * math.cos(math.radians(POOL[0]))
def off(p, d, brg): r = math.radians(brg); return (p[0] + d * math.cos(r) / ky, p[1] + d * math.sin(r) / kx)
def surf(p, rad=8):
    r = int((lat0 - p[0]) / dlat); c = int((p[1] - lon0) / dlon); k = int(rad / 2.2) + 1
    return float(S[r - k:r + k + 1, c - k:c + k + 1].max()) - G0
def dist(a, b): return math.hypot((a[0] - b[0]) * ky, (a[1] - b[1]) * kx)
def clearance(a, ha, b_, hb):
    m = 1e9; nn = max(1, int(dist(a, b_) / 2))
    for i in range(nn + 1):
        t = i / nn; p = (a[0] + (b_[0] - a[0]) * t, a[1] + (b_[1] - a[1]) * t)
        m = min(m, ha + (hb - ha) * t - surf(p))
    return m
wps = []
def wp(p, h, **kw): wps.append(dict(lat=round(p[0], 6), lon=round(p[1], 6), h=h, photo=False, **kw))
# 1 Anflug von Osten über die Felder, Sonne hinter dem Grundstück
wp(off(POOL, 125, 95), 20, video="start", hover=2)
wp(off(POOL, 85, 93), 21)
wp(off(POOL, 42, 90), 22, video="stop", hover=1)
# 2 Dreiviertelkreis um den Pool im Uhrzeigersinn: Ost → Süd → West → Nord
R = 42
for i, brg in enumerate(range(90, 361, 30)):
    kw = {}
    if i == 0: continue
    if i == 1: kw = dict(video="start")
    wp(off(POOL, R, brg % 360), 22, **kw)
wps[-1].update(video="stop", hover=1)
# 3 Rückzug nach Nordosten über die Felder mit Steigen, Kamera bleibt auf dem Pool
wp(off(POOL, 58, 18), 25, video="start")
wp(off(POOL, 95, 34), 38)
wp(off(POOL, 135, 42), 50, video="stop", hover=2)
TR = 30
plan = {"name": "muehle-film-1",
        "beschreibung": "Drei Kamerafahrten an der Mühle, Kamera immer auf den Pool: 1) Anflug von Osten über die Felder von 20 auf 22 m, 2) Dreiviertelkreis um den Pool mit 42 m Radius in 22 m Höhe, 3) Rückzug nach Nordosten über die Felder mit Steigen auf 50 m. Jede Fahrt ist ein eigener Clip. Gegen DGT-LiDAR 2024 geprüft: überall mindestens 10 m über Bäumen und Dächern. Start auf dem Parkplatz vor dem Tor. Für die goldene Stunde, Flug vor Sonnenuntergang beenden.",
        "speed": 3, "rcLost": "goHome", "transitHoehe": TR, "start": {"lat": START[0], "lon": START[1]},
        "ziel": {"lat": POOL[0], "lon": POOL[1], "h": 0}, "waypoints": wps}
pts = [(START, TR)] + [((w["lat"], w["lon"]), w["h"]) for w in wps] + [(START, TR)]
tot = 0; worst = 1e9
for (a, ha), (b_, hb) in zip(pts, pts[1:]):
    hh = TR if (a == START or b_ == START) else None
    c = clearance(a, hh or ha, b_, hh or hb); d = dist(a, b_); tot += d; worst = min(worst, c)
    print(f"{d:5.0f} m  h {ha:>4}→{hb:<4} Abstand zur Oberfläche min {c:5.1f} m  Abstand Pool {dist(b_, POOL):4.0f}", file=sys.stderr)
print(f"Strecke {tot:.0f} m, ~{tot / 3 / 60:.1f} min, kleinster Abstand {worst:.1f} m", file=sys.stderr)
assert worst >= 10, "zu tief"
json.dump(plan, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
