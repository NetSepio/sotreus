#!/usr/bin/env python3
"""Builds the offline warning-region boundaries for Context › Environment from Natural Earth (public domain).

The phone looks up which official warning service covers its own position, so the request names only a
state or country and never carries coordinates.

Inputs (https://github.com/nvkelso/natural-earth-vector/tree/master/geojson):
  ne_50m_admin_0_countries.geojson, ne_50m_admin_1_states_provinces.geojson
Output (context/core/src/main/assets/regions/warning_regions.bin), little-endian:
  int32 region count, then per region:
    uint8 code length, code (UTF-8): "US-CA" style for US states and DC, ISO 3166-1 alpha-2 otherwise
    float32 south, west, north, east
    int32 ring count, then per ring int32 n and n int32 (lat * 1e5, lon * 1e5) pairs
  Rings are tested together with the even-odd rule, so holes need no flag.
Rings are simplified with Douglas-Peucker at TOLERANCE degrees (about 1 km).

Usage: python3 build_region_assets.py <dir with the geojson files> <output file>
"""
import json
import struct
import sys

TOLERANCE = 0.01

# US territories NWS issues alerts for (NWS area code = ISO alpha-2).
US_TERRITORIES = {"PR", "GU", "VI", "AS", "MP"}
# Countries with a MeteoAlarm legacy feed (see WarningRegions.METEOALARM).
METEOALARM = {
    "AD", "AT", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE", "IL", "IT",
    "LV", "LT", "LU", "MT", "MD", "ME", "NL", "MK", "NO", "PL", "PT", "RO", "RS", "SK", "SI", "ES", "SE", "CH", "UA", "GB",
}


def simplify(points, tol):
    if len(points) < 4:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = points[a], points[b]
        dx, dy = bx - ax, by - ay
        norm = (dx * dx + dy * dy) ** 0.5
        best, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / norm if norm else ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            if d > best:
                best, at = d, i
        if best > tol:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(points, keep) if k]


def rings_of(geometry):
    polys = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    out = []
    for poly in polys:
        for ring in poly:
            r = simplify([(lon, lat) for lon, lat in ring], TOLERANCE)
            if len(r) >= 4:
                out.append(r)
    return out


def main(src, out_path):
    regions = []
    for f in json.load(open(f"{src}/ne_50m_admin_1_states_provinces.geojson"))["features"]:
        p = f["properties"]
        if p.get("iso_a2") == "US" and p.get("iso_3166_2", "").startswith("US-"):
            regions.append((p["iso_3166_2"], rings_of(f["geometry"])))
    for f in json.load(open(f"{src}/ne_50m_admin_0_countries.geojson"))["features"]:
        code = f["properties"].get("ISO_A2_EH")
        if code in US_TERRITORIES or code in METEOALARM:
            regions.append((code, rings_of(f["geometry"])))
    missing = (METEOALARM | US_TERRITORIES) - {c for c, _ in regions}
    if missing:
        sys.exit(f"missing regions: {sorted(missing)}")
    with open(out_path, "wb") as o:
        o.write(struct.pack("<i", len(regions)))
        for code, rings in sorted(regions):
            lats = [lat for r in rings for _, lat in r]
            lons = [lon for r in rings for lon, _ in r]
            c = code.encode()
            o.write(struct.pack("<B", len(c)) + c)
            o.write(struct.pack("<4f", min(lats), min(lons), max(lats), max(lons)))
            o.write(struct.pack("<i", len(rings)))
            for r in rings:
                o.write(struct.pack("<i", len(r)))
                for lon, lat in r:
                    o.write(struct.pack("<ii", round(lat * 1e5), round(lon * 1e5)))
    print(f"{len(regions)} regions, {sum(len(r) for _, rs in regions for r in rs)} points")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
