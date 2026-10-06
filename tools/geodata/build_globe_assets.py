#!/usr/bin/env python3
"""Builds the offline globe assets for the Context scene from Natural Earth (public domain).

Inputs (https://github.com/nvkelso/natural-earth-vector/tree/master/geojson):
  ne_110m_land.geojson, ne_50m_coastline.geojson
Outputs (feature/context/src/main/assets/globe/):
  land_dots.bin  int32 count, then int16 (lat, lon) pairs: Fibonacci-sphere points that fall on land
  coast_50m.bin  int32 line count, then per line int32 n and n int16 (lat, lon) pairs
Coordinates are quantised as lat * 32767 / 90 and lon * 32767 / 180, little-endian.

Usage: python3 build_globe_assets.py <dir with the geojson files> <output dir>   (needs numpy)
"""
import json
import math
import struct
import sys

import numpy as np

DOTS = 30000


def q(lat, lon):
    return int(round(lat * 32767 / 90)), int(round(lon * 32767 / 180))


def land_rings(path):
    rings = []
    for f in json.load(open(path))["features"]:
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        for poly in polys:
            rings.extend(np.array(r, dtype=np.float64) for r in poly)
    return rings


def fibonacci(n):
    i = np.arange(n) + 0.5
    lat = np.degrees(np.arcsin(1 - 2 * i / n))
    lon = (np.degrees(np.pi * (1 + 5 ** 0.5) * i) + 180) % 360 - 180
    return lat, lon


def on_land(lat, lon, rings):
    inside = np.zeros(lat.shape, dtype=bool)
    for r in rings:
        x0, y0 = r[:-1, 0], r[:-1, 1]
        x1, y1 = r[1:, 0], r[1:, 1]
        sel = (lat >= r[:, 1].min()) & (lat <= r[:, 1].max()) & (lon >= r[:, 0].min()) & (lon <= r[:, 0].max())
        if not sel.any():
            continue
        px, py = lon[sel][:, None], lat[sel][:, None]
        crosses = ((y0 > py) != (y1 > py)) & (px < (x1 - x0) * (py - y0) / np.where(y1 == y0, 1e-12, y1 - y0) + x0)
        inside[np.where(sel)[0]] ^= (crosses.sum(axis=1) % 2).astype(bool)
    return inside


def main(src, out):
    lat, lon = fibonacci(DOTS)
    mask = on_land(lat, lon, land_rings(f"{src}/ne_110m_land.geojson"))
    with open(f"{out}/land_dots.bin", "wb") as f:
        f.write(struct.pack("<i", int(mask.sum())))
        for a, b in zip(lat[mask], lon[mask]):
            f.write(struct.pack("<hh", *q(a, b)))
    lines = []
    for feat in json.load(open(f"{src}/ne_50m_coastline.geojson"))["features"]:
        g = feat["geometry"]
        parts = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]
        lines.extend(p for p in parts if len(p) >= 2)
    with open(f"{out}/coast_50m.bin", "wb") as f:
        f.write(struct.pack("<i", len(lines)))
        for p in lines:
            f.write(struct.pack("<i", len(p)))
            for x, y in p:
                f.write(struct.pack("<hh", *q(y, x)))
    print(f"land dots {int(mask.sum())} of {DOTS}; coastline lines {len(lines)}, points {sum(len(p) for p in lines)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
