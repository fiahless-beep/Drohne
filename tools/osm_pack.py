#!/usr/bin/env python3
"""Overpass-JSON (out geom) → kompakte GeoJSONs für Planer und App.
Aufruf: python3 tools/osm_pack.py overpass-strassen-lisboa.json plaene/daten/osm/strassen-lisboa.geojson
        python3 tools/osm_pack.py overpass-parks-lisboa.json plaene/daten/osm/parks-lisboa.geojson --parks
Straßen: LineString mit w = typische Breite in m (nach highway-Klasse) und n = Name. Parks: Polygon.
"""
import json, sys
WIDTH = {"motorway": 16, "trunk": 14, "primary": 12, "secondary": 10, "tertiary": 8, "residential": 6, "unclassified": 6, "living_street": 5, "service": 4,
         "pedestrian": 5, "footway": 2, "path": 1.5, "cycleway": 2, "steps": 1.5, "track": 2}
def main(src, dst, parks=False):
    d = json.load(open(src, encoding="utf-8")); out = []
    for e in d["elements"]:
        if e.get("type") == "way" and "geometry" in e:
            pts = [[round(p["lon"], 6), round(p["lat"], 6)] for p in e["geometry"]]
            if parks:
                if len(pts) >= 4 and pts[0] == pts[-1]: out.append({"type": "Feature", "properties": {"n": e.get("tags", {}).get("name", "")}, "geometry": {"type": "Polygon", "coordinates": [pts]}})
            else:
                hw = e.get("tags", {}).get("highway", ""); w = WIDTH.get(hw)
                if w is None or hw in ("steps", "track", "path"): continue
                if hw in ("footway", "cycleway") and len(pts) < 2: continue
                out.append({"type": "Feature", "properties": {"w": w, "n": e.get("tags", {}).get("name", "")}, "geometry": {"type": "LineString", "coordinates": pts}})
        elif parks and e.get("type") == "relation" and "members" in e:
            for m in e["members"]:
                if m.get("role") == "outer" and "geometry" in m:
                    pts = [[round(p["lon"], 6), round(p["lat"], 6)] for p in m["geometry"]]
                    if len(pts) >= 4: out.append({"type": "Feature", "properties": {"n": e.get("tags", {}).get("name", "")}, "geometry": {"type": "Polygon", "coordinates": [pts]}})
    json.dump({"type": "FeatureCollection", "features": out}, open(dst, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"{dst}: {len(out)} Features", file=sys.stderr)
if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if not x.startswith("--")]; main(a[0], a[1], "--parks" in sys.argv)
