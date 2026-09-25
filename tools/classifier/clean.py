#!/usr/bin/env python3
"""Drop photos that are not a picture of the animal itself.

iNat research-grade observations include tracks, scat, feathers, bones, eggs
and nests. Zero-shot prompts do not catch them: scored against its own species
name, a photo of goose tracks still reads as the goose (measured: 60 of 60
tagged sign photos kept). So this trains a small probe instead, on BioCLIP 2
image embeddings of photos iNat observers have tagged either "Organism" or as a
sign (feather, scat, track, bone, molt, egg, hair, construction), reports its
cross-validated accuracy, and applies it to the corpus. Writes:

    data/clean.jsonl             photo_id, keep, p_animal
    data/rejects.jpg             a contact sheet of a sample of rejects, for a spot check
    data/bioclip_zeroshot.json   BioCLIP's own 254-way accuracy on the test split (a reference number)

Usage (inside the uv env):
    uv run python clean.py [--batch 64] [--limit N]
"""

from __future__ import annotations

import argparse
import json
import os
import random
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import certifi

os.environ.setdefault("SSL_CERT_FILE", certifi.where())

import numpy as np
import open_clip
import torch
from PIL import Image
from torch.utils.data import DataLoader, Dataset

from download import fetch
from select_corpus import CATALOGUE, DATA, EVIDENCE_TERM, OPEN_LICENCES, OTHER_LABEL, Api

MODEL = "hf-hub:imageomics/bioclip-2"
ORGANISM = 24
SIGNS = "23,25,26,27,28,30,31,35"  # feather, scat, track, bone, molt, egg, hair, construction
REFERENCE_PAGES = 6                 # of 200 observations, per side
KEEP_THRESHOLD = 0.15  # spot-checked: below it is mostly signs; 0.15-0.5 is mostly distant or dim animals, worth keeping


class Photos(Dataset):
    def __init__(self, paths, preprocess):
        self.paths, self.preprocess = paths, preprocess

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        try:
            return self.preprocess(Image.open(self.paths[i]).convert("RGB")), i
        except Exception:  # noqa: BLE001 — an unreadable file is embedded as zeros and rejected
            return torch.zeros(3, 224, 224), -1 - i


def embed(model, preprocess, paths, device, batch) -> tuple[np.ndarray, np.ndarray]:
    """L2-normalised image embeddings, and which paths could be read."""
    out, ok = None, np.ones(len(paths), dtype=bool)
    for n, (x, idx) in enumerate(DataLoader(Photos(paths, preprocess), batch_size=batch, num_workers=8)):
        with torch.no_grad():
            e = torch.nn.functional.normalize(model.encode_image(x.to(device)).float(), dim=-1).cpu().numpy()
        if out is None:
            out = np.zeros((len(paths), e.shape[1]), dtype=np.float32)
        for j, i in enumerate(idx.tolist()):
            if i < 0:
                ok[-1 - i] = False
            else:
                out[i] = e[j]
        if n % 100 == 0:
            print(f"  embedded {n * batch}/{len(paths)}", flush=True)
    return out, ok


def reference_photos() -> tuple[list[Path], list[int]]:
    """Photos iNat observers tagged Organism (1) or as a sign of the animal (0), downloaded once."""
    api = Api(DATA / "cache" / "api", refresh=False)
    folder = DATA / "reference"
    folder.mkdir(parents=True, exist_ok=True)
    wanted = []
    for value, label in ((str(ORGANISM), 1), (SIGNS, 0)):
        obs = api.observations({"taxon_id": 1, "term_id": EVIDENCE_TERM, "term_value_id": value, "photos": "true",
                                "photo_license": OPEN_LICENCES, "quality_grade": "research"}, pages=REFERENCE_PAGES)
        for o in obs:
            p = next((p for p in o["photos"] if p["license_code"] in ("cc0", "cc-by")), None)
            if p is not None:
                wanted.append(({"photo_id": p["id"], "url": p["url"].replace("/square.", "/medium.")}, label))
    with ThreadPoolExecutor(16) as pool:
        list(pool.map(lambda w: fetch(w[0], folder), wanted))
    paths, labels = [], []
    for row, label in wanted:
        dest = folder / f"{row['photo_id']}.jpg"
        if dest.exists():
            paths.append(dest)
            labels.append(label)
    return paths, labels


def train_probe(x: np.ndarray, y: np.ndarray, epochs: int = 300) -> torch.nn.Linear:
    probe = torch.nn.Linear(x.shape[1], 1)
    opt = torch.optim.AdamW(probe.parameters(), lr=1e-2, weight_decay=1e-3)
    xt, yt = torch.from_numpy(x), torch.from_numpy(y).float()
    pos_weight = torch.tensor((len(y) - y.sum()) / max(y.sum(), 1))
    for _ in range(epochs):
        loss = torch.nn.functional.binary_cross_entropy_with_logits(probe(xt).squeeze(1), yt, pos_weight=pos_weight)
        opt.zero_grad(); loss.backward(); opt.step()
    return probe


