# Animal Dex

A personal Android app that turns a real-world wildlife life list into a Pokédex: a curated Pacific-region catalogue of species, each starting as a silhouette and unlocking when you register a photo of it from your phone's gallery. Photos stay in the gallery and are referenced, never copied; progress is tracked per ecosystem and per taxonomic class.

Design docs: `DESIGN.md` (product) and `ARCHITECTURE.md` (technical, including the slice map in section 9).

## Prerequisites

- **JDK 17** (Temurin). Check with `java -version`; anything newer is untested and AGP 8.x expects 17.
- **Android SDK** at `/opt/homebrew/share/android-commandlinetools`, with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools` installed and licenses accepted.
- No system Gradle and no Android Studio are needed — the repo carries the Gradle 8.13 wrapper.

Create `local.properties` at the repo root (it is git-ignored, so it is not in a fresh clone):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
# Optional. Without it the app treats Xeno-canto as "no call found" (ARCHITECTURE.md 5.4).
xc.api.key=<your Xeno-canto API key>
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
