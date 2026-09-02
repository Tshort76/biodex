# BioDex

A personal Android app that turns a real-world life list into a Pokédex. It ships with a curated catalogue for one region — the **Pacific USA BioDex**, everything west of the Rocky Mountains — holding 120 animals and 80 plants. Every species starts as a silhouette and unlocks when you photograph it yourself.

Your photos stay in your gallery. The app stores a reference and a small thumbnail, never a copy.

There is deliberately **no species identification** in the app. If you don't know what you're looking at, use Google Lens and then type the name in.

---

## Using it

**Catching something.** Tap the **+** button on the grid, or open a species and tap *Register this species*. Search by name, pick the species, attach a photo from your gallery, and register. The species flips from silhouette to your photograph and the counter ticks up. Photograph the same species again and the picture joins its strip without ceremony — the fanfare is reserved for firsts.

The gallery picker needs an explicit **Done** tap after you select a photo. Selecting alone returns nothing.

**Something not in the catalogue.** Type its name and choose *add your own species*. GBIF resolves the name to a real species, Wikipedia supplies habitat text and a photograph, and for a plant Duke's ethnobotanical database supplies its recorded medicinal uses. You get a confirmation card before anything is saved, because a name like "sparrow" matches several species and a silent wrong pick would be permanent. Your own species get **U-numbers** and sit outside the completion fraction, so they never make the dex unfinishable.

Offline, the entry is created immediately from the name and photo alone and filled in the next time you open it with a connection.

**Filtering.** The chip row composes rather than replaces: *Plants* + *Edible* + *Riparian & Wetland* narrows to exactly that. Search matches common and scientific names.

**Plants have a uses section** where an animal has nothing. It reads in a deliberate order — any **caution** first, then the curated note about which part and which season, then a muted line recording what Duke's holds. Those are three different kinds of claim and the layout ranks them by how much they should be trusted.

> **The uses data is documentation, not advice.** Medicinal information is what a public dataset records, edible tags are one person's curation, and neither is an identification. 27 of the 80 plants carry a caution; every plant with a toxicity record in Duke's is *required* to carry one, enforced when the catalogue is built. Never eat or use a plant on the strength of this app.

**Your photos can break.** If you delete a photo from your gallery, the entry stays caught and shows its thumbnail with an offer to re-link. A photo that lives only in Google Photos' cloud and hasn't downloaded may not resolve until you're online. Turning on *Keep a local copy* in Settings makes future registrations immune to this, at the cost of storing the photo twice; it is off by default because linking rather than copying is the point.

**Backups matter more than usual here**, precisely because photos are referenced. See below.

---

## Building and installing

**Prerequisites:** JDK 17 (Temurin) and the Android SDK command-line tools with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`. No Android Studio and no system Gradle — the repo carries the Gradle 8.13 wrapper.

Create `local.properties` at the repo root (git-ignored, so absent from a fresh clone):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # build and install onto a connected phone
```

Debug is the only build type; there is no release configuration or signing story, because this is sideloaded onto one phone.

**To enable USB debugging:** on the phone, Settings → About phone → tap *Build number* seven times, then Settings → System → Developer options → **USB debugging**. Plug in with a data-capable cable and accept the "Allow USB debugging?" prompt. `adb devices` should read `device`, not `unauthorized`. Turning on **Stay awake** in the same menu saves a lot of unlocking.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/platform-tools"

./gradlew testDebugUnitTest          # 340 JVM tests, no device needed
./gradlew connectedDebugAndroidTest  # 43 instrumented tests, phone required
adb exec-out screencap -p > shot.png
```

> `connectedDebugAndroidTest` **uninstalls the app when it finishes.** If BioDex disappears from your phone after a test run, that is why — reinstall with `./gradlew installDebug`.

---

## Backups

`Settings → Export collection…` writes one ZIP and hands it to the share sheet:

```
manifest.json          species, entries, captures, and a report on every photo
thumbnails/<id>.jpg    every thumbnail the app owns
photos/<id>.jpg        a full-size copy of every photo whose reference resolved
```

**A photo whose gallery reference is already broken cannot be exported.** The bytes are gone from the device and no archive can invent them. The export reports the two cases separately: a *revoked* reference (you deleted the photo) will never export, while a *cloud-only or offline* one usually will if you try again with a connection. The thumbnail and every detail of the catch are in the archive either way, so the entry restores — only the full-size photograph is lost.

The manifest is written last, from the files that actually landed, so it can never name a photo the archive does not hold.

**Import merges, it never replaces.** It adds what is missing, skips capture ids it already has (so importing twice is a no-op), keeps the earlier catch date, and deletes nothing. Restored photos become local copies; no URI grant is recreated, because a grant from another phone means nothing here.

---

## The catalogue

`app/src/main/assets/catalogue/pacific.json` is generated and committed, so no build touches the network. It is built from three hand-authored input files plus four public sources:

| Source | Supplies | Licence |
|---|---|---|
| GBIF | accepted scientific name, kingdom, class, synonyms | open |
| Wikipedia | habitat prose, description, page link | CC BY-SA |
| Wikimedia Commons | reference image and its credit | per-image |
| Dr. Duke's (USDA ARS) | plant medicinal uses, activity list, poison flag | CC0 |

```bash
cd tools/catalogue
python3 build_catalogue.py --out ../../app/src/main/assets/catalogue/pacific.json
```

Standard library only — no virtualenv, no dependencies. Responses cache under `tools/catalogue/cache/`, so a re-run makes zero HTTP requests; `--refresh` bypasses it. See `tools/catalogue/README.md`.

Two rules the build enforces rather than trusting:

- **Every plant with a `Poison` record in Duke's must carry a `Caution:` sentence**, or the build fails naming the species. The cautioned set is decided by a public dataset, not by whoever wrote the entry.
- **A synonym is only accepted if it keeps the accepted name's specific epithet.** Without this, GBIF offers Port Orford cedar as a synonym of coast redwood, and the eastern sycamore for the California one — which would have shipped confident, fluent, completely wrong data.

Edible tags are curatorial judgement and are not derived from any source; the catalogue says so in each entry's provenance.

---

## Design documents

`DESIGN.md` is the product design — the domain model, the numbered requirements, and every product decision with its rationale and what was rejected. `ARCHITECTURE.md` is the technical design, including the slice map the build followed and a running deviation log recording every place the implementation departed from the plan and why. Both are current.

---

## State

340 JVM unit tests and 43 instrumented tests pass. The app runs on a Pixel 7 Pro (Android 17), where the full loop has been walked by hand: the catalogue imports, images load from Wikimedia, the picker attaches a gallery photo, registration unlocks a species, plant cautions render, and the user-added flow resolves live against GBIF and Wikipedia.

What that has **not** covered: a persisted URI grant surviving a reboot; a cloud-only Google Photos item that has never been downloaded; export producing a ZIP another app opens, and import reading one back; and the *Keep a local copy* path writing a file. Those want a real day of use rather than a test.

Bird-call playback was designed, built, and then removed once the app covered plants as well as animals — a call is meaningless for a fern. `ARCHITECTURE.md` section 12.1 records what went and why.
