#!/usr/bin/env python3
"""Choose the training photos for the species classifier and write the manifest.

For every catalogue species, and for an `other` class of lookalike species the
dex does not hold, this asks the iNaturalist API for research-grade observations
whose photo is CC0 or CC-BY, keeps one photo per observation and a few per
observer, and writes `data/manifest.jsonl`: one row per photo with its label,
split, licence and attribution. `download.py` fetches the bytes afterwards.

Only CC0 and CC-BY photos are used, so the weights can sit in this MIT repo
(docs/CLASSIFIER-PLAN.md section 3). Observations annotated as tracks, scat,
feathers, bones and similar are dropped here; unannotated ones of that kind are
caught later by `clean.py`.

Usage:
    python3 select_corpus.py              # resolve, select, write the manifest
    python3 select_corpus.py --refresh    # ignore the API cache

Standard library only. Every API response is cached under `data/cache/api/`, so
a re-run makes zero requests.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
CATALOGUE = HERE.parent.parent / "app/src/main/assets/catalogue/pacific.json"
OVERRIDES = HERE / "taxon_overrides.json"
API = "https://api.inaturalist.org/v1"
USER_AGENT = "biodex-classifier/0.1 (personal project; github.com/Tshort76/biodex)"

OPEN_LICENCES = "cc0,cc-by"
# Pacific USA, roughly: the coast to the Rockies, Mexico to Canada.
PACIFIC_BBOX = {"swlat": 31.0, "swlng": -125.5, "nelat": 49.5, "nelng": -109.0}

# iNat's "Evidence of Presence" annotation (term 22). 24 is Organism; the rest are
# signs of the animal rather than the animal.
EVIDENCE_TERM = 22
NOT_THE_ANIMAL = {23, 25, 26, 27, 28, 29, 30, 31, 32, 35}

PER_SPECIES = 500          # observations kept per dex species, all splits
PER_OBSERVER = 10          # so one prolific photographer does not define a species
SPLITS = (("train", 0.8), ("val", 0.1), ("test", 0.1))
OTHER_LABEL = "other"
OTHER_PER_FAMILY = 4       # lookalike species drawn from each dex family
OTHER_PER_SPECIES = 25
OTHER_TEST_SHARE = 0.2     # of lookalike species, held out whole for the open-set test


# --------------------------------------------------------------------------
# Pure selection rules (tested in test_select_corpus.py)
# --------------------------------------------------------------------------

def shows_the_animal(obs: dict) -> bool:
    """False when an Evidence-of-Presence annotation says the photo is a sign, not the animal."""
    for a in obs.get("annotations") or []:
        if a.get("controlled_attribute_id") == EVIDENCE_TERM and a.get("controlled_value_id") in NOT_THE_ANIMAL:
            return False
    return True


def open_photo(obs: dict) -> dict | None:
    """The observation's first CC0/CC-BY photo, or None."""
    for p in obs.get("photos") or []:
        if p.get("license_code") in ("cc0", "cc-by"):
            return p
    return None


def pick(observations: list[dict], limit: int, per_observer: int) -> list[dict]:
    """One photo per observation, at most `per_observer` per user, up to `limit`, in input order."""
    seen_obs: set[int] = set()
    by_user: Counter = Counter()
    out = []
    for obs in observations:
        if len(out) >= limit:
            break
        user = (obs.get("user") or {}).get("login")
        if obs["id"] in seen_obs or by_user[user] >= per_observer or not shows_the_animal(obs):
            continue
        if open_photo(obs) is None:
            continue
        seen_obs.add(obs["id"])
        by_user[user] += 1
        out.append(obs)
    return out


