# Species classifier

Trains the on-device model that will replace the Google Lens hand-off on the Identify screen. The design and the reasoning behind it are in [`docs/CLASSIFIER-PLAN.md`](../../docs/CLASSIFIER-PLAN.md); this file is how to run it.

Everything the pipeline downloads or produces lives under `data/`, which is git-ignored: the photos, the API cache, checkpoints, exported models and the Python environment. Nothing in it is ever committed.

## Setup

Python 3.12 (torch and litert-torch do not support 3.14 yet), through uv. The environment lives inside `data/` so it is ignored with everything else:

```bash
cd tools/classifier
export UV_PROJECT_ENVIRONMENT=$PWD/data/.venv
uv sync
```

## The pipeline

| Step | Command | What it does |
|---|---|---|
| 1 | `python3 select_corpus.py` | Resolves each catalogue species to an iNaturalist taxon, picks research-grade observations with a **CC0 or CC-BY** photo (one photo per observation, at most 10 per observer, Pacific region first), adds an `other` class of lookalike species the dex does not hold, and writes `data/manifest.jsonl`. About 2,000 API requests at one a second; cached, so a re-run makes none. Standard library only. |
| 2 | `python3 download.py` | Fetches every photo (500 px) from the iNaturalist Open Data bucket on S3 to `data/images/`. Resumable. |
| 3 | `uv run python clean.py` | Scores each photo with BioCLIP 2 and drops the ones that are not a picture of the animal: tracks, scat, feathers, bones, empty habitat, fish on a deck. Writes `data/clean.jsonl` and a contact sheet of rejects (`data/rejects.jpg`) to spot-check. |
| 4 | `uv run python train.py` | Fine-tunes MobileNetV4-Medium at 256 px on MPS. Writes `data/runs/<name>/` with the best checkpoint, labels and `metrics.json` (top-1/top-3 per animal group, and how well it flags species it has never seen). |
| 5 | `uv run python export.py data/runs/<name>` | Converts to LiteRT with the input normalisation inside the graph, checks the converted model against PyTorch on real photos, and writes `species*.tflite`, `model.json` and the golden tensors for the on-phone check. |

`make classifier-test` (part of `make check`) runs the selection rules' tests, which need nothing installed.

## Rules the pipeline keeps

- **Licences.** CC0 and CC-BY photos only, so the weights can live in this MIT repo. Every photo's observer and licence is in the manifest, which is the attribution record for a published model.
- **Splits are by observation**, from a hash of its id, so a re-run never moves a photo between train and test.
- **The owner's own photos are for evaluation only**, never training.
- **Name problems go in `taxon_overrides.json`**, with the reason beside each entry, rather than being guessed at.
