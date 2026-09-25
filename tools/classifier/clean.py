#!/usr/bin/env python3
"""Drop photos that are not a picture of the animal itself, using BioCLIP 2 zero-shot.

iNat research-grade observations include tracks, scat, feathers, bones, burrows,
empty habitat and fish laid out on a deck. For each downloaded photo this scores
its own species' name against a handful of "not the animal" prompts and rejects
the photo when a reject prompt wins. Writes:

    data/clean.jsonl        photo_id, keep, p_species, top reject prompt
    data/rejects.jpg        a contact sheet of a sample of rejects, for a spot check
    data/bioclip_zeroshot.json   BioCLIP's own 254-way accuracy on the test split (a reference number)

Usage (inside the uv env):
    uv run python clean.py [--batch 64] [--limit N]
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import open_clip
import torch
from PIL import Image
from torch.utils.data import DataLoader, Dataset

from select_corpus import CATALOGUE, DATA, OTHER_LABEL

MODEL = "hf-hub:imageomics/bioclip-2"
REJECT_PROMPTS = [
    "a photo of animal tracks in mud or sand",
    "a photo of animal scat or droppings",
    "a photo of a feather on the ground",
    "a photo of bones or a skull",
    "a photo of an empty landscape",
    "a photo of a burrow or a nest with no animal",
    "a photo of a dead fish held in a person's hands",
    "a photo of a fish lying on a boat deck or a cutting board",
    "a photo of a museum specimen with a label",
    "a blurry photo with no animal visible",
]
KEEP_THRESHOLD = 0.5  # the species prompt must take at least this share against the rejects


class Photos(Dataset):
    def __init__(self, rows, preprocess):
        self.rows, self.preprocess = rows, preprocess

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        path = DATA / "images" / f"{self.rows[i]['photo_id']}.jpg"
        try:
            return self.preprocess(Image.open(path).convert("RGB")), i
        except Exception:  # noqa: BLE001 — unreadable file: scored as a reject
            return torch.zeros(3, 224, 224), -1 - i


def encode_text(model, tokenizer, texts, device):
    with torch.no_grad():
        t = model.encode_text(tokenizer(texts).to(device))
    return torch.nn.functional.normalize(t.float(), dim=-1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL)
    tokenizer = open_clip.get_tokenizer(MODEL)
    model = model.to(device).eval()

    taxa = json.loads((DATA / "taxa.json").read_text())
    lookalikes = json.loads((DATA / "lookalikes.json").read_text())
    other_names = {s["taxon_id"]: s["name"] for g in lookalikes.values() for s in g}
    dex_ids = [s["id"] for s in json.loads(CATALOGUE.read_text())["species"] if s["id"] in taxa]

    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    rows = [r for r in rows if (DATA / "images" / f"{r['photo_id']}.jpg").exists()]
    if args.limit:
        rows = random.Random(0).sample(rows, args.limit)

    def name_of(r):
        return other_names.get(r["taxon_id"]) if r["label"] == OTHER_LABEL else taxa[r["label"]]["name"]

    names = sorted({name_of(r) for r in rows})
    name_index = {n: i for i, n in enumerate(names)}
    species_text = encode_text(model, tokenizer, [f"a photo of {n}." for n in names], device)
    reject_text = encode_text(model, tokenizer, REJECT_PROMPTS, device)
    dex_text = encode_text(model, tokenizer, [f"a photo of {taxa[i]['name']}." for i in dex_ids], device)
    scale = model.logit_scale.exp().item()

    loader = DataLoader(Photos(rows, preprocess), batch_size=args.batch, num_workers=8)
    results = [None] * len(rows)
    embeddings = np.zeros((len(rows), dex_text.shape[1]), dtype=np.float16)
    zs_hits = {"top1": 0, "top3": 0, "n": 0}
    for n, (x, idx) in enumerate(loader):
        with torch.no_grad():
            img = torch.nn.functional.normalize(model.encode_image(x.to(device)).float(), dim=-1)
        own = species_text[[name_index[name_of(rows[max(i, -1 - i)])] for i in idx.tolist()]]
        own_logit = (img * own).sum(-1, keepdim=True) * scale
        rej_logit = img @ reject_text.T * scale
        probs = torch.cat([own_logit, rej_logit], dim=1).softmax(-1).cpu()
        dex_rank = (img @ dex_text.T).topk(3, dim=-1).indices.cpu()
        for j, i in enumerate(idx.tolist()):
            unreadable = i < 0
            i = max(i, -1 - i)
            r = rows[i]
            p = probs[j, 0].item()
            top_reject = REJECT_PROMPTS[int(probs[j, 1:].argmax())]
            results[i] = {"photo_id": r["photo_id"], "keep": (not unreadable) and p >= KEEP_THRESHOLD,
                          "p_species": round(p, 3), "reject": None if p >= KEEP_THRESHOLD else top_reject}
            embeddings[i] = img[j].cpu().numpy()
            if r["split"] == "test" and r["label"] != OTHER_LABEL:
                zs_hits["n"] += 1
                top = [dex_ids[k] for k in dex_rank[j].tolist()]
                zs_hits["top1"] += top[0] == r["label"]
                zs_hits["top3"] += r["label"] in top
        if n % 50 == 0:
            print(f"{n * args.batch}/{len(rows)}", flush=True)

    with open(DATA / "clean.jsonl", "w") as f:
        for res in results:
            f.write(json.dumps(res) + "\n")
    np.save(DATA / "bioclip_embeddings.npy", embeddings)
    (DATA / "bioclip_rows.json").write_text(json.dumps([r["photo_id"] for r in rows]))
    zs = {k: (v / zs_hits["n"] if k != "n" else v) for k, v in zs_hits.items()} if zs_hits["n"] else {}
    (DATA / "bioclip_zeroshot.json").write_text(json.dumps(zs, indent=1))

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
