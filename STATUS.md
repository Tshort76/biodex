# Where the project is

**Last updated 2026-09-03.** This is the fast-moving file: it says what is built, what was checked and how, and what is worth doing next. `DESIGN.md` and `ARCHITECTURE.md` hold the *why* and do not go stale the way this does.

To check whether it has drifted, read the commits since the date above:

```bash
git log --oneline --since=2026-09-03
```

## Read these first, in this order

1. **`CLAUDE.md`** — the working conventions, the build environment, and the traps (stale test results after a catalogue change, the design-register citation rule).
2. **This file** — what exists today.
3. **`DESIGN.md`** — numbered product requirements (`M##`/`S##`/`C##`), decisions (`D##`). The registers are cited ~540 times from code comments, so they are the map between a requirement and the code that implements it.
4. **`ARCHITECTURE.md`** — the technical decisions and their reasoning. Note its shape: §1–10 are the v1 design plus a per-slice deviation log, §11 designs the plants expansion. **Both stop before the work listed below**, so read §11.8 before trusting §9 or §11.6 as a picture of today.

## What is built

The app is complete and in daily use on a phone. It ships one region, the Pacific USA BioDex — `catalogueVersion: 3`, 230 species: **120 animals, 80 plants, 30 fungi**.

Everything in the slice maps (`ARCHITECTURE.md` §9, slices 1–8, and §11.6, slices 9–13) is shipped. So is a second wave of work that was never sliced, because it arrived as a conversation rather than a plan:

- **Fungi as a third kingdom** — own classes (mushroom, bracket, other fungus), own silhouettes, own progress meter and stats block, own dex-number range. Not a flavour of plant (`D27`).
- **An in-app camera** — `MediaStore.ACTION_IMAGE_CAPTURE` into the app's cache through a FileProvider, promoted to the gallery at registration. It needs **no `CAMERA` permission**; the system camera app holds it, and declaring a permission you do not hold is what throws (`D26`).
- **Plant identification through Pl@ntNet** — opt-in per photo, plants only. One downscaled, re-encoded copy of one photo goes out (so EXIF and GPS are stripped); candidates are checked against GBIF before display; the app never picks one (`D19`–`D23`, `M36`).
- **A plant keeps no photograph of its own** (`M41`) — its tile shows the catalogue's reference image, and the same rule applies to a plant the user adds. Animals and fungi still keep theirs. The invariant lives in `AddSpeciesRegistrar` (the single write path, JVM-testable); the side effects — promoting a camera shot, sweeping the cache — live in the confirm-card ViewModel, the first place that knows the kingdom.
- **The caution pass** (`D14`) — the build rule that forced a warning onto every fungus was deleted; it wrote paragraphs onto the turkey tail and the puffball, which is noise, not safety. Ten of the thirty fungi carry one short sentence. The Duke's `Poison` → mandatory `Caution:` rule for plants was **kept** — that set is decided by a public dataset, not by a writer. One disclaimer, at the top of `README.md`.
- **Grid filters as dropdowns** (`M23`, `D28`) — caught chips over Class / Ecosystem / Uses menus, no kingdom control.

## What was last verified, and how

As of the last commit: **441 JVM tests, 43 instrumented tests, 13 Python tests, all passing.**

```bash
./gradlew testDebugUnitTest --rerun-tasks          # 441 — --rerun-tasks matters, see CLAUDE.md
./gradlew connectedDebugAndroidTest                # 43, phone required; it uninstalls the app after
cd tools/catalogue && python3 -m unittest test_build_catalogue   # 13
```

There are no screenshot or UI tests, by choice — so **UI work is finished on the phone, not in the suite.** The filter dropdowns were driven through `adb` on a Pixel 7 Pro: each menu opened, a value picked, composition confirmed (Mammals + Riparian & Wetland gives exactly four species), and all three clear paths exercised. That device pass is what caught the two defects the tests could not see — the menus were rendering on Material's default lavender surface instead of the app palette, and an open menu gave no sign of which option was active.

**If you pick up identification work, the phone's app data was cleared, so the Pl@ntNet key must be re-entered** in Settings before anything on that path will run. The key lives only there — never in the repo, a commit, or the APK.

## Deliberately not built

Do not "fix" these; each is a decision with reasoning behind it.

- **R8 / code shrinking is off for release.** It would probably work, but it fails silently at run time in the serialization paths (`app/build.gradle.kts` has the full comment).
- **No identification for animals or fungi.** No provider we trust, and a candidate list against an empty fungal catalogue reads "not in dex" for every row, which is the feature not working (`D23`).
- **The offline add-your-own plant keeps its photo** (`C12`). Offline there is no lookup, so no kingdom, so `M41` cannot be applied at that moment. Left alone deliberately: the owner's answer was to photograph offline and add the species later when back online.
- **No cloud sync, accounts, or backend of any kind.** See `DESIGN.md` §7.

## Where to pick up

Nothing is half-finished — the tree is clean and everything is pushed. Candidate next work, in no particular order:

- **`C09`–`C12` in `DESIGN.md`** are the explicitly-deferred wants; they are the best-specified backlog.
- **`ARCHITECTURE.md` §11.4 has one superseded paragraph** (the chip row) and its §2 directory tree still uses the pre-rename package name. Both are marked in place rather than rewritten. Anything else you find stale in §11 is worth marking the same way.
- **A second region** (`C03`) is the largest coherent feature left. The schema already carries a `regions` table for exactly this; what it needs is a second curated input set and the pipeline run.

## One untracked file

`DESIGN-identification.md` at the repo root is a design proposal the owner asked to keep **uncommitted**. Its decisions were folded into `DESIGN.md`'s registers (`D19`–`D27`) and the proposal itself stays out of git. It exists only on the original machine — if you are on a fresh clone and it is missing, nothing is wrong, and the registers hold everything that mattered.