def split_for(obs_id: int, splits=SPLITS) -> str:
    """A stable split from the observation id, so a re-run never moves a photo between splits."""
    h = int(hashlib.sha1(str(obs_id).encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    edge = 0.0
    for name, share in splits:
        edge += share
        if h < edge:
            return name
    return splits[-1][0]


def manifest_row(obs: dict, label: str, split: str) -> dict:
    photo = open_photo(obs)
    lat, lng = (obs.get("location") or ",").split(",") if obs.get("location") else (None, None)
    return {
        "photo_id": photo["id"],
        "obs_id": obs["id"],
        "label": label,
        "taxon_id": obs["taxon"]["id"],
        "split": split,
        "url": photo["url"].replace("/square.", "/medium."),
        "license": photo["license_code"],
        "observer": (obs.get("user") or {}).get("login"),
        "observed_on": obs.get("observed_on"),
        "lat": float(lat) if lat else None,
        "lng": float(lng) if lng else None,
    }


def choose_lookalikes(candidates: dict[int, list[dict]], dex_taxa: set[int], per_family: int) -> list[dict]:
    """From species_counts per family, the most-observed species the dex does not hold."""
    chosen: dict[int, dict] = {}
    for family_id in sorted(candidates):
        rows = [r for r in candidates[family_id] if r["taxon"]["id"] not in dex_taxa]
        for r in sorted(rows, key=lambda r: -r["count"])[:per_family]:
            chosen.setdefault(r["taxon"]["id"], {"taxon_id": r["taxon"]["id"], "name": r["taxon"]["name"], "family_id": family_id})
    return list(chosen.values())


def partition_other(species: list[dict], test_share: float, seed: int = 7) -> tuple[list[dict], list[dict]]:
    """Split lookalike species (not photos) into train and a disjoint open-set test group."""
    ordered = sorted(species, key=lambda s: s["taxon_id"])
    random.Random(seed).shuffle(ordered)
    cut = round(len(ordered) * test_share)
    return ordered[cut:], ordered[:cut]


# --------------------------------------------------------------------------
# API access, cached
# --------------------------------------------------------------------------

class Api:
    def __init__(self, cache: Path, refresh: bool):
        self.cache, self.refresh, self.requests = cache, refresh, 0
        cache.mkdir(parents=True, exist_ok=True)

    def get(self, path: str, params: dict) -> dict:
        url = f"{API}/{path}?{urllib.parse.urlencode(sorted(params.items()))}"
        key = self.cache / (hashlib.sha1(url.encode()).hexdigest() + ".json")
        if key.exists() and not self.refresh:
            return json.loads(key.read_text())
        for attempt in range(5):
            try:
                time.sleep(1.1)  # iNat asks for about one request a second
                req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                body = json.load(urllib.request.urlopen(req, timeout=60))
                break
            except Exception as e:  # noqa: BLE001 — retried, then raised
                if attempt == 4:
                    raise
                print(f"  retry {attempt + 1} after {e}", file=sys.stderr)
                time.sleep(10 * (attempt + 1))
        self.requests += 1
        key.write_text(json.dumps(body))
        return body

    def observations(self, params: dict, pages: int) -> list[dict]:
        out, id_below = [], None
        for _ in range(pages):
            p = {**params, "per_page": 200, "order_by": "id", "order": "desc"}
            if id_below:
                p["id_below"] = id_below
            results = self.get("observations", p)["results"]
            out += results
            if len(results) < 200:
                break
            id_below = results[-1]["id"]
        return out


def base_query(taxon_id: int) -> dict:
    return {"taxon_id": taxon_id, "quality_grade": "research", "photos": "true", "photo_license": OPEN_LICENCES}


# --------------------------------------------------------------------------
# The build
# --------------------------------------------------------------------------

KINGDOM_TAXON = {"animal": 1, "fungus": 47170}


def best_taxon(results: list[dict], name: str, kingdom: str) -> dict | None:
    """The iNat taxon a catalogue name means: an exact name or synonym match, in the right kingdom,
    at the rank the name implies (two words a species, one a genus), most-observed first."""
    want = name.lower()
    rank = "species" if len(name.split()) == 2 else "genus" if len(name.split()) == 1 else None
    hits = [t for t in results
            if want in (t["name"].lower(), (t.get("matched_term") or "").lower())
            and t.get("is_active", True)
            and KINGDOM_TAXON[kingdom] in t.get("ancestor_ids", [])
            and (rank is None or t["rank"] == rank)]
    return max(hits, key=lambda t: t.get("observations_count", 0), default=None)


def resolve_taxa(api: Api, species: list[dict], overrides: dict) -> dict[str, dict]:
    """Catalogue id -> iNat taxon (id, name, rank, ancestor ids). Unresolved names are reported."""
    out, missing = {}, []
    for s in species:
        name = overrides.get(s["id"], s["scientificName"])
        t = best_taxon(api.get("taxa", {"q": name, "per_page": 30})["results"], name, s["kingdom"])
        if t is None:
            missing.append(f"{s['id']} ({name})")
            continue
        out[s["id"]] = {"taxon_id": t["id"], "name": t["name"], "rank": t["rank"], "ancestor_ids": t.get("ancestor_ids", [])}
    if missing:
        print("Unresolved — add them to taxon_overrides.json:\n  " + "\n  ".join(missing), file=sys.stderr)
    return out


def family_ids(api: Api, taxa: dict[str, dict]) -> list[int]:
    """The family of every resolved taxon, read from iNat's ranked ancestor list."""
    ids = sorted({t["taxon_id"] for t in taxa.values()})
    out: set[int] = set()
    for i in range(0, len(ids), 30):
        for t in api.get("taxa/" + ",".join(map(str, ids[i:i + 30])), {})["results"]:
            out.update(a["id"] for a in t.get("ancestors", []) if a.get("rank") == "family")
    return sorted(out)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--refresh", action="store_true", help="ignore the API cache")
    args = ap.parse_args()

    api = Api(DATA / "cache" / "api", args.refresh)
    species = json.loads(CATALOGUE.read_text())["species"]
    overrides = json.loads(OVERRIDES.read_text()) if OVERRIDES.exists() else {}
    overrides = {k: v for k, v in overrides.items() if not k.startswith("_")}

    taxa = resolve_taxa(api, species, overrides)
    rows: list[dict] = []
    report = []
    for s in species:
        t = taxa.get(s["id"])
        if not t:
            continue
        local = api.observations({**base_query(t["taxon_id"]), **PACIFIC_BBOX}, pages=4)
        picked = pick(local, PER_SPECIES, PER_OBSERVER)
        if len(picked) < PER_SPECIES:
            seen = {o["id"] for o in picked}
            anywhere = [o for o in api.observations(base_query(t["taxon_id"]), pages=4) if o["id"] not in seen]
            picked = pick(picked + anywhere, PER_SPECIES, PER_OBSERVER)
        rows += [manifest_row(o, s["id"], split_for(o["id"])) for o in picked]
        report.append((s["id"], s["taxClass"], len(picked)))
        print(f"{s['id']:<32} {len(picked):>4}   ({api.requests} requests so far)", flush=True)

    # The `other` class: the most-observed non-dex species in each dex species' family, in the region.
    families = family_ids(api, taxa)
    dex_taxa = {t["taxon_id"] for t in taxa.values()}
    candidates = {}
    for fam in families:
        res = api.get("observations/species_counts", {**PACIFIC_BBOX, "taxon_id": fam, "quality_grade": "research",
                                                        "photo_license": OPEN_LICENCES, "per_page": 30})["results"]
        candidates[fam] = [r for r in res if r["taxon"]["rank"] == "species" and r["count"] >= OTHER_PER_SPECIES]
    lookalikes = choose_lookalikes(candidates, dex_taxa, OTHER_PER_FAMILY)
    other_train, other_test = partition_other(lookalikes, OTHER_TEST_SHARE)
    for group, split in ((other_train, None), (other_test, "open_test")):
        for sp in group:
            obs = api.observations({**base_query(sp["taxon_id"]), **PACIFIC_BBOX}, pages=1)
            picked = pick(obs, OTHER_PER_SPECIES, PER_OBSERVER)
            rows += [manifest_row(o, OTHER_LABEL, split or split_for(o["id"])) for o in picked]
        print(f"other ({'test' if split else 'train'}): {len(group)} species", flush=True)

    DATA.mkdir(exist_ok=True)
    with open(DATA / "manifest.jsonl", "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    (DATA / "taxa.json").write_text(json.dumps(taxa, indent=1))
    (DATA / "lookalikes.json").write_text(json.dumps({"train": other_train, "open_test": other_test}, indent=1))

    by_split = Counter(r["split"] for r in rows)
    print(f"\n{len(rows)} photos {dict(by_split)}; {api.requests} API requests")
    thin = sorted((n, i, c) for i, c, n in report if n < 200)
    print(f"{len(thin)} species under 200 photos:")
    for n, i, c in thin:
        print(f"  {i:<32} {c:<20} {n}")


if __name__ == "__main__":
    main()
