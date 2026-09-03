# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-user Android app (Kotlin, Jetpack Compose, Room) that turns a real-world life list into a Pokédex. It ships one curated region — the Pacific USA BioDex: 120 animals, 80 plants, 30 fungi. Species start as silhouettes and unlock when the user registers a photo.

`README.md` is written for the user. `DESIGN.md` (product requirements) and `ARCHITECTURE.md` (technical decisions) are the tracked design record — see **The design registers** below, which is the convention most likely to trip you up.

## Environment

The build needs JDK 17 and the Android SDK. Neither is on `PATH` by default in a fresh shell — export both before any Gradle command, or the build fails confusingly:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$(sed -n 's/^sdk\.dir=//p' local.properties)
export PATH="$PATH:$ANDROID_HOME/platform-tools"    # for adb
```

There is no Android Studio and no system Gradle — the repo carries the Gradle 8.13 wrapper. `local.properties` (holding `sdk.dir`, the SDK's location on this machine) is git-ignored, so it is absent from a fresh clone and nothing builds until you write it.

## Commands

```bash
./gradlew assembleDebug      # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # build and install onto a connected phone
./gradlew testDebugUnitTest  # 441 JVM tests, no device
./gradlew connectedDebugAndroidTest   # 43 instrumented tests, phone required
./gradlew compileDebugKotlin # fastest check that Kotlin still compiles

# one test class / one test
./gradlew testDebugUnitTest --tests "dev.tlong.biodex.ui.PlantUiTest"
./gradlew testDebugUnitTest --tests "*.PlantUiTest.a fungus caution reaches the screen"
```

Catalogue pipeline (pure stdlib Python, no virtualenv):

```bash
cd tools/catalogue
python3 -m unittest test_build_catalogue           # 13 tests
python3 build_catalogue.py --out ../../app/src/main/assets/catalogue/pacific.json
```

Two traps worth knowing before you trust a green run:

- **`testDebugUnitTest` does not treat the catalogue asset as an input.** Change `pacific.json` or anything under `tools/catalogue/` and Gradle reports `BUILD SUCCESSFUL in 3s` off stale results. Use `--rerun-tasks` to actually re-run the suite after a catalogue change.
- **A full catalogue build exceeds the default 2-minute Bash timeout.** Pass a longer one (600000 ms). Responses cache under `tools/catalogue/cache/` (git-ignored), so a re-run makes zero HTTP requests; `--refresh` bypasses the cache.

`connectedDebugAndroidTest` **uninstalls the app when it finishes** — reinstall with `./gradlew installDebug` if BioDex vanishes from the phone after a test run.

## The design registers — the convention to respect

`DESIGN.md` and `ARCHITECTURE.md` carry numbered registers: `M##` (MUST), `S##` (SHOULD), `C##` (COULD), `D##` (decisions), `R##` (risks). Roughly 540 citations of these ids live in Kotlin and Python comments (`// M23: a plain membership test…`), which makes them load-bearing rather than decorative:

- **Never renumber.** Both documents leave holes where a requirement was struck (M06 and D4 are gone with bird-call playback) precisely because the surviving numbers are referenced from code.
- **When you change shipped behavior that a register describes, revise the register in place** and add a `D##` recording why. A citation pointing at a stale requirement is worse than no citation. Revisions are marked in the text (`- **M23** *(revised, see D28)* — …`) and noted in the version paragraph at the top of `DESIGN.md`.
- The per-slice "corrections" subsections in `ARCHITECTURE.md` (§3.4, §5.5, §6.5 and friends) are **dated historical records of what a slice did at the time**, not current-state claims. Leave them frozen; do not "fix" them to match today's code.

`ARCHITECTURE.md` §2's directory tree still says `pokedex-animals/` and `dev/tlong/animaldex/` — the rename to BioDex is recorded in §11.5, and the real package is `dev.tlong.biodex`.

## Architecture

**One Gradle module** (`:app`), deliberately — package discipline substitutes for module boundaries. Everything is hand-wired: no Hilt, no DI framework. `AppContainer.kt` owns every singleton (database, HTTP clients, repositories, caches) and `App.kt` constructs it; screens reach it through `LocalContext.current.appContainer`.

```
app/src/main/kotlin/dev/tlong/biodex/
├── AppContainer.kt     hand-wired singletons — the only composition root
├── data/
│   ├── db/             Room: AppDatabase, entities, DAOs, converters
│   ├── catalogue/      asset JSON models + first-run/upgrade importer & reconciler
│   ├── photo/          picker handling, grant persistence, thumbnails, URI states
│   ├── identify/       Pl@ntNet identification (plants only, opt-in per photo)
│   ├── net/            GbifClient, WikipediaClient
│   ├── backup/         export/import ZIP
│   ├── settings/       DataStore-backed prefs (holds the Pl@ntNet key)
│   └── repo/           DexRepository, SpeciesLookupRepository, registrars
├── domain/             plain models the UI consumes + DexProgressMath
├── media/              Coil loader, image cache, NetworkMonitor
└── ui/<screen>/        one package per screen: Screen.kt + State.kt + ViewModel.kt
```

