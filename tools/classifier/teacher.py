#!/usr/bin/env python3
"""Fit a linear probe on BioCLIP 2 embeddings to distil into the MobileNetV4 student.

A single D -> num_labels linear layer on top of the frozen, L2-normalised
BioCLIP embeddings clean.py already wrote, scaled by 30 so the softmax over
cosine-similarity-like logits isn't flat. Class-balanced (inverse frequency)
cross-entropy, trained on the train split only. Reports closed-set accuracy on
test and open-set separation on open_test, then writes teacher logits for
every photo so train.py can distil from them.

Writes data/teacher_logits.npy (float16 [N, num_labels], aligned to
bioclip_rows.json), resources/teacher_labels.json and resources/teacher_metrics.json.

Usage (inside the uv env):
    uv run python teacher.py [--epochs 40] [--batch 1024]
"""

from __future__ import annotations

import argparse
import json

import numpy as np
import torch
import torch.nn.functional as F

from select_corpus import CATALOGUE, DATA, RESOURCES, OTHER_LABEL
from train import auroc, load_rows

SCALE = 30.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=1e-3)
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"

    catalogue = json.loads(CATALOGUE.read_text())["species"]
    rows = load_rows()
    present = {r["label"] for r in rows}
    labels = [s["id"] for s in catalogue if s["id"] in present] + [OTHER_LABEL]
    label_index = {l: i for i, l in enumerate(labels)}
    other_index = label_index[OTHER_LABEL]

    photo_ids = json.loads((DATA / "bioclip_rows.json").read_text())
    row_of = {pid: i for i, pid in enumerate(photo_ids)}
    embeddings = np.load(DATA / "bioclip_embeddings.npy")  # float16 [N, D], L2-normalised

    def xy(split: str) -> tuple[torch.Tensor, torch.Tensor]:
        keep = [r for r in rows if r["split"] == split and r["photo_id"] in row_of]
        idx = [row_of[r["photo_id"]] for r in keep]
        x = torch.from_numpy(embeddings[idx].astype(np.float32))
        y = torch.tensor([label_index[r["label"]] for r in keep], dtype=torch.long)
        return x, y

    train_x, train_y = xy("train")
    val_x, val_y = xy("val")
    test_x, test_y = xy("test")
    open_x, _ = xy("open_test")

    counts = torch.bincount(train_y, minlength=len(labels)).float().clamp(min=1)
    weight = (1.0 / counts)
    weight = weight * (len(labels) / weight.sum())

    probe = torch.nn.Linear(embeddings.shape[1], len(labels)).to(device)
    opt = torch.optim.AdamW(probe.parameters(), lr=args.lr, weight_decay=1e-4)
    loss_fn = torch.nn.CrossEntropyLoss(weight=weight.to(device))

    train_x, train_y = train_x.to(device), train_y.to(device)
    n = train_x.shape[0]
    for epoch in range(args.epochs):
        probe.train()
        perm = torch.randperm(n, device=device)
        for start in range(0, n, args.batch):
            idx = perm[start : start + args.batch]
            logits = probe(train_x[idx] * SCALE)
            loss = loss_fn(logits, train_y[idx])
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()

    probe.eval()
    with torch.no_grad():

        def predict(x: torch.Tensor) -> torch.Tensor:
            out = []
            for start in range(0, x.shape[0], args.batch):
                out.append(probe(x[start : start + args.batch].to(device) * SCALE).float().cpu())
            return torch.cat(out)

        val_logits = predict(val_x)
        test_logits = predict(test_x)
        open_logits = predict(open_x)

    known_test = test_y != other_index
    top3 = test_logits.topk(3, dim=1).indices
    hit1 = (top3[:, 0] == test_y) & known_test
    hit3 = (top3 == test_y[:, None]).any(1) & known_test
    top1 = hit1.sum().item() / known_test.sum().item()
    top3_acc = hit3.sum().item() / known_test.sum().item()

    def known_score(logits: torch.Tensor) -> torch.Tensor:
        p = logits.softmax(1)
        p[:, other_index] = 0
        return p.max(1).values

    known_val_mask = val_y != other_index
    threshold = known_score(val_logits[known_val_mask]).quantile(0.05).item()
    known_test_score = known_score(test_logits[known_test])
    unseen_score = known_score(open_logits)
    unseen_flagged = ((open_logits.argmax(1) == other_index) | (unseen_score < threshold)).float().mean().item()

    metrics = {
        "labels": len(labels),
        "n_train": n,
        "test": {"top1": round(top1, 4), "top3": round(top3_acc, 4), "n": known_test.sum().item()},
        "open_set": {
            "auroc": round(auroc(known_test_score, unseen_score), 4),
            "threshold": round(threshold, 4),
            "unseen_species_flagged_not_in_dex": round(unseen_flagged, 4),
            "n_unseen": open_x.shape[0],
        },
    }
    (RESOURCES / "teacher_metrics.json").write_text(json.dumps(metrics, indent=1))
    (RESOURCES / "teacher_labels.json").write_text(json.dumps(labels, indent=1))

    # Teacher logits for every row in bioclip_rows.json order, so train.py can join on photo_id.
    with torch.no_grad():
        all_x = torch.from_numpy(embeddings.astype(np.float32))
        all_logits = predict(all_x)
    np.save(DATA / "teacher_logits.npy", all_logits.numpy().astype(np.float16))

    print(json.dumps(metrics, indent=1))


if __name__ == "__main__":
    main()
