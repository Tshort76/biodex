#!/usr/bin/env python3
"""Convert a trained run to a LiteRT model and prove the conversion kept its answers.

The exported graph takes a 1x3xHxW float tensor of raw 0-255 RGB values and does
the normalisation itself, so the phone never has to know timm's mean and std.
Writes, beside the run's checkpoint:

    species.tflite      the model (fp32); species.int8.tflite, int8 weights, ships when it passes
    model.json          labels, input size, crop_pct, threshold, catalogue version
    golden/             ~20 input tensors and the PyTorch logits for them, for the
                        on-phone parity check (docs/CLASSIFIER-PLAN.md section 5)

An embedding run (distill.py, `"kind": "embedding"` in metrics.json) exports the
same way with the output L2-normalised inside the graph; the golden test then
compares name top-1 and the cosine between outputs, and names.json and
name_embeddings.npy (copied into the run by distill.py) sit beside the model.

Usage (inside the uv env):
    uv run python export.py data/runs/mobilenetv4_conv_medium [--fp32]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import timm
import torch
from PIL import Image
from timm.data import create_transform

from select_corpus import CATALOGUE, DATA


class Normalised(torch.nn.Module):
    """The classifier with timm's input normalisation folded in: expects 0-255 RGB."""

    def __init__(self, model, mean, std, l2=False):
        super().__init__()
        self.model, self.l2 = model, l2
        self.register_buffer("mean", torch.tensor(mean).view(1, 3, 1, 1) * 255)
        self.register_buffer("std", torch.tensor(std).view(1, 3, 1, 1) * 255)

    def forward(self, x):
        y = self.model((x - self.mean) / self.std)
        return torch.nn.functional.normalize(y, dim=-1) if self.l2 else y


def golden_inputs(cfg, n: int, size: int) -> tuple[np.ndarray, list[int]]:
    """Real test photos, resized and cropped the way the phone will, as 0-255 floats."""
    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    rows = [r for r in rows if r["split"] == "test" and (DATA / "images" / f"{r['photo_id']}.jpg").exists()][:: 97][:n]
    tf = create_transform(**{**cfg, "mean": (0, 0, 0), "std": (1 / 255, 1 / 255, 1 / 255)}, is_training=False)
    x = torch.stack([tf(Image.open(DATA / "images" / f"{r['photo_id']}.jpg").convert("RGB")) for r in rows])
    return x.numpy().astype(np.float32), [r["photo_id"] for r in rows]


def softmax(logits: np.ndarray) -> np.ndarray:
    z = logits - logits.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def run_tflite(path: Path, x: np.ndarray) -> np.ndarray:
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    got = []
    for i in range(len(x)):
        interp.set_tensor(inp["index"], x[i : i + 1])
        interp.invoke()
        got.append(interp.get_tensor(outp["index"])[0])
    return np.stack(got)


def parity(path: Path, x: np.ndarray, expected: np.ndarray) -> tuple[int, float, float]:
    """Returns (top-1 agreement count, max |Δlogit|, max |Δprob|)."""
    got = run_tflite(path, x)
    max_abs_prob_delta = float(np.abs(softmax(got) - softmax(expected)).max())
    return int((got.argmax(1) == expected.argmax(1)).sum()), float(np.abs(got - expected).max()), max_abs_prob_delta


