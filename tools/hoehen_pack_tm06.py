#!/usr/bin/env python3
"""Packt DGT-LiDAR-Kacheln (PT-TM06, 2 m) ohne GDAL in ein HOE1-Raster (siehe tools/hoehen_pack.py).
Aufruf: muehle_pack.py <ordner mit MDT-2m-*.tif / MDS-2m-*.tif> <lat> <lon> <halbe kante m> <ausgabe.bin>"""
import glob, math, struct, sys
import numpy as np
from PIL import Image

A = 6378137.0; F = 1 / 298.257222101; LAT0 = math.radians(39 + 40 / 60 + 5.73 / 3600); LON0 = math.radians(-(8 + 7 / 60 + 59.19 / 3600))
E2 = F * (2 - F)
def merid(p):
    e4 = E2 * E2; e6 = e4 * E2
    return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * p - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * p) + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * p) - (35 * e6 / 3072) * math.sin(6 * p))
M0 = merid(LAT0)
def tm(lat, lon):
    p = math.radians(lat); l = math.radians(lon) - LON0
    s = math.sin(p); c = math.cos(p); t = math.tan(p)
    n = A / math.sqrt(1 - E2 * s * s); T = t * t; C = E2 / (1 - E2) * c * c; a = l * c
    x = n * (a + (1 - T + C) * a ** 3 / 6 + (5 - 18 * T + T * T + 72 * C - 58 * E2 / (1 - E2)) * a ** 5 / 120)
    y = merid(p) - M0 + n * t * (a * a / 2 + (5 - T + 9 * C + 4 * C * C) * a ** 4 / 24 + (61 - 58 * T + T * T + 600 * C - 330 * E2 / (1 - E2)) * a ** 6 / 720)
    return x, y

def load(pattern):
    tiles = []
    for f in glob.glob(pattern):
        im = Image.open(f); tp = im.tag_v2[33922]; sc = im.tag_v2[33550]
        tiles.append((tp[3], tp[4], sc[0], np.array(im, dtype=np.float32)))
    return tiles
def sample(tiles, x, y, r):
    """Größter Wert im (2r+1)²-Fenster um die Zelle; None bei NoData."""
    for x0, y0, d, arr in tiles:
        c = int((x - x0) / d); rw = int((y0 - y) / d)
        if 0 <= rw < arr.shape[0] and 0 <= c < arr.shape[1]:
            w = arr[max(0, rw - r):rw + r + 1, max(0, c - r):c + r + 1]; w = w[w > -900]
            return float(w.max()) if w.size else None
    return None

def main(folder, lat, lon, half, out):
    lat = float(lat); lon = float(lon); half = float(half)
    mdt = load(folder + "/MDT-2m-*.tif"); mds = load(folder + "/MDS-2m-*.tif")
    dlat = 0.00002; dlon = 0.000025
    rows = int(2 * half / 111200 / dlat); cols = int(2 * half / (111320 * math.cos(math.radians(lat))) / dlon)
    lat_top = lat + rows / 2 * dlat; lon_left = lon - cols / 2 * dlon
    g = np.full((rows, cols), -32768, np.int16); s = g.copy()
    for r in range(rows):
        for c in range(cols):
            x, y = tm(lat_top - (r + 0.5) * dlat, lon_left + (c + 0.5) * dlon)
            a = sample(mdt, x, y, 0); b = sample(mds, x, y, 1)   # Oberfläche vorsichtig: höchster Wert der Nachbarzellen
            if a is not None: g[r, c] = round(a * 10)
            if b is not None: s[r, c] = round(max(b, a if a is not None else b) * 10)
    with open(out, "wb") as fh:
        fh.write(b"HOE1" + struct.pack("<ddddii", lat_top, lon_left, dlat, dlon, rows, cols)); fh.write(g.astype("<i2").tobytes()); fh.write(s.astype("<i2").tobytes())
    print(rows, cols, "fehlend", int((g == -32768).sum()), int((s == -32768).sum()))
    return lat_top, lon_left, dlat, dlon, g, s

if __name__ == "__main__":
    lat_top, lon_left, dlat, dlon, g, s = main(*sys.argv[1:])
    def at(la, lo): r = int((lat_top - la) / dlat); c = int((lo - lon_left) / dlon); return g[r, c] / 10, s[r, c] / 10
    for n, la, lo in [("start", 38.901381, -9.388113), ("baum16", 38.901341, -9.388688), ("baum11", 38.901809, -9.388157), ("pool", 38.901734, -9.388308), ("rundbau", 38.901745, -9.388050)]:
        print(n, at(la, lo))
    print("max Oberfläche über Start im Raster:", s.max() / 10 - at(38.901381, -9.388113)[0])
