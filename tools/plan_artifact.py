#!/usr/bin/env python3
"""Flugplaner-Artifact: zeigt einen Plan so, wie die App ihn vorbereitet (Transit, Heimflug, Tempo je Abschnitt),
in derselben 3D-Darstellung wie die App (assets/view3d.html), mit Gebäude- und Oberflächen-Prüfung.
Felix (8.11.): erst hier am Rechner rausgezoomt anschauen und ändern, dann aufs Handy.

Aufruf: python3 tools/plan_artifact.py plaene/aktuell.json [--geojson plaene/daten/gebaeude-lisboa.geojson]
        [--hoehen app/src/main/assets/hoehen.bin] [--out /pfad/flugplaner.html]
"""
import array
import json
import math
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRANSIT = 30.0; LOW_SPEED = 4.0; TRANSIT_SPEED = 5.0; NEAR_MIN = 1.5; NEAR_DIST = 30.0; MARGIN = 8.0; CLEAR = 8.0
RADIUS = 350.0  # Gebäude im Umkreis der Strecke


class Local:
    def __init__(self, lat0, lon0): self.lat0, self.lon0, self.cx = lat0, lon0, math.cos(math.radians(lat0))
    def xy(self, lat, lon): return ((lon - self.lon0) * 111320 * self.cx, (lat - self.lat0) * 110540)
    def ll(self, x, y): return (self.lat0 + y / 110540, self.lon0 + x / (111320 * self.cx))


def dseg(p, a, b):
    ax, ay = a; bx, by = b; px, py = p; dx, dy = bx - ax, by - ay; l = dx * dx + dy * dy
    t = 0 if l == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def inside(pt, poly):
    x, y = pt; c = False
    for i in range(len(poly)):
        x1, y1 = poly[i]; x2, y2 = poly[(i + 1) % len(poly)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1 + 1e-12) + x1: c = not c
    return c