def embedding_parity(path: Path, x: np.ndarray, expected: np.ndarray, names: np.ndarray) -> tuple[int, float]:
    """Returns (name top-1 agreement count, max 1 - cosine between the two output vectors)."""
    got = run_tflite(path, x)
    cos = (got * expected).sum(1) / (np.linalg.norm(got, axis=1) * np.linalg.norm(expected, axis=1))
    return int(((got @ names.T).argmax(1) == (expected @ names.T).argmax(1)).sum()), float((1 - cos).max())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run", type=Path)
    ap.add_argument("--fp32", action="store_true", help="skip the int8 weight-only variant")
    ap.add_argument("--golden", type=int, default=20)
    args = ap.parse_args()

    import litert_torch  # heavy import, only needed here

    metrics = json.loads((args.run / "metrics.json").read_text())
    embedding = metrics.get("kind") == "embedding"
    labels = None if embedding else json.loads((args.run / "labels.json").read_text())
    cfg = metrics["input"]
    model = timm.create_model(metrics["model"], pretrained=False, num_classes=metrics["dim"] if embedding else len(labels))
    model.load_state_dict(torch.load(args.run / "best.pt", map_location="cpu"))
    wrapped = Normalised(model.eval(), cfg["mean"], cfg["std"], l2=embedding).eval()

    size = cfg["input_size"][1]
    sample = (torch.rand(1, 3, size, size) * 255,)
    edge = litert_torch.convert(wrapped, sample)
    out = args.run / "species.tflite"
    edge.export(str(out))

    candidates = [out]
    if not args.fp32:
        from ai_edge_quantizer import quantizer, recipe
        q = quantizer.Quantizer(str(out))
        q.load_quantization_recipe(recipe.weight_only_wi8_afp32())
        int8 = args.run / "species.int8.tflite"
        q.quantize().export_model(str(int8), overwrite=True)
        candidates.insert(0, int8)

    # Golden test: a converted model must agree with PyTorch on real photos. The smallest that passes ships.
    x, photo_ids = golden_inputs(cfg, args.golden, size)
    with torch.no_grad():
        expected = wrapped(torch.from_numpy(x)).numpy()
    chosen = None
    names = np.load(args.run / "name_embeddings.npy").astype(np.float32) if embedding else None
    for path in candidates:
        fp32 = path.name == "species.tflite"
        if embedding:
            agree, max_dcos = embedding_parity(path, x, expected, names)
            ok = agree == len(x) and max_dcos <= (1e-4 if fp32 else 1e-2)
            stats = (agree, max_dcos)
            print(f"{path.name}: {path.stat().st_size / 1e6:.1f} MB; name top-1 agreement {agree}/{len(x)}; max |Δcos| {max_dcos:.2e}; {'PASS' if ok else 'FAIL'}")
        else:
            agree, max_delta, max_prob_delta = parity(path, x, expected)
            ok = agree == len(x) and max_prob_delta <= (1e-3 if fp32 else 2e-2)
            stats = (agree, max_delta, max_prob_delta)
            print(f"{path.name}: {path.stat().st_size / 1e6:.1f} MB; top-1 agreement {agree}/{len(x)}; max |Δlogit| {max_delta:.4f}; max |Δprob| {max_prob_delta:.4f}; {'PASS' if ok else 'FAIL'}")
        if ok and chosen is None:
            chosen, out, chosen_stats = path, path, stats
    if chosen is None:
        sys.exit("golden test FAILED: no converted model matches PyTorch")

    golden = args.run / "golden"
    golden.mkdir(exist_ok=True)
    np.save(golden / "inputs.npy", x)
    np.save(golden / ("expected_embeddings.npy" if embedding else "expected_logits.npy"), expected)
    (golden / "photo_ids.json").write_text(json.dumps(photo_ids))

    if embedding:
        agree, max_dcos = chosen_stats
        (args.run / "model.json").write_text(json.dumps({
            "kind": "embedding", "model": metrics["model"], "file": out.name, "dim": metrics["dim"],
            "input": {"layout": "NCHW", "size": size, "range": "0-255 RGB", "crop_pct": cfg["crop_pct"], "interpolation": cfg["interpolation"]},
            "names": "names.json", "name_embeddings": "name_embeddings.npy", "text_prompt": "a photo of {name}.",
            "golden": {"top1_agreement": f"{agree}/{len(x)}", "max_dcos": max_dcos},
        }, indent=1))
        return

    agree, max_delta, max_prob_delta = chosen_stats
    catalogue_version = json.loads(CATALOGUE.read_text())["catalogueVersion"]
    (args.run / "model.json").write_text(json.dumps({
        "model": metrics["model"], "file": out.name, "catalogueVersion": catalogue_version,
        "input": {"layout": "NCHW", "size": size, "range": "0-255 RGB", "crop_pct": cfg["crop_pct"], "interpolation": cfg["interpolation"]},
        "threshold": metrics["open_set"]["threshold"], "labels": labels,
        "golden": {"top1_agreement": f"{agree}/{len(x)}", "max_abs_logit_delta": max_delta, "max_abs_prob_delta": max_prob_delta},
    }, indent=1))


if __name__ == "__main__":
    main()
