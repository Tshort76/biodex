#!/usr/bin/env python3
"""Fetch every photo in data/manifest.jsonl to data/images/<photo_id>.jpg.

Photos come from the iNaturalist Open Data bucket on S3 (the CC0/CC-BY originals
live there), not the iNat API, so this does not count against the API's media
limits. Already-downloaded files are skipped, so it resumes after an interruption.

Usage:
    python3 download.py [--workers 16]

Standard library only.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from select_corpus import DATA, USER_AGENT

IMAGES = DATA / "images"


def fetch(row: dict) -> str | None:
    dest = IMAGES / f"{row['photo_id']}.jpg"
    if dest.exists() and dest.stat().st_size > 0:
        return None
    try:
        req = urllib.request.Request(row["url"], headers={"User-Agent": USER_AGENT})
        body = urllib.request.urlopen(req, timeout=60).read()
    except Exception as e:  # noqa: BLE001 — a missing photo is reported, not fatal
        return f"{row['photo_id']}: {e}"
    tmp = dest.with_suffix(".part")
    tmp.write_bytes(body)
    tmp.rename(dest)
    return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=16)
    args = ap.parse_args()

    IMAGES.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    failures = []
    with ThreadPoolExecutor(args.workers) as pool:
        futures = [pool.submit(fetch, r) for r in rows]
        for n, f in enumerate(as_completed(futures), 1):
            if err := f.result():
                failures.append(err)
            if n % 2000 == 0:
                print(f"{n}/{len(rows)}  ({len(failures)} failed)", flush=True)
    (DATA / "download_failures.txt").write_text("\n".join(failures))
    print(f"done: {len(rows)} rows, {len(failures)} failed", file=sys.stderr)


if __name__ == "__main__":
    main()
