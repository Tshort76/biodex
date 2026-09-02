# BioDex

A personal Android app that turns a real-world wildlife life list into a Pokédex: a curated Pacific-region catalogue of species, each starting as a silhouette and unlocking when you register a photo of it from your phone's gallery. Photos stay in the gallery and are referenced, never copied; progress is tracked per ecosystem and per taxonomic class.

Design docs: `DESIGN.md` (product) and `ARCHITECTURE.md` (technical, including the slice map in section 9).

## Prerequisites

- **JDK 17** (Temurin). Check with `java -version`; anything newer is untested and AGP 8.x expects 17.
- **Android SDK** at `/opt/homebrew/share/android-commandlinetools`, with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools` installed and licenses accepted.
- No system Gradle and no Android Studio are needed — the repo carries the Gradle 8.13 wrapper.

Create `local.properties` at the repo root (it is git-ignored, so it is not in a fresh clone):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## Build

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Debug is the only build type; there is no release configuration.

## Install on the phone

`adb` is not on `PATH` — it lives in the SDK:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/platform-tools"

adb devices                                                    # confirm the phone is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or, with the phone already connected, let Gradle do both steps:

```bash
./gradlew installDebug
```

### Enabling USB debugging

1. On the phone, open **Settings → About phone** and tap **Build number** seven times to unlock Developer options.
2. Go to **Settings → System → Developer options** and turn on **USB debugging**.
3. Plug the phone into the Mac with a data-capable USB cable.
4. Run `adb devices`. The phone shows a "Allow USB debugging?" dialog the first time — accept it (tick "Always allow from this computer"), then run `adb devices` again and confirm the phone reads `device` rather than `unauthorized`.

## Other useful commands

```bash
./gradlew testDebugUnitTest          # JVM unit tests, no device needed
./gradlew connectedDebugAndroidTest  # instrumented tests, phone required
adb exec-out screencap -p > shot.png # screenshot, for visual checks without Android Studio
```

## What the finished app does

All eight slices are built. The app is one screen deep in most places, and the loop is:

- **Dex grid** — 120 curated Pacific species in dex order, uncaught ones drawn as a class silhouette. Live search over common and scientific names, and one chip row composing three filters (caught state, class, ecosystem). The header carries the region and the `47 / 120` progress pill; the gear opens Settings.
- **Entry detail** — for a caught species: your own photos, the Wikimedia reference image with its credit, habitat text, ecosystems, and the outbound link. An uncaught species stays withheld: silhouette, name, number, and a Register button.
- **Register** — pick a species, attach a gallery photo, and the species unlocks with a brief reveal. The photo is *referenced*, not copied: the app persists a URI grant and keeps its own 640 px thumbnail, so the collection still renders if the gallery photo later disappears. A broken reference shows the thumbnail plus a re-link offer, and never un-catches the species.
- **Add your own species** — a name outside the catalogue is resolved through GBIF (scientific name and class) and Wikipedia (habitat text and image), and shown as a confirmation card you can edit before anything is written. Offline, the entry is created immediately from the name and photo alone and backfilled the next time you open it online. User-added species get U-numbers and sit outside the completion fraction.
- **Stats** — overall progress, seven ecosystem meters, class bars, and a recently-caught strip. A species in several ecosystems counts in each, so the ecosystem totals sum past 120 on purpose.
- **Settings** — the "keep a local copy" switch, cache sizes and a clear button, the photo-permission count, export/import, and the licenses screen.

### Backup: what an export actually contains

`Settings → Export collection` writes one ZIP and hands it to the share sheet:

```
manifest.json            the whole collection: species, entries, captures, and a photo report
thumbnails/<id>.jpg      every thumbnail the app owns
photos/<id>.jpg          a full-size copy of every photo whose reference still resolved
```

**A photo whose gallery reference is already broken cannot be exported** — the bytes are gone from the device, and no archive can invent them. The export says so in numbers, splitting the two cases: a *revoked* reference (the photo was deleted from the gallery) will never export, while a *cloud-only or offline* one usually will if you export again with a connection. The thumbnail and every detail of the catch are in the archive either way, so the entry restores; only the full-size photograph is lost.

The manifest is written last, from the files that actually landed, so it never names a photo the archive does not hold.

Import (`Settings → Import from an archive`) merges rather than replaces. It adds species, entries and captures the database does not have, skips capture ids it already has (so importing the same archive twice is a no-op), keeps the local catch date when it is earlier, and never deletes anything. Restored photos are written into app storage as local copies; no URI grant is ever recreated, because a grant from another phone is meaningless here.

## The catalogue pipeline

The 120-species asset at `app/src/main/assets/catalogue/pacific.json` is generated, and committed, so no build ever touches the network:

```bash
cd tools/catalogue
python3 -m venv .venv && .venv/bin/pip install requests
.venv/bin/python build_catalogue.py --out ../../app/src/main/assets/catalogue/pacific.json
```

Responses are cached under `tools/catalogue/cache/`, so a re-run makes zero HTTP requests; `--refresh` bypasses the cache. The run report lands in `cache/report.txt`. See `tools/catalogue/README.md` for the details.

## What has never been verified on a device

This is the honest part. **No phone has ever been connected to this project.** Everything below is true as of the last commit:

- `./gradlew assembleDebug`, `./gradlew assembleDebugAndroidTest` and `./gradlew testDebugUnitTest` pass — 242 JVM unit tests, 0 failures.
- **Nothing in this app has ever rendered.** No screen has been seen on a device or an emulator, so layout, spacing, colour in real light, the reveal's feel and the dark-theme palette are all unobserved.
- **No instrumented test has ever run.** The `app/src/androidTest/` suite (Room schema and DAO round-trips, cascade behaviour, the importer against the real 120-species asset, the photo gateway) compiles and has never executed. `./gradlew connectedDebugAndroidTest` is the first thing to run with a phone attached.
- **The whole photo layer is unexercised against real Android.** Nobody has run the system photo picker, watched a persistable URI grant survive a reboot, seen a revoked grant produce the re-link state, or confirmed that a cloud-only Google Photos item behaves as the code assumes. The exception-to-state mapping in `PhotoRef.kt` is an assertion about what Android throws, not an observation.
- **No network call has ever been made from the app.** The two API clients are tested against real payloads captured with `curl` and checked in as fixtures; the app itself has never talked to GBIF, Wikipedia or Wikimedia, so the User-Agent has not been proven acceptable to Wikimedia in practice.
- **Export has never produced a file another app opened**, and import has never read one. The ZIP writing, manifest and merge run end to end in the JVM suite against an in-memory fake filesystem, which proves the rules and not the FileProvider, the share sheet, or the document picker.
- **The S03 local-copy path has never written a file**, and clearing the caches has never been observed to leave thumbnails and entries intact.

Each slice's phone check is listed in `ARCHITECTURE.md` section 9. None of them has been performed.
