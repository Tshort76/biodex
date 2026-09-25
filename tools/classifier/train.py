#!/usr/bin/env python3
"""Fine-tune the species classifier on the cleaned corpus.

A 255-way head (the catalogue species that have photos, plus `other`) on a
timm backbone pretrained on ImageNet. Class-balanced sampling, label smoothing,
random-resized-crop down to a quarter of the frame (the owner's photos are
snaps at a distance, not close-ups). fp32 on MPS.

Writes data/runs/<name>/: best.pt, labels.json, metrics.json.

Usage (inside the uv env):
    uv run python train.py [--model mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k] [--epochs 20]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
from collections import Counter, defaultdict

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

import timm
import torch
from PIL import Image
from timm.data import create_transform, resolve_data_config
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from select_corpus import CATALOGUE, DATA, OTHER_LABEL


class Photos(Dataset):
    def __init__(self, rows, label_index, transform):
        self.rows, self.label_index, self.transform = rows, label_index, transform

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        img = Image.open(DATA / "images" / f"{r['photo_id']}.jpg").convert("RGB")
        return self.transform(img), self.label_index[r["label"]]


def load_rows() -> list[dict]:
    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    rows = [r for r in rows if (DATA / "images" / f"{r['photo_id']}.jpg").exists()]
    clean = DATA / "clean.jsonl"
    if clean.exists():
        keep = {c["photo_id"] for c in map(json.loads, open(clean)) if c["keep"]}
        rows = [r for r in rows if r["photo_id"] in keep]
    return rows


def evaluate(model, loader, device, other_index):
    """Logits for a whole split, in loader order."""
    model.eval()
    out, targets = [], []
    with torch.no_grad():
        for x, y in loader:
            out.append(model(x.to(device)).float().cpu())
            targets.append(y)
    return torch.cat(out), torch.cat(targets)


def closed_set_metrics(logits, targets, labels, groups, other_index):
    known = targets != other_index
    top3 = logits.topk(3, dim=1).indices
    hit1 = (top3[:, 0] == targets) & known
    hit3 = (top3 == targets[:, None]).any(1) & known
    m = {"top1": hit1.sum().item() / known.sum().item(), "top3": hit3.sum().item() / known.sum().item(), "n": known.sum().item()}
    by_group = defaultdict(lambda: [0, 0, 0])
    for t, h1, h3 in zip(targets.tolist(), hit1.tolist(), hit3.tolist()):
        if t == other_index:
            continue
        g = by_group[groups[labels[t]]]
        g[0] += 1; g[1] += h1; g[2] += h3
    m["by_group"] = {g: {"n": n, "top1": round(a / n, 3), "top3": round(b / n, 3)} for g, (n, a, b) in sorted(by_group.items())}
    return m


def known_score(logits, other_index):
    """Confidence that the photo is a dex species: the best dex-species probability."""
    p = logits.softmax(1)
    p[:, other_index] = 0
    return p.max(1).values


def auroc(pos, neg):
    """Probability a random known photo outscores a random unseen-species photo."""
    scores = torch.cat([pos, neg])
    ranks = scores.argsort().argsort().float() + 1
    return ((ranks[: len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))).item()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k")
    ap.add_argument("--size", type=int, default=256, help="input side in pixels")
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=96)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--name", default=None)
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    run = DATA / "runs" / (args.name or args.model.split(".")[0])
    run.mkdir(parents=True, exist_ok=True)

    catalogue = json.loads(CATALOGUE.read_text())["species"]
    rows = load_rows()
    present = {r["label"] for r in rows}
    labels = [s["id"] for s in catalogue if s["id"] in present] + [OTHER_LABEL]
    label_index = {l: i for i, l in enumerate(labels)}
    other_index = label_index[OTHER_LABEL]
    groups = {s["id"]: s["taxClass"] for s in catalogue} | {OTHER_LABEL: OTHER_LABEL}
    split = defaultdict(list)
    for r in rows:
        split[r["split"]].append(r)
    print({k: len(v) for k, v in split.items()}, f"{len(labels)} labels", flush=True)

    model = timm.create_model(args.model, pretrained=True, num_classes=len(labels))
    cfg = resolve_data_config({"input_size": (3, args.size, args.size)}, model=model)
    train_tf = create_transform(**cfg, is_training=True, scale=(0.25, 1.0), auto_augment="rand-m6-mstd0.5-inc1")
    eval_tf = create_transform(**cfg, is_training=False)
    model = model.to(device)

    counts = Counter(r["label"] for r in split["train"])
    weights = [1.0 / counts[r["label"]] for r in split["train"]]
    sampler = WeightedRandomSampler(weights, num_samples=len(weights), replacement=True)
    loader_kw = dict(batch_size=args.batch, num_workers=args.workers, persistent_workers=True)
    train_loader = DataLoader(Photos(split["train"], label_index, train_tf), sampler=sampler, drop_last=True, **loader_kw)
    val_loader = DataLoader(Photos(split["val"], label_index, eval_tf), **loader_kw)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.05)
    steps = args.epochs * len(train_loader)
    warmup = len(train_loader)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warmup) * 0.5 * (1 + math.cos(math.pi * min(s, steps) / steps)))
    loss_fn = torch.nn.CrossEntropyLoss(label_smoothing=0.1)

    best, history = -1.0, []
    for epoch in range(args.epochs):
        model.train()
        t0, seen, total = time.time(), 0, 0.0
        for x, y in train_loader:
            x, y = x.to(device), y.to(device)
            loss = loss_fn(model(x), y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            seen += len(y); total += loss.item() * len(y)
        logits, targets = evaluate(model, val_loader, device, other_index)
        m = closed_set_metrics(logits, targets, labels, groups, other_index)
        history.append({"epoch": epoch + 1, "loss": round(total / seen, 4), "val_top1": round(m["top1"], 4), "val_top3": round(m["top3"], 4), "secs": round(time.time() - t0)})
        print(history[-1], flush=True)
        if m["top1"] > best:
            best = m["top1"]
            torch.save(model.state_dict(), run / "best.pt")

    # Final numbers from the best checkpoint: closed-set on test, open-set on species never trained on.
    model.load_state_dict(torch.load(run / "best.pt", map_location=device))
    test_logits, test_targets = evaluate(model, DataLoader(Photos(split["test"], label_index, eval_tf), **loader_kw), device, other_index)
    open_rows = [{**r, "label": OTHER_LABEL} for r in split["open_test"]]
    open_logits, _ = evaluate(model, DataLoader(Photos(open_rows, label_index, eval_tf), **loader_kw), device, other_index)
    val_logits, val_targets = evaluate(model, val_loader, device, other_index)

    known_val = known_score(val_logits[val_targets != other_index], other_index)
    threshold = known_val.quantile(0.05).item()  # accept 95% of known val photos
    known_test = known_score(test_logits[test_targets != other_index], other_index)
    unseen = known_score(open_logits, other_index)
    unseen_says_other = ((open_logits.argmax(1) == other_index) | (unseen < threshold)).float().mean().item()

    metrics = {
        "model": args.model, "labels": len(labels), "epochs": args.epochs, "history": history,
        "test": closed_set_metrics(test_logits, test_targets, labels, groups, other_index),
        "open_set": {"auroc": round(auroc(known_test, unseen), 4), "threshold": round(threshold, 4),
                     "unseen_species_flagged_not_in_dex": round(unseen_says_other, 4), "n_unseen": len(open_rows)},
        "input": {k: cfg[k] for k in ("input_size", "mean", "std", "interpolation", "crop_pct")},
    }
    (run / "metrics.json").write_text(json.dumps(metrics, indent=1))
    (run / "labels.json").write_text(json.dumps(labels, indent=1))
    print(json.dumps({k: metrics[k] for k in ("test", "open_set")}, indent=1))


if __name__ == "__main__":
    main()
