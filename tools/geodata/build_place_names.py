#!/usr/bin/env python3
"""Builds the offline area names for Context › Environment from Natural Earth (public domain).

The phone names the area it is in (nearest town, its state or province, the country) without
sending coordinates to a geocoding service.

Inputs (https://github.com/nvkelso/natural-earth-vector/tree/master/geojson):
  ne_50m_admin_0_countries.geojson, ne_10m_populated_places_simple.geojson
Outputs (context/core/src/main/assets/regions/), little-endian:
  countries.bin  same layout as warning_regions.bin (see build_region_assets.py); code = ADM0_A3
  places.bin     int32 country count, then per country: str code, str name
                 int32 place count, then per place (sorted by latitude):
                   int32 lat * 1e5, int32 lon * 1e5, int32 population, str country code, str name, str admin-1 name
                 str = uint8 byte length + UTF-8

Usage: python3 build_place_names.py <dir with the geojson files> <output dir>
"""
import json
import struct
import sys

from build_region_assets import rings_of


def s(text):
    b = (text or "").encode()[:255]
    return struct.pack("<B", len(b)) + b


def main(src, out):
    countries = []
    names = {}
    for f in json.load(open(f"{src}/ne_50m_admin_0_countries.geojson"))["features"]:
        p = f["properties"]
        code = p["ADM0_A3"]
        names[code] = p.get("NAME_EN") or p["NAME"]
        rings = rings_of(f["geometry"])
        if not rings:
            # A tiny country can simplify away; keep its unsimplified outline instead.
            g = f["geometry"]
            polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
            rings = [[(lon, lat) for lon, lat in r] for poly in polys for r in poly]
        countries.append((code, rings))
    with open(f"{out}/countries.bin", "wb") as o:
        o.write(struct.pack("<i", len(countries)))
        for code, rings in sorted(countries):
            lats = [lat for r in rings for _, lat in r]
            lons = [lon for r in rings for lon, _ in r]
            o.write(s(code))
            o.write(struct.pack("<4f", min(lats), min(lons), max(lats), max(lons)))
            o.write(struct.pack("<i", len(rings)))
            for r in rings:
                o.write(struct.pack("<i", len(r)))
                for lon, lat in r:
                    o.write(struct.pack("<ii", round(lat * 1e5), round(lon * 1e5)))
    places = []
    for f in json.load(open(f"{src}/ne_10m_populated_places_simple.geojson"))["features"]:
        p = f["properties"]
        places.append((p["latitude"], p["longitude"], int(p.get("pop_max") or 0), p["adm0_a3"], p["name"], p.get("adm1name") or ""))
        names.setdefault(p["adm0_a3"], p.get("adm0name") or p["adm0_a3"])
    places.sort()
    with open(f"{out}/places.bin", "wb") as o:
        o.write(struct.pack("<i", len(names)))
        for code, name in sorted(names.items()):
            o.write(s(code) + s(name))
        o.write(struct.pack("<i", len(places)))
        for lat, lon, pop, code, name, adm1 in places:
            o.write(struct.pack("<iii", round(lat * 1e5), round(lon * 1e5), pop) + s(code) + s(name) + s(adm1))
    print(f"{len(countries)} countries, {len(places)} places")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
