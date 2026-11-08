#!/usr/bin/env python3
"""3D-Ansicht eines Flugplans: Gebäude (Grundriss + Höhe) im Umkreis als Klötze, Flugweg als Linie.

Aufruf: python3 tools/plan3d.py plaene/aktuell.json plaene/daten/gebaeude-lisboa.geojson ausgabe.html [startLat startLon] [radiusM]
Der Plan wird wie in der App aufgelöst (ziel/kreis/relativ/waypoints) und mit Transit (30 m über Start und Ziel) gezeichnet.
Die Seite braucht nur three.js von cdnjs; Daten sind eingebettet.
"""
import json
import math
import sys

TRANSIT = 30.0
MARGIN = 8.0
CLEAR = 8.0


def offset(lat, lon, dist, bearing):
    r = 6371000.0
    b = math.radians(bearing)
    la1 = math.radians(lat)
    la2 = math.asin(math.sin(la1) * math.cos(dist / r) + math.cos(la1) * math.sin(dist / r) * math.cos(b))
    lo2 = math.radians(lon) + math.atan2(math.sin(b) * math.sin(dist / r) * math.cos(la1), math.cos(dist / r) - math.sin(la1) * math.sin(la2))
    return math.degrees(la2), math.degrees(lo2)


def resolve(plan):
    pts = []
    ziel = plan.get("ziel")
    if "kreis" in plan and "waypoints" not in plan:
        k = plan["kreis"]
        n = k.get("punkte", 8)
        start = k.get("start", 0.0)
        cw = k.get("uhrzeigersinn", True)
        for i in range(n + 1):
            b = start + (i * 360.0 / n) * (1 if cw else -1)
            la, lo = offset(ziel["lat"], ziel["lon"], k["radius"], b)
            pts.append((la, lo, k["h"]))
    elif "relativ" in plan and "waypoints" not in plan:
        for w in plan["relativ"]:
            la, lo = offset(ziel["lat"], ziel["lon"], w["abstand"], w["richtung"])
            pts.append((la, lo, w["h"]))
    else:
        for w in plan["waypoints"]:
            pts.append((w["lat"], w["lon"], w["h"]))
    return pts


def main():
    a = sys.argv[1:]
    if len(a) < 3:
        sys.exit(__doc__)
    plan = json.load(open(a[0], encoding="utf-8"))
    geo = json.load(open(a[1], encoding="utf-8"))
    out = a[2]
    start = (float(a[3]), float(a[4])) if len(a) >= 5 else None
    radius = float(a[5]) if len(a) >= 6 else 300.0
    pts = resolve(plan)
    if start is None:
        start = plan.get("start", {}).get("lat"), plan.get("start", {}).get("lon")
        if start[0] is None:
            start = (pts[0][0] + 0.0008, pts[0][1])  # Annahme: Start ~90 m nördlich vom ersten Punkt
    top = max(TRANSIT, max(p[2] for p in pts))
    path = [(start[0], start[1], 0.0), (start[0], start[1], top), (pts[0][0], pts[0][1], top)] + pts + [(pts[-1][0], pts[-1][1], top), (start[0], start[1], top), (start[0], start[1], 0.0)]
    # lokales Koordinatensystem um den Mittelpunkt aller Punkte
    lat0 = sum(p[0] for p in path) / len(path)
    lon0 = sum(p[1] for p in path) / len(path)
    kx = 111320.0 * math.cos(math.radians(lat0))
    ky = 110540.0

    def xy(lat, lon):
        return ((lon - lon0) * kx, (lat - lat0) * ky)

    bld = []
    for f in geo.get("features", []):
        g = f.get("geometry") or {}
        pr = f.get("properties") or {}
        h = pr.get("h", pr.get("Altura_MDS"))
        if not h:
            continue
        rings = [g["coordinates"][0]] if g.get("type") == "Polygon" else [p[0] for p in g.get("coordinates", [])] if g.get("type") == "MultiPolygon" else []
        for ring in rings:
            poly = [xy(la, lo) for lo, la, *_ in ring]
            if any(abs(x) < radius and abs(y) < radius for x, y in poly):
                bld.append({"h": round(float(h), 1), "n": pr.get("n", pr.get("MORADA", "")), "p": [[round(x, 1), round(y, 1)] for x, y in poly]})
    route = [[round(xy(la, lo)[0], 1), round(xy(la, lo)[1], 1), h] for la, lo, h in path]
    name = plan.get("name", "plan")
    html = TEMPLATE.replace("__NAME__", name).replace("__DATA__", json.dumps({"b": bld, "r": route, "margin": MARGIN, "clear": CLEAR, "start": list(xy(*start))}, ensure_ascii=False))
    open(out, "w", encoding="utf-8").write(html)
    print(f"{len(bld)} Gebäude, {len(route)} Wegpunkte -> {out}")