def dpoly(p, poly):
    if inside(p, poly): return 0.0
    return min(dseg(p, poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


def seg_building_dist(a, b, poly, steps=20):
    return min(dpoly((a[0] + (b[0] - a[0]) * k / steps, a[1] + (b[1] - a[1]) * k / steps), poly) for k in range(steps + 1))


def load_hoehen(path):
    b = open(path, "rb").read(); lat0, lon0, dlat, dlon, rows, cols = struct.unpack("<ddddii", b[4:44]); n = rows * cols
    g = array.array("h"); g.frombytes(b[44:44 + 2 * n]); s = array.array("h"); s.frombytes(b[44 + 2 * n:44 + 4 * n])
    def smp(a, lat, lon):
        r = int((lat0 - lat) / dlat); c = int((lon - lon0) / dlon)
        if r < 0 or c < 0 or r >= rows or c >= cols: return None
        v = a[r * cols + c]; return None if v == -32768 else v / 10
    return (lambda lat, lon: smp(g, lat, lon)), (lambda lat, lon: smp(s, lat, lon))


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    opts = {argv[i][2:]: argv[i + 1] for i in range(len(argv) - 1) if argv[i].startswith("--")}
    plan = json.load(open(args[0], encoding="utf-8"))
    start = plan.get("start"); wps0 = plan["waypoints"]
    home = (start["lat"], start["lon"]) if start else (wps0[0]["lat"], wps0[0]["lon"])
    loc = Local(*home)
    # Wie MissionController.prepare: Transit senkrecht über dem Start, Abstieg erst über dem Ziel, Rückweg oben
    transit = max(TRANSIT, max(w["h"] for w in wps0))
    def W(lat, lon, h, **k): return dict(lat=lat, lon=lon, h=h, photo=k.get("photo", False), hover=k.get("hover", 0), pitch=k.get("pitch", -20), yaw=k.get("yaw"), kind=k.get("kind", "plan"))
    def beside(p, towards):
        d = math.hypot(towards[0] - p[0], towards[1] - p[1]) or 1; return (p[0] + (towards[0] - p[0]) / d * 1.5, p[1] + (towards[1] - p[1]) / d * 1.5)
    f, l = wps0[0], wps0[-1]
    wps = [W(home[0], home[1], transit, kind="transit")]
    if f["h"] < transit - 1:
        b = beside(loc.xy(f["lat"], f["lon"]), (0, 0)); la, lo = loc.ll(*b); wps.append(W(la, lo, transit, kind="transit"))
    for w in wps0: wps.append(W(w["lat"], w["lon"], w["h"], photo=w.get("photo", False), hover=w.get("hover", 0), pitch=w.get("pitch", -20), yaw=w.get("yaw")))
    if l["h"] < transit - 1:
        b = beside(loc.xy(l["lat"], l["lon"]), (0, 0)); la, lo = loc.ll(*b); wps.append(W(la, lo, transit, kind="transit"))
    wps.append(W(home[0], home[1], transit, kind="transit"))
    for w in wps: w["x"], w["y"] = loc.xy(w["lat"], w["lon"])
    # Gebäude im Umkreis
    bl = []
    gj = opts.get("geojson", str(ROOT / "plaene/daten/gebaeude-lisboa.geojson"))
    if Path(gj).exists():
        d = json.load(open(gj, encoding="utf-8"))
        for ft in d["features"]:
            g = ft["geometry"]; h = float(ft["properties"].get("h") or 0)
            rings = [g["coordinates"][0]] if g["type"] == "Polygon" else [p[0] for p in g["coordinates"]]
            for r in rings:
                poly = [loc.xy(p[1], p[0]) for p in r]
                cx = sum(p[0] for p in poly) / len(poly); cy = sum(p[1] for p in poly) / len(poly)
                if min(math.hypot(cx - w["x"], cy - w["y"]) for w in wps) < RADIUS: bl.append((h, poly, ft["properties"].get("n", "")))
    # Tempo je Abschnitt + Abstand/Konflikte
    base_speed = min(plan.get("speed", 5), TRANSIT_SPEED)
    conflicts = []; rth = max(transit, (max((b[0] for b in bl), default=0) + CLEAR)); rth = min(max(rth, 20), 120)
    for i, w in enumerate(wps):
        n = wps[i + 1] if i + 1 < len(wps) else None
        cap = min(base_speed, LOW_SPEED if (w["h"] < TRANSIT - 0.5 or (n and n["h"] < TRANSIT - 0.5)) else TRANSIT_SPEED)
        dist = 1e9; nearest = ""
        if n:
            lowest = min(w["h"], n["h"])
            for h, poly, name in bl:
                if h + CLEAR <= lowest: continue
                dd = seg_building_dist((w["x"], w["y"]), (n["x"], n["y"]), poly)
                if dd < dist: dist, nearest = dd, name
                if dd < MARGIN: conflicts.append((i, name, h, dd))
        w["speed"] = round(cap if dist >= NEAR_DIST else max(NEAR_MIN, cap * (dist - MARGIN) / (NEAR_DIST - MARGIN)) if dist > MARGIN else NEAR_MIN, 1)
        w["dist"] = None if dist >= 1e8 else round(dist, 1); w["near"] = nearest
    # Oberflächen-Wächter (Höhenmodell), relativ zum Startgelände
    terrain = []; rise = None
    hp = opts.get("hoehen", str(ROOT / "app/src/main/assets/hoehen.bin"))
    if Path(hp).exists():
        ground, surf = load_hoehen(hp); g0 = ground(*home)
        if g0 is not None:
            for i in range(len(wps) - 1):
                a, b = wps[i], wps[i + 1]; L = math.hypot(b["x"] - a["x"], b["y"] - a["y"]); steps = max(1, int(L / 3)); worst = None
                for k in range(steps + 1):
                    x = a["x"] + (b["x"] - a["x"]) * k / steps; y = a["y"] + (b["y"] - a["y"]) * k / steps; alt = a["h"] + (b["h"] - a["h"]) * k / steps
                    la, lo = loc.ll(x, y); s = surf(la, lo)
                    if s is None: continue
                    need = s - g0 + CLEAR
                    if alt < need and (worst is None or need - alt > worst[0]): worst = (need - alt, need, alt)
                if worst: terrain.append((i, worst[1], worst[2]))
            rises = [ground(w["lat"], w["lon"]) for w in wps0]; rises = [r - g0 for r in rises if r is not None]
            if rises: rise = max(rises)
    length = sum(math.hypot(wps[i + 1]["x"] - wps[i]["x"], wps[i + 1]["y"] - wps[i]["y"]) + abs(wps[i + 1]["h"] - wps[i]["h"]) for i in range(len(wps) - 1))
    dur = sum((math.hypot(wps[i + 1]["x"] - wps[i]["x"], wps[i + 1]["y"] - wps[i]["y"]) / max(wps[i]["speed"], 0.5)) for i in range(len(wps) - 1)) + sum((2 if w["photo"] else 0) + w["hover"] for w in wps) + transit / 2.5 + transit / 1.5
    # Gelände aus dem Höhenmodell (10-m-Raster, relativ zum Startboden) + Gebäude auf Geländehöhe
    terr = None; bg = {}
    if Path(hp).exists():
        ground, surf = load_hoehen(hp); g0 = ground(*home)
        if g0 is not None:
            xs = [w["x"] for w in wps]; ys = [w["y"] for w in wps]; M = 300.0; step = 10.0
            x0 = math.floor((min(xs) - M) / step) * step; y0 = math.floor((min(ys) - M) / step) * step
            nx = int((max(xs) + M - x0) / step) + 2; ny = int((max(ys) + M - y0) / step) + 2
            zs = []
            for j in range(ny):
                for i in range(nx):
                    la, lo = loc.ll(x0 + i * step, y0 + j * step); v = ground(la, lo); zs.append(round((v - g0) if v is not None else 0, 1))
            terr = {"x0": x0, "y0": y0, "dx": step, "dy": step, "nx": nx, "ny": ny, "z": zs}
            for idx, (h, poly, _) in enumerate(bl):
                cx = sum(p[0] for p in poly) / len(poly); cy = sum(p[1] for p in poly) / len(poly); la, lo = loc.ll(cx, cy); v = ground(la, lo)
                if v is not None: bg[idx] = round(v - g0, 1)
    # Szene wie View3D.kt
    route = [[round(w["x"], 1), round(w["y"], 1), w["h"], w["yaw"], w["pitch"], (2 if w["photo"] else 0) + w["hover"], w["speed"]] for w in wps]
    scene = {"b": [dict({"h": h, "p": [[round(x, 1), round(y, 1)] for x, y in poly]}, **({"g": bg[idx]} if bg.get(idx) else {})) for idx, (h, poly, _) in enumerate(bl)], "r": route, "clear": CLEAR, "mar": MARGIN}
    if terr: scene["t"] = terr
    # 3D-Code der App übernehmen
    html3d = (ROOT / "app/src/main/assets/view3d.html").read_text(encoding="utf-8")
    js = re.search(r"<script>\n(// Koordinaten.*?)</script>", html3d, re.S).group(1)
    js = js.replace("init();\n", "")
    def esc(t): return str(t).replace("&", "&amp;").replace("<", "&lt;")
    rows = []
    for i, w in enumerate(wps):
        what = "Transit" if w["kind"] == "transit" else ("Foto" if w["photo"] else "Wegpunkt")
        extra = " · %d s Schweben" % w["hover"] if w["hover"] else ""
        d = "–" if w["dist"] is None else ("%.0f m" % w["dist"] if w["dist"] < 200 else "> 200 m")
        warn = ' class="bad"' if any(c[0] == i for c in conflicts) or any(t[0] == i for t in terrain) else (' class="warn"' if w["dist"] is not None and w["dist"] < NEAR_DIST else "")
        rows.append(f'<tr{warn}><td>{i + 1}</td><td>{what}{extra}</td><td>{w["h"]:.0f} m</td><td>{w["speed"]:.1f} m/s</td><td>{d}</td><td>{esc(w["near"])}</td></tr>')
    checks = []
    checks.append(("bad" if conflicts else "ok", f"Gebäude-Wächter: {len(conflicts)} Konflikt(e) unter 8 m Abstand" if conflicts else f"Gebäude-Wächter: Strecke frei ({len(bl)} Gebäude geprüft)"))
    for i, name, h, dd in conflicts[:6]: checks.append(("bad", f"Abschnitt {i + 1}→{i + 2}: {esc(name) or 'Gebäude'} ({h:.0f} m hoch) nur {dd:.1f} m entfernt"))
    if Path(hp).exists():
        checks.append(("bad" if terrain else "ok", f"Oberflächen-Wächter: {len(terrain)} Konflikt(e)" if terrain else "Oberflächen-Wächter: Strecke frei (Oberfläche + 8 m)"))
        for i, need, alt in terrain[:6]: checks.append(("bad", f"Abschnitt {i + 1}→{i + 2}: braucht {need:.0f} m, geplant {alt:.0f} m"))
        if rise is not None: checks.append(("info", f"Gelände am Ziel {rise:+.0f} m gegenüber dem Start"))
    else: checks.append(("warn", "Höhenmodell nicht gefunden, Oberfläche nicht geprüft"))
    low = [w for w in wps if w["h"] < TRANSIT]
    checks.append(("info", f"Transithöhe {transit:.0f} m, Heimflughöhe {rth:.0f} m, Tempo max {base_speed:.0f} m/s" + (f", {len(low)} Punkte unter 30 m (max 4 m/s, nur tags)" if low else "")))
    checks_html = "".join(f'<li class="{c}">{t}</li>' for c, t in checks)
    name = plan.get("name", Path(args[0]).stem)
    desc = esc(plan.get("beschreibung", ""))
    page = f"""<title>Flugplaner</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: Cockpit-Dunkel wie die App; 3D-Bühne oben bildfüllend, darunter Prüfung und Punktliste. */
:root {{ --bg: #0b0f14; --panel: #151b23; --line: #243040; --fg: #e8edf2; --muted: #93a1b0; --accent: #2f8cff; --ok: #30d158; --warn: #ff9f0a; --bad: #ff453a;
  --mono: "SF Mono", ui-monospace, Menlo, Consolas, monospace; --sans: -apple-system, system-ui, "Segoe UI", Roboto, sans-serif; color-scheme: dark; }}
@media (prefers-color-scheme: light) {{ :root:not([data-theme="dark"]) {{ --bg: #eef2f6; --panel: #ffffff; --line: #d6dde5; --fg: #121820; --muted: #5b6878; color-scheme: light; }} }}
:root[data-theme="light"] {{ --bg: #eef2f6; --panel: #ffffff; --line: #d6dde5; --fg: #121820; --muted: #5b6878; color-scheme: light; }}
body {{ background: var(--bg); color: var(--fg); font: 15px/1.45 var(--sans); padding-block: 12px 32px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }}
.wrap {{ max-width: 1200px; margin: 0 auto; display: grid; gap: 14px; }}
header {{ display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 18px; }}
header h1 {{ font-size: 20px; margin: 0; font-weight: 650; letter-spacing: -0.01em; }}
header .facts {{ color: var(--muted); font-variant-numeric: tabular-nums; }}
.desc {{ color: var(--muted); max-width: 70ch; margin: 0; }}
.stage {{ position: relative; aspect-ratio: 16 / 9; max-height: 72vh; width: 100%; max-width: 100%; border-radius: 14px; overflow: hidden; background: #c9d6e2; border: 1px solid var(--line); }}
.stage.full {{ position: fixed; inset: 0; z-index: 50; aspect-ratio: auto; max-height: none; border-radius: 0; border: 0; }}
.stage:fullscreen {{ aspect-ratio: auto; max-height: none; border-radius: 0; }}
.stage.full .exit, .stage:fullscreen .exit {{ display: block; }}
.exit {{ display: none; position: absolute; right: max(12px, env(safe-area-inset-right, 0px)); top: max(12px, env(safe-area-inset-top, 0px)); font: 600 13px var(--sans); color: #fff; background: rgba(20,24,30,.6); border: 0; border-radius: 999px; padding: 8px 12px; cursor: pointer; }}
.stage canvas {{ display: block; width: 100%; height: 100%; touch-action: none; }}
#hint {{ position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); color: #3a4652; background: rgba(255,255,255,.75); padding: 8px 14px; border-radius: 20px; }}
#cam {{ position: absolute; left: 50%; bottom: 14px; transform: translateX(-50%); color: #f5f7fa; font-weight: 600; font-size: 13px; background: rgba(20,24,30,.6); padding: 6px 12px; border-radius: 16px; pointer-events: none; opacity: 0; transition: opacity .4s; }}
#reticle {{ position: absolute; left: 50%; top: 50%; width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 1.5px solid rgba(255,255,255,.7); border-radius: 50%; pointer-events: none; display: none; }}
#reticle:after {{ content: ""; position: absolute; left: 11px; top: 11px; width: 4px; height: 4px; background: #fff; border-radius: 50%; }}
.bar {{ display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }}
.bar button {{ font: 600 14px var(--sans); color: var(--fg); background: var(--panel); border: 1px solid var(--line); border-radius: 999px; padding: 8px 14px; cursor: pointer; }}
.bar button.primary {{ background: var(--accent); border-color: var(--accent); color: #fff; }}
.bar button:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 2px; }}
.bar .tip {{ color: var(--muted); font-size: 13px; }}
.grid {{ display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.6fr); gap: 14px; }}
@media (max-width: 760px) {{ .grid {{ grid-template-columns: 1fr; }} }}
section {{ background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px; min-width: 0; }}
section h2 {{ font-size: 12px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); margin: 0 0 8px; font-weight: 600; }}
ul.checks {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }}
ul.checks li {{ padding-left: 20px; position: relative; }}
ul.checks li:before {{ content: ""; position: absolute; left: 0; top: .5em; width: 10px; height: 10px; border-radius: 50%; background: var(--muted); }}
ul.checks li.ok:before {{ background: var(--ok); }} ul.checks li.warn:before {{ background: var(--warn); }} ul.checks li.bad:before {{ background: var(--bad); }}
.tbl {{ overflow-x: auto; }}
table {{ border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; font-size: 14px; }}
th, td {{ text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); white-space: nowrap; }}
th {{ color: var(--muted); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .06em; }}
tr.warn td:first-child {{ box-shadow: inset 3px 0 var(--warn); }} tr.bad td:first-child {{ box-shadow: inset 3px 0 var(--bad); }}
td:last-child {{ color: var(--muted); max-width: 26ch; overflow: hidden; text-overflow: ellipsis; }}
@media (prefers-reduced-motion: reduce) {{ #cam {{ transition: none; }} }}
</style>
<div class="wrap">
  <header><h1>{esc(name)}</h1><span class="facts">{length:.0f} m · ca. {dur / 60:.0f} min · {len(wps0)} Punkte + Transit · Höhe {min(w["h"] for w in wps):.0f}–{max(w["h"] for w in wps):.0f} m</span></header>
  <p class="desc">{desc}</p>
  <div class="stage"><canvas id="view"></canvas><div id="hint">3D lädt …</div><div id="reticle"></div><div id="cam"></div><button class="exit" id="bExit" type="button">Vollbild beenden</button></div>
  <div class="bar">
    <button class="primary" id="bPreview" type="button">Vorschau abspielen</button>
    <button id="bCam" type="button">Kamera wechseln</button>
    <button id="bOrbit" type="button">Übersicht</button>
    <button id="bSpeed" type="button" title="Vorschau-Tempo">4×</button>
    <button id="bFull" type="button">Vollbild</button>
    <span class="tip">Ziehen dreht, Rad oder zwei Finger zoomen. Orange = Gebäude näher als 8 m unter der Route.</span>
  </div>
  <div class="grid">
    <section><h2>Prüfung wie in der App</h2><ul class="checks">{checks_html}</ul></section>
    <section><h2>Punkte, so wie die App sie fliegt</h2><div class="tbl"><table><thead><tr><th>#</th><th>Art</th><th>Höhe</th><th>Tempo</th><th>Nächstes Gebäude</th><th>Straße</th></tr></thead><tbody>{"".join(rows)}</tbody></table></div></section>
  </div>
</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const SCENE = {json.dumps(scene, separators=(",", ":"))};
{js}
if (init()) {{ setScene(SCENE); }}
document.getElementById('bPreview').addEventListener('click', () => {{ flying = false; startPreview(); }});
document.getElementById('bCam').addEventListener('click', () => {{ flying = false; cycleCamera(); }});
document.getElementById('bOrbit').addEventListener('click', () => {{ if (preview) {{ flightMode = 'orbit'; preview.stage = 'fly'; }} else camMode = 'orbit'; camPos = null; showLabel('Übersicht'); }});
const speeds = [1, 2, 4, 8]; let si = 2;
document.getElementById('bSpeed').addEventListener('click', e => {{ si = (si + 1) % speeds.length; setPreviewScale(speeds[si]); e.currentTarget.textContent = speeds[si] === 1 ? 'Echtzeit' : speeds[si] + '×'; }});
const stage = document.querySelector('.stage'), bFull = document.getElementById('bFull');
function fullOn() {{ return !!document.fullscreenElement || stage.classList.contains('full'); }}
function setFull(on) {{ stage.classList.toggle('full', on); bFull.textContent = on ? 'Vollbild beenden' : 'Vollbild'; setTimeout(resize, 50); setTimeout(resize, 400); }}
bFull.addEventListener('click', () => {{ if (fullOn()) {{ if (document.fullscreenElement) document.exitFullscreen().catch(() => {{}}); setFull(false); }}
  else {{ const pr = stage.requestFullscreen ? stage.requestFullscreen() : Promise.reject(); Promise.resolve(pr).then(() => setFull(true)).catch(() => setFull(true)); }} }});
document.addEventListener('fullscreenchange', () => {{ if (!document.fullscreenElement) setFull(false); }});
document.getElementById('bExit').addEventListener('click', () => bFull.click());
document.addEventListener('keydown', e => {{ if (e.key === 'Escape' && stage.classList.contains('full')) setFull(false); }});
</script>
"""
    out = Path(opts.get("out", "/tmp/flugplaner.html")); out.write_text(page, encoding="utf-8")
    print(f"{out}: {len(wps)} Punkte, {len(bl)} Gebäude, {len(conflicts)} Gebäude-, {len(terrain)} Oberflächen-Konflikte, {len(page) // 1024} KB", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(__doc__)
    main(sys.argv[1:])
