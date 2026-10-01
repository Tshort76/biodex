# On-device species classifier — feasibility and plan

_Draft 2026-09-24, revised the same day after a design review. Phases 0–2 are being built in `tools/classifier/` (its README says how to run them); the app is untouched. Register ids marked `?` are proposed, not assigned._

> **Paused 2026-10-01.** Latest result and diagnosis: §1b. To resume:
> 1. **Decide the next experiment.** Either broaden distillation to thousands of Pacific species (~20 photos each), training on cosine only or with name terms restricted to trained names; or measure a ViT-B BioCLIP image encoder as the on-phone model.
> 2. **Data.** All data lives in `tools/classifier/data/`, which is git-ignored and symlinked from worktrees. It was trimmed to about 1.4 GB on 2026-10-01. It keeps the photo lists (`manifest.jsonl`, `clean.jsonl`), BioCLIP's embedding of every photo, the name list, the reference photos for the cleaning probe and two runs (`cnx-open`, `cnx-embed`). The photos, the venv and the API cache were deleted. To resume, run `uv sync`, then `python3 download.py`, which re-fetches the 137k photos from S3 with no API calls.
> 3. **Throwaway diagnostics.** These are in `data/scratch/`: `ceiling.py` (BioCLIP's name-matching ceiling), `prior_diag.py` (the trained-name bias in §1b) and `eval_open.py` (the penalty sweep for the `other` class).
> 4. **Long runs.** Launch them detached (`nohup … & disown`) and caffeinate the machine. Background jobs in Claude Code are killed at their time limit.
> 5. **Still open with the owner.** The owner's original photos plus the Google Lens hard cases are needed for a real-world evaluation.

## 1. Verdict

**Feasible, and mostly buildable by Claude.** The four questions that could have killed it have workable answers:

| Question | Answer |
|---|---|
| Is there training data we may ship weights from? | Yes — iNaturalist Open Data on AWS (`s3://inaturalist-open-data`, no account needed) carries a licence per photo. Train on **CC0 + CC-BY only** and the weights are compatible with this public MIT repo. Per-species coverage: §3. |
| Can it run on the phone with no backend? | Yes — LiteRT 2.2 (`com.google.ai.edge.litert:litert`), converted from PyTorch with `litert-torch`, GPU accelerator on the Pixel 7 Pro's Mali-G710 with a CPU fallback. Well under a second per photo (unmeasured; JPEG decode will dominate). |
| What does it cost the APK? | ≈ 9 MB native runtime + ≈ 9 MB model (int8 weights, fp32 activations; 35 MB at fp32) → **≈ 18 MB added**, against an ~850 KB catalogue today. |
| Can it be trained here? | Yes — M4 Pro, 48 GB, MPS. torch 2.13 + timm 1.0.30 + litert-torch 0.9.4 resolve on Python 3.12 (not 3.14). Fine-tuning a ~10M-param backbone on ~50–80k images is hours, not days. |

Accuracy is the one honest unknown: **85–93 % top-1 is an extrapolation**, not a measurement (iNat21's 10,000-class benchmark gets 65–76 % with a ResNet-50; 254 classes is far easier, but insects and fungi will lag). Phase 2 measures it before any app code is written.

**Why this does not reopen D59.** D59 rejected "keeping identification for a future animal provider", and the D23 reasoning behind it was that a *third-party* model answers from the whole world, so most answers read "not in dex". A first-party model is trained on this catalogue: every answer is a dex entry, and a trained `other` class lets it say "not in dex" honestly.

## 1a. Results so far (2026-09-28)

Corpus: 117,999 CC0/CC-BY photos selected for all 254 species plus 384 lookalike species, 112,978 kept after cleaning (5,021 tracks, scat, bones and the like dropped by a probe on BioCLIP 2 embeddings).

| Run | Test top-1 | Test top-3 | Unseen species flagged "not in dex" |
|---|---|---|---|
| MNv4, lr 1e-3 (first recipe) | 73.7 % | 87.1 % | 12 % |
| MNv4, distilled from BioCLIP (lr 1e-3) | 68.6 % | 82.4 % | 11 % |
| **MNv4, lr 2e-4 — current best (`data/runs/mnv4-lr2e-4`)** | **80.6 %** | **91.0 %** | 15 % (AUROC 0.73) |
| BioCLIP 2 linear probe (reference; too large for the phone) | 95.9 % | 98.6 % | 69 % (AUROC 0.93) |

The first recipe's learning rate was wrecking the pretrained features (12 % after one epoch; 59 % at 2e-4).

**"Not in dex" (2026-09-28).** Re-scored on a broader held-out set (6,715 photos of 20 % of all non-dex species, never trained on):

| Model | Top-1 dex | Top-3 dex | Dex photos called "other" | Unseen species flagged |
|---|---|---|---|---|
| `mnv4-lr2e-4` (lookalike-only `other`) | 80.6 % | 91.0 % | 0 % | 1.3 % |
| `mnv4-open` (broad `other`: +1,320 common non-dex Pacific species, 25 % of each batch) | 63.8 % | 73.4 % | 18.5 % | 71.3 % |
| `mnv4-open`, logit penalty 1 on `other` | 68.2 % | 80.1 % | 8.3 % | 53.9 % |
| `mnv4-open`, penalty 2 | 69.8 % | 82.9 % | 3.4 % | 36.6 % |
| BioCLIP 2 probe (reference, too large for the phone) | 95.9 % | 98.6 % | — | ~69 % |

| **`cnx-open` — ConvNeXt-Nano (15M params, `convnext_nano.in12k_ft_in1k`), same broad `other` at 15 % of each batch, lr 1e-4, 12 epochs — current best** | 84.6 % | 90.7 % | 6.0 % | 71.0 % |
| `cnx-open`, penalty 1 on `other` — **proposed operating point** | 86.2 % | 92.7 % | 3.3 % | 62.9 % |
| `cnx-open`, penalty 2 | 87.1 % | 94.0 % | 1.6 % | 54.8 % |

Nearest-neighbour distance on `mnv4-lr2e-4` features flags only 9–20 % (AUROC 0.59–0.67), and the max-probability threshold is no better, so the broad `other` class is the only mechanism that works. It costs ~10 points of species accuracy even with `other` penalised away, and both runs end on a plateau (final loss 1.84 vs 1.37): a 9M-parameter MobileNetV4 lacks the capacity to do both. ConvNeXt-Nano does: with `other` penalised away it reaches 87.9 % / 95.1 %, and its per-group top-1 is 66 % for fish and 77–98 % for everything else. The penalty (subtracted from the `other` logit on the phone) is the one knob between flagging unseen species and wrongly flagging dex ones; 1 is the proposed default, a collecting game preferring a missed \"not in dex\" on 3 % of dex photos over naming a wrong species for most unseen ones. Export: fp32 passes the golden gate in every run (ConvNeXt 60.6 MB); int8 weights never do (ConvNeXt 15.8 MB, 19/20).

Weakest groups: fish 61 % top-1, amphibians 71 %; fungi and invertebrates are ~90 %+. The open-set "not in dex" signal does not work yet. Export: the fp32 model (35 MB) matches PyTorch exactly; the int8-weight model (9.5 MB) agrees on 20/20 top-1 but drifts up to 0.12 in probability and fails the golden gate.

## 1b. Naming species outside the dex (2026-10-01)

The 255-way design above can only say "not in dex"; it never names a non-dex animal, which is most of the value. So the design moved to **name matching**: the phone model maps a photo to a 768-d vector, and the answer is the nearest of a list of BioCLIP 2 text embeddings ("a photo of {name}.") for 1,962 names. The list can grow without retraining. `names.py` builds the list; `distill.py` trains the student (ConvNeXt-Nano) to reproduce BioCLIP 2's image embedding.

**Ceiling.** BioCLIP 2 itself, matched against the same 1,945–1,962 names, gets 84–85 % top-1 and ~93 % top-3 on held-out species it was never shown in this project.

**First student (`data/runs/cnx-embed`, 12 epochs, cosine + name KD + name CE):**

| Test photos | Student top-1 / top-3 | BioCLIP 2 top-1 / top-3 |
|---|---|---|
| Dex species | 86.6 % / 93.6 % | 89.3 % / 96.0 % |
| `other` species seen in training | 56.9 % / 81.4 % | 85.6 % / 94.2 % |
| **Species never trained on (`open_test`)** | **11.4 % / 44.6 %** | **84.7 % / 93.0 %** |

Export passes the golden gate at both fp32 (61.9 MB) and, for the first time, int8 weights (16.1 MB, 20/20, max Δcos 1.6e-4).

**Why it fails on unseen species — two causes:**

1. **Bias toward trained names.** The KD and CE terms score each photo against all 1,962 names, and the 345 names with no training photos are never the target, so the student learns to rank them low: 87 % of its top-1 answers on unseen species are trained names (BioCLIP's: 13 %). A post-hoc boost on untrained names trades rather than fixes: +0.02 lifts unseen to 36 % but drops dex to 84 %.
2. **The student only imitates BioCLIP on what it has seen.** Even restricted to the untrained names, it gets 65 % against BioCLIP's 93 %. Embedding quality on unseen kinds of animal is the real limit.

Distillation needs no labels, only photos for BioCLIP to embed, so the fix is breadth: distil on photos of every species a Pacific user is likely to meet (thousands, ~20 each) and drop or restrict the name terms to trained names. Alternative if breadth is not enough: run a smaller BioCLIP image encoder (ViT-B) on the phone directly.

## 2. What the model is

- **A 255-way image classifier**: the 254 catalogue species plus one `other` class. The photo is classified whole, in one pass. Not a detector (§7).
- **Backbone: `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k` at 256 px** (≈9M params, Apache-2.0). The ImageNet-12k pretraining covers far more fine-grained classes than ImageNet-1k, which is what species transfer wants; 256 px instead of the native 384 keeps it phone-fast. `efficientnet_b0.ra4_e3600_r224_in1k` (5.3M) is tried only if MNv4 misses the Phase 1 bar.
- **No location or season prior in v1.** The regional list is already baked in by training only on the 254. A prior from `rangeCells` and the photo's GPS (D63 already reads it) is added only if the Phase 2 confusion matrix shows pairs that geography or season would separate.
- **Suggests, never decides.** D2 and D10 reject auto-selecting the top match; this keeps that. The model fills a candidate list, the user taps one, and the capture goes through `CaptureRegistrar.register` as today.

Rejected: BioCLIP 2 on the phone (ViT-L/14, ≈300 M params, 10× over budget; no mobile BioCLIP exists); the timm iNat21 fine-tunes (all CC-BY-NC); iNaturalist's CV API (partner-only); ML Kit / MediaPipe (they need NHWC input and TFLite metadata to save ~20 lines of label mapping); ExecuTorch (CPU-only on the Pixel 7 from the Maven AAR); ONNX Runtime (33 MB native, NNAPI deprecated).

## 3. Data

**Source.** Selection through the iNat API (about 2,000 requests at one a second, cached — well inside its ~10 k/day cap); photo bytes from the iNat Open Data bucket on S3 (`photos/<id>/medium.jpg`, 500 px), which is where CC0/CC-BY photos live and which the API's media limits do not cover. The bucket's metadata ships only as a 35 GB monthly tarball, which is why selection uses the API instead.

**Selection, per catalogue species:**
1. Resolve `scientificName` → iNat `taxon_id` through `/v1/taxa`, with a hand-edited `taxon_overrides.json` for synonyms and splits. An unresolved name is reported, not guessed.
2. Research-grade observations, photo licence `cc0` or `cc-by`, **one photo per observation**, at most ~10 per observer, so one prolific photographer does not define a species.
3. Prefer observations inside the Pacific region; top up from anywhere when a species is short.
4. Cap at 400 images per species; split train / validation / test **by observation**, never by photo.

**The `other` class.** ~6–8 k photos of Pacific-region species **not** in the dex, weighted toward lookalikes (same genus, then same family, then common neighbours). A disjoint set of further out-of-dex species is held back as the open-set test — the owner's photos contain no out-of-dex subjects, so this is where the "Not sure" threshold is tuned.

**Only pictures of the animal.** The owner does not want the model to learn tracks or scat. Observations annotated with iNat's Evidence-of-Presence values for feathers, scat, tracks, bones, molts, galls, eggs, hair, leaf mines or constructions are dropped at selection; only ~15 % of observations carry that annotation, so it is a negative filter, never a positive one. Then every downloaded image goes through **BioCLIP 2 zero-shot on the laptop** (MIT) against the species' name plus a set of "not the animal" prompts (tracks, scat, feathers, bones, empty habitat, a burrow, a fish in hands or on a deck, a museum specimen), and images it scores far off are dropped. A contact sheet per species is kept for a spot check. The same pass reports BioCLIP's own zero-shot accuracy on the owner's photos — a reference number, not a gate.

**Licence stance.** CC0 + CC-BY only. An attribution manifest (`photo_id, observer, licence, url`) is generated with the dataset and published with the weights, under a `MODEL-LICENSE` noting CC-BY attribution. Images are never committed; they live in a git-ignored cache like `tools/catalogue/cache/`. One build, one stance — settled before Phase 0 (§8).

**Coverage (measured 2026-09-24, iNat API, research grade, any location).** Research-grade observations with a CC0 or CC-BY photo, per catalogue species. About 9 % of all research-grade observations qualify, but the catalogue is mostly common species, so that is enough:

| Usable observations | Species |
|---|---|
| 400 or more (the full cap) | **207** |
| 200–399 | 21 |
| 100–199 | 10 |
| 50–99 | 9 |
| under 50 | **7** |

By group, every amphibian, bird, reptile and bracket fungus clears 200. **Fish are the weak group**: 16 of 24 are under 200 and 4 are under 50 (barred surfperch 16, black rockfish 27, white sturgeon 27, blue rockfish 32). The other three under 50 are American matsutake (16), mountain beaver (37) and Jerusalem cricket (2). The cricket is almost certainly a naming problem: its catalogue name matches only 15 research-grade observations under any licence, so it needs a genus-level override in `taxon_overrides.json`. Many fish photos on iNat are of caught fish on a deck, which is also not how the owner will photograph them, so fish are the group most likely to be left out of v1.

**The owner's photos are eval-only, forever.** The originals of the owner's captures (63 entries at the last count; n is small, so every figure on them is quoted with its confidence interval) plus the hard cases Lens got wrong. They never enter training, so they stay an honest measure of the real distribution: phone snaps, worn moths, subjects at a distance. Getting the originals off the phone is a Phase 0 task, since the app stores picker URIs and thumbnails, not files.

## 4. Phases

Each phase ends at a checkpoint with a number. A bad number stops the plan instead of rolling into the next phase.

| Phase | What | Who | Checkpoint |
|---|---|---|---|
| **0. Corpus** | `tools/classifier/` Python project (uv, Python 3.12): taxon resolution, DuckDB selection, download, BioCLIP cleaning, manifest. The owner's eval photos are pulled to a local, git-ignored folder. | Claude; owner supplies eval photos | Per-species image counts. Each species under ~50 usable images is either kept with fewer or left out of the model (it stays in the dex; the model just never suggests it). |
| **1. Train** | Fine-tune MNv4-Conv-M at 224 px: class-balanced sampling, label smoothing 0.1, random-resized-crop down to 0.25 scale, light RandAugment, AdamW + cosine, ~30 epochs on MPS. | Claude | **Go/no-go.** Aggregate **top-3 on the owner's photos**, with its CI; proposed bar ≥ 90 %. Per-group top-1 and open-set AUROC come from the iNat test split. Below the bar: EffNet-B0, then distillation from BioCLIP 2, before giving up. |
| **2. Export** | A wrapper with ImageNet normalisation **inside** the graph (the phone feeds 0–255 floats); `litert-torch` → `.tflite` at fp32 and with int8 weights (the quantizer has no fp16 recipe); the smallest that passes the golden test ships; staged average pooling if the global mean NaNs on the GPU; a Python golden test (`ai_edge_litert.Interpreter` against PyTorch on ~20 tensors). Labels keyed by catalogue `id`, plus `model.json` (version, `catalogueVersion` trained against, input size, threshold). | Claude | Top-1 agreement 20/20; max \|Δlogit\| < 1e-2 at fp32, < 0.25 with int8 weights. Model under 20 MB. (A smoke test of an untrained MNv4 converted in 11 s and matched PyTorch to 2e-5.) |
| **3. App** | §5. | Claude; owner installs and judges on the phone | The on-phone golden check passes on the GPU; the owner uses it for a week of real captures. |
| **4. Iterate** | Retrain as the iNat snapshot grows or the catalogue changes. New owner captures join the **eval** set. | Both | Eval accuracy over time. |

## 5. App changes

Small, and shaped to the existing conventions.

- **Seam.** `data/identify/` left with Pl@ntNet in v21, so this reintroduces it as a one-line `fun interface SpeciesClassifier { suspend fun classify(bitmap: Bitmap): FloatArray }` with `LiteRtSpeciesClassifier` as its one implementation, built in `AppContainer`. The JVM fake is why it exists. S14 is revised to name it.
- **Preprocessing.** Decode with `ImageDecoder` (applies EXIF rotation, minSdk 29 safe), resize the short side then centre-crop exactly as timm's `crop_pct` does, one `getPixels` into a reused `FloatArray` in NCHW planes. Off the main thread.
- **Ranking is pure.** `rankCandidates(logits, labels, threshold): Suggestions` lives in `IdentifyState.kt` and is JVM-tested: top-3, softmax scores, labels missing from the installed catalogue dropped, and the `other`/max-logit outcome as "not in dex". The ViewModel only calls the classifier and feeds the result in.
- **Identify screen.** When the photo lands, a "Looks like…" row shows the top 3 dex species with each score labelled as a model score. Tapping one sets `selectedId`, the same path as picking a search result, and the user still presses Capture. Below the threshold the row says "Not sure — not in your dex?" and Lens (S06/D79) is the next action. Lens stays on every outcome. Whether this runs automatically or behind a button is open (§8).
- **Capture note.** A capture made from a suggestion gets its note prefilled with "BioDex model · 87 %", editable (S11 revived).
- **User-added species** are never suggested; a top answer of `other` is exactly the case where the add flow is the right next step.
- **Build.** `implementation("com.google.ai.edge.litert:litert:2.2.0")`, `androidResources { noCompress += "tflite" }`, the model under `assets/model/`. Not the legacy `litert-support` artifact (not 16 KB aligned).
- **Tests.** JVM: `rankCandidates` and the Identify state with a fake classifier. On-device parity: the ~20 golden tensors and expected logits ship in debug builds, and a **debug-only Settings row** runs them through the real GPU path after `make install` and shows top-1 agreement and max Δ. This is the only check that exercises the Mali GPU, since an emulator has none, and `make test-device` is off limits on the owner's phone because it uninstalls the app.
- **Registers.** One new decision, `D80?` — a first-party on-device model: licence stance, suggests-never-decides, why it does not reopen D59 — plus `M47?` for on-device suggestions on Identify. S14, S11 and M07 are revised in place, D2 too if the model runs automatically. `BACKLOG.md` loses both identification ideas and the "Not going to happen" bullet that cites D23. `CHANGELOG.md` gets the version.

## 6. Risks

| Risk | Mitigation |
|---|---|
| **Thin CC0/CC-BY coverage** for rare fish, invertebrates and fungi (about 1 in 10 research-grade observations carries an open licence). | §3's coverage table; the Phase 0 checkpoint decides per species. |
| **Accuracy is extrapolated.** | The Phase 1 go/no-go, before any app work. |
| **Distribution gap** — iNat photos are enthusiasts' close-ups; the owner's are phone snaps at a distance. | Aggressive random-resized-crop; the gate is measured on the owner's photos, not the iNat split. |
| **fp16 global pooling → NaN on Mali GPUs.** | Known fix (staged average pooling); the on-phone golden row catches it; CPU fallback if the GPU accelerator fails to start. |
| **Preprocessing drift** (bicubic vs bilinear, crop order, NCHW). | Normalisation inside the graph; golden tests on raw tensors and on JPEGs. |
| **Small subject in a big frame** — a bird at 50 m is a few pixels after a 224 px resize. | Measured in Phase 3; if it dominates the misses, §7's crop. |
| **CC-BY attribution for weights** is legally unsettled. | The manifest ships with the weights regardless; nothing NC in any build. |

## 7. Later, only if the numbers ask for it

- **Cropping the subject** (a small detector, or a user drag-to-crop) — only if Phase 3 shows small-subject misses dominate. It would also give the reference-photo crop something better than a centred fit.
- **Location / season prior** — only if the confusion matrix shows pairs they would separate.
- **Int8** — if APK size matters more than the last point of accuracy.
- **A second region (C03)** means a retrain; the pipeline takes a different `region.json`.

## 8. Open with the owner

1. **In-app camera?** "Take a picture from within the app" could mean a camera button inside BioDex (new; there is none today, and a camera shot carries unredacted GPS) or the existing picker flow with the model in place of the Lens hop. This plan assumes the latter.
2. **Automatic or on a button?** Running the model as soon as the photo lands is free and private (nothing leaves the phone), so this plan leans automatic, which means revising D2 ("a suggestion service the user invokes").
3. **Licence stance** — CC0 + CC-BY only, attribution manifest, no NC in any build. Settled before Phase 0.
4. **APK growth** of ~18 MB (int8 weights), or ~44 MB at fp32.
5. **The eval photos** — the originals of your captures, plus the hard cases Lens got wrong.
