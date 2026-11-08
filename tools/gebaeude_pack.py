#!/usr/bin/env python3
"""Packt plaene/daten/gebaeude-*.geojson (Gebäude mit Höhe) in ein kompaktes Binärformat für die App.

Quelle Lissabon: ArcGIS-Layer Edificios_Lisboa_3D der Câmara Municipal (Felder MORADA, Altura_MDS in m).
Format (little-endian), Datei app/src/main/assets/gebaeude.bin:
  magic "GEB1", uint32 Anzahl Gebäude
  je Gebäude: int32 minLatE6, int32 minLonE6, int32 maxLatE6, int32 maxLonE6, uint16 Höhe in dm, uint16 Anzahl Ecken,
              dann je Ecke int32 latE6, int32 lonE6 (nur äußerer Ring, letzter = erster Punkt wird weggelassen)
Aufruf: python3 tools/gebaeude_pack.py plaene/daten/*.geojson app/src/main/assets/gebaeude.bin
"""
import glob
import json
import struct
import sys


def rings(geom):
    t = geom.get("type")
    if t == "Polygon":
        yield geom["coordinates"][0]
    elif t == "MultiPolygon":
        for poly in geom["coordinates"]:
            yield poly[0]


def main(inputs, out):
    files = [f for pat in inputs for f in sorted(glob.glob(pat))]
    n = 0
    body = bytearray()
    for f in files:
        with open(f, encoding="utf-8") as fh:
            data = json.load(fh)
        for feat in data.get("features", []):
            props = feat.get("properties") or {}
            h = props.get("Altura_MDS", props.get("h"))
            if h is None or feat.get("geometry") is None:
                continue
            try:
                h = float(h)
            except (TypeError, ValueError):
                continue
            if h <= 0:
                continue
            for ring in rings(feat["geometry"]):
                pts = [(round(lat * 1e6), round(lon * 1e6)) for lon, lat, *_ in ring]
                if len(pts) > 1 and pts[0] == pts[-1]:
                    pts = pts[:-1]
                if len(pts) < 3 or len(pts) > 65535:
                    continue
                lats = [p[0] for p in pts]
                lons = [p[1] for p in pts]
                body += struct.pack("<iiiiHH", min(lats), min(lons), max(lats), max(lons), min(65535, int(round(h * 10))), len(pts))
                for la, lo in pts:
                    body += struct.pack("<ii", la, lo)
                n += 1
    with open(out, "wb") as fh:
        fh.write(b"GEB1" + struct.pack("<I", n) + body)
    print(f"{n} Gebäude aus {len(files)} Datei(en) -> {out} ({len(body) / 1e6:.1f} MB)")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1:-1], sys.argv[-1])
