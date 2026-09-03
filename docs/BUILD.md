# Building BioDex

Everything routine is a `make` target — `make` on its own lists them. This file covers the parts that are setup or judgement rather than a command: what you need installed, how release signing works, and how to get the app onto a phone.

```bash
make doctor     # check the toolchain and say what is missing
make check      # JVM + catalogue tests, no phone needed
make install    # build and install onto an attached phone
```

## Prerequisites

- **JDK 17** (Temurin). On macOS `make` finds it through `/usr/libexec/java_home -v 17`; elsewhere export `JAVA_HOME` yourself and the Makefile will use it.
- **Android SDK command-line tools**, with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`.

No Android Studio and no system Gradle — the repo carries the Gradle 8.13 wrapper.

Then create `local.properties` at the repo root, naming your SDK. It is git-ignored, so a fresh clone has none and nothing builds until you write it:

```properties
sdk.dir=/path/to/android-commandlinetools
```

`make doctor` checks all of this and names whatever is missing.

## Release signing

A debug APK is fine on your own phone but a poor thing to hand to anyone else: it is marked debuggable, and it is signed with your machine's throwaway `~/.android/debug.keystore`, so nobody can ever install an update built anywhere else. A release build fixes both.

Make a signing key once, **outside the repository**, and keep it somewhere you back up:

```bash
keytool -genkeypair -v -keystore ~/.android/biodex-release.jks -alias biodex \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` at the repo root (git-ignored, like `local.properties`):

```properties
storeFile=/path/to/your-release.jks
storePassword=…
keyAlias=biodex
keyPassword=…
```

```bash
make release    # app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the build still works and produces an unsigned APK, so a fresh clone is never blocked on a key you do not have.

> **That keystore is the app's identity for good.** Android will only accept an update signed by the same key. Lose it and everyone with the app installed has to uninstall it — losing their collection — before they can install anything you build afterwards. It lives outside git on purpose, which means git is not backing it up for you.

Code shrinking (R8) is deliberately switched off for release. Every library here ships its own keep rules so it would most likely work, but the way it fails is silent — a stripped serializer breaks the *add your own species* fetch or a backup import at run time, with nothing wrong at build time. `app/proguard-rules.pro` carries the rules; turning it on is a change to make with a phone in hand and those two paths exercised.

## Installing without building

Sideloading an APK does **not** require developer options or USB debugging — that is only needed for `adb`. Copy the APK to the phone, tap it, and allow installs from whichever app you opened it with when Android asks.

A debug and a release APK cannot coexist or replace each other, so uninstall one before installing the other.

## Working on the app over USB

On the phone: Settings → About phone → tap *Build number* seven times, then Settings → System → Developer options → **USB debugging**. Plug in with a data-capable cable and accept the "Allow USB debugging?" prompt. `adb devices` should read `device`, not `unauthorized`. Turning on **Stay awake** in the same menu saves a lot of unlocking.

```bash
make install        # build and install
make test-device    # instrumented tests on the phone
make screenshot     # writes shot.png
```

> `make test-device` **uninstalls the app when it finishes.** If BioDex disappears from your phone after a test run, that is why — `make install` puts it back.

## Rebuilding the catalogue

The bundled catalogue asset is generated and committed, so no ordinary build touches the network:

```bash
make catalogue        # rebuild app/src/main/assets/catalogue/pacific.json
make catalogue-test   # the pipeline's own tests
```

Responses cache under `tools/catalogue/cache/` (git-ignored), so a re-run makes zero HTTP requests; pass `--refresh` to `build_catalogue.py` to bypass the cache. A cold run is slow and hits four public APIs. See `tools/catalogue/README.md`.

One trap worth knowing: the catalogue asset is **not** an input to the Gradle test task, so after changing it `make test` can report success off stale results. `make check` uses `--rerun-tasks` for exactly this reason.
