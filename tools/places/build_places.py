#!/usr/bin/env python3
"""Build the bundled offline place list the "Where was this?" prompt suggests from.

Reads `places.json` (the states and the feature codes worth keeping) and the
GeoNames dump for the United States, and writes a tab-separated asset — one
place per line, `name<TAB>state<TAB>tier` — to
`app/src/main/assets/places/pacific.tsv`. DESIGN.md D68 has the why; the app
loads the file once and ranks suggestions by tier, so the file needs no order
of its own beyond being stable, and it is sorted for a readable diff.

GeoNames is CC BY 4.0, so the attribution in README.md is a condition of
shipping this file, not a courtesy.

Usage:
    python3 build_places.py                      # default --out path
    python3 build_places.py --out /some/where.tsv
    python3 build_places.py --refresh            # re-download the dump

Standard library only. The 71 MB dump is cached under `cache/`, so a re-run
makes zero requests.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE_DIR = HERE / "cache"
ASSET_DIR = HERE / ".." / ".." / "app" / "src" / "main" / "assets" / "places"
DEFAULT_OUT = ASSET_DIR / "pacific.tsv"

DUMP_URL = "https://download.geonames.org/export/dump/US.zip"
USER_AGENT = "BioDex/1.0 (personal Android app; https://github.com/Tshort76/biodex)"

# GeoNames' columns, as the dump's readme.txt names them. Only these four matter.
COL_NAME = 1
COL_FEATURE_CLASS = 6
COL_FEATURE_CODE = 7
COL_ADMIN1 = 10


def load_config(path: Path) -> tuple[set[str], dict[str, int]]:
    """The states to keep, and the tier each `class.code` pair earns."""
    config = json.loads(path.read_text(encoding="utf-8"))
    states = set(config["states"])
    tiers = {}
    for tier, codes in config["tiers"].items():
        for code in codes:
            tiers[code] = int(tier)
    return states, tiers


def fetch_dump(refresh: bool = False) -> bytes:
    """The GeoNames US dump, from the cache unless [refresh] says otherwise."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached = CACHE_DIR / "US.zip"
    if cached.exists() and not refresh:
        return cached.read_bytes()
    request = urllib.request.Request(DUMP_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response:
        payload = response.read()
    cached.write_bytes(payload)
    return payload


def dump_lines(payload: bytes):
    """The dump's one text member, line by line, without holding it all decoded."""
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        with archive.open("US.txt") as member:
            for raw in io.TextIOWrapper(member, encoding="utf-8"):
                yield raw


def usable_name(name: str) -> bool:
    """
    Reject what would be noise in a suggestion list rather than a place: an empty
    name, a bare number ("Site 4"), and anything that is not plain enough to type
    on a phone keyboard. Names with accents are kept — the app folds them on
    search, and "Cañada" should read as itself in the list.
    """
    stripped = name.strip()
    if len(stripped) < 3:
        return False
    return any(character.isalpha() for character in stripped)


def select_places(lines, states: set[str], tiers: dict[str, int]) -> list[tuple[str, str, int]]:
    """
    Every row of the dump that is in one of [states] and carries a feature code
    in [tiers], as `(name, state, tier)`, deduplicated on name and state.

    Deduplication is the whole reason this is worth doing at build time: the dump
    holds 232,000 rows for these three states and roughly a fifth of them are a
    name the app already has — five parks called "City Park" in California are
    one suggestion, because what the app stores is the label "City Park, CA".
    Where two rows share a name and a state, the better tier wins.
    """
    best: dict[tuple[str, str], int] = {}
    for raw in lines:
        fields = raw.rstrip("\n").split("\t")
        if len(fields) <= COL_ADMIN1:
            continue
        state = fields[COL_ADMIN1]
        if state not in states:
            continue
        tier = tiers.get(f"{fields[COL_FEATURE_CLASS]}.{fields[COL_FEATURE_CODE]}")
        if tier is None:
            continue
        name = unicodedata.normalize("NFC", fields[COL_NAME].strip())
        if not usable_name(name):
            continue
        key = (name, state)
        if tier < best.get(key, 99):
            best[key] = tier
    return sorted((name, state, tier) for (name, state), tier in best.items())


def render(places: list[tuple[str, str, int]]) -> str:
    return "".join(f"{name}\t{state}\t{tier}\n" for name, state, tier in places)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--config", type=Path, default=HERE / "places.json")
    parser.add_argument("--refresh", action="store_true", help="re-download the dump")
    args = parser.parse_args(argv)

    states, tiers = load_config(args.config)
    print(f"states: {', '.join(sorted(states))}; feature codes: {len(tiers)}")
    payload = fetch_dump(refresh=args.refresh)
    places = select_places(dump_lines(payload), states, tiers)
    if not places:
        print("refusing to write an empty place list", file=sys.stderr)
        return 1

    text = render(places)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    counts = {tier: sum(1 for _, _, t in places if t == tier) for tier in (0, 1, 2)}
    print(
        f"wrote {args.out} — {len(places)} places "
        f"({counts[0]} towns, {counts[1]} parks and trails, {counts[2]} natural features), "
        f"{len(text.encode('utf-8')) / 1024:.0f} KB"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
