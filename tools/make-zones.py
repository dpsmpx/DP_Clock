#!/usr/bin/env python3
"""Таблица поясов для мировых часов (D-019) из zone.tab базы tz.

    tools/make-zones.py [/usr/share/zoneinfo/zone.tab]

Пишет core/model/src/main/resources/com/dpsmpx/dpclock/model/zones.tsv:
пояс, страна (ISO 3166), широта и долгота главного города в градусах.
База tz — общественное достояние; версия — в первой строке файла.
"""
import os
import re
import sys

SOURCE = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/zoneinfo/zone.tab"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "core/model/src/main/resources/com/dpsmpx/dpclock/model/zones.tsv")


def degrees(text, width):
    """±DDMM[SS] или ±DDDMM[SS] — в градусы."""
    sign = -1 if text[0] == "-" else 1
    digits = text[1:]
    whole = int(digits[:width])
    minutes = int(digits[width:width + 2])
    seconds = int(digits[width + 2:width + 4]) if len(digits) > width + 2 else 0
    return sign * (whole + minutes / 60 + seconds / 3600)


def version():
    zi = os.path.join(os.path.dirname(SOURCE), "tzdata.zi")
    if os.path.exists(zi):
        with open(zi, encoding="utf-8") as f:
            match = re.match(r"# version (\S+)", f.readline())
            if match:
                return match.group(1)
    return "unknown"


rows = []
with open(SOURCE, encoding="utf-8") as f:
    for line in f:
        if line.startswith("#") or not line.strip():
            continue
        country, coordinates, zone = line.rstrip("\n").split("\t")[:3]
        match = re.fullmatch(r"([+-]\d+)([+-]\d+)", coordinates)
        lat = degrees(match.group(1), 2)
        lon = degrees(match.group(2), 3)
        rows.append((zone, country, lat, lon))

rows.sort()
os.makedirs(os.path.dirname(TARGET), exist_ok=True)
with open(TARGET, "w", encoding="utf-8") as out:
    out.write(f"# tz {version()}: zone.tab, общественное достояние; tools/make-zones.py\n")
    for zone, country, lat, lon in rows:
        out.write(f"{zone}\t{country}\t{lat:.4f}\t{lon:.4f}\n")
print(f"{len(rows)} поясов → {os.path.relpath(TARGET, ROOT)}")
