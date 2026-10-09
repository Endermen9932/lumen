#!/usr/bin/env python3
"""Builds app/src/main/assets/places.dat (gzip; not named .gz because the Android build unpacks such assets) – the offline place names for the "Ort" search filter.

Data: GeoNames (https://www.geonames.org, CC BY 4.0). Download next to this script, then run it:

    curl -O https://download.geonames.org/export/dump/cities5000.zip \\
         -O https://download.geonames.org/export/dump/admin1CodesASCII.txt \\
         -O https://download.geonames.org/export/dump/alternateNamesV2.zip
    python3 build_places.py <download dir> <repo>/app/src/main/assets/places.dat

Output (UTF-8, tab separated, gzip):
    R <tab> CC.ADM1 <tab> region name (German if known)
    C <tab> lat <tab> lon <tab> CC <tab> ADM1 <tab> population <tab> name (German if known) <tab> other names|…
"""
import gzip
import io
import sys
import zipfile
from pathlib import Path

# Populated places only – no city districts (PPLX), historical or abandoned places.
SKIP_CODES = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}


def main(src: Path, out: Path) -> None:
    cities = {}
    with zipfile.ZipFile(src / "cities5000.zip") as z, z.open("cities5000.txt") as f:
        for line in io.TextIOWrapper(f, "utf-8"):
            c = line.rstrip("\n").split("\t")
            if c[7] in SKIP_CODES:
                continue
            cities[c[0]] = {
                "name": c[1], "ascii": c[2], "lat": float(c[4]), "lon": float(c[5]),
                "cc": c[8], "adm1": c[10], "pop": int(c[14] or 0),
            }
    regions = {}
    region_by_gid = {}
    for line in (src / "admin1CodesASCII.txt").read_text("utf-8").splitlines():
        code, name, ascii_name, gid = line.split("\t")
        regions[code] = name
        region_by_gid[gid] = code

    # German names: preferred ones win, then short ones, never historic or colloquial names.
    german = {}
    wanted = set(cities) | set(region_by_gid)
    with zipfile.ZipFile(src / "alternateNamesV2.zip") as z, z.open("alternateNamesV2.txt") as f:
        for line in io.TextIOWrapper(f, "utf-8"):
            c = line.rstrip("\n").split("\t")
            if c[2] != "de" or c[1] not in wanted:
                continue
            if len(c) > 7 and (c[6] == "1" or c[7] == "1"):
                continue
            rank = (0 if c[4] == "1" else 1, 0 if c[5] == "1" else 1)
            old = german.get(c[1])
            if old is None or rank < old[0]:
                german[c[1]] = (rank, c[3])

    rows = []
    for gid, code in region_by_gid.items():
        name = german.get(gid, (None, regions[code]))[1]
        name = name.removeprefix("Land ")  # "Land Berlin" -> "Berlin"
        rows.append(f"R\t{code}\t{name}")
    for gid, c in sorted(cities.items(), key=lambda kv: -kv[1]["pop"]):
        de = german.get(gid, (None, None))[1]
        name = de or c["name"]
        others = [n for n in dict.fromkeys([c["name"], c["ascii"]]) if n and n != name]
        rows.append(f"C\t{c['lat']:.4f}\t{c['lon']:.4f}\t{c['cc']}\t{c['adm1']}\t{c['pop']}\t{name}\t{'|'.join(others)}")

    out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as g:
        g.write("\n".join(rows) + "\n")
    print(f"{len(cities)} places, {len(region_by_gid)} regions, {sum(1 for g in cities if g in german)} with German name -> {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
