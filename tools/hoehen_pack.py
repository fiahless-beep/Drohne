#!/usr/bin/env python3
"""Packt Gelände (MDT) und Oberfläche (MDS) aus DGT-LiDAR-Kacheln in ein Raster für die App (assets/hoehen.bin).

Vorbereitung auf dem Mac (GDAL, z. B. brew install gdal), aus den Kacheln in ~/FelixOS/data/dgt-lidar/:
  gdalbuildvrt mdt.vrt MDT-2m-*.tiff
  gdalbuildvrt mds.vrt MDS-2m-*.tiff
  gdalwarp -t_srs EPSG:4326 -tr 0.00005 0.00005 -r bilinear -te -9.235 38.66 -9.085 38.80 -ot Float32 mdt.vrt mdt-4326.tif
  gdalwarp -t_srs EPSG:4326 -tr 0.00005 0.00005 -r max      -te -9.235 38.66 -9.085 38.80 -ot Float32 mds.vrt mds-4326.tif
  gdal_translate -of ENVI -ot Int16 -scale 0 3276.7 0 32767 mdt-4326.tif mdt.raw   (Dezimeter; erzeugt mdt.raw + mdt.hdr)
  gdal_translate -of ENVI -ot Int16 -scale 0 3276.7 0 32767 mds-4326.tif mds.raw
  python3 tools/hoehen_pack.py mdt.raw mds.raw app/src/main/assets/hoehen.bin

Format hoehen.bin (little-endian): magic "HOE1", double lat0 (oberer Rand), double lon0 (linker Rand), double dLat, double dLon (beide positiv),
  int32 rows, int32 cols, dann rows*cols int16 Gelände in dm (zeilenweise von Nord nach Süd), dann rows*cols int16 Oberfläche in dm.
  Fehlwert: -32768. 5-m-Raster über Lissabon ≈ 2800 × 3000 Zellen × 2 × 2 Byte ≈ 34 MB.
"""
import os
import re
import struct
import sys


def read_hdr(path):
    txt = open(path, encoding="utf-8", errors="replace").read()
    g = lambda k: re.search(r"^\s*" + k + r"\s*=\s*(.+)$", txt, re.M | re.I)
    samples = int(g("samples").group(1)); lines = int(g("lines").group(1))
    bo = int(g("byte order").group(1)) if g("byte order") else 0
    dt = int(g("data type").group(1))
    mi = re.search(r"map info\s*=\s*\{([^}]*)\}", txt, re.I).group(1).split(",")
    # ENVI map info: {proj, refx, refy, lon0, lat0, dlon, dlat, ...}; refx/refy = 1,1 (oberer linker Zellenrand)
    lon0 = float(mi[3]); lat0 = float(mi[4]); dlon = float(mi[5]); dlat = float(mi[6])
    assert dt == 2, "data type muss Int16 (2) sein"
    return samples, lines, lon0, lat0, dlon, dlat, bo


def main(mdt, mds, out):
    s1, l1, lon0, lat0, dlon, dlat, bo1 = read_hdr(os.path.splitext(mdt)[0] + ".hdr")
    s2, l2, lon2, lat2, dlon2, dlat2, bo2 = read_hdr(os.path.splitext(mds)[0] + ".hdr")
    assert (s1, l1) == (s2, l2) and abs(lon0 - lon2) < 1e-9 and abs(lat0 - lat2) < 1e-9, "MDT und MDS müssen dasselbe Raster haben"
    a = open(mdt, "rb").read(); b = open(mds, "rb").read()
    assert len(a) == s1 * l1 * 2 and len(b) == len(a), "Rohdaten passen nicht zu den Headern"
    if bo1 == 1:  # big-endian -> little
        a = b"".join(a[i + 1:i + 2] + a[i:i + 1] for i in range(0, len(a), 2))
    if bo2 == 1:
        b = b"".join(b[i + 1:i + 2] + b[i:i + 1] for i in range(0, len(b), 2))
    with open(out, "wb") as fh:
        fh.write(b"HOE1" + struct.pack("<ddddii", lat0, lon0, dlat, dlon, l1, s1))
        fh.write(a); fh.write(b)
    print(f"{l1} x {s1} Zellen, {dlat:.5f}° x {dlon:.5f}°, Ursprung {lat0:.5f}/{lon0:.5f} -> {out} ({(len(a) * 2) / 1e6:.1f} MB)")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