**The state-holder pattern is the thing to copy.** Each screen's `combine` over the repository's cold flows is a **top-level pure function in its own `*State.kt` file**; the ViewModel is that function plus `stateIn`. This exists so the JVM suite can exercise filtering, progress math and screen state with no device, no Main dispatcher and no Room — the fake repository is a handful of `MutableStateFlow`s. Put logic in the state file, not the ViewModel; the ViewModel should own only the mutable inputs and the sharing policy.

```kotlin
// DexGridState.kt — testable without a device
fun dexGridUiState(species: Flow<…>, …, filters: Flow<DexGridFilters>): Flow<DexGridUiState>

// DexGridViewModel.kt — this function plus stateIn, and nothing else of substance
val uiState: StateFlow<DexGridUiState> = dexGridUiState(…).stateIn(viewModelScope, …)
```

**Invariants belong at the one door into the store, not on the screen.** `AddSpeciesRegistrar` enforces rules like M41 (a plant keeps no photograph of its own) because it is the single write path and is JVM-testable; the ViewModel keeps only the side effects it alone can perform, such as promoting a camera shot to the gallery once the kingdom is known.

**Photos are referenced, never copied** (except the opt-in "keep a local copy" setting). The app stores a URI and a small thumbnail. A deleted gallery photo is an expected state with its own UI, not an error.

**Room migrations are hand-written with `exportSchema` on.** There is no `fallbackToDestructiveMigration` and there must not be — this database holds a collection that cannot be re-earned.

## The catalogue

`app/src/main/assets/catalogue/pacific.json` is **generated and committed**, so no build touches the network. It is built by `tools/catalogue/build_catalogue.py` from four hand-authored inputs (`region.json` plus `curated_animals/plants/fungi.json`) joined against GBIF, Wikipedia, Wikimedia Commons and Dr. Duke's (CC0, plants only — there is no Duke's data behind a fungus by construction).

Two rules the build **enforces rather than trusts**, and both should stay that way:

- Every plant with a `Poison` record in Duke's must carry a `Caution:` sentence, or the build fails naming the species. This is what keeps the cautioned set decided by a public dataset rather than by whoever wrote the entry. Trimming an entry's note can therefore fail the build legitimately — restore a short caution rather than deleting the rule.
- A GBIF synonym is accepted only if it keeps the accepted name's specific epithet. Without it, GBIF offers Port Orford cedar as a synonym of coast redwood.

The four input files are split so a plant edit, a fungus edit and an animal edit never touch the same file. Edit them with **surgical string replacements** — a full `json.dump` rewrite reformats the whole file and buries a 3-line change in a 300-line diff. Use `ensure_ascii=False` when matching text, since the files store `—` and `'` raw.

## Tone of the product

This is a collecting game, not a field guide. The user has corrected this twice: Pokédex entries are a sentence or two, and safety text was cut back to one short sentence per genuinely dangerous species. The single disclaimer lives at the top of `README.md`. Do not add caution paragraphs, hedging blockquotes, or per-screen warnings; if something genuinely needs a caveat, it gets one short sentence.

## Secrets and signing

- **The Pl@ntNet API key never enters the repo, a commit, chat, or the APK.** The repository is public and a key compiled into an APK is extractable. It lives only in the app's private settings on the phone. Do not dump `shared_prefs` via `run-as` in a way that would print it.
- The release keystore and `keystore.properties` live outside the repository and are git-ignored — see README, "Building a release APK". Never echo the store or key password into a transcript. A clone without `keystore.properties` still builds; it just produces an unsigned release APK.
- R8 is deliberately off for release; the comment in `app/build.gradle.kts` explains why (silent runtime failures in serialization paths).

## Git

Pushes go over SSH. If a push fails with a 403, the remote is resolving to the wrong account — check `git remote -v` and the local SSH config rather than retrying.

`DESIGN-identification.md` at the repo root is an untracked proposal the owner asked to keep uncommitted — leave it that way.

## Driving the phone

Verification on a real device is a normal part of finishing UI work here, since there are no screenshot tests. `adb shell input text` **drops characters in Compose text fields** ("Dandelion" arrives as "Dandeln") — type one character per call in a loop. To read the database, pull `biodex.db` **plus `biodex.db-wal` and `biodex.db-shm`** or you will see no rows; there is no `sqlite3` binary on the device, so query the pulled files locally.
