#!/usr/bin/env python3
"""Distil BioCLIP 2's image embedding into a phone-sized student.

The student maps a photo to a 768-d vector, L2-normalised. On the phone it is
dotted against name_embeddings.npy (names.py) and the best matches are the
answer, so the name list can grow without retraining. Trained on every train
photo (catalogue and `other` species) with three terms: cosine to the teacher's
embedding of the photo, KL between the student's and teacher's softmax over the
names, and cross-entropy on the photo's own name. Reports name top-1/3/5 for
the student and the teacher on test catalogue, test `other` and open_test photos
(species never trained on).

Writes data/runs/<name>/: best.pt, last.pt, metrics.json, names.json, name_embeddings.npy.

Usage (inside the uv env):
    uv run python distill.py [--model convnext_nano.in12k_ft_in1k] [--epochs 12] [--limit N]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import time
from collections import defaultdict

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

import numpy as np
import timm
import torch
import torch.nn.functional as F
from PIL import Image
from timm.data import create_transform, resolve_data_config
from torch.utils.data import DataLoader, Dataset

from select_corpus import DATA, OTHER_LABEL
from train import load_rows


class Photos(Dataset):
    """Yields (image, the teacher's embedding of the photo, the photo's name index)."""

    def __init__(self, rows, transform, teacher, teacher_row, name_of):
        self.rows, self.transform = rows, transform
        self.teacher, self.teacher_row, self.name_of = teacher, teacher_row, name_of

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        img = Image.open(DATA / "images" / f"{r['photo_id']}.jpg").convert("RGB")
        t = torch.from_numpy(self.teacher[self.teacher_row[r["photo_id"]]].astype("float32"))
        return self.transform(img), t, self.name_of[r["taxon_id"]]


def embed(model, loader, device):
    """L2-normalised student embeddings for a whole split, with the teacher's and the name targets, in loader order."""
    model.eval()
    s, t, y = [], [], []
    with torch.no_grad():
        for x, teach, idx in loader:
            s.append(F.normalize(model(x.to(device)).float(), dim=-1).cpu())
            t.append(teach)
            y.append(idx)
    return torch.cat(s), torch.cat(t), torch.cat(y)


def name_topk(emb, names, targets, ks=(1, 3, 5)):
    top = (emb @ names.T).topk(max(ks), dim=1).indices
    return {f"top{k}": round((top[:, :k] == targets[:, None]).any(1).float().mean().item(), 4) for k in ks}