def cross_validate(x: np.ndarray, y: np.ndarray, folds: int = 5) -> dict:
    order = np.random.default_rng(0).permutation(len(y))
    tp = fp = tn = fn = 0
    for k in range(folds):
        test = order[k::folds]
        train = np.setdiff1d(order, test)
        probe = train_probe(x[train], y[train])
        with torch.no_grad():
            p = torch.sigmoid(probe(torch.from_numpy(x[test])).squeeze(1)).numpy() >= KEEP_THRESHOLD
        t = y[test] == 1
        tp += int((p & t).sum()); fn += int((~p & t).sum()); tn += int((~p & ~t).sum()); fp += int((p & ~t).sum())
    return {"animal_photos_kept": round(tp / (tp + fn), 3), "sign_photos_rejected": round(tn / (tn + fp), 3),
            "n_animal": tp + fn, "n_sign": tn + fp}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL)
    tokenizer = open_clip.get_tokenizer(MODEL)
    model = model.to(device).eval()

    ref_paths, ref_labels = reference_photos()
    print(f"reference: {sum(ref_labels)} animal, {len(ref_labels) - sum(ref_labels)} sign photos", flush=True)
    ref_x, ref_ok = embed(model, preprocess, ref_paths, device, args.batch)
    ref_x, ref_y = ref_x[ref_ok], np.array(ref_labels)[ref_ok]
    cv = cross_validate(ref_x, ref_y)
    print(f"probe, 5-fold cross-validated: {cv}", flush=True)
    probe = train_probe(ref_x, ref_y)

    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    rows = [r for r in rows if (DATA / "images" / f"{r['photo_id']}.jpg").exists()]
    if args.limit:
        rows = random.Random(0).sample(rows, args.limit)
    x, ok = embed(model, preprocess, [DATA / "images" / f"{r['photo_id']}.jpg" for r in rows], device, args.batch)
    with torch.no_grad():
        p_animal = torch.sigmoid(probe(torch.from_numpy(x)).squeeze(1)).numpy()
    results = [{"photo_id": r["photo_id"], "keep": bool(ok[i] and p_animal[i] >= KEEP_THRESHOLD), "p_animal": round(float(p_animal[i]), 3)}
               for i, r in enumerate(rows)]
    with open(DATA / "clean.jsonl", "w") as f:
        for res in results:
            f.write(json.dumps(res) + "\n")
    np.save(DATA / "bioclip_embeddings.npy", x.astype(np.float16))
    (DATA / "bioclip_rows.json").write_text(json.dumps([r["photo_id"] for r in rows]))

    # BioCLIP's own zero-shot answer over the catalogue, on the test split: the reference to beat.
    taxa = json.loads((DATA / "taxa.json").read_text())
    dex_ids = [s["id"] for s in json.loads(CATALOGUE.read_text())["species"] if s["id"] in taxa]
    with torch.no_grad():
        text = torch.nn.functional.normalize(model.encode_text(tokenizer([f"a photo of {taxa[i]['name']}." for i in dex_ids]).to(device)).float(), dim=-1).cpu().numpy()
    test = [i for i, r in enumerate(rows) if r["split"] == "test" and r["label"] != OTHER_LABEL and ok[i]]
    top = np.argsort(-(x[test] @ text.T), axis=1)[:, :3]
    truth = [dex_ids.index(rows[i]["label"]) for i in test]
    zs = {"top1": round(float(np.mean(top[:, 0] == truth)), 4), "top3": round(float(np.mean([t in row for t, row in zip(truth, top)])), 4), "n": len(test)}
    (DATA / "bioclip_zeroshot.json").write_text(json.dumps({"zero_shot": zs, "probe_cv": cv}, indent=1))

    rejects = [r for r in results if not r["keep"]]
    print(f"kept {len(results) - len(rejects)} / {len(results)}; rejected {len(rejects)}")
    print(f"BioCLIP 2 zero-shot on the test split (254-way): {zs}")
    contact_sheet([r["photo_id"] for r in random.Random(1).sample(rejects, min(100, len(rejects)))], DATA / "rejects.jpg")


def contact_sheet(photo_ids: list[int], out: Path, cell: int = 128, cols: int = 10) -> None:
    if not photo_ids:
        return
    sheet = Image.new("RGB", (cols * cell, ((len(photo_ids) + cols - 1) // cols) * cell), "white")
    for k, pid in enumerate(photo_ids):
        im = Image.open(DATA / "images" / f"{pid}.jpg").convert("RGB")
        im.thumbnail((cell, cell))
        sheet.paste(im, ((k % cols) * cell, (k // cols) * cell))
    sheet.save(out, quality=85)


if __name__ == "__main__":
    main()
