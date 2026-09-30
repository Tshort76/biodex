#!/usr/bin/env python3
"""Embed species names with the BioCLIP 2 text encoder, for the distilled student to match against.

The student (distill.py) outputs a BioCLIP 2 image embedding; the phone dots it with
this matrix and the best matches are the answer. Every taxon in the corpus (the
catalogue plus the lookalike pool) gets a row, and `--extra-names` appends more
without retraining anything. Writes:

    data/names.json             [{name, taxon_id, catalogue_id}], row order of the matrix
    data/name_embeddings.npy    float16 [M, 768], L2-normalised, "a photo of {name}."
    data/taxon_names.json       taxon_id -> name for every manifest photo (subspecies take their species' name)

Usage (inside the uv env):
    uv run python names.py [--extra-names FILE]
"""

from __future__ import annotations

import argparse
import json

import numpy as np
import open_clip
import torch

from clean import MODEL
from select_corpus import DATA


def cached_taxa() -> dict[int, tuple[str, str]]:
    """taxon_id -> (name, rank) for every taxon seen in the cached iNaturalist responses."""
    found = {}

    def walk(o):
        if isinstance(o, dict):
            t = o.get("taxon")
            if isinstance(t, dict) and "id" in t and "name" in t:
                found[t["id"]] = (t["name"], t.get("rank"))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    for f in (DATA / "cache" / "api").glob("*.json"):
        try:
            walk(json.loads(f.read_text()))
        except ValueError:
            pass
    return found


def collect(extra: list[str]) -> tuple[list[dict], dict[int, str]]:
    """The name list, and the name of every taxon_id the manifest carries (a subspecies takes its species' name)."""
    taxa = json.loads((DATA / "taxa.json").read_text())
    lookalikes = json.loads((DATA / "lookalikes.json").read_text())
    by_name: dict[str, dict] = {}
    for cid, t in taxa.items():
        by_name.setdefault(t["name"], {"name": t["name"], "taxon_id": t["taxon_id"], "catalogue_id": cid})
    for group in lookalikes.values():
        for t in group:
            by_name.setdefault(t["name"], {"name": t["name"], "taxon_id": t["taxon_id"], "catalogue_id": None})
    taxon_name = {e["taxon_id"]: n for n, e in by_name.items()}

    manifest = {json.loads(line)["taxon_id"] for line in open(DATA / "manifest.jsonl")}
    missing = manifest - taxon_name.keys()
    if missing:
        cache = cached_taxa()
        for tid in sorted(missing):
            name, _ = cache[tid]
            name = " ".join(name.split()[:2])
            taxon_name[tid] = name
            by_name.setdefault(name, {"name": name, "taxon_id": None, "catalogue_id": None})
    entries = [by_name[n] for n in sorted(by_name)]
    entries += [{"name": n, "taxon_id": None, "catalogue_id": None} for n in extra if n not in by_name]
    return entries, taxon_name


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--extra-names", type=argparse.FileType("r"), help="file with one extra name per line")
    args = ap.parse_args()

    extra = list(dict.fromkeys(l.strip() for l in args.extra_names if l.strip())) if args.extra_names else []
    entries, taxon_name = collect(extra)

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model, _, _ = open_clip.create_model_and_transforms(MODEL)
    tokenizer = open_clip.get_tokenizer(MODEL)
    model = model.to(device).eval()
    out = []
    for i in range(0, len(entries), 256):
        tokens = tokenizer([f"a photo of {e['name']}." for e in entries[i : i + 256]]).to(device)
        with torch.no_grad():
            out.append(torch.nn.functional.normalize(model.encode_text(tokens).float(), dim=-1).cpu())
    emb = torch.cat(out).numpy().astype(np.float16)

    (DATA / "names.json").write_text(json.dumps(entries, indent=1))
    np.save(DATA / "name_embeddings.npy", emb)
    (DATA / "taxon_names.json").write_text(json.dumps(taxon_name))
    print(f"{len(entries)} names, embeddings {emb.shape}")


if __name__ == "__main__":
    main()