def loss_terms(s, t, y, names, args):
    cos = (1 - (s * t).sum(1)).mean()
    T = args.kd_temp
    kd = F.kl_div(F.log_softmax(args.scale * s @ names.T / T, 1), F.softmax(args.scale * t @ names.T / T, 1), reduction="batchmean") * T * T
    ce = F.cross_entropy(args.scale * s @ names.T, y, label_smoothing=0.1)
    return cos, kd, ce


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="convnext_nano.in12k_ft_in1k")
    ap.add_argument("--size", type=int, default=256, help="input side in pixels")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--name", default="cnx-embed")
    ap.add_argument("--resume", action="store_true", help="resume from run/last.pt if present")
    ap.add_argument("--limit", type=int, default=0, help="use only the first N rows of each split (smoke test)")
    ap.add_argument("--scale", type=float, default=100.0, help="logit scale on the name similarities (BioCLIP's is about 100)")
    ap.add_argument("--kd-temp", type=float, default=2.0, help="softmax temperature for the name KD term")
    ap.add_argument("--w-cos", type=float, default=1.0)
    ap.add_argument("--w-kd", type=float, default=1.0)
    ap.add_argument("--w-ce", type=float, default=0.5)
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    run = DATA / "runs" / args.name
    run.mkdir(parents=True, exist_ok=True)

    entries = json.loads((DATA / "names.json").read_text())
    names = torch.from_numpy(np.load(DATA / "name_embeddings.npy").astype(np.float32)).to(device)
    index_of = {e["name"]: i for i, e in enumerate(entries)}
    name_of = {int(tid): index_of[n] for tid, n in json.loads((DATA / "taxon_names.json").read_text()).items()}
    teacher = np.load(DATA / "bioclip_embeddings.npy")
    teacher_row = {pid: i for i, pid in enumerate(json.loads((DATA / "bioclip_rows.json").read_text()))}

    split = defaultdict(list)
    for r in load_rows():
        if len(split[r["split"]]) < (args.limit or math.inf):
            split[r["split"]].append(r)
    print({k: len(v) for k, v in split.items()}, f"{len(entries)} names", flush=True)

    model = timm.create_model(args.model, pretrained=True, num_classes=768)
    cfg = resolve_data_config({"input_size": (3, args.size, args.size)}, model=model)
    train_tf = create_transform(**cfg, is_training=True, scale=(0.35, 1.0), auto_augment=None, re_prob=0.0)
    eval_tf = create_transform(**cfg, is_training=False)
    model = model.to(device)

    def dataset(rows, tf):
        return Photos(rows, tf, teacher, teacher_row, name_of)

    loader_kw = dict(batch_size=args.batch, num_workers=args.workers, persistent_workers=args.workers > 0)
    train_loader = DataLoader(dataset(split["train"], train_tf), shuffle=True, drop_last=True, **loader_kw)
    val_loader = DataLoader(dataset(split["val"], eval_tf), **loader_kw)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.05)
    steps = args.epochs * len(train_loader)
    warmup = len(train_loader)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warmup) * 0.5 * (1 + math.cos(math.pi * min(s, steps) / steps)))

    best, history, start_epoch = -1.0, [], 0
    if args.resume:
        last = run / "last.pt"
        if last.exists():
            ck = torch.load(last, map_location=device)
            model.load_state_dict(ck["model"])
            opt.load_state_dict(ck["optimizer"])
            sched.load_state_dict(ck["scheduler"])
            best, history, start_epoch = ck["best"], ck["history"], ck["epoch"]
            print(f"resuming from epoch {start_epoch}", flush=True)
        else:
            print("--resume set but no last.pt found, starting fresh", flush=True)

    for epoch in range(start_epoch, args.epochs):
        model.train()
        t0, seen, total = time.time(), 0, 0.0
        for x, t, y in train_loader:
            x, t, y = x.to(device), t.to(device), y.to(device)
            s = F.normalize(model(x).float(), dim=-1)
            cos, kd, ce = loss_terms(s, t, y, names, args)
            loss = args.w_cos * cos + args.w_kd * kd + args.w_ce * ce
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            seen += len(y); total += loss.item() * len(y)
        s, t, y = embed(model, val_loader, device)
        top = name_topk(s, names.cpu(), y, ks=(1, 3))
        history.append({"epoch": epoch + 1, "loss": round(total / seen, 4), "val_cos": round((s * t).sum(1).mean().item(), 4),
                        "val_top1": top["top1"], "val_top3": top["top3"], "secs": round(time.time() - t0)})
        print(history[-1], flush=True)
        if top["top1"] > best:
            best = top["top1"]
            torch.save(model.state_dict(), run / "best.pt")
        ck = {"model": model.state_dict(), "optimizer": opt.state_dict(), "scheduler": sched.state_dict(),
              "epoch": epoch + 1, "best": best, "history": history}
        tmp = run / "last.pt.tmp"
        torch.save(ck, tmp)
        os.replace(tmp, run / "last.pt")

    # Final numbers from the best checkpoint, beside the teacher's own on the same photos.
    model.load_state_dict(torch.load(run / "best.pt", map_location=device))
    cpu_names = names.cpu()
    groups = {
        "test_catalogue": [r for r in split["test"] if r["label"] != OTHER_LABEL],
        "test_other": [r for r in split["test"] if r["label"] == OTHER_LABEL],
        "open_test": split["open_test"],
    }
    results = {}
    for group, rows in groups.items():
        if not rows:
            results[group] = {"n": 0}
            continue
        s, t, y = embed(model, DataLoader(dataset(rows, eval_tf), **loader_kw), device)
        results[group] = {"n": len(rows), "student": name_topk(s, cpu_names, y), "teacher": name_topk(t, cpu_names, y),
                          "cos_to_teacher": round((s * t).sum(1).mean().item(), 4)}

    metrics = {
        "kind": "embedding", "model": args.model, "dim": 768, "names": len(entries), "epochs": args.epochs, "lr": args.lr,
        "batch": args.batch, "args": vars(args), "history": history, **results,
        "input": {k: cfg[k] for k in ("input_size", "mean", "std", "interpolation", "crop_pct")},
    }
    (run / "metrics.json").write_text(json.dumps(metrics, indent=1))
    for f in ("names.json", "name_embeddings.npy"):
        shutil.copy(DATA / f, run / f)
    print(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
