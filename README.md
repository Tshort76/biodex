# BioDex

A personal Android app that turns a real-world life list into a Pokédex. It ships with a curated catalogue for one region — the **Pacific USA BioDex**, everything west of the Rocky Mountains — holding 120 animals, 80 plants and 30 fungi. Every species starts as a silhouette and unlocks when you photograph it yourself.

> **This is intended as a fun activity, and nothing in it should be construed as medical or dietary advice.** Exercise caution and do your own research on plants and animals before engaging with them.

Your photos stay in your gallery. For an animal or a fungus the app stores a reference and a small thumbnail, never a copy; a plant keeps no photograph at all, and its entry shows the catalogue's own reference picture instead.

**Identification is opt-in, and only for plants.** Nothing is uploaded unless you press *Identify* on a photo you attached. When you do, a reduced copy of that one photo — re-encoded, so its EXIF and its GPS coordinates are gone — goes to the Pl@ntNet API, which sends back candidate species you choose from. The app never picks one for you and never claims the thing in your photo *is* a species. Animals and fungi have no identification at all: if you don't know what you're looking at, use Google Lens and type the name in.

---

## Using it

**Catching something.** Tap the **+** button on the grid, or open a species and tap *Register this species*. Search by name, pick the species, attach a photo from your gallery, and register. The species flips from silhouette to your photograph and the counter ticks up. Photograph the same species again and the picture joins its strip without ceremony — the fanfare is reserved for firsts.

The gallery picker needs an explicit **Done** tap after you select a photo. Selecting alone returns nothing.

**Something not in the catalogue.** Type its name and choose *add your own species*. GBIF resolves the name to a real species, Wikipedia supplies habitat text and a photograph, and for a plant Duke's ethnobotanical database supplies its recorded medicinal uses. You get a confirmation card before anything is saved, because a name like "sparrow" matches several species and a silent wrong pick would be permanent. Your own species get **U-numbers** and sit outside the completion fraction, so they never make the dex unfinishable.

Offline, the entry is created immediately from the name and photo alone and filled in the next time you open it with a connection.

**Filtering.** The chip row composes rather than replaces: *Plants* + *Food source* + *Riparian & Wetland* narrows to exactly that. Search matches common and scientific names.

**Plants have a uses section** where an animal has nothing: a short note on which part and which season, and a muted line recording what Duke's holds. Any plant Duke's records as toxic carries a one-line caution, and the build fails if one is missing — so which plants get a warning is decided by a public dataset rather than by whoever wrote the entry.

**Fungi carry no uses**, no medicinal line and no identification. A mushroom gets a note only when the species itself is dangerous, which is ten of the thirty. The rest read like an animal.

**Your photos can break.** If you delete a photo from your gallery, the entry stays caught and shows its thumbnail with an offer to re-link. A photo that lives only in Google Photos' cloud and hasn't downloaded may not resolve until you're online. Turning on *Keep a local copy* in Settings makes future registrations immune to this, at the cost of storing the photo twice; it is off by default because linking rather than copying is the point.

**Backups matter more than usual here**, precisely because photos are referenced. `Settings → Export collection…` writes one ZIP — the catalogue, every entry, every thumbnail, and a full-size copy of each photo whose reference still resolves — and hands it to the share sheet. Import merges rather than replaces: it adds what is missing, skips catches it already has, and deletes nothing.

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

### Building a release APK

The debug APK is fine on your own phone but a poor thing to hand to anyone else: it is marked debuggable, and it is signed with your machine's throwaway `~/.android/debug.keystore`, so nobody can ever install an update built anywhere else. A release build fixes both.

You need a signing key. Make one once, **outside the repository**, and keep it somewhere you back up:

```bash
keytool -genkeypair -v -keystore ~/.android/biodex-release.jks -alias biodex \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` at the repo root (git-ignored, like `local.properties`):

```properties
storeFile=/Users/you/.android/biodex-release.jks
storePassword=…
keyAlias=biodex
keyPassword=…
```

```bash
./gradlew assembleRelease   # APK at app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the build still works and produces an unsigned APK, so a fresh clone is never blocked on a key you don't have.

> **That keystore is the app's identity for good.** Android will only accept an update signed by the same key. Lose it and everyone with the app installed has to uninstall it — losing their collection — before they can install anything you build afterwards. It lives outside git on purpose, which means git is not backing it up for you.

Code shrinking (R8) is deliberately switched off for release. Every library here ships its own keep rules so it would most likely work, but the way it fails is silent — a stripped serializer breaks the *add your own species* fetch or a backup import at run time, with nothing wrong at build time. `app/proguard-rules.pro` carries the rules; turning it on is a change to make with a phone in hand and those two paths exercised.

### Installing it without building it

Sideloading an APK does **not** require developer options or USB debugging — that is only needed for `adb`. Copy the APK to the phone, tap it, and allow installs from whichever app you opened it with when Android asks. A debug and a release APK cannot coexist or replace each other, so uninstall one before installing the other.

### Working on the app over USB

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

## License

Code is MIT. The bundled catalogue is CC BY-SA 4.0, because it reuses Wikipedia prose, with GBIF (CC BY 4.0) and Dr. Duke's (CC0) underneath it and per-image Commons credits carried in each entry. `LICENSE` has the split in full, and the app shows the same thing at *Settings → Licenses and attribution*.
