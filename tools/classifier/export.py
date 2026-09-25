#!/usr/bin/env python3
"""Convert a trained run to a LiteRT model and prove the conversion kept its answers.

The exported graph takes a 1x3xHxW float tensor of raw 0-255 RGB values and does
the normalisation itself, so the phone never has to know timm's mean and std.
Writes, beside the run's checkpoint:

    species.tflite      the model (fp32); species.int8.tflite, int8 weights, ships when it passes
    model.json          labels, input size, crop_pct, threshold, catalogue version
    golden/             ~20 input tensors and the PyTorch logits for them, for the
                        on-phone parity check (docs/CLASSIFIER-PLAN.md section 5)

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

    def __init__(self, model, mean, std):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor(mean).view(1, 3, 1, 1) * 255)
        self.register_buffer("std", torch.tensor(std).view(1, 3, 1, 1) * 255)

    def forward(self, x):
        return self.model((x - self.mean) / self.std)


def golden_inputs(cfg, n: int, size: int) -> tuple[np.ndarray, list[int]]:
    """Real test photos, resized and cropped the way the phone will, as 0-255 floats."""
    rows = [json.loads(line) for line in open(DATA / "manifest.jsonl")]
    rows = [r for r in rows if r["split"] == "test" and (DATA / "images" / f"{r['photo_id']}.jpg").exists()][:: 97][:n]
    tf = create_transform(**{**cfg, "mean": (0, 0, 0), "std": (1 / 255, 1 / 255, 1 / 255)}, is_training=False)
    x = torch.stack([tf(Image.open(DATA / "images" / f"{r['photo_id']}.jpg").convert("RGB")) for r in rows])
    return x.numpy().astype(np.float32), [r["photo_id"] for r in rows]


def parity(path: Path, x: np.ndarray, expected: np.ndarray) -> tuple[int, float]:
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    got = []
    for i in range(len(x)):
        interp.set_tensor(inp["index"], x[i : i + 1])
        interp.invoke()
        got.append(interp.get_tensor(outp["index"])[0])
    got = np.stack(got)
    return int((got.argmax(1) == expected.argmax(1)).sum()), float(np.abs(got - expected).max())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run", type=Path)
    ap.add_argument("--fp32", action="store_true", help="skip the int8 weight-only variant")
    ap.add_argument("--golden", type=int, default=20)
    args = ap.parse_args()

    import litert_torch  # heavy import, only needed here

    metrics = json.loads((args.run / "metrics.json").read_text())
    labels = json.loads((args.run / "labels.json").read_text())
    cfg = metrics["input"]
    model = timm.create_model(metrics["model"], pretrained=False, num_classes=len(labels))
    model.load_state_dict(torch.load(args.run / "best.pt", map_location="cpu"))
    wrapped = Normalised(model.eval(), cfg["mean"], cfg["std"]).eval()

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
    for path in candidates:
        agree, max_delta = parity(path, x, expected)
        ok = agree == len(x) and max_delta <= (1e-2 if path.name == "species.tflite" else 0.25)
        print(f"{path.name}: {path.stat().st_size / 1e6:.1f} MB; top-1 agreement {agree}/{len(x)}; max |Δlogit| {max_delta:.4f}; {'PASS' if ok else 'FAIL'}")
        if ok and chosen is None:
            chosen, out, chosen_stats = path, path, (agree, max_delta)
    if chosen is None:
        sys.exit("golden test FAILED: no converted model matches PyTorch")
    agree, max_delta = chosen_stats

    golden = args.run / "golden"
    golden.mkdir(exist_ok=True)
    np.save(golden / "inputs.npy", x)
    np.save(golden / "expected_logits.npy", expected)
    (golden / "photo_ids.json").write_text(json.dumps(photo_ids))

    catalogue_version = json.loads(CATALOGUE.read_text())["catalogueVersion"]
    (args.run / "model.json").write_text(json.dumps({
        "model": metrics["model"], "file": out.name, "catalogueVersion": catalogue_version,
        "input": {"layout": "NCHW", "size": size, "range": "0-255 RGB", "crop_pct": cfg["crop_pct"], "interpolation": cfg["interpolation"]},
        "threshold": metrics["open_set"]["threshold"], "labels": labels,
        "golden": {"top1_agreement": f"{agree}/{len(x)}", "max_abs_logit_delta": max_delta},
    }, indent=1))


if __name__ == "__main__":
    main()