TEMPLATE = r"""<title>Flugplan 3D __NAME__</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
:root { --bg: #eef1ee; --fg: #1b2228; --muted: #5d6a73; --panel: #ffffff; --line: #d8dedc; --accent: #0f7fa8; --warn: #d2571a; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg: #11171b; --fg: #e8edf0; --muted: #9aa7ae; --panel: #182026; --line: #2a343a; --accent: #1c8fb5; --warn: #d9772f; color-scheme: dark } }
:root[data-theme="dark"] { --bg: #11171b; --fg: #e8edf0; --muted: #9aa7ae; --panel: #182026; --line: #2a343a; --accent: #1c8fb5; --warn: #d9772f; color-scheme: dark }
html, body { height: 100%; }
body { margin: 0; background: var(--bg); color: var(--fg); font-family: system-ui, sans-serif; display: grid; grid-template-rows: auto 1fr; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 10px; box-sizing: border-box; }
header { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: baseline; padding-bottom: 8px; }
h1 { font-size: 1.1rem; margin: 0; }
.muted { color: var(--muted); font-size: .85rem; }
#view { width: 100%; height: 100%; min-height: 360px; border: 1px solid var(--line); border-radius: 6px; background: var(--panel); touch-action: none; }
.legend span { display: inline-block; margin-right: 12px; } .legend i { display: inline-block; width: 12px; height: 12px; border-radius: 2px; vertical-align: -2px; margin-right: 4px; }
</style>
<header><h1>Flugplan 3D: __NAME__</h1><span class="muted legend"><span><i style="background:#8d9aa5"></i>Gebäude (gemessene Höhe)</span><span><i style="background:var(--warn)"></i>Gebäude, das dem Weg näher als 8 m kommt</span><span><i style="background:var(--accent)"></i>Flugweg mit Transit</span></span><span class="muted">Ziehen = drehen, Rad/Zwei Finger = Zoom. Höhen über dem Startpunkt.</span></header>
<canvas id="view"></canvas>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const D = __DATA__;
const canvas = document.getElementById('view');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
const scene = new THREE.Scene();
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
scene.background = new THREE.Color(css('--panel'));
const cam = new THREE.PerspectiveCamera(50, 1, 1, 5000);
scene.add(new THREE.HemisphereLight(0xffffff, 0x667788, 1.0));
const sun = new THREE.DirectionalLight(0xffffff, 0.6); sun.position.set(200, 400, 100); scene.add(sun);
// Boden
const ground = new THREE.Mesh(new THREE.PlaneGeometry(2000, 2000), new THREE.MeshLambertMaterial({ color: 0xd9ded8 }));
ground.rotation.x = -Math.PI / 2; scene.add(ground);
const grid = new THREE.GridHelper(1000, 50, 0xb0b8b4, 0xcfd5d1); scene.add(grid);
// Flugweg: Punkte x=Ost, y=Hoch, z=-Nord
const route = D.r.map(p => new THREE.Vector3(p[0], p[2], -p[1]));
function distPointSeg(p, a, b) { const ab = b.clone().sub(a); const t = Math.max(0, Math.min(1, p.clone().sub(a).dot(ab) / ab.lengthSq())); return p.distanceTo(a.clone().add(ab.multiplyScalar(t))); }
function nearRoute(poly, h) { // Konflikt: ein Eckpunkt oder eine Kante näher als margin an einem Abschnitt, der tiefer als h+clear ist
  for (let i = 0; i < route.length - 1; i++) { const a = route[i], b = route[i + 1]; if (Math.min(a.y, b.y) >= h + D.clear) continue;
    const a2 = new THREE.Vector3(a.x, 0, a.z), b2 = new THREE.Vector3(b.x, 0, b.z);
    for (let k = 0; k <= 20; k++) { const p = a2.clone().lerp(b2, k / 20); for (let j = 0; j < poly.length; j++) { const q1 = new THREE.Vector3(poly[j][0], 0, -poly[j][1]), q2 = new THREE.Vector3(poly[(j + 1) % poly.length][0], 0, -poly[(j + 1) % poly.length][1]); if (distPointSeg(p, q1, q2) < D.margin) return true; } } }
  return false; }
const warn = new THREE.Color(css('--warn'));
D.b.forEach(b => { const shape = new THREE.Shape(b.p.map(q => new THREE.Vector2(q[0], -q[1]))); const g = new THREE.ExtrudeGeometry(shape, { depth: b.h, bevelEnabled: false }); g.rotateX(-Math.PI / 2); const conflict = nearRoute(b.p, b.h); const m = new THREE.Mesh(g, new THREE.MeshLambertMaterial({ color: conflict ? warn : 0x8d9aa5 })); scene.add(m); const e = new THREE.LineSegments(new THREE.EdgesGeometry(g), new THREE.LineBasicMaterial({ color: 0x4a555c })); scene.add(e); });
const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints(route), new THREE.LineBasicMaterial({ color: new THREE.Color(css('--accent')) })); scene.add(line);
route.forEach((p, i) => { const s = new THREE.Mesh(new THREE.SphereGeometry(1.2, 10, 10), new THREE.MeshLambertMaterial({ color: i === 0 ? 0x2a9d4a : css('--accent') })); s.position.copy(p); scene.add(s); const pole = new THREE.Line(new THREE.BufferGeometry().setFromPoints([p, new THREE.Vector3(p.x, 0, p.z)]), new THREE.LineBasicMaterial({ color: 0x9aa7ae, transparent: true, opacity: 0.5 })); scene.add(pole); });
// Kamera-Orbit
const center = new THREE.Box3().setFromPoints(route).getCenter(new THREE.Vector3()); center.y = 10;
let theta = -0.7, phi = 0.9, dist = Math.max(150, route.reduce((m, p) => Math.max(m, p.distanceTo(center)), 0) * 2.2);
function place() { cam.position.set(center.x + dist * Math.cos(phi) * Math.sin(theta), center.y + dist * Math.sin(phi), center.z + dist * Math.cos(phi) * Math.cos(theta)); cam.lookAt(center); }
let drag = null;
canvas.addEventListener('pointerdown', e => { drag = { x: e.clientX, y: e.clientY }; canvas.setPointerCapture(e.pointerId); });
canvas.addEventListener('pointermove', e => { if (!drag) return; theta -= (e.clientX - drag.x) * 0.006; phi = Math.max(0.1, Math.min(1.5, phi + (e.clientY - drag.y) * 0.006)); drag = { x: e.clientX, y: e.clientY }; place(); });
canvas.addEventListener('pointerup', () => drag = null);
canvas.addEventListener('wheel', e => { e.preventDefault(); dist = Math.max(40, Math.min(3000, dist * (1 + e.deltaY * 0.001))); place(); }, { passive: false });
function resize() { const r = canvas.getBoundingClientRect(); renderer.setSize(r.width, r.height, false); renderer.setPixelRatio(Math.min(2, devicePixelRatio)); cam.aspect = r.width / r.height; cam.updateProjectionMatrix(); }
window.addEventListener('resize', resize);
resize(); place();
(function loop() { renderer.render(scene, cam); requestAnimationFrame(loop); })();
</script>
"""

if __name__ == "__main__":
    main()
