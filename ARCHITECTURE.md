# BioDex (formerly Animal Dex) — Technical Architecture (v1 + the BioDex expansion)

Companion to `DESIGN.md` (product requirements, approved; v4 adds plants and the BioDex naming) and `mockup.html` (visual design, approved). Sections 1–10 are the v1 design plus the deviation log eight slices appended; section 11 designs the BioDex expansion on top of what was actually built. This document tells the implementing agents every cross-cutting decision so no slice has to invent one. Written 2026-09-01; every version number below was verified against release pages on that date.

---

## 1. Stack and versions, pinned

### 1.1 The build toolchain

| Component | Version | Why this one |
|---|---|---|
| JDK | 17 (Temurin, already installed) | Required by AGP 8.x. Do not upgrade to 21; nothing needs it. |
| Gradle wrapper | **8.13** | The minimum and default for AGP 8.13. The project carries `gradlew`; there is no system Gradle. |
| Android Gradle Plugin | **8.13.2** | Supports compileSdk 36 (max API 36.1), requires Gradle 8.13 and JDK 17, and its bundled R8 supports Kotlin 2.3. |
| Kotlin | **2.3.10** | AGP 8.13.2 explicitly supports Kotlin 2.3. |
| Compose compiler plugin | **2.3.10** (`org.jetbrains.kotlin.plugin.compose`) | Since Kotlin 2.0 the Compose compiler ships with Kotlin and its version must equal the Kotlin version. There is no separate `composeCompilerVersion` anymore — do not set `composeOptions.kotlinCompilerExtensionVersion`. |
| kotlinx-serialization plugin | **2.3.10** (`org.jetbrains.kotlin.plugin.serialization`) | Same rule: matches the Kotlin version. |
| KSP | **2.3.11** (`com.google.devtools.ksp`) | KSP versioning was decoupled from the Kotlin version at KSP 2.3.0, so the version string is plain (`2.3.11`), not the old `<kotlin>-<ksp>` pair. If plugin resolution fails with this id, check https://github.com/google/ksp/releases before improvising. |
| compileSdk / targetSdk | **36** | Matches the installed `platforms;android-36` and `build-tools;36.0.0`. |
| minSdk | **29** | See 1.3. |

**Decision: AGP 8.13.2, not AGP 9.x.** The current AGP is 9.4.0, but AGP 9 requires Gradle 9.6, builds Kotlin support into AGP itself, and changes the variant DSL — a migration guide exists because the changes are breaking. Nothing in this app needs any of it, and the 8.x DSL is what every reference and every implementer's prior knowledge matches. Rejected: AGP 9.x (churn with no payoff for a debug-only personal app).

### 1.2 Libraries

All declared once in `gradle/libs.versions.toml` (the version catalog). No dependency is declared with an inline version string anywhere in a build file.

| Library | Coordinates | Version |
|---|---|---|
| Compose BOM | `androidx.compose:compose-bom` | **2026.08.00** (carries ui/foundation 1.12.0, material3 1.4.0) |
| Material 3 | `androidx.compose.material3:material3` | from BOM |
| Activity Compose | `androidx.activity:activity-compose` | **1.13.0** |
| Navigation Compose | `androidx.navigation:navigation-compose` | **2.10.0** |
| Lifecycle | `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | **2.11.0** |
| Room | `androidx.room:room-runtime`, `room-ktx`; `room-compiler` via KSP | **2.8.4** (the stable 2.x line; Room 3 is alpha — do not use it) |
| Coil | `io.coil-kt.coil3:coil-compose`, `io.coil-kt.coil3:coil-network-okhttp` | **3.5.0** (Coil 3 needs the network artifact declared explicitly or remote URLs silently fail) |
| OkHttp | `com.squareup.okhttp3:okhttp` | **4.12.0** (shared by Coil and the API clients) |
| kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.9.0** |
| kotlinx-coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.10.2** |
| ExifInterface | `androidx.exifinterface:exifinterface` | **1.4.2** (see 12.1) |
| Test: JUnit | `junit:junit` | 4.13.2 |
| Test: AndroidX test | `androidx.test.ext:junit` 1.3.0, `androidx.test:runner` 1.7.0 | instrumented only |

**Decision: plain OkHttp + kotlinx-serialization, no Retrofit.** There are three GET endpoints in the whole runtime app. Retrofit would add a library, an annotation processor surface, and a converter for less code than it saves. Rejected: Retrofit, Ktor client.

**Decision: no dependency-injection framework.** A single hand-written `AppContainer` object created in the `Application` class holds the database, the OkHttp client, the repositories, and the media cache. ViewModels get dependencies through a small `viewModelFactory`. Rejected: Hilt (adds KAPT/KSP config, an annotation vocabulary, and build time for a project with about six injectable objects).

### 1.3 minSdk 29, justified

The app targets exactly one physical phone the user owns, which is recent. minSdk 29 (Android 10) buys:

- `ContentResolver.loadThumbnail()` for cheap thumbnail generation from any content URI.
- Scoped storage semantics from day one — no legacy external-storage branches.
- No runtime storage permission at all: the photo picker and persistable URI grants need none.

The picker story is unaffected: `ActivityResultContracts.PickVisualMedia` uses the system Photo Picker on Android 13+ and transparently falls back to `ACTION_OPEN_DOCUMENT` below that; both paths return a URI on which `takePersistableUriPermission` works. So minSdk 29 loses nothing the design needs. Rejected: minSdk 26 (adds branches nobody will ever run), minSdk 33 (needless — the backport is free).

### 1.4 Build variants and signing

Debug build only, installed with `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or `./gradlew installDebug` with the phone connected). The default debug keystore is generated automatically. No release build type configuration, no minification (`isMinifyEnabled = false` everywhere), no Play Store metadata. `local.properties` carries `sdk.dir=/opt/homebrew/share/android-commandlinetools` and is git-ignored.

### 1.5 Version corrections (recorded by slice 1, 2026-09-01)

Slice 1 built the stack for real. Everything in 1.1 held: Gradle 8.13, AGP 8.13.2, Kotlin 2.3.10, the matching Compose and serialization compiler plugins, and KSP `2.3.11` all resolved and compiled with no change. Room 2.8.4, Coil 3.5.0, OkHttp 4.12.0, kotlinx-serialization 1.9.0, kotlinx-coroutines 1.10.2 and activity-compose 1.13.0 also resolved as pinned, so risk R1's two named worries (the KSP plugin id and serialization 1.9.0) did not materialize.

Three androidx versions in 1.2 did not. Each of them declares a hard floor of **AGP 9.1.0 and compileSdk 37**, which `assembleDebug` enforces as a build failure, not a warning. Rather than move the whole toolchain to AGP 9 (the decision in 1.1 rejects that deliberately, and API 37 is not installed on the build machine), each library steps back to the newest release that builds against AGP 8.13.2 / compileSdk 36. The version catalog carries the same note at each entry.

| Library | Planned | Actual | Reason |
|---|---|---|---|
| Compose BOM | 2026.08.00 | **2026.06.01** | 2026.08.00 carries Compose 1.12.0, which requires AGP 9.1+ / compileSdk 37. 2026.06.01 carries Compose 1.11.4 and Material 3 in the same line. |
| Navigation Compose | 2.10.0 | **2.9.8** | Same AGP 9.1+ / compileSdk 37 floor. Type-safe `@Serializable` route objects have shipped since Navigation 2.8, so section 6.1's routing design is unaffected. |
| Lifecycle | 2.11.0 | **2.10.0** | Same AGP 9.1+ / compileSdk 37 floor. `collectAsStateWithLifecycle()` and the Compose ViewModel helpers behave identically. |

One further detail worth knowing rather than correcting: `androidx.exifinterface` was pinned at 1.4.1 while Media3 pulled 1.4.2, so Gradle resolved the graph to 1.4.2. When the call feature took Media3 with it (12.1) the pin moved to 1.4.2, so the resolved graph is unchanged and nothing was silently downgraded.

Any later slice that wants to move to AGP 9 must move Gradle to 9.6, install `platforms;android-37`, and re-verify — a deliberate decision, not a drive-by upgrade.

---

## 2. Module and package layout

**Decision: one Gradle module.** A personal app with one developer-agent at a time per slice gains nothing from module boundaries except slower builds and more build files to keep in sync. Package discipline substitutes for module discipline. Rejected: `:core`/`:data`/`:ui` split.

```
pokedex-animals/
├── settings.gradle.kts              # root project + :app
├── build.gradle.kts                 # plugin declarations (apply false)
├── gradle.properties                # jvmargs, AndroidX flags
├── gradle/
│   ├── libs.versions.toml           # every version in section 1, nowhere else
│   └── wrapper/                     # gradle-wrapper.jar + properties (8.13)
├── gradlew / gradlew.bat
├── tools/
│   └── catalogue/                   # build-time pipeline, pure Python, no app coupling (section 7)
│       ├── curated_species.json     # the 120 names + ecosystem tags (human-curated input)
│       ├── build_catalogue.py       # fetch + assemble script
│       ├── cache/                   # HTTP response cache, git-ignored
│       └── README.md                # how to run, where the XC API key comes from
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   ├── catalogue/pacific.json       # pipeline output: the 120 species
        │   │   └── silhouettes/<species-id>.svg # not used at runtime; see below
        │   ├── res/
        │   │   ├── drawable/                    # silhouette vectors as VectorDrawable XML
        │   │   └── values/                      # app name, launcher icon
        │   └── kotlin/dev/tlong/animaldex/
        │       ├── App.kt                       # Application subclass; owns AppContainer
        │       ├── AppContainer.kt              # hand-wired singletons (db, http, repos, caches)
        │       ├── MainActivity.kt              # single activity, sets the NavHost
        │       ├── data/
        │       │   ├── db/                      # Room: AppDatabase, entities, DAOs, converters
        │       │   ├── catalogue/               # asset JSON models + first-run/upgrade importer
        │       │   ├── photo/                   # picker handling, grant persistence, thumbnails,
        │       │   │                            #   URI resolution states, local-copy writer
        │       │   ├── net/                     # GbifClient, WikipediaClient
        │       │   └── repo/                    # DexRepository, SpeciesLookupRepository
        │       ├── domain/                      # plain models the UI consumes (Species, Entry,
        │       │                                #   Capture, DexProgress) + mapping from entities
        │       ├── ui/
        │       │   ├── theme/                   # Color.kt, Type.kt, Theme.kt (section 6.4)
        │       │   ├── nav/NavGraph.kt          # all routes, one file
        │       │   ├── common/                  # shared composables: meters, chips, SpeciesCell,
        │       │   │                            #   AttributionLine, PhotoThumb (broken-state aware)
        │       │   ├── grid/                    # DexGridScreen + DexGridViewModel
        │       │   ├── detail/                  # EntryDetailScreen + ViewModel
        │       │   ├── register/                # RegisterScreen + ViewModel
        │       │   ├── addspecies/              # ConfirmCardScreen + ViewModel (user-added flow)
        │       │   ├── reveal/                  # UnlockRevealOverlay (composable, not a route)
        │       │   ├── photoviewer/             # PhotoViewerScreen + ViewModel
        │       │   ├── stats/                   # StatsScreen + ViewModel
        │       │   └── settings/                # SettingsScreen + ViewModel, export/import
        │       └── media/                       # Coil image loader, image cache, network monitor
        ├── test/                                # JVM unit tests (section 8)
        └── androidTest/                         # instrumented tests (section 8)
```

Silhouettes: the pipeline does not produce them (no API supplies clean species silhouettes). v1 ships **one generic silhouette per taxonomic class** (bird, mammal, reptile, amphibian, fish, insect, other invertebrate) as hand-drawn VectorDrawables in `res/drawable/`, in the style of the mockup's SVG shapes. `Species.silhouetteAsset` in the catalogue names a drawable; every curated species maps to its class silhouette unless a per-species one is added later. This is a deliberate scope cut: 120 bespoke silhouettes is an art project, not v1 engineering, and the schema already supports upgrading them species-by-species.

---

## 3. Data layer

### 3.1 Room schema

Database `AppDatabase`, name `animaldex.db`, `version = 1`, `exportSchema = true` with the schema JSON checked into `app/schemas/` (add the `room.schemaLocation` KSP arg). All DAOs expose `Flow` for reads and `suspend` for writes.

**species** — one row per species, curated and user-added alike.

| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | Curated: slug from the pipeline (`western-screech-owl`). User-added: `user-<UUID>`. |
| regionId | TEXT | `pacific` |
| dexNumber | INTEGER | Curated: 1–120. User-added: 1001, 1002, … (rendered as U01, U02 — presentation subtracts 1000). Keeps one sortable integer column. |
| source | TEXT | `curated` \| `user` (enum via converter) |
| detailsPending | INTEGER (bool) | user-added only (M20) |
| commonName | TEXT | |
| scientificName | TEXT nullable | |
| taxClass | TEXT | enum: bird / mammal / reptile / amphibian / fish / insect / other_invertebrate |
| habitatText | TEXT nullable | |
| description | TEXT nullable | |
| imageUrl | TEXT nullable | |
| infoUrl | TEXT nullable | |
| imageAttribution | TEXT nullable | pre-formatted credit line, e.g. `Wikimedia Commons · CC BY-SA 4.0 · J. Doe` |
| silhouetteRes | TEXT | drawable resource name (`sil_bird`); resolved with `getIdentifier` once, cached |
| userEditedFields | TEXT | JSON array of field names the user hand-edited; empty for curated (M21) |

Indices: `(regionId, dexNumber)` unique; `commonName`; `scientificName`.

**ecosystems** — the seven Pacific ecosystems, seeded from the catalogue asset. Columns: `id` TEXT PK (`coastal-rainforest`), `regionId`, `name`, `sortOrder` INTEGER.

**species_ecosystems** — join table. Columns: `speciesId` TEXT, `ecosystemId` TEXT, composite PK, index on `ecosystemId`, FKs to both parents with `ON DELETE CASCADE`.

**entries** — zero or one per species. Columns: `speciesId` TEXT PK (FK → species), `caughtAt` INTEGER (epoch millis), `favoriteCaptureId` TEXT nullable. `captureCount` is *not* stored — it is a `COUNT(*)` in the DAO query (DESIGN.md marks it derived; storing it invites drift).

**captures** — one per registration event.

| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID string |
| speciesId | TEXT FK → species, indexed | |
| photoUri | TEXT | the persisted content URI, stored as `uri.toString()` |
| thumbPath | TEXT | relative path under `filesDir`, e.g. `thumbnails/<id>.jpg` — relative so a backup/restore to a different absolute `filesDir` still resolves |
| localCopyPath | TEXT nullable | relative path under `filesDir/photos/` when "keep a local copy" is on |
| takenAt | INTEGER | epoch millis; EXIF else registration time |
| lat / lng | REAL nullable | EXIF only (see risk R6: usually absent) |
| locationLabel | TEXT nullable | |
| note | TEXT nullable | |
| createdAt | INTEGER | registration time; `isFirst` is derived (`MIN(createdAt)` per species), not stored |

**meta** — single-row key/value table (`key` TEXT PK, `value` TEXT). Keys: `catalogueVersion` (integer as text), `schemaSeededAt`. This is what the importer compares against the asset.

Type converters: enums ⇄ TEXT by name; `List<String>` ⇄ JSON via kotlinx-serialization (used only for `userEditedFields`). Timestamps stay as raw `Long` epoch millis — no Date converter, no time-zone ambiguity.

**Migration stance for v1:** `exportSchema = true` from the first commit, schemas in git, and **no** `fallbackToDestructiveMigration` — the user's life list must never be wiped by an upgrade. While the app is pre-first-install-on-the-phone, agents may bump the schema freely by uninstalling; the moment real captures exist on the user's phone (end of slice 5), every schema change ships a real `Migration`. Write migrations by hand; there is no Room auto-migration wired (auto-migrations are fine to add for simple column adds, using the checked-in schemas).

### 3.2 The bundled catalogue asset

**Decision: JSON in `assets/catalogue/pacific.json`, imported into Room by the app, not a prepopulated database file.** The clinching reason: Room's `createFromAsset` applies only on fresh install, so a later catalogue update (new app version, revised habitat text, species added) needs an importer that reconciles anyway. One mechanism handles both first run and every upgrade. JSON is also what the pipeline naturally emits, is diffable in git, and avoids coupling the pipeline to Room's binary format. Rejected: prepopulated `.db` asset (two mechanisms, opaque diffs), parsing the JSON on every launch instead of importing (makes joins and filtered queries against captures awkward).

Asset shape (kotlinx-serialization models in `data/catalogue/`):

```json
{
  "catalogueVersion": 1,
  "regionId": "pacific",
  "regionName": "Pacific",
  "ecosystems": [
    { "id": "coastal-rainforest", "name": "Coastal Rainforest", "sortOrder": 1 }
  ],
  "species": [
    {
      "id": "western-screech-owl",
      "dexNumber": 21,
      "commonName": "Western Screech-Owl",
      "scientificName": "Megascops kennicottii",
      "taxClass": "bird",
      "ecosystemIds": ["oak-chaparral", "riparian-wetland", "urban-suburban"],
      "habitatText": "Low-elevation woodlands, streamside groves and suburban parks…",
      "description": "…",
      "imageUrl": "https://upload.wikimedia.org/…",
      "infoUrl": "https://en.wikipedia.org/wiki/Western_screech_owl",
      "imageAttribution": "Wikimedia Commons · CC BY-SA 4.0 · <author>",
      "silhouetteRes": "sil_bird",
      "provenance": { "scientificName": "gbif", "habitatText": "wikipedia:section", "...": "..." }
    }
  ]
}
```

`provenance` is carried in the asset for auditability but not imported into Room — the app does not use it.

### 3.3 Import and reconciliation

`CatalogueImporter` runs on `Application` start, on a background dispatcher, before the grid's flow emits (the grid shows its normal loading state; import of 120 rows takes well under a second):

1. Parse the asset header. If `meta.catalogueVersion` equals the asset's, do nothing.
2. Otherwise, in **one transaction**: upsert every ecosystem row; upsert every curated species row **by id**; replace the `species_ecosystems` rows *for curated species only*; write the new `catalogueVersion`.

Invariants the importer must hold (these are the M-requirements' data-safety core, and slice 3's unit tests):

- **Import never touches `entries`, `captures`, or any species row with `source = 'user'`.** The user's data and user-added species are structurally out of reach — the importer iterates the asset, and the asset contains only curated species.
- A curated species removed from a future asset is **not deleted** if it has an entry; it is kept as-is (orphaned rows without entries may be deleted). Deleting a caught species would destroy captures via cascade.
- `userEditedFields` on curated species is always empty in v1 (curated species are not editable, per DESIGN.md §7), so upsert may overwrite all curated fields wholesale. The field exists on the row for user-added species and for C06 promotion later.
- Species promotion (C06) is out of v1 scope; the importer does not attempt to match user-added species against new curated ones.

### 3.4 Data-layer corrections (recorded by slice 3, 2026-09-01)

Slice 3 built the schema, the importer and the repository for real. Section 3.1's tables, columns, indices and foreign keys are implemented exactly as written, `exportSchema = true` produces `app/schemas/dev.tlong.animaldex.data.db.AppDatabase/1.json` (checked in), and there is no `fallbackToDestructiveMigration` anywhere. Four things needed a decision the document did not make.

| Point | What slice 3 did | Reason |
|---|---|---|
| Progress math (6.3) | `DexRepository.dexProgress()` reads four small row flows and computes `DexProgress` in a pure Kotlin function (`domain/DexProgressMath.kt`), rather than in SQL aggregates. | Section 8 requires progress math to be a JVM unit test, and SQL aggregates can only be exercised on a device — the two instructions are in tension, and no phone is available to run the instrumented suite. At 120-plus rows the in-memory pass is free; it is the same reasoning section 6.2 already applies to search and filters. |
| `entries.favoriteCaptureId` | Nullable, **no foreign key**. | `captures` references `species` and an entry would reference a capture, which makes an insert-order cycle Room rejects. The consequence is explicit: whoever deletes a capture must null this column itself (slice 5's job), or it dangles. |
| Importer structure | Every decision the importer makes is a pure function, `CatalogueReconciler.decide()`, returning an `ImportPlan` that a `CatalogueStore` applies in one transaction. Room implements the store; the JVM tests use an in-memory fake that reproduces `ON DELETE CASCADE`. | 3.3's invariants are about data safety, not about Room. Splitting them out is what lets "a catalogue update never destroys entries, captures or user-added species" be a unit test rather than a claim. |
| Missing catalogue asset | The importer logs and returns `AssetMissing`, leaving the database empty; it never throws. Section 9's suggestion of a stand-in `pacific.json` was **not** taken. | Slice 2 owns `app/src/main/assets/catalogue/pacific.json` and was generating it concurrently; writing a stand-in would have clobbered live output. Slice 3's tests run against a ten-species fixture in `app/src/test/resources/` and `app/src/androidTest/assets/` instead, and the instrumented test that asserts 120 species skips itself until the real asset is bundled. |

Two build-file additions, both mechanical: the `room.schemaLocation` KSP argument (3.1 asks for it) and `testOptions.unitTests.isReturnDefaultValues = true`, without which any JVM test reaching an `android.util.Log` call dies on "not mocked". The version catalog was not touched — the importer tests use `runBlocking` rather than adding `kotlinx-coroutines-test`.

---

### 3.5 Export and import (recorded by slice 8, 2026-09-01)

S01 had no design section before this one — section 9's slice brief is its whole specification ("ZIP via `ShareSheet`: metadata JSON + thumbnails + resolvable full-size copies") — so everything here is a decision slice 8 made, not a deviation from one.

**The archive.** `manifest.json` at the root, `thumbnails/<captureId>.jpg`, `photos/<captureId>.jpg`. A plain ZIP any file manager opens, with no app-specific container, because the point of a backup is that it outlives the thing that wrote it. Written to `cacheDir/exports/` and shared as a `content://` URI through a `FileProvider` (`${applicationId}.files`, `res/xml/file_paths.xml` exposing that one directory and nothing else).

| Point | What slice 8 did | Reason |
|---|---|---|
| **The manifest is written last, from what landed** | The export plans optimistically, writes every photo it can, collects the entry names that actually succeeded, and only then builds the manifest — filtering every `thumbEntry`/`photoEntry` through that set. | A reference can resolve at plan time and fail while its bytes are copying. Building the manifest from intent would produce an archive that names photos it does not contain, which is the exact failure S01 exists to prevent: the user believes they have a backup. The invariant "the manifest never names a file the archive does not hold" is true by construction and is the slice's most important unit test. |
| Photo bytes are buffered before the entry is opened | `writeEntry` reads the whole file/stream into memory, then opens the `ZipEntry` and writes it. | A `ZipOutputStream` entry cannot be un-opened. Streaming straight through would leave a truncated zero-byte `photos/<id>.jpg` behind on failure — a file a human browsing the archive would read as a photo. One image in memory at a time is the cost. |
| Unexportable photos are reported by **reason**, not as one number | `PhotoDisposition` carries `MISSING_REVOKED` / `MISSING_OFFLINE` / `MISSING_UNREADABLE`, and the Settings copy says which is which. | 4.2's two broken states mean different things to the user: a revoked reference will never export (the gallery photo is gone), a cloud-only one usually will on the next try with a connection. Collapsing them would tell the user their photos are lost when half of them are one retry away. |
| Curated species export as identity only; an import never creates one | The manifest carries every curated species' id, number and name, but `planImport` inserts **only** user-added species. Captures of a curated species this install does not have are skipped and counted. | The bundled asset owns curated rows (3.3). Letting an archive create one would put a species in the database that the next catalogue import cannot reconcile, and would let a file talk the app into inventing catalogue entries. `BackupSpecies.toEntity` hard-codes `source = USER` for the same reason. |
| Import merges, never replaces | Existing species, entries and captures are left untouched; capture ids already present are skipped (so a second import of the same archive is a no-op); an existing entry keeps its identity and its favorite and only takes an **earlier** `caughtAt`. | "Restores onto a fresh install without destroying anything already there" is the brief. Idempotency falls out of the capture-id skip, which is what makes a re-import safe rather than merely non-destructive. |
| Restored photos become **local copies**, and no grant is recreated | A restored `photos/<id>.jpg` is written to `filesDir/photos/` and set as `localCopyPath`; the archived `photoUri` is kept for provenance only and never passed to `takePersistableUriPermission`. | 4.5 already names import as the second writer of `localCopyPath`. A grant belonging to another phone's gallery cannot be held here, and resolution short-circuits to the local copy (4.2) so the archived URI is never probed. A capture whose photo was not in the archive restores as thumbnail-plus-`Revoked`, which M12 already handles. |
| Rows are written after files, and only for files that arrived | `withRestoredFiles` recomputes the plan from the set of entries actually extracted, dropping `localCopyPath` for any photo that failed. | The same ordering rule registration follows (4.1 step 3): nothing in the database may name a file that is not on disk. |
| A restored `favoriteCaptureId` is validated | Nulled when the capture it names is not present after the merge. | `entries.favoriteCaptureId` has no foreign key (3.4), so nothing else would stop it dangling. The DAO's status query then falls back to the earliest capture. |
| The platform seam | `BackupGateway` (resolve, owned-file read/write, gallery open, archive open, export sink, share URI) with `AndroidBackupGateway` as its only implementation. | The pattern of 3.4, 4.6 and 5.6. It is what lets a **real** export run through a real `ZipOutputStream` and be imported back in the JVM suite against an in-memory filesystem — the round trip is a test, not a claim. |

**Two DAO additions**, both `@Query` methods on existing DAOs (the rule slice 5 established for `countForUri`): `EntryDao.entriesOnce`, `CaptureDao.capturesOnce` and `CaptureDao.captureIdsOnce`. No entity, no column, no schema change; the checked-in schema JSON is unchanged.

---

## 4. The photo-reference layer

All of this lives in `data/photo/`. The public surface is one class, `PhotoStore`, plus a `PhotoRef` resolution result type.

### 4.1 Taking and persisting a reference

Registration launches `ActivityResultContracts.PickVisualMedia` (image-only, single select) from the Register screen. On result:

1. `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` — wrapped in try/catch: Photo Picker URIs on some versions do not offer a persistable grant and throw `SecurityException`. If persisting fails, the app proceeds anyway (the picker's grant lasts long enough to generate the thumbnail; the stored thumbnail is the durable fallback M11/M12 already require, so a non-persistable grant degrades to "thumbnail always, full photo while the grant lives").
2. Read EXIF (`ExifInterface` over an opened `InputStream`): `DateTimeOriginal` → `takenAt` (fallback: now); GPS → `lat`/`lng` if present (it usually will not be — see risk R6).
3. Generate the thumbnail (4.3) **before** the registration transaction commits, so a capture row never exists without its thumbnail.
4. Insert the capture (and entry, if first) in one transaction.

### 4.2 Resolving and rendering, and the two broken states

Full-size rendering (Photo Viewer, detail hero when showing an own photo) goes through `PhotoStore.resolve(capture): PhotoRef`, which returns one of three states — implementers must keep the distinction, because M12's UX differs:

- **`Available(uri)`** — `contentResolver.openInputStream` succeeded (opened and immediately closed as a probe, or simply handed to Coil, which loads content URIs natively).
- **`Revoked`** — opening threw `SecurityException` (grant revoked, photo deleted from the gallery). Permanent. UI: stored thumbnail, "Full photo unavailable" banner, **Re-link photo** button. Re-linking launches the picker again, replaces `photoUri`, regenerates the thumbnail, releases the old grant.
- **`Unavailable`** — opening threw `FileNotFoundException`/`IOException` while the grant is intact — typically a cloud-only Google Photos item with the device offline. Transient. UI: stored thumbnail, "Photo is in the cloud — connect to load it". **No** re-link prompt; retry on next view.

If a capture has `localCopyPath`, resolution short-circuits to the local file and none of this applies.

The grid and photo strips **never** resolve gallery URIs (M11): they render `thumbPath` files only. URI resolution happens only on explicit full-size views, so a broken gallery reference can never blank the collection.

### 4.3 The thumbnail cache

Not a cache — a permanent, app-owned artifact created at registration (it is the only rendering of the capture the app is guaranteed to keep). Location: `filesDir/thumbnails/<captureId>.jpg` (internal storage — survives everything except app uninstall or user data-clear; included in S01 export). Generation: decode via `ImageDecoder` with a target of **640 px on the long edge**, save as JPEG quality 85 (~50–150 KB; 5,000 captures ≈ well under 1 GB, realistically a few hundred captures ≈ tens of MB). Keyed by capture id, so re-linking a photo overwrites the same path. Deleted when its capture is deleted (S07).

### 4.4 The 5,000-grant cap

One sentence of policy, not an engineering subsystem: **every code path that removes or replaces a capture's URI calls `releasePersistableUriPermission` on the old URI** (in a try/catch — releasing an already-gone grant throws). With one grant per capture and release-on-delete, a personal life list cannot approach 5,000. Settings shows the current count (`contentResolver.persistedUriPermissions.size`) under cache management, purely informational. Rejected: proactive grant-pool management, LRU grant eviction (solving a problem a single user cannot have).

### 4.5 "Keep a local copy" (S03, default off)

A boolean in Jetpack-free `SharedPreferences` (`settings` file; this app does not need DataStore for three booleans — decision: SharedPreferences, rejected DataStore as ceremony). When on, registration additionally streams the full-size bytes to `filesDir/photos/<captureId>.jpg` and sets `localCopyPath`. Turning the setting on later does **not** retroactively copy old captures in v1 (documented in the Settings UI copy). Import from an S01 archive sets `localCopyPath` for restored photos — that is the one other writer.

### 4.6 Photo-layer corrections (recorded by slice 5, 2026-09-01)

Slice 5 built the photo layer, the register flow, the unlock reveal and the photo viewer. Section 4's substance held: persistable grants taken on the picker result, no full-size copy by default, thumbnails written before the transaction, three resolution states, release-on-delete. Nine things needed a decision the document did not make, and one of them corrects an assumption 4.4 makes.

| Point | What slice 5 did | Reason |
|---|---|---|
| The `PhotoStore` class (4) | Split in two. `PhotoGateway` is an interface holding **only** the platform calls (grant, EXIF, `ImageDecoder`, probe, file writes), implemented once by `AndroidPhotoGateway`; `CaptureRegistrar` holds every decision above it and takes the gateway plus a `CaptureStore` interface. `DexRepository` implements `CaptureStore`. | No phone exists, and slice 5 is the slice with the widest gap between what can be built and what can be verified. The split is what lets registration, deletion, favouriting and re-linking run end-to-end in the JVM suite against fakes, leaving only genuinely platform-bound code untested. Same reasoning slice 3 used for `CatalogueReconciler` (3.4). |
| `PhotoRef` states (4.2) | A fourth state, `LocalCopy(relativePath)`. | 4.2 describes the local-copy short-circuit in prose but leaves it implicit in the type. Making it a state means the Photo Viewer's rendering choice is total over the sealed interface — an S03 capture cannot fall through to a "revoked" branch. |
| **The 5,000-grant policy (4.4)** | Every release is conditional on `SELECT COUNT(*) FROM captures WHERE photoUri = :uri` being 1. | 4.4 says "one grant per capture", but the same gallery photo can legitimately be registered against two species (a frame with a heron and an owl in it). Releasing on the first deletion would silently break the second capture's reference. This is the correction, not a refinement. |
| Thumbnail failure (4.1 step 3) | Aborts the registration: nothing is written, and a grant taken only for that attempt is released. The Register screen says the photo could not be read and that nothing was saved. | 4.1 orders the thumbnail before the transaction but does not say what happens when it fails. A capture row without its thumbnail is exactly the row M11 and M12 cannot honour. |
| Grant pressure (4.4) | `grantPressure(count)` returns FINE / NEAR_CAP / AT_CAP at 4,500 and 5,000, and the Register screen shows a warning line for the last two. Settings still owns the informational count (slice 8). | 4.4 asks the app to "behave sanely as it approaches" the cap without saying where. A warning costs one string and no engineering; the proactive pool management 4.4 rejects is still rejected. |
| Two DAO additions | `CaptureDao.countForUri` and `CaptureDao.updateReference`. | Both are `@Query` methods on an existing DAO: no entity, no column, no schema change, so section 9's "`data/db/` is not edited again" rule is not in play. The checked-in schema JSON is unchanged. |
| Re-link (4.2) | Keeps the capture's id, `createdAt`, `takenAt` and note; only `photoUri` and `thumbPath` change. A re-link whose thumbnail fails leaves the old reference in place. | 4.2 says "replaces `photoUri`, regenerates the thumbnail, releases the old grant" but not what happens to the rest of the row. Keeping the identity is what stops a re-link from moving the catch date. |
| S03's switch | `CaptureRegistrar` takes `keepLocalCopy: () -> Boolean`, wired to `{ false }` in `AppContainer`. | 4.5 puts the setting in slice 8. This is the whole of what slice 8 has to change in the registration path. |
| EXIF | `parseExifDateTime` is a pure function parsing `yyyy:MM:dd HH:mm:ss` in the device's zone, falling back to `TAG_DATETIME` then to registration time. Missing GPS is not logged as anything. | R3 is confirmed in the code's shape rather than argued about: location is an ordinary null, and the timestamp path is the one that carries weight, so it is the one under test. |

### 4.7 Settings and grants (recorded by slice 8, 2026-09-01)

| Point | What slice 8 did | Reason |
|---|---|---|
| S03's switch (4.5, 4.6) | `AppContainer.captureRegistrar` now takes `keepLocalCopy = settings::keepLocalCopyNow` — a **live read** of `SharedPreferences` on every registration, not a captured value. | Flipping the switch has to affect the next photo, not the next process. This is the whole of what slice 5 left for this slice, and it is one line. |
| Not retroactive (4.5) | Unchanged, and now stated in the Settings copy beside the switch. | 4.5 rules out copying old captures in v1 and asks for the UI to say so; the sentence is the requirement, not polish. |
| The grant count (4.4) | Settings shows `n of 5000 photo permissions held`, in `warn` colour with an instruction once `grantPressure()` leaves `FINE`. | 4.4 asks for the informational count; slice 5 already built the threshold function for the Register screen's warning, so the two surfaces cannot disagree. |
| Cache management (5.3) | `CacheManager` takes the container's `ImageLoader` as a supplier, reports images and lookups separately, and clears only the image cache. (It also held the audio cache until 12.1 removed it.) | Clearing never touches `filesDir`: thumbnails and local copies are permanent artifacts (4.3), not cache. |

### 6.6 UI corrections (recorded by slice 5, 2026-09-01)

| Point | What slice 5 did | Reason |
|---|---|---|
| The `EntryDetail` route (6.1) | Gained a second boolean, `photoAdded`, alongside `justUnlocked`. Registration navigates with exactly one of them true. | M09 asks for a "brief acknowledgment" on a repeat registration, and DESIGN.md §4 is explicit that it must not be the reveal. Both are one-shot moments guarded by `rememberSaveable`, so a rotation or process death cannot replay either. |
| The reveal's glow (6.4) | A flat `accentSoft` halo behind the art, not a radial gradient. | D8's brief is restraint. A gradient reads heavier, and nobody can look at it on a phone yet to judge — the cheaper thing is the thing to ship first. |
| Grid cell and photo strip | Both render `thumbPath` through Coil with the class silhouette as the **error slot**. Neither resolves a gallery URI. | M11's rule, made structural: the only screen that resolves a URI is the Photo Viewer, so a broken reference cannot blank the collection. The error slot covers the remaining case — a thumbnail file that is somehow gone — with a shape rather than a hole. |
| Register results | Capped at 25 rows, and an empty query lists the catalogue rather than showing nothing. | The screen carries the photo row and two buttons under the list; 120 unbounded rows would bury them. Search itself reuses the grid's `matchesQuery` verbatim, so M07 and M14 cannot drift apart. |
| "Add your own species" | Ships **visibly disabled**, labelled "coming in a later update", with the route hook stubbed. | Slice 7 owns M08 and M18–M21. Shipping the button disabled means the screen's shape does not change when that slice lands. |
| Google Lens (S06) | A plain `ACTION_SEND` image share through the system chooser, with `FLAG_GRANT_READ_URI_PERMISSION`, shown once a photo is attached. | There is no Lens-specific API worth taking a dependency on, and the chooser is what the user's existing workflow already goes through. |

**Not verified.** No phone is connected. `assembleDebug`, `assembleDebugAndroidTest` and `testDebugUnitTest` pass; nobody has run the picker, seen the reveal, or watched a grant survive a reboot. Section 9's slice-5 done-check is entirely outstanding, and the instrumented suite (including the new `AndroidPhotoGatewayTest` and `CaptureRegistrarRoomTest`) has never executed.

---

## 5. Network layer

### 5.1 What happens when

| Call | Build time (pipeline) | Runtime (app) |
|---|---|---|
| GBIF `GET /v1/species/match?name=` | every curated species | user-added lookup + backfill (M18/M20) |
| Wikipedia parse API (sections → habitat), REST summary (lede + image) | every curated species | user-added lookup + backfill |
| Wikimedia image bytes | — | streamed by Coil on view, disk-cached (S02) |

Everything else the app shows is bundled. There is no other network traffic; photos and locations never leave the device (M10).

### 5.2 Clients

One `OkHttpClient` singleton in `AppContainer`, with:

- `User-Agent: BioDex/1.0 (personal Android app; tlong@unified.health)` — Wikipedia requires a descriptive UA and this is also good citizenship at GBIF.
- An HTTP response cache (`Cache(cacheDir/http, 20 MB)`) — makes repeated lookups and backfill retries cheap.
- 10 s connect / 20 s read timeouts.

Two thin clients in `data/net/`, each a class with suspend functions returning a sealed `LookupResult<T>` (`Found`, `NotFound`, `Failed(cause)`), parsing with kotlinx-serialization (`ignoreUnknownKeys = true` everywhere — these APIs add fields freely):

- **`GbifClient.match(name)`** → accepted scientific name, rank, class, confidence, and the `alternatives` list (M18's candidate picker).
- **`WikipediaClient.habitatAndSummary(title)`** → two requests: `action=parse&prop=sections` to find a section titled `Habitat` or `Distribution and habitat` (case-insensitive contains "habitat"), then `&section=N&prop=wikitext` for its prose (stripped of markup — a small regex pass is acceptable for v1: drop templates `{{…}}`, refs `<ref…>`, links keep label). Fallback: REST `page/summary` lede. Also from the summary: `originalimage.source` as the canonical image URL and the page URL as `infoUrl`.

`SpeciesLookupRepository` composes them: GBIF first (it supplies the scientific name Wikipedia is keyed by — Wikipedia is queried by scientific name, falling back to the user's common name if the page is missing), then Wikipedia, and for a plant the bundled Duke's index (11.4). Total failure of any one source degrades to that field being empty and editable on the confirm card; total failure of GBIF offers "save with details pending" (M20 path) or hand-editing.

### 5.3 Caching and offline behavior

- **Reference images**: Coil's default memory cache plus an explicit disk cache (`cacheDir/coil_images`, 250 MB max). Cache hit = offline works (S02). Miss while offline = the composable's error slot shows the silhouette — the D3 graceful degradation.
- **API lookups**: no offline queue. Offline user-add takes the M20 path (create with `detailsPending = true`); backfill triggers when a pending entry's detail screen opens with connectivity present (checked via `ConnectivityManager.activeNetwork` — a simple "is there a network" probe, not reachability engineering).
- Settings' cache management screen shows the cache sizes and offers "clear reference caches" (images; never thumbnails, never local copies).

### 5.4 *Removed*

This section specified the Xeno-canto API key and its `local.properties` → `BuildConfig` plumbing. It went with the call feature — see 12.1.

### 5.5 Media-layer corrections (recorded by slice 6, 2026-09-01)

Slice 6 built the reference-image path for real (it also built the call-audio path, removed later — 12.1). Section 5.3 held as written: Coil with an explicit 250 MB disk cache at `cacheDir/coil_images`, and the silhouette as the offline/error slot. Eight things needed a decision the document did not make; the three that concerned only the audio player have been dropped from the table below.

| Point | What slice 6 did | Reason |
|---|---|---|
| The `OkHttpClient` (5.2) | Built as specified, plus a `User-Agent` **interceptor**; media traffic uses `newBuilder().cache(null)`, so images never write to the 20 MB HTTP cache. | OkHttp sends no descriptive UA by default and Wikimedia rejects generic clients, so the header is load-bearing rather than good citizenship. The 20 MB cache exists for slice 7's API lookups; letting a few 3 MB JPEGs through it would evict them for no gain, since Coil already owns those bytes. |
| Coil's singleton (5.3) | `App` implements `SingletonImageLoader.Factory`. | Coil 3 resolves `AsyncImage` against a process-wide loader. Without this hook the configured loader would exist and nothing would use it — every call site built in slices 4 and 5 would keep Coil's default, and S02's disk cache would never be consulted. |
| The uncaught hero | Stays the silhouette, and does **not** request the reference image at all. | M05 and DESIGN.md §5 are explicit: an uncaught species is "present, named, but withheld". Section 9's slice-6 done-check only names a caught species, and 6.5's note that "the hero shows the Wikimedia image" describes the mockup's frame 2, which is the caught state. So `caught` is an input to the hero's state machine, not just a loading detail. |
| The credit chip (M17) | Rendered only while the reference photo is actually on screen. | Crediting Wikimedia over a silhouette this app drew itself is the wrong claim. The always-on `AttributionLine` at the foot of the screen already carries the credit in every other state, so M17 is satisfied without it. |
| Offline versus failed | A `NetworkMonitor` (`ConnectivityManager.activeNetwork` probe plus a default-network callback) distinguishes the two, and adds `ACCESS_NETWORK_STATE` to the manifest. | 5.3 asks for exactly this probe, for the backfill trigger. Reused here so an un-cached image in airplane mode reads "not cached — connect to load it" (D3's graceful degradation) rather than "could not be loaded" (a fault). It is a normal permission: install-time, no runtime prompt. |
| The hero's image slot | The `AsyncImage` stays in the composition once requested and is hidden with `alpha = 0f` when it is not the thing on show; the silhouette is drawn **underneath** it rather than being Coil's `error` painter. Retry is a generation counter that `key()`s the `AsyncImage`, bumped when connectivity returns after a failure. | Removing a failed `AsyncImage` from the composition resets Coil's painter, which reports its way back through `Loading` — which would put the image back and retry forever against a URL that is not answering. Keeping it also means the frame is never empty between request and first pixel. Retry has to build a new painter: the model has not changed, so resetting the screen's own idea of the load phase restarts nothing. Connectivity deliberately does not reset that phase either — an image already on screen must not vanish when the phone enters airplane mode, which is exactly what section 9's slice-6 check watches for. |

**The `SpeciesCell` staleness slice 5 flagged is unchanged, and the ImageLoader cannot fix it.** Coil 2's `ImageLoader.Builder.addLastModifiedToFileCacheKey` — the option that would key an app-owned file's cache entry by its modification time — does not exist in Coil 3.5.0 (checked against the artifact, not from memory). The remaining route is a per-request `memoryCacheKey` carrying the file's `lastModified`, which costs a `stat` per cell per composition and cannot be observed to work without a phone. Left alone deliberately; a re-linked photo may still serve its old thumbnail from the memory cache until the process restarts.

**Not verified.** No phone is connected, so nothing in this slice had ever rendered at the time it was written. `assembleDebug`, `assembleDebugAndroidTest` and `testDebugUnitTest` pass (122 tests). Section 9's slice-6 done-check was entirely outstanding, and no instrumented test had executed. In particular: nobody had seen a Wikimedia image load, confirmed the User-Agent satisfies Wikimedia, or watched the disk cache serve an image offline.

---

### 5.6 Network-layer corrections (recorded by slice 7, 2026-09-01)

Slice 7 built the API clients, `SpeciesLookupRepository`, the confirmation card and the backfill path. Section 5.2's composition held — GBIF first because it supplies the name the other sources are keyed by, then Wikipedia, with any one source's failure degrading to an empty editable field. Eleven things needed a decision the document did not make, and the first is a correction rather than a refinement. (One of the eleven concerned the removed call lookup and has been dropped from the table.)

| Point | What slice 7 did | Reason |
|---|---|---|
| **`GbifClient.match(name)` (5.2)** | Two requests, not one. `species/match?name=` first; when it answers `matchType: NONE`, fall back to `species/search?qField=VERNACULAR&rank=SPECIES&status=ACCEPTED&datasetKey=<backbone>&highertaxonKey=1`. | **`species/match` does not resolve common names at all** — verified live on 2026-09-01, "Varied Thrush" and "sparrow" both return `{"matchType":"NONE"}`. The build-time pipeline never met this because the curator supplies scientific names; the runtime user types a common name, which is the whole of M08. The captured payload is the fixture `gbif_match_varied_thrush.json`. Keeping `match` first is not vestigial: it wins when the user types a binomial, and it is the only endpoint that returns GBIF's own confidence, match type and alternatives. |
| Candidate ranking | An English vernacular equal to the typed name is promoted to the front; extinct taxa sink to the back; otherwise GBIF's own order stands. | GBIF's relevance is substring-based, so "Coyote" returns *Coyote Snowfly* and *Coyote Cloudywing* above *Canis latrans*, and the one species GBIF literally calls "sparrow" is *Palaeostruthus eurius*, a fossil. Both are in the fixtures and both are tests. The rule degrades to GBIF's order when nothing matches exactly, so a miss is never worse than the API's own answer. |
| The platform seam | One `JsonFetcher` interface (`get(url): FetchResult`) implemented once by `OkHttpJsonFetcher`; every client above it parses strings. | No phone, and the parsing is where this slice's risk lives. The seam is what lets all three clients run in the JVM suite against real captured payloads — the split slices 3 and 5 made for `CatalogueStore` and `PhotoGateway`. |
| `WikipediaClient` (5.2) | Four requests, not two: summary → sections → section wikitext → **Commons `imageinfo&iiprop=extmetadata`**. Section calls use the *normalised* title from the summary. | 5.2 omits the Commons call, but both the card and the species row need the credit line (M17), and the pipeline already knew how to build it. The title matters more than it looks: `action=parse` does **not** follow redirects, so asking for the sections of "Ixoreus naevius" returns an empty list while "Varied thrush" returns nine. Both shapes are checked-in fixtures. |
| Wikitext stripping | `Wikitext.strip` is a port of the pipeline's `strip_wikitext`, in the same order: comments, refs, `{{convert}}` expansion, tables, depth-matched file links, template peeling, link labels, tags, headings. | 5.2 permits "a small regex pass"; the pipeline's version already learned the cases against 120 real articles. Re-deriving it would have re-learned that a caption's own `[[link]]` breaks a non-greedy `[[File:…]]` regex. |
| **Offline never reaches the card** | With no connectivity the Confirm screen writes the details-pending species and its photo immediately and hands the route a navigation event; no card is shown. | M19 ("nothing is written until you accept") and M20 ("created immediately from the name and photo alone; registration never blocks on the network") both apply to this screen, and M20 is the more specific rule. There is nothing to confirm when there is nothing to confirm *against*. Online-but-failed is a different path and keeps the card, degraded, exactly as 5.2 describes. |
| **The backfill looks up but does not write** | A details-pending entry opened online runs the lookup in `EntryDetailViewModel` and, only on a resolved match, emits a draft id that the route turns into the same confirmation card. Nothing is stored until the user accepts. | M20's "backfills automatically … then presents the same confirmation card" could be read as write-then-show. It is not read that way here: a silent write of a GBIF top hit is precisely the Roosevelt Elk failure D10 exists to prevent. A lookup that fails or finds nothing presents nothing and leaves the entry pending for the next open. |
| `detailsPending` lifecycle | Pending means "no scientific name". It is set by `detailsPendingFor(fields)` on every write, so accepting a card that still has no resolved identity keeps the entry pending and the next online open tries again. | M20 never says when the flag clears, and without an answer "an edited field survives a re-backfill" is unimplementable — there would be no second backfill. A scientific name is exactly what Wikipedia is keyed by, so it is the honest test of whether the lookup is still owed. |
| `taxClass` for a pending species | `other_invertebrate`, with `sil_other_invertebrate`, corrected on the first successful backfill. | The column is `NOT NULL` and the enum has no unknown member (3.1, and section 9 forbids touching `data/db` before a real migration). This is the least-false default: it claims nothing about vertebrates, and `TaxClass.fromWireName` already falls back the same way. |
| Accept-path ordering | Species row and memberships in one transaction, **then** `CaptureRegistrar.register`; a `ThumbnailFailed` deletes the species row again. | The foreign key forces the species to exist before its capture can. Inverting the order would mean duplicating the registrar's thumbnail check, which is the one piece of logic M11 and M12 both depend on. The rollback is safe because the row has no captures yet, so the cascade takes nothing with it. |

**Two structural notes.** `UserSpeciesStore` is a new interface on `DexRepository` beside `CaptureStore`, and every decision above it lives in `AddSpeciesRegistrar` and `domain/UserSpecies.kt` — the same split as 3.4 and 4.6, and the reason M21 is a unit test rather than a claim. `SpeciesDao.speciesOnceById` was added as a `@Query` on an existing DAO (no entity, no column, no schema change — the rule slice 5 already established for `countForUri`); the checked-in schema JSON is unchanged.

**Not verified.** No phone is connected, so nothing in this slice has ever rendered, and no lookup has ever run against the live API *from the app* — the payloads were captured with `curl` and checked in as fixtures. `assembleDebug`, `assembleDebugAndroidTest` and `testDebugUnitTest` pass (199 tests, up from 122). Section 9's slice-7 done-check is entirely outstanding: nobody has typed "Varied Thrush", seen the confirm card, accepted a U01, watched it trail the grid outside the 47/120, added one in airplane mode, or watched a hand-edited field survive a re-backfill on a real database. `UserSpeciesRoomTest` — like every instrumented test slices 3, 5 and 6 wrote — has never executed.

---

## 6. UI layer

### 6.1 Screens and routes

Single activity, one `NavHost` in `ui/nav/NavGraph.kt`. Routes are type-safe (Navigation 2.10 serializable route objects — `@Serializable data class`/`data object` per destination, no string-template routes).

| DESIGN.md screen | Route object | Composable / holder |
|---|---|---|
| 1. Dex Grid (home) | `DexGrid` (start) | `DexGridScreen` + `DexGridViewModel` |
| 2. Entry Detail | `EntryDetail(speciesId)` | `EntryDetailScreen` + `EntryDetailViewModel` |
| 3. Register a Species | `Register(preselectedSpeciesId: String?)` | `RegisterScreen` + `RegisterViewModel` |
| 4. Add Species — Confirm | `ConfirmSpecies(draftId)` | `ConfirmCardScreen` + `ConfirmSpeciesViewModel` |
| 5. Unlock Reveal | **not a route** — a full-screen overlay composable shown by `EntryDetailScreen` when navigated with `justUnlocked = true`; auto-dismisses (~1.5 s) or on tap, revealing the detail beneath. This matches DESIGN.md ("overlay, not a destination") and keeps back-stack semantics trivial. |
| 6. Photo Viewer | `PhotoViewer(captureId)` | `PhotoViewerScreen` + `PhotoViewerViewModel` |
| 7. Stats | `Stats` | `StatsScreen` + `StatsViewModel` |
| 8. Settings | `Settings` | `SettingsScreen` + `SettingsViewModel` |

Register→Confirm hand-off: the Register screen holds the picked photo URI and typed name; navigating to Confirm passes a `draftId` keying an in-memory `AddSpeciesDraftHolder` in `AppContainer` (a URI and a lookup result do not belong in route arguments). Registration success navigates to `EntryDetail(speciesId, justUnlocked = isFirst)` with `popUpTo(DexGrid)` so back from detail returns to the grid (DESIGN.md §6 navigation rule).

The mockup's bottom nav shows Dex / Map / Stats; Map is C01 (out of v1), so v1's bottom bar has **Dex and Stats** only, with Settings via a top-bar icon on the grid — matching DESIGN.md §6, which routes Stats and Settings from the grid's top area. The floating Register button sits on the grid (M-flow) and a Register action on uncaught detail screens.

### 6.2 State-holder pattern

One ViewModel per screen, each exposing a single `StateFlow<XxxUiState>` (a data class; sealed only where the screen has genuinely disjoint modes, e.g. `ConfirmSpeciesUiState.Loading / Ready / Offline`). Collection via `collectAsStateWithLifecycle()`. One-shot events (navigate-after-register, toasts like "+1 photo") use a `Channel`-backed `Flow` on the ViewModel. ViewModels take repositories from `AppContainer` via a shared `viewModelFactory` helper; no ViewModel touches a DAO, Room, or OkHttp directly.

Search and filters (M14) are ViewModel state combined over the repository's cold flows: `combine(speciesFlow, searchQuery, filters) { … }` filtering in memory — 120-plus rows is nothing; do not push search into SQL `LIKE` queries.

### 6.3 Derived progress

`DexRepository` exposes `dexProgress(): Flow<DexProgress>` computed with SQL aggregates (counts per class, and per ecosystem via the join table, curated-only, plus user-added addenda per D9). Stats and the grid header share this flow.

### 6.4 Theme — the mockup's language, as Compose

`ui/theme/` translates `mockup.html`'s tokens directly; the palette below is copied from its CSS custom properties and must not be re-invented:

- **`Color.kt`**: two token sets as immutable `AnimalDexColors` (light: bg `#FBFAF7`, fg `#22282E`, muted `#5D6670`, faint `#8B939B`, rule `#DCDFD9`, card `#FFFFFF`, codeBg `#EEF0EA`, accent `#0E6E63`, accentSoft `#E3EFEC`, ok `#2F6B4F`, warn `#9A6A1C`, warnSoft `#F6ECD9`, stop `#9C3A3A`, stopSoft `#F6E6E4`, sil `#3A4148`, silBg `#E7E9E3`; dark: bg `#171C21`, fg `#D9DEE2`, muted `#97A1AA`, faint `#7C858E`, rule `#323A41`, card `#1E242A`, codeBg `#232A30`, accent `#4CBCAB`, accentSoft `#1E2F2C`, ok `#7FC6A0`, warn `#D5A04A`, warnSoft `#2E2718`, stop `#E08B84`, stopSoft `#33201E`, sil `#11151A`, silBg `#2B333B`). Delivered through a `staticCompositionLocalOf` (`LocalDexColors`) *alongside* a Material3 `ColorScheme` mapped from the same tokens (primary = accent, surface = card, background = bg, outline = rule) so Material components and bespoke components agree. Theme follows the system dark setting (`isSystemInDarkTheme()`); no in-app toggle.
- **`Type.kt`**: the mockup pairs a serif display face with a system sans. Bundling Iowan Old Style is not licensable; **decision: `FontFamily.Serif` (Noto Serif on-device) for display styles** (screen titles, species names, the big stats number, reveal name) and `FontFamily.SansSerif` for everything else. Tabular numerals for all counters (`fontFeatureSettings = "tnum"`).
- **Component vocabulary** in `ui/common/`, each matching a mockup element: `SpeciesCell` (grid cell: art area with photo thumb or silhouette on `silBg`, `#NNN` number in faint, name line), `EcosystemMeter` (warn-colored fill bar, `12/24` tabular value, the `+1` user-added addendum), `ClassMeter` (accent fill), `FilterChip` row (outlined; selected = accent on accentSoft), `AttributionLine` (faint small text), `ProgressPill` (accent on accentSoft, `47 / 120`). The eco meters use warn, class meters use accent — that distinction is in the mockup and is intentional.
- **Silhouette treatment**: uncaught art areas are `silBg` with the class silhouette tinted `sil` (a `ColorFilter.tint`); the unlock reveal cross-fades silhouette → thumbnail with a scale from 0.96 and a soft accentSoft radial glow, plus one `HapticFeedbackType.LongPress` tick — restrained per D8.

### 6.5 UI-layer corrections (recorded by slice 4, 2026-09-01)

Slice 4 built the grid and the read-only detail screen. Section 6.1's routes, 6.2's state-holder pattern and 6.4's palette held as written; the theme files slice 1 shipped needed no change. Six things needed a decision the document did not make, and none of them changes a contract a later slice depends on.

| Point | What slice 4 did | Reason |
|---|---|---|
| State composition (6.2) | Each screen's `combine` over the repository's cold flows is a **top-level pure function** (`ui/grid/DexGridState.kt`, `ui/detail/EntryDetailState.kt`); the ViewModel is that function plus `stateIn`. | No phone is available, so M14 has to be provable in the JVM suite. A ViewModel's `viewModelScope` needs a Main dispatcher and `kotlinx-coroutines-test`, which the version catalog does not carry; a plain function over `MutableStateFlow` fakes needs neither, and it is the same code the ViewModel runs. |
| Region display name | `DexProgress` carries `regionId` only — the asset's `regionName` is not imported into Room — so the header maps `"pacific" → "Pacific"` in the UI (`regionLabelFor`). | The alternative is a schema change, and 9's rule is that `data/db/` is not edited again before a real migration. C03 (more regions) turns this into a table read. |
| `ui/common/` scope (6.4) | Ships `SpeciesCell`, `ProgressPill`, `RegionPill`, `DexFilterChip`, `SectionHeader`, `LinkRow`, `CaughtChip`, `AttributionLine`, `SilhouetteIcon`. `EcosystemMeter` and `ClassMeter` are **not** built. | Nothing in slice 4 renders a meter; the Stats screen is slice 8's and is the only caller. Building an unrendered, unviewable component against a guess at its use is how it comes out wrong. |
| Caught cells and the detail hero | Both render the class silhouette — caught cells tinted `accent` with a small `✓` badge, uncaught tinted `sil` on `silBg`. | The mockup's caught cells show the user's photo and the hero shows the Wikimedia image; neither exists yet (photos are slice 5, reference-image loading slice 6). The frames, sizes and credit chip are in place so those slices only swap what fills them. |
| Filter chips (M14) | One scrolling row holding three dimensions — caught state, class, ecosystem — single-select within each, AND across them and with the search query. Tapping a selected chip clears it; `All` clears all three. | The mockup shows one flat chip row and gives no clear affordance; composing across dimensions is what M14 asks for, and re-tapping is the cheapest clear. |
| Fish silhouette | Hand-drawn in the mockup's register; the other six adapt `mockup.html`'s SVG paths (bird, quad→mammal, lizard, frog, butterfly→insect, slug→other invertebrate). | The mockup has no fish shape. |

Slice 3's two hand-offs are discharged: `ui/grid/TempDexCount.kt` and its hook in `NavGraph.kt` are deleted, and both screens consume `AppContainer.dexRepository` through its read-only surface.

### 6.7 UI corrections (recorded by slice 6, 2026-09-01)

Slice 6 changed two components and one screen, and every decision behind them is recorded in **5.5** with the media layer they belong to — the uncaught hero staying silhouetted (M05), the credit chip appearing only over a real photograph (M17), and the hero keeping its image slot in the composition rather than swapping it for Coil's error painter.

One signature change a later slice will meet: `entryDetailUiState()` takes five flows instead of four, with connectivity joining the four repository flows.

**Not verified.** No phone is connected, so section 9's slice-4 done-check has not run and nobody has seen these screens render. What is proven is `assembleDebug`, and the JVM suite covering search, filter composition and the detail screen's ecosystem resolution.

### 6.8 UI corrections (recorded by slice 7, 2026-09-01)

The confirm card, the draft holder and the backfill trigger are recorded in **5.6** with the network layer they belong to. Three things belong here.

| Point | What slice 7 did | Reason |
|---|---|---|
| "Add your own species" (6.6) | Enabled once the user has typed a name **and** attached a photo, rather than only when the search found nothing. The label says which half is still missing. | M20 creates an offline entry "from the name and photo alone", so both halves are load-bearing. Gating on `noResults` alone would leave the button dead for a name that happens to substring-match a catalogue species the user did not mean. `RegisterRoute.onAddOwnSpecies` now takes the name and the photo URI; nothing else about the screen changed. |
| The draft holder (6.1) | In-memory (`ConcurrentHashMap` in `AppContainer`). The Confirm route renders a `Missing` state when its draft is gone rather than crashing. | 6.1 asks for exactly this, and a draft's whole life is two screens — persisting it would mean a table for something that must not outlive a process. Process death mid-flow is the one case that state must cover, and it does. |
| Ecosystems on a backfill | The write path takes `ecosystemIds: List<String>?`, where `null` means "leave them alone", and only the card's own accept passes a list. | D10: no API maps species onto these seven ecosystems, so nothing automatic may ever write them. Making it a nullable argument rather than a convention is what makes "a backfill never touches the user's pick" a test. |
| Which fields the card lets you edit | Common name, scientific name, class and habitat text. The mockup's "✎ change" on the image, and editors for the description and the outbound link, are **not** built. | M19 asks for "edit any field by hand"; this is a deliberate v1 trim, not an oversight. The four built are the ones that decide what the entry *is* — a wrong class picks the wrong silhouette, a wrong scientific name mis-keys every future backfill. Nothing structural is missing: `applyFieldEdits` and `mergeLookup` already treat an edited `imageUrl` as user-owned (and lock its credit line with it), so a later slice adds an affordance and no logic. |

---

### 6.9 UI corrections (recorded by slice 8, 2026-09-01)

Slice 8 built the Stats and Settings screens, the licenses screen and the two meters slice 4 deferred. 6.4's palette and 6.2's state-holder pattern held; the export and import decisions behind the Settings screen are recorded in **3.5** with the layer they belong to.

| Point | What slice 8 did | Reason |
|---|---|---|
| `EcosystemMeter` / `ClassMeter` (6.4) | Built as one shared row — label, track, `12/24` in tabular figures, and a fixed-width slot for D9's `+1` — differing only in fill colour: **ecosystem meters `warn`, class meters `accent`**, as 6.4 specifies. | Slice 4 deferred these to their only caller. One row means the two breakdowns cannot drift apart; the colour is the only thing that distinguishes them, which is what the mockup does. |
| **Seven class bars, not the mockup's six** | Insects and other invertebrates get their own rows rather than a merged "Invertebrates". | The catalogue, the filter chips and the silhouettes all treat them as separate classes. A stats screen that groups differently from the grid it is meant to reconcile with makes the user do arithmetic — and reconciling by hand-count is this slice's phone check. |
| The recently-caught strip (S08) | Sourced from **caught species** ordered by `caughtAt`, not from recent captures. | S08 asks for recently *caught* things and for the date of the last new catch. Sourcing from captures would list the same species twice for two photos, and a `+1` photo of a species caught last spring would push it to the front of the strip and move the "last new catch" date. `SpeciesSummary` already carries name, date and thumbnail, so this also needs no new query. |
| Stats' data source (6.3) | The screen reads the *same* `dexProgress()` flow the grid header reads. | 6.3 says they share it. Making that literal is what makes "the stats reconcile with the grid" structural rather than a coincidence of two similar computations. |
| The Settings screen's shape | Sections — Photos (S03 switch + grant count), Backup (export/import + the outcome message), Caches, About + Licenses — as cards in the mockup's language. | `mockup.html` has no settings frame. Following its vocabulary (section headers, cards, accent CTA, ghost button) rather than inventing a second visual language was the cheaper and more consistent choice. |
| Licenses as a route | `Licenses` is an eighth route, rendered from `assets/licenses.md` by a renderer that understands `#`, `##`, `-` and paragraphs — and nothing else. | The text is long enough to need its own screen and its own scroll. A Markdown library for four constructs would be a dependency for nothing. |
| Stats' back affordance | The bottom bar's Dex tab and the back arrow both `popBackStack()`. | Stats is always reached from the grid, so popping returns exactly where the user was, and the back stack never grows a Grid → Stats → Grid chain. |

**Not verified.** No phone is connected. `assembleDebug`, `assembleDebugAndroidTest` and `testDebugUnitTest` pass (242 tests, up from 201). Section 9's slice-8 done-check is entirely outstanding: nobody has hand-counted the stats against the grid, seen an export reach a file manager, imported an archive, watched a cache clear leave thumbnails intact, or confirmed that toggling S03 makes the next registration write `filesDir/photos/`.

---

## 7. The catalogue build pipeline

Lives in `tools/catalogue/`. Pure Python 3 (system `python3` plus `requests` in a local venv; the README gives the two setup lines). Runs only on the build machine; the app never sees it.

### 7.1 Input: `curated_species.json`

The human-curated list — the only hand-authored data. The 120 entries themselves are an implementation slice (slice 2), authored by an agent with the user's regional brief, spread across the seven ecosystems and classes roughly as the mockup's stats screen implies (birds ~52, mammals ~20, inverts ~22, reptiles ~9, amphibians ~7, fish ~10). Worked example of the shape:

```json
{
  "regionId": "pacific",
  "regionName": "Pacific",
  "ecosystems": [
    { "id": "coastal-rainforest",  "name": "Coastal Rainforest",     "sortOrder": 1 },
    { "id": "rocky-shore-kelp",    "name": "Rocky Shoreline & Kelp", "sortOrder": 2 },
    { "id": "oak-chaparral",       "name": "Oak Woodland & Chaparral","sortOrder": 3 },
    { "id": "riparian-wetland",    "name": "Riparian & Wetland",     "sortOrder": 4 },
    { "id": "high-desert",         "name": "High Desert & Sagebrush","sortOrder": 5 },
    { "id": "alpine",              "name": "Sierra/Cascade Alpine",  "sortOrder": 6 },
    { "id": "urban-suburban",      "name": "Urban & Suburban",       "sortOrder": 7 }
  ],
  "species": [
    { "dexNumber": 3,  "commonName": "Great Blue Heron",    "scientificName": "Ardea herodias",
      "ecosystemIds": ["riparian-wetland", "rocky-shore-kelp", "urban-suburban"] },
    { "dexNumber": 21, "commonName": "Western Screech-Owl", "scientificName": "Megascops kennicottii",
      "ecosystemIds": ["oak-chaparral", "riparian-wetland", "urban-suburban"] },
    { "dexNumber": 88, "commonName": "Banana Slug",         "scientificName": "Ariolimax columbianus",
      "ecosystemIds": ["coastal-rainforest"] }
  ]
}
```

Scientific names are supplied in the input (the curator knows them); GBIF still normalizes to the *accepted* name and supplies the class. Ecosystem tags are judgment, assigned here, never fetched (per the brief). An optional per-species `overrides` object lets the curator pin any output field (e.g. a better `infoUrl` or a hand-tightened `habitatText`) — the script copies overrides over fetched values last.

### 7.2 The script: `build_catalogue.py`

`python3 build_catalogue.py --out ../../app/src/main/assets/catalogue/pacific.json`. Per species:

1. **GBIF** `species/match?name=<scientific>&strict=false` → accepted `species` (canonical name), `class` → mapped to the app's `taxClass` enum (Aves→bird, Mammalia→mammal, Reptilia (incl. Squamata/Testudines)→reptile, Amphibia→amphibian, Insecta→insect, fish classes (Actinopterygii, Chondrichthyes, Elasmobranchii)→fish, everything else→other_invertebrate). A non-exact match or a class contradiction is **logged as a warning, not auto-fixed** — the run report lists them for the curator.
2. **Wikipedia**: resolve the page by scientific name (fall back to common name); `action=parse&prop=sections` → find the habitat section → fetch and strip its wikitext to 1–3 sentences for `habitatText`; REST `page/summary` → `description` (lede, first 2 sentences) and `originalimage.source` → `imageUrl`; page URL → `infoUrl`. Image attribution: Commons `imageinfo` (`prop=imageinfo&iiprop=extmetadata`) → license + artist → `imageAttribution`.
3. Assemble the output record with `silhouetteRes` = `sil_<taxClass>` and a `provenance` map naming the source of every fetched field (`"habitatText": "wikipedia:section:Distribution and habitat"` or `"override"`).

Cross-cutting behavior:

- **Cache**: every HTTP response is written to `cache/<sha1-of-url>.json` and reused on re-run (`--refresh` bypasses). A full re-run against a warm cache makes zero requests.
- **Rate limiting**: ≥ 1 s between Wikipedia requests, ≥ 0.5 s between GBIF requests. Descriptive `User-Agent` with the user's email on every request.
- **Report**: the run ends with a summary — species with no habitat section (lede fallback used), no image, GBIF fuzzy matches — written to `cache/report.txt` for curator review. Exit non-zero if any species failed entirely.
- **Validation**: asserts 120 species, unique dexNumbers 1–120, every `ecosystemId` declared, every record has `commonName`, `scientificName`, `taxClass`.

The output lands directly in `app/src/main/assets/catalogue/pacific.json` and **is committed to git** — builds must not depend on the network or on re-running the pipeline. Bumping `catalogueVersion` in the input is manual and only done when the output should reach existing installs (section 3.3).

---

## 8. Testing strategy

Realistic for one person; nothing here needs a CI farm.

**JVM unit tests (`app/src/test/`, run with `./gradlew testDebugUnitTest`, no device)** — the highest-value layer, covering the logic that could silently corrupt data or lie in the UI:

- Catalogue reconciliation: importer invariants from 3.3 (fake DAO or in-memory maps behind the repository interface — the invariants, not Room, are under test).
- Search + filter composition (M14) over an in-memory species list.
- Progress math (M15/D9): multi-ecosystem counting, user-added addenda excluded from curated fractions.
- Asset and API JSON parsing against fixture files checked into `test/resources/` (one real captured response per API, plus edge fixtures: GBIF ambiguous match, Wikipedia page with no habitat section).
- `PhotoRef` state mapping (exception type → Revoked vs Unavailable) with a fake resolver.
- EXIF date parsing fallbacks.

**Instrumented tests (`app/src/androidTest/`, run on the phone with `./gradlew connectedDebugAndroidTest`)** — kept to the two things a JVM cannot honestly fake:

- Room: schema builds, DAO round-trips, cascade behavior on capture/entry delete, the importer against the real bundled asset (count = 120, joins resolve).
- A first-run smoke test: app launches, grid populates.

**Hand-checked on the phone (each is a slice's "done" gate, listed per slice in section 9)** — the picker flow end-to-end, grant survival across reboot, the Revoked state (delete a gallery photo, reopen), the cloud-only-offline state, audio playback and its cache, the unlock reveal feel, and Google Photos picker behavior. These involve the gallery, the network, or aesthetics — automation would test a mock of the phone, not the phone.

No UI/screenshot tests (no Android Studio, no maintained baseline, one user). No test pyramid beyond the above.

---

## 9. The slice map

Eight slices. **Hot files** — the files more than one slice would naturally touch — are: `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `App.kt`, `AppContainer.kt`, `ui/nav/NavGraph.kt`, and `data/db/AppDatabase.kt`. The rules that keep slices safe:

- Slice 1 creates every hot file. Slice 3 creates the **complete** Room schema (all tables from section 3.1, including ones no UI uses yet) so `AppDatabase.kt` and the entity files are never edited again until a real post-v1 migration.
- A slice that must edit a hot file is marked **sequential**; only slices marked **parallel-safe** may run concurrently, and their file sets are disjoint by construction.
- Every slice ends with `./gradlew installDebug` and its stated check on the phone.

---

**Slice 1 — Walking skeleton.** *Sequential; everything depends on it.*
Goal: an empty app builds from the command line and installs and launches on the phone.
Creates: Gradle wrapper (8.13), `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` (**all** of section 1.2 declared now, even libraries no code uses yet — later slices then never touch the catalog), `app/build.gradle.kts` (all plugins, compileSdk 36, minSdk 29, BuildConfig field for `XC_API_KEY`), `AndroidManifest.xml` (single activity, `INTERNET` permission), `App.kt`, `AppContainer.kt` (empty holder), `MainActivity.kt`, `ui/theme/*` (full palette from 6.4), `ui/nav/NavGraph.kt` with all eight route objects and placeholder composables ("Dex Grid — coming soon"), launcher icon.
Done: `./gradlew installDebug` succeeds on the connected phone; the app opens showing the themed placeholder grid route; tapping nothing crashes nothing.

**Slice 2 — Catalogue pipeline.** *Parallel-safe with slices 3–8 (touches only `tools/catalogue/` plus the single asset file `app/src/main/assets/catalogue/pacific.json`, which no other slice writes).*
Goal: the 120-species Pacific catalogue exists as a committed, validated asset with provenance and attribution.
Creates: everything under `tools/catalogue/` (curated input with all 120 species and ecosystem tags, `build_catalogue.py`, README), and the generated `pacific.json` asset.
Done: `python3 build_catalogue.py` exits 0 from a cold cache; re-run makes zero HTTP requests; the report lists coverage gaps; the asset validates (120 unique dex numbers, all fields per 3.2); `git diff` shows the asset committed.
Depends on: nothing (not even slice 1 — but the asset's final path is inside `app/`, so land after slice 1 exists or create the directory).

**Slice 3 — Data layer.** *Sequential (edits hot files `AppContainer.kt`; creates `AppDatabase.kt` and all of `data/db/`, `data/catalogue/`, `data/repo/`, `domain/`).*
Goal: the full Room schema exists, the bundled catalogue imports on first run, and repositories expose the flows every screen will consume.
Creates: all entities/DAOs/converters per 3.1, `CatalogueImporter` per 3.3, `DexRepository`, domain models, JVM tests (reconcile invariants, parsing, progress math), instrumented Room tests. Uses the real slice-2 asset if landed, else a 10-species `pacific.json` stand-in with the same shape (slice 2 later overwrites the file; no code changes).
Done: `testDebugUnitTest` and `connectedDebugAndroidTest` pass; a temporary count read on the placeholder grid route shows "120 species / 0 caught" on the phone.
Depends on: slice 1.

**Slice 4 — Dex grid and read-only detail.** *Sequential (edits `NavGraph.kt`).*
Goal: the collection is browsable — grid with silhouettes, search, filter chips, progress header, and a detail screen showing bundled data.
Creates: `ui/common/` component vocabulary (6.4), `ui/grid/*`, `ui/detail/*` (hero shows silhouette or reference image placeholder; habitat, ecosystems, attribution, Register button on uncaught), class silhouette VectorDrawables in `res/drawable/`. Edits: `NavGraph.kt` (replace grid/detail placeholders).
Done: on the phone — grid shows 120 silhouetted species in dex order under "Pacific · 0 / 120"; search "western" narrows live; class and ecosystem chips compose with search (M14); tapping opens detail (M03/M05).
Depends on: slice 3.

**Slice 5 — Register, photos, unlock.** *Sequential (edits `NavGraph.kt`, `AppContainer.kt`, `ui/detail/`).*
Goal: the core loop works — pick a species, attach a gallery photo, unlock it, see your photo everywhere it should appear.
Creates: `data/photo/*` (PhotoStore, thumbnailer, EXIF reader, resolution states per section 4), `ui/register/*`, `ui/reveal/*`, `ui/photoviewer/*` (including the Revoked re-link and Unavailable states, S07 delete, S04 favorite), Lens share affordance (S06). Edits: `NavGraph.kt` (register/photoviewer routes, unlock arg on detail), detail screen (photo strip), grid cell (thumbnail rendering).
Done on the phone: register an uncaught species with a gallery photo → reveal plays, counter reads 1/120, grid cell shows the thumbnail; reboot the phone → photo still opens (grant persisted); delete the gallery photo → detail shows thumbnail + "full photo unavailable" + re-link, entry stays caught (M09–M13 checks).
Depends on: slice 4.

**Slice 6 — Reference media: images (and, at the time, calls — 12.1).** *Sequential (edits the hot files `AppContainer.kt` and `ui/detail/*`; slice 7 edits `AppContainer.kt` too, so 6 and 7 are strictly ordered — do not run them in parallel).*
Goal: detail screens stream and cache the canonical image, with attribution and error states.
Creates: `media/*`, Coil `ImageLoader` with the disk cache, offline placeholder behavior. Edits: `ui/detail/*`, `AppContainer.kt`.
Done on the phone: a caught species' detail shows the Wikimedia image with credit; airplane mode after one view → image still shows (S02); airplane mode on a never-viewed species → silhouette placeholder and error state, no crash.
Depends on: slice 4 (detail screen exists); independent of slice 5.

**Slice 7 — User-added species.** *Sequential, after slice 6 (edits `NavGraph.kt` and `AppContainer.kt`).*
Goal: a name outside the catalogue becomes a user-added entry via lookup + confirmation card, with the offline details-pending path.
Creates: `data/net/*` (three clients + `SpeciesLookupRepository` per 5.2), `ui/addspecies/*` (confirm card: GBIF candidates, image, habitat, call-found row, manual ecosystem multi-select, edit-by-hand, per M19), draft holder, backfill trigger on detail-open (M20/M21), U-number rendering. Edits: `NavGraph.kt` (confirm route), `AppContainer.kt` (client wiring), and `ui/register/*` ("Add your own species").
Done on the phone: registering "Varied Thrush" (not in catalogue) online shows the populated confirm card, accepting creates U01 trailing the grid, excluded from 47/120 (M02); the same flow in airplane mode creates a details-pending entry immediately, and opening it later online presents the card (M20); an edited field survives a re-backfill (M21).
Depends on: slice 5 (register flow and photo layer).

**Slice 8 — Stats, settings, export.** *Sequential (last; edits `NavGraph.kt` and whatever `AppContainer` wiring slices 6/7 left).*
Goal: the remaining screens — stats with meters, settings with local-copy toggle, cache management, export/import.
Creates: `ui/stats/*` (overall meter, seven ecosystem meters with addenda, class bars, recent-catch strip per M15/S08), `ui/settings/*` (S03 toggle, cache sizes + clear, grant count, licenses screen from a hand-kept `licenses.md` asset), S01 export (ZIP via `ShareSheet`: metadata JSON + thumbnails + resolvable full-size copies) and import.
Done on the phone: stats numbers reconcile with the grid by hand-count; export produces a ZIP another file manager can open; clearing caches leaves thumbnails and entries intact; toggling local-copy makes the next registration write `filesDir/photos/`.
Depends on: slices 5–7 (it summarizes and configures what they built).

**Ordering summary**: 1 → 3 → 4 → 5 → 6 → 7 → 8, strictly sequential; slice 2 is the one parallel-safe slice and may run alongside any of 3–8 (its file set — `tools/catalogue/` plus the asset — is disjoint from everything).

---

## 10. Risks and unknowns

**R1 — Version skew despite pinning.** All numbers in section 1 were verified against release pages on 2026-09-01, but two carry residual risk: the KSP plugin id format post-decoupling (pin `2.3.11`; if resolution fails, the KSP releases page is authoritative) and kotlinx-serialization 1.9.0 (if absent from Maven Central, fall back to 1.8.1 — Room and the asset models use nothing newer than 1.6 features). Mitigation: slice 1 exists precisely to burn this risk down first; any substitution is recorded in the version catalog with a comment.

**R2 — Cloud-only Google Photos items.** A picked photo that lives only in Google's cloud may fail to open offline, and its persistable grant may behave differently from a MediaStore item (some picker URIs refuse `takePersistableUriPermission` outright). Mitigation is already structural: thumbnails are generated at registration while the picker grant is live, persist failure is tolerated (4.1), and resolution distinguishes transient from revoked (4.2). Hand-check in slice 5 with a genuinely cloud-only item (free up device storage in Google Photos, then pick).

**R3 — Photo Picker strips EXIF location.** Confirmed behavior: the Android 13+ system picker redacts GPS metadata from returned URIs by default, and `ACCESS_MEDIA_LOCATION` does not apply to picker-authority URIs. So M13's location field will usually be null on modern devices; the EXIF *datetime* survives, so `takenAt` is unaffected. Stance: accept it — M13 says location comes "from EXIF only, if present", and `locationLabel` (user-typed) is the designed fallback. An August 2026 mainline picker update adds an "Include location info?" prompt, so newer devices may start returning GPS with no code change. Rejected: switching to raw `ACTION_OPEN_DOCUMENT` everywhere just to keep GPS (worse picker UX for one nullable field).

**R4 — *Closed by removal.*** This risk was about the bird-call source: its API key was a human step no agent could take, and its coverage was thin outside birds. Neither ever got resolved, and the feature it belonged to is gone — see 12.1.

**R5 — Wikipedia habitat-section variance.** Section titles vary ("Habitat", "Distribution and habitat", "Habitat and distribution", "Ecology"), wikitext stripping is heuristic, and some articles (especially invertebrates) have thin prose. Mitigation: the section-finder matches any title containing "habitat" (falling back to "Ecology", then the lede); the pipeline report flags every fallback; `overrides` lets the curator hand-write the stubborn ones. Runtime user-adds share the same fallback chain and are always editable (M19/M21).

**R6 — No Android Studio: no Compose previews, no layout inspector.** All visual verification is on the phone. Mitigation: every UI slice's done-check is a phone check; `adb exec-out screencap -p > shot.png` gives agents screenshots for self-review; the theme is fully token-driven so visual fixes are palette-file edits, not archaeology. Compose `@Preview` annotations may still be written (they compile fine) but nothing renders them.

**R7 — Room schema regret after real data exists.** The point of no return is the first real capture on the user's phone (slice 5). Before it: change schema freely, reinstall. After it: hand-written `Migration` objects only, `exportSchema` history in git making them writable. The full-schema-in-slice-3 rule exists to keep post-v1 migrations rare.

**R8 — Grant revocation en masse.** Android can revoke persistable grants if the provider app (Google Photos) is updated/cleared in unusual ways. The design already survives it (thumbnails + Revoked state + re-link, C05's periodic checker stays a future option). No preemptive engineering.

**R9 — The build machine's SDK install is in flight.** Slice 1 assumes `platforms;android-36` and `build-tools;36.0.0` finished installing and `local.properties` points at `/opt/homebrew/share/android-commandlinetools`. If `installDebug` fails on license acceptance, run `sdkmanager --licenses`. Also install `platform-tools` for `adb` and enable USB debugging on the phone — slice 1's done-check is the first moment all of this is exercised.

---

## 11. The BioDex expansion

Written 2026-09-02 against the code as it exists after slice 8 (242 JVM tests, running on the user's Pixel 7 Pro), not against sections 1–10's original prose: where a deviation-log entry (3.4, 4.6, 5.5, 5.6, 6.5–6.9) changed something, this section builds on the deviation. It covers DESIGN.md v4's five new things — the BioDex naming (D11), plants as a second, separately counted kingdom (D12, D13), uses as a headline filter (D14, D15), two ecosystem renames (D16), and the Register screen's scroll bug (D18) — plus the package rename (D17). Revised the same day for the Duke's correction: medicinal uses are sourced from a CC0 USDA file (11.3), not hand-written. The slice map is in 11.6.

**The one rule that makes this cheap: the new app installs fresh.** The package moves from `dev.tlong.animaldex` to `dev.tlong.biodex`, which is a different application to Android, with a different data sandbox. The only data in the old one is one throwaway test capture. So everything below is designed against an **empty database**: the schema changes are made in place in the entity classes, the database stays at `version = 1`, the checked-in schema JSON is regenerated (not migrated), and there is **no `Migration`, no `fallbackToDestructiveMigration`, and no reading of `animaldex.db`**. The user-added dex-number base moves from 1000 to 9000 in the same breath, which also means a v3 backup archive's user-added `dexNumber`s are not stable across this boundary (the import re-bases them — 11.1). Do not build migration machinery for this change. The rule that every later schema change ships a hand-written `Migration` resumes the moment the first real capture lands in the new app (R7).

### 11.1 Schema delta

All in `data/db/Entities.kt` and `Converters.kt`, edited once by slice 9 and then frozen again. `AppDatabase` is renamed to database file `biodex.db`, still `version = 1`, `exportSchema = true`; the schema lands at `app/schemas/dev.tlong.biodex.data.db.AppDatabase/1.json`. The old directory `app/schemas/dev.tlong.animaldex.data.db.AppDatabase/` is **deleted**, not moved — the file is regenerated by the KSP run.

**species** — three new columns, one changed comment.

| Column | Type | Notes |
|---|---|---|
| kingdom | TEXT | enum via converter: `animal` \| `plant`. Never null; a details-pending species is `animal` until backfill (5.6's `other_invertebrate` default keeps its kingdom). |
| taxClass | TEXT | enum grows four members: `tree` \| `shrub` \| `herb` \| `fern`. The Kotlin enum carries `kingdom` per member (11.2); the pairing `kingdom == taxClass.kingdom` is an invariant the importer, the registrar and the backup import all enforce, and a unit test pins. |
| uses | TEXT | JSON array of `edible` \| `medicinal`, through the existing `List<String>` converter. `[]` for every animal and for a plant without a recorded use. `edible` is curated; `medicinal` is derived from Duke's at build time (11.3) and stored, so the filter is a plain membership test. |
| usesNote | TEXT nullable | The curated part-and-season note with any `Caution:` sentence; null unless `uses` is non-empty (enforced at every write path, and by the pipeline's validation). |
| medicinalActivities | TEXT | JSON array of up to eight Duke's activity names, most-cited first; `[]` when Duke's has nothing. Same converter. |
| medicinalRecordCount | INTEGER | Duke's record count for the species; 0 for animals and for plants with no record. |
| usesAttribution | TEXT nullable | `Dr. Duke's Phytochemical and Ethnobotanical Databases · USDA ARS · CC0` when the two columns above are populated, else null. |
| dexNumber | INTEGER | Unchanged type, changed ranges: curated animals 1–120, curated plants **2001–2080**, user-added **9001+** (`USER_DEX_NUMBER_BASE = 9000`; `PLANT_DEX_NUMBER_BASE = 2000`). One sortable column still orders the whole grid: animals, plants, then user-added. |

Indices unchanged; `(regionId, dexNumber)` stays unique, which is exactly why plants need their own stored range.

**regions** — new table, seeded from the asset: `id` TEXT PK (`pacific`), `name` TEXT (`Pacific USA`), `sortOrder` INTEGER. It replaces the `regionLabelFor("pacific") → "Pacific"` shim 6.5 recorded, which was the deferred cost of not importing `regionName`. `DexProgress` gains `regionName` read from this table; the `regionLabelFor` function and its `regionLabel: (String) -> String` parameters on the three state functions are deleted.

**meta** — the importer compares `catalogueVersion` as before; the asset ships as version **2** (the plants are a content change that must reach the fresh install — trivially true, but the number should be honest).

**Backup manifest** (`data/backup/BackupModels.kt`): `BackupSpecies` gains `kingdom` (default `"animal"`), `uses` (default empty), `usesNote` (default null), `medicinalActivities` (default empty), `medicinalRecordCount` (default 0) and `usesAttribution` (default null) with kotlinx defaults so a v3 archive still parses; `planImport` re-bases user-added `dexNumber`s onto the new base rather than trusting the archived value (it already allocates the next U-number for a species it has not seen — this makes that the only path). The archive's identification string becomes "BioDex" (`BackupService` line 122's message and the manifest's `app` field).

Everything else in section 3 — entries, captures, species_ecosystems, the importer's invariants (3.3), `favoriteCaptureId` without a foreign key (3.4) — is untouched.

### 11.2 Domain changes (`domain/Models.kt`, `domain/DexProgressMath.kt`)

- `enum class Kingdom(wireName) { ANIMAL("animal"), PLANT("plant") }` with the same `fromWireName` fallback pattern (unknown → `ANIMAL`).
- `TaxClass` gains `TREE("tree", PLANT)`, `SHRUB("shrub", PLANT)`, `HERB("herb", PLANT)`, `FERN("fern", PLANT)`; every existing member gets `kingdom = ANIMAL`. `fromWireName` keeps falling back to `OTHER_INVERTEBRATE`. `TaxClass.entries.filter { it.kingdom == k }` is the list every chip row and picker uses — **no screen iterates `TaxClass.entries` raw any more** (the confirm card's `TaxClassPicker` currently does; slice 9 fixes it, since after slice 9 it would otherwise offer "tree" for a user-added sparrow).
- `enum class PlantUse(wireName) { EDIBLE("edible"), MEDICINAL("medicinal") }`.
- `SpeciesSummary` gains `kingdom` and `uses: Set<PlantUse>`; `SpeciesDetail` additionally `usesNote: String?`, `medicinalActivities: List<String>`, `medicinalRecordCount: Int`, `usesAttribution: String?`.
- `DukeIndex` (`data/catalogue/DukeIndex.kt`): a lazily-loaded, in-memory map from binomial (`"Genus species"`, lower-cased) to `DukeRecord(activities: List<String>, recordCount: Int, poison: Boolean)`, parsed once from the bundled asset `assets/catalogue/duke_ethnobot.json` (11.3) on first use — only a plant confirmation card ever needs it, so a session that never adds a plant never pays the parse. `lookup(accepted, synonyms)` tries the accepted name then each synonym in order and returns the first hit. `medicinalByRule(record) = record.activities.size >= 3` is the one rule the pipeline and the app share, written down here so they cannot drift: the pipeline's Python and this Kotlin both implement it and slice 12's fixture pins the same species on both sides of the threshold. `displayDexNumber(dexNumber, source, kingdom)`: `#021` / `P012` / `U01`. A companion `storedDexNumber(kingdom, n)` applies the base, used by the importer (the asset carries per-kingdom `dexNumber` 1..n, 11.3) and by nothing else.
- `Meter` is unchanged. `DexProgress` becomes `(regionId, regionName, animals: Meter, plants: Meter, perClass: List<Pair<TaxClass, Meter>>, perEcosystem: List<EcosystemProgress>)` with `EcosystemProgress(ecosystem, animals: Meter, plants: Meter)`. `userAddedCount` stays one number (`animals.userAdded + plants.userAdded`). `DexProgressMath.compute` partitions curated species by kingdom before doing what it does today; the existing tests are extended, not replaced — every v3 assertion about animals must still hold with an empty plant list, which is the property that says the plants did not touch the animal meter.
- `UsesNote.cautionSplit(note): Pair<String, String?>` — a pure function that splits the note at the first sentence beginning `Caution:` (case-insensitive, at a sentence start), so the detail screen and the confirm card render the same emphasis (S09) from one rule.

### 11.3 The catalogue pipeline (`tools/catalogue/`)

**Input files.** `curated_species.json` is split into three, so the plant curator and any animal edit never touch the same file:

- `region.json` — `catalogueVersion`, `regionId`, `regionName` ("Pacific USA"), and the seven ecosystems with the two renamed names (`alpine` → "Sierra & Cascade Mountains", `rocky-shore-kelp` → "Shoreline, Dunes & Kelp"; ids unchanged).
- `curated_animals.json` — the existing 120 entries, moved with `git mv` and otherwise byte-identical (the `ecosystems` block and header move to `region.json`).
- `curated_plants.json` — the 80 plants. Entry shape:

```json
{ "dexNumber": 47, "commonName": "Blue Elderberry", "scientificName": "Sambucus cerulea",
  "plantClass": "shrub",
  "ecosystemIds": ["riparian-wetland", "oak-chaparral", "urban-suburban"],
  "edible": true,
  "dukeName": "Sambucus nigra",
  "usesNote": "Berries, late summer to early autumn — cook or dry before eating; flowers for cordial and tea. Caution: raw berries, leaves, stems and bark are toxic; red-berried elders are not this species." }
```

`plantClass` is required and must be one of `tree` / `shrub` / `herb` / `fern`. `edible` is optional (default `false`) and is the curator's only use tag — **the `medicinal` tag is not an input field**; it is derived from Duke's (below), and a curator who disagrees with the derivation pins it with `overrides.medicinal: true|false`, which the provenance records. `dukeName` is an optional pin for the Duke's join when GBIF's accepted name and synonyms all miss (elderberry is the known case: Duke's files it under *Sambucus nigra*). `usesNote` is ≤ 240 characters and is **required** when `edible` is true (the part and the season) and when Duke's has a `Poison` record for the species (a `Caution:` sentence); it is forbidden when the species ends up with no use tag, and optional otherwise. `wikipediaTitle` and `overrides` work exactly as for animals. Growth form, ecosystem tags, the edible tag and the note are editorial judgment, never fetched — D13 and D14 say why. The medicinal tag, the activities and the poison check are sourced.

**The Duke's source.** Dr. Duke's Phytochemical and Ethnobotanical Databases (USDA ARS), **CC0**. One bulk file, not per-species queries: `Duke-Source-CSV.zip` (5.8 MB), figshare article 24660351. The data.gov and Ag Data Commons HTML pages return 403 to a plain fetch; the script resolves the download through `https://api.figshare.com/v2/articles/24660351/files` (which returns the real `download_url`, currently `https://ndownloader.figshare.com/files/43363335`) and caches the zip under `cache/duke/`, so a re-run makes no request. Inside, `ETHNOBOT.csv` holds 82,873 records over 13,010 taxa with `GENUS`, `SPECIES` and `ACTIVITY` columns. It is a medicinal corpus: 15 `Food` and 14 `Fruit` records in the whole file, which is why edible stays curated. `ACTIVITY` includes `Poison` (1,654 records), which is the caution checklist.

**Per plant, the script does:**

1. **GBIF** `species/match` as for animals. The match's `kingdom` must be `Plantae`; anything else is a validation *failure* (not a warning) — a plant entry that resolves to an animal is a curator typo that must not ship. GBIF's `class` is recorded in provenance and used for one decision only: `silhouetteRes = sil_tree_conifer` when `plantClass == tree` and the class is `Pinopsida` (or the match's `order` is `Pinales`, since GBIF's plant classes are inconsistent), else `sil_tree_broadleaf`; the other forms map to `sil_shrub`, `sil_herb`, `sil_fern`. A `plantClass` of `fern` whose GBIF class is not `Polypodiopsida` is a warning for the curator.
2. **Duke's join** — build `{(genus, species) → [activities]}` from `ETHNOBOT.csv` once per run. Look the plant up by the GBIF-accepted binomial, then by each synonym GBIF returns for the match (`species/{key}/synonyms`, one extra cached request per plant), then by `dukeName` if pinned; the first hit wins and `provenance.duke` records which name matched (`"duke:accepted"`, `"duke:synonym:Mahonia aquifolium"`, `"duke:pinned"`, or `"duke:none"`). Synonyms genuinely bite — Oregon grape has 4 records as *Mahonia aquifolium* and 0 as *Berberis aquifolium* — so the join is not optional. From the hit: `medicinalRecordCount` = records excluding `Poison`; `medicinalActivities` = the distinct non-`Poison` activities ordered by record count, capped at eight, title-cased; `medicinal` = `count(distinct non-Poison activities) >= 3` unless overridden; `poisonRecorded` (pipeline-internal) = any `Poison` record. `usesAttribution` is set when the hit is non-empty. **No hit is an ordinary state**: about a fifth of sampled species (devil's club, evergreen huckleberry) have nothing, and the report lists them under "no Duke's record" without counting them as failures.
3. **Wikipedia** — unchanged fetch, same section-finder chain. Plant articles commonly title the section "Distribution and habitat" or "Ecology", both already handled; the report's lede-fallback list will show how many needed pinning. Additionally, when the article has a section whose title contains "Uses", "Culinary", "Edib", "Medicin" or "Ethnobot", its stripped text (first 600 characters) is written to **`cache/plant_uses_review.txt`** beside the report — a curator aid for checking the hand-written `usesNote`, and **never** copied into the asset. The asset's `provenance.uses` is always `"curated"`.
4. Assemble with `kingdom: "plant"`, `taxClass: <plantClass>`, `uses` (edible from the input, medicinal from step 2), `usesNote`, `medicinalActivities`, `medicinalRecordCount`, `usesAttribution`, the silhouette from step 1, and provenance — `provenance.uses` is `"curated"` for the edible half and `"duke:…"` for the medicinal half, never unconditionally `"curated"`.

Animals go through the existing path and gain `kingdom: "animal"`, `uses: []`, `usesNote: null`, the three Duke's columns empty, and a GBIF kingdom check (`Animalia`) of their own.

**Output** stays one file, `app/src/main/assets/catalogue/pacific.json`, `catalogueVersion: 2`, with `regionName` and the species array carrying both kingdoms; `dexNumber` in the asset is the per-kingdom number (1–120, 1–80) and the importer applies the base (11.2) — the asset stays readable and the curator never types 2047.

**A second asset**, `app/src/main/assets/catalogue/duke_ethnobot.json`: the whole `ETHNOBOT` table compacted to `{"genus species": {"a": ["Astringent", …], "n": 105, "p": true}}` over all 13,010 taxa, activities deduplicated into a string table so the file lands around 1–2 MB. It is committed like `pacific.json`, regenerated by the same run, and is what the app's `DukeIndex` (11.2) reads for user-added plants — the CC0 license is what makes bundling it legitimate, and bundling is what makes the lookup work offline (D3). A `LICENSE-duke.txt` beside it records the source and the CC0 statement, and `licenses.md` gains the same paragraph.

**Validation** grows: exactly 120 animals and exactly 80 plants; animal dex numbers exactly 1–120, plant dex numbers exactly 1–80; every `kingdom`/`taxClass` pairing valid; `uses` ⊆ {edible, medicinal}; `usesNote` present when `edible` or `poisonRecorded`, absent when `uses` is empty; **every species with a Duke's `Poison` record carries a sentence beginning `Caution:` — a build failure otherwise**, so the set of cautioned species is decided by the source, not by whoever wrote the notes; `medicinal` ⇔ `medicinalActivities.size >= 3` unless `provenance.medicinal == "override"`; `medicinalActivities` empty ⇒ `usesAttribution` null; every animal has `uses == []` and empty Duke's fields; `silhouetteRes` consistent with class and kingdom; the GBIF kingdom matches the declared kingdom; `duke_ethnobot.json` parses and contains every plant that had a hit.

**The plant list** (the curatorial brief for slice 10, in dex order; swap species freely, keep the shape). Uses in brackets — `E` edible, `M` medicinal, `!` the note needs a `Caution:` sentence. All are native to the region unless marked *nat.* (naturalized and ubiquitous).

Trees, P001–P040: Douglas-fir *Pseudotsuga menziesii*; Coast Redwood *Sequoia sempervirens*; Giant Sequoia *Sequoiadendron giganteum*; Ponderosa Pine *Pinus ponderosa*; Sugar Pine *P. lambertiana*; Jeffrey Pine *P. jeffreyi*; Lodgepole Pine *P. contorta*; Whitebark Pine *P. albicaulis*; Great Basin Bristlecone Pine *P. longaeva*; Single-leaf Pinyon *P. monophylla* [E]; Western Juniper *Juniperus occidentalis*; Incense-cedar *Calocedrus decurrens*; Western Redcedar *Thuja plicata* [M]; Sitka Spruce *Picea sitchensis* [E, tips]; Western Hemlock *Tsuga heterophylla*; Grand Fir *Abies grandis*; California Red Fir *A. magnifica*; Noble Fir *A. procera*; Western Larch *Larix occidentalis*; Pacific Yew *Taxus brevifolia* [M !]; Monterey Cypress *Hesperocyparis macrocarpa*; Coast Live Oak *Quercus agrifolia* [E !, acorns leached]; Valley Oak *Q. lobata* [E !]; Blue Oak *Q. douglasii*; California Black Oak *Q. kelloggii* [E !]; Oregon White Oak *Q. garryana*; Tanoak *Notholithocarpus densiflorus*; Pacific Madrone *Arbutus menziesii* [E]; Bigleaf Maple *Acer macrophyllum* [E]; Vine Maple *A. circinatum*; Red Alder *Alnus rubra* [M]; Black Cottonwood *Populus trichocarpa* [M]; Quaking Aspen *P. tremuloides*; Pacific Willow *Salix lasiandra* [M]; California Bay Laurel *Umbellularia californica* [E !]; Pacific Dogwood *Cornus nuttallii*; Joshua Tree *Yucca brevifolia*; Pacific Crabapple *Malus fusca* [E]; Curl-leaf Mountain Mahogany *Cercocarpus ledifolius*; California Sycamore *Platanus racemosa*.

Shrubs, P041–P058: Salal *Gaultheria shallon* [E]; Evergreen Huckleberry *Vaccinium ovatum* [E]; Red Huckleberry *V. parvifolium* [E]; Salmonberry *Rubus spectabilis* [E]; Thimbleberry *R. parviflorus* [E]; Himalayan Blackberry *R. armeniacus* [E, *nat.*]; Blue Elderberry *Sambucus cerulea* [E M !]; Oregon Grape *Berberis aquifolium* [E M]; Common Manzanita *Arctostaphylos manzanita* [E]; Toyon *Heteromeles arbutifolia* [E !]; Western Serviceberry *Amelanchier alnifolia* [E]; Nootka Rose *Rosa nutkana* [E]; Red-flowering Currant *Ribes sanguineum* [E]; Big Sagebrush *Artemisia tridentata* [M]; Yerba Santa *Eriodictyon californicum* [M]; Chokecherry *Prunus virginiana* [E !]; Cascara *Frangula purshiana* [M !]; Devil's Club *Oplopanax horridus* [M].

Herbs, P059–P077: Miner's Lettuce *Claytonia perfoliata* [E]; Stinging Nettle *Urtica dioica* [E M !]; Common Yarrow *Achillea millefolium* [M]; Common Camas *Camassia quamash* [E !, Death Camas lookalike]; Fireweed *Chamaenerion angustifolium* [E]; Western Wild Ginger *Asarum caudatum* [M !]; Self-heal *Prunella vulgaris* [M]; Common Mullein *Verbascum thapsus* [M, *nat.*]; Beach Strawberry *Fragaria chiloensis* [E]; Broadleaf Cattail *Typha latifolia* [E !, iris lookalike]; Wapato *Sagittaria latifolia* [E]; Yerba Buena *Clinopodium douglasii* [E M]; California Poppy *Eschscholzia californica* [M]; Nodding Onion *Allium cernuum* [E !]; Common Chickweed *Stellaria media* [E, *nat.*]; Dandelion *Taraxacum officinale* [E M, *nat.*]; Broadleaf Plantain *Plantago major* [M, *nat.*]; Watercress *Nasturtium officinale* [E !, *nat.*]; California Mugwort *Artemisia douglasiana* [M].

Ferns, P078–P080: Western Sword Fern *Polystichum munitum*; Licorice Fern *Polypodium glycyrrhiza* [E M]; Bracken *Pteridium aquilinum* [E !, documented but carcinogenic — the note says so].

That is 40 / 18 / 19 / 3 as growth forms, with about 35 edible tags; the `M` marks above are the curator's expectation, but **the medicinal tag is whatever Duke's says** — the report shows the derived set and the curator pins only genuine disagreements. The user's "40 trees, 25 edible, 15 medicinal" named this shape (one composition bucket plus overlapping tags), confirmed after the draft. The `!` marks are likewise expectations: the binding list of species that *must* carry a `Caution:` sentence is the Duke's `Poison` set the build enforces, and the curator adds the lookalike and preparation cautions on top. What remains hand-written is the edible notes (part and season) and those cautions — written against the review file and a published regional field guide, and kept short, because they are the only text in the plant list without a source behind it.

**README** gets a "Plants" section carrying the entry shape, the Duke's download and join (with the figshare API route, since the landing pages 403), the validation rules including the poison-caution rule, the `plant_uses_review.txt` aid, and the sentence that edible is curated while medicinal is sourced. The `User-Agent` becomes `BioDex/1.0 (…)`.

### 11.4 UI changes

**App bar (M29, D11)** — `GridAppBar` renders the serif title "BioDex", `RegionPill(progress.regionName)` ("PACIFIC USA"), then two `ProgressPill`s: animals in `accent` on `accentSoft` as today, plants in `ok` on `accentSoft` with a small leaf glyph, both without inner spaces (`47/120`). The plant pill is omitted while `plants.total == 0`. The title is `maxLines = 1, overflow = Ellipsis` inside a `weight(1f, fill = false)` so it gives way before the pills do. The Stats header follows the same pattern with the two pills under the title. The Settings About text becomes "BioDex 1.0 — Pacific USA BioDex, a personal life list…", followed by M30's disclaimer sentence.

**Chip row (M23)** — `DexGridFilters` gains `kingdom: Kingdom?` and `use: PlantUse?`; `matchesFilters` ANDs two more clauses (`use` matches iff `use in species.uses`, which is never true for an animal). The row order is `All · Uncaught · Animals · Plants · Edible · Medicinal · <classes> · <ecosystems>`, single-select within each dimension, tap-to-clear as today. The class chips rendered are `TaxClass.entries.filter { filters.kingdom == null || it.kingdom == filters.kingdom }`. No coupling rules between dimensions beyond AND — selecting "Trees" already implies plants, so the state does not need to set `kingdom`. `chipLabel()` gains `Trees / Shrubs / Herbs / Ferns` (an exhaustive `when` that slice 9 must extend or the build breaks; same for `classLabel()` in `StatsState.kt`).

**Grid cell** — unchanged except the number: `P012` is the kingdom mark. No extra badge.

**Silhouettes (M25)** — five new VectorDrawables in `res/drawable/`: `sil_tree_conifer`, `sil_tree_broadleaf`, `sil_shrub`, `sil_herb`, `sil_fern`, drawn in the register of the seven existing ones (flat, single path, 100-unit viewBox) and matching `mockup.html`'s new symbols. `Silhouettes.byClass` gains `TREE → sil_tree_broadleaf` (the class fallback; the asset's `silhouetteRes` picks conifer per species), `SHRUB`, `HERB`, `FERN`. `Silhouette.kt` is **not** touched by slice 9 — its map already has a fallback, so a `TREE` species resolves to `sil_other_invertebrate` for the hours between slices 9 and 11 without breaking a build.

**Entry detail (M24, D15)** — `entryDetailUiState` gains the kingdom; the screen renders, after Habitat, for `PLANT` with `uses.isNotEmpty()`, a new `UsesSection` in `ui/common/`: a section header "Uses", a row of tag chips (`Edible` in `ok`, `Medicinal` in `accent`), the `Caution:` sentence (from `UsesNote.cautionSplit`) rendered first in `stop` on `stopSoft` with a leading `⚠`, then the rest of the curated note in body text, then — when `medicinalRecordCount > 0` — a source line in the `muted` attribution register, "Duke's records 105 traditional uses: astringent, diuretic, wound, …", so the sourced half and the curated half are visibly different kinds of text, and the M30 disclaimer plus `usesAttribution` in the `AttributionLine` style beneath; for `PLANT` with no uses, and for every animal, **nothing** — Habitat is followed by the photo strip.

**Stats (M15, M26)** — the big number becomes two stacked meters ("47 / 120 animals", "3 / 80 plants") each with its own bar; the "+3 of your own" line stays one number. "By ecosystem" rows render two thin bars per ecosystem — animal in `warn` as today, plant in `ok` — with `12/24 · 2/15` in the value column. "By class" becomes two groups under sub-headers "Animals" and "Plants", using the same `ClassMeter` row; `perClass` is grouped by `taxClass.kingdom` in `StatsState`. The recently-caught strip is cross-kingdom and unchanged.

**Register screen (M28, D18)** — `RegisterScreen` moves to `Scaffold(topBar = { title + SearchField }, bottomBar = { PhotoAttachRow + Lens link + grant warning + error + PrimaryCta + GhostCta })` with a `LazyColumn` of `SpeciesResultRow`s as the content. `REGISTER_RESULT_LIMIT` and its `.take()` are deleted (the cap existed only to keep the buttons reachable — 6.6). Scroll-on-arrival: `RegisterUiState` gains `preselectedIndex: Int?` (the index of `selected` within `results`, or null); the screen holds a `rememberLazyListState()` and a `LaunchedEffect(preselectedIndex != null && !scrolledOnce)` that calls `listState.scrollToItem(index, scrollOffset = -viewportHeight/3)` exactly once per screen instance (guarded by `rememberSaveable`), so a rotation does not re-scroll under the user's thumb. The pure `registerUiState` function is where the index is computed, so the JVM test is "preselected species is present in the results and its index is reported" with the full catalogue and an empty query.

**Confirm card (M19, M27)** — `ConfirmSpeciesUiState.Ready` gains `kingdom`, `plantClass`, `uses`, `usesNote`. GBIF's match carries `kingdom` and `SpeciesLookupRepository` reads it. The card: the kingdom is shown beside the class in the match row ("Arbutus menziesii · plant · tree"); the `TaxClassPicker` offers only the resolved kingdom's classes, with a small "animal / plant" toggle beside it for the mis-resolved case (switching resets the class to the kingdom's default: `OTHER_INVERTEBRATE` or `HERB`); for a plant the card gains a `UsesEditor`: an edible toggle, a medicinal toggle defaulted from `DukeIndex.lookup(accepted, synonyms)` by `medicinalByRule`, the Duke's activities and count shown read-only beneath it (or "No Duke's record" — an ordinary state), and a single-line note field pre-filled with `Caution: recorded as poisonous in Duke's ethnobotanical database.` when the record has `poison = true`, with the same caution rendering as the detail screen; `defaultPlantClass(gbifClass, gbifOrder)` is the pipeline's step 1, ported. `SpeciesLookupRepository` reads `DukeIndex` for `Plantae` — an offline, in-process lookup, so the M20 offline path still creates the pending entry immediately and the Duke's fields arrive on backfill with everything else. `AddSpeciesRegistrar` and `domain/UserSpecies.kt` write the new fields and enforce `usesNote == null` when `uses` is empty; `applyFieldEdits` treats `uses` and `usesNote` as user-owned once touched (M21 applies to them as to any field).

**Unlock reveal (S10)** — `UnlockRevealOverlay` takes the kingdom's meter and label: "4 / 80 plants".

### 11.5 The rename

Mechanical, and the reason slice 9 is sequential: `git mv app/src/{main,test,androidTest}/kotlin/dev/tlong/animaldex → …/biodex`, then `package dev.tlong.animaldex` → `package dev.tlong.biodex` and every `import dev.tlong.animaldex.` in every `.kt` under `app/src`; `namespace` and `applicationId` in `app/build.gradle.kts`; `rootProject.name` in `settings.gradle.kts`; `app_name` in `strings.xml` → "BioDex"; the theme name in `themes.xml`; `AnimalDexTheme`/`AnimalDexColors` → `BioDexTheme`/`BioDexColors`; the database file name in `AppContainer` → `biodex.db`; the `User-Agent` → `BioDex/1.0 (personal Android app; tlong@unified.health)`; the share-sheet title in `SettingsScreen` ("Save your BioDex backup"); the archive message in `BackupService`; the `FileProvider` authority is `${applicationId}.files` and follows automatically. `licenses.md` and the pipeline script's `USER_AGENT` too. The launcher icon is unchanged. `grep -ri animaldex` and `grep -r "Animal Dex"` over the repository must both come back empty except in this document's history and DESIGN.md's changelog line — that grep is slice 9's first done-check.

On the phone: `adb uninstall dev.tlong.animaldex` by hand, then `./gradlew installDebug`. Two launcher entries would otherwise coexist, which is harmless but confusing.

### 11.6 The slice map, continued

Five slices, numbered 9–13. **Hot files** for this expansion are everything slice 9 touches: after it lands, `data/db/*`, `domain/Models.kt`, `domain/DexProgressMath.kt`, `AppContainer.kt`, `NavGraph.kt`, `app/build.gradle.kts` and `settings.gradle.kts` are frozen again. Slices 10–13 are file-disjoint from each other by construction and touch no hot file; the table at the end says exactly which directories each owns. Every slice ends with `./gradlew testDebugUnitTest` and `./gradlew installDebug` plus its phone check — the phone exists now, so "not verified" is no longer an acceptable line in a slice's deviation entry.

---

**Slice 9 — Rename, schema delta, kingdom plumbing, header.** *Sequential; everything depends on it.*
Goal: the app is BioDex, installs fresh as `dev.tlong.biodex`, and every layer knows about kingdoms, uses and the plant number range — with the shipped 120-animal behaviour unchanged.
Does: the rename (11.5) including deleting the old `app/schemas/…animaldex…` directory; the schema delta (11.1) and the `regions` table with its DAO, entity, importer step and asset model field; the domain changes (11.2) including `Kingdom`, the four plant `TaxClass` members with their `kingdom`, `PlantUse`, the bases, `displayDexNumber` and `storedDexNumber`, the per-kingdom `DexProgress` and math, `UsesNote.cautionSplit`; the importer applies the per-kingdom base and enforces the kingdom/class pairing; the three Duke's columns and the backup manifest fields and re-basing (11.1); the exhaustive `when`s that stop compiling — `chipLabel()` in `DexGridState.kt`, `classLabel()` in `StatsState.kt` — get their four plant labels; the `TaxClassPicker` in `ConfirmCardScreen.kt` filters by the resolved kingdom (the only `ui/addspecies/` edit this slice makes, so a user-added animal is never offered "tree" in the interim); `GridAppBar` and the Stats header render "BioDex" + `RegionPill(regionName)` + the two progress pills with the overflow rules (11.4); the About text and disclaimer; `regionLabelFor` deleted. Extends the existing tests (progress math with an empty plant list must reproduce every v3 assertion; importer invariants with a two-kingdom fixture; backup round trip with the new fields).
Does **not**: touch `Silhouette.kt`, `res/drawable/`, the detail screen's slot, the chip row's new chips, the Stats bars, the Register layout, the pipeline, or the asset. The asset still says `catalogueVersion: 1` with 120 animals and no `kingdom` field (only its `regionName` string changes, above) — the asset model defaults `kingdom` to `animal` and `uses` to empty so the v1 asset imports unchanged.
Done: `grep -ri animaldex` over the repo is empty; `testDebugUnitTest` passes with every v3 test still present; `adb uninstall dev.tlong.animaldex && ./gradlew installDebug` puts a "BioDex" launcher icon on the phone; the grid header reads "BioDex · PACIFIC USA · 0/120" with no plant pill (slice 9 makes exactly one edit to `pacific.json`: `regionName` → `"Pacific USA"`, safe because 9 is sequential and slice 10 regenerates the whole file); registering one animal reads 1/120 and the reveal still plays.

**Slice 10 — Plant catalogue pipeline and the 80 plants.** *Parallel-safe with 11, 12 and 13 (touches only `tools/catalogue/` and the single asset file).*
Goal: the Pacific USA catalogue asset carries 120 animals and 80 plants, validated, with provenance, uses and the conifer/broadleaf silhouette choice.
Does: the three-file input split (11.3, `git mv` for the animals), `curated_plants.json` authored to the brief with every use and note written from a real reference and every `!` species carrying a `Caution:` sentence; `build_catalogue.py` grows the plant path, the Duke's download (via the figshare API, cached), the synonym-aware join, the derived medicinal tag, the poison-caution rule, the kingdom checks, `plant_uses_review.txt` and the new validation; README's Plants section; the regenerated `pacific.json` at `catalogueVersion: 2` with the two ecosystem renames, plus the new `duke_ethnobot.json` and `LICENSE-duke.txt`.
Done: `python3 build_catalogue.py` exits 0 from a cold cache and makes zero requests on re-run; the report lists lede fallbacks and GBIF warnings for review; the asset validates (120 + 80, ranges, pairings, notes, every Duke's-poison species cautioned); the report lists the derived medicinal set, the no-record set and which name each join matched on (Oregon grape must show `duke:synonym:Mahonia aquifolium`); `duke_ethnobot.json` is under 2 MB; `git diff --stat` shows `curated_animals.json` as a pure rename. **The asset is committed only after slice 9 has landed** — imported by the v3 app, plant dex numbers 1–80 collide with animals on the unique index and the import fails; the pipeline work itself can proceed in parallel with slice 9.

**Slice 11 — Plants in the UI: silhouettes, chips, detail, stats.** *Parallel-safe with 10, 12 and 13.*
Goal: a plant looks and reads like a plant everywhere the read-side UI shows it.
Does: the five silhouette drawables and `Silhouettes.byClass` (11.4); the kingdom and use chips and the kingdom-filtered class chips in `ui/grid/`; `UsesSection` in `ui/common/` and the detail screen's uses slot (`ui/detail/`); the Stats screen's two overall meters, two-bar ecosystem rows and kingdom-grouped class bars (`ui/stats/`, `ui/common/Meters.kt`); the reveal's kingdom label (`ui/reveal/`). Tests run against a **two-kingdom fixture** in `app/src/test/resources/` (ten animals, six plants including one with both uses, a caution and a Duke's line, one medicinal-only with activities and no note, one with no uses) — the slice-3 pattern — so the slice is verifiable before slice 10's asset exists.
Done (JVM): a plant with no uses produces a detail state with no uses section; a plant with uses produces the section with the caution split; the "Edible" filter never returns an animal; Stats groups classes under the right kingdom and every v3 animal number is unchanged. Done (phone, **after slice 10's asset is committed**): the grid shows P001 Douglas-fir with a conifer silhouette after #120; "Plants" then "Edible" narrows to the edible plants; Blue Elderberry's detail shows the tags, the red caution line, the note, the "Duke's records 60 traditional uses" line and the attributed disclaimer, ; Yarrow's shows Medicinal with 105 records and no curated note; Douglas-fir's detail goes from Habitat straight to photos; the header reads `1/120 · 0/80` after the slice-9 animal; Stats reconciles with the grid by hand-count for both kingdoms.

**Slice 12 — User-added plants.** *Parallel-safe with 10, 11 and 13 (owns `data/net/`, `data/repo/AddSpeciesRegistrar.kt`, `domain/UserSpecies.kt`, `ui/addspecies/`).*
Goal: adding a name that resolves to a plant produces a plant entry with a growth form, uses and a note the user chose.
Does: `GbifClient` surfaces `kingdom`, `class`, `order` and the synonym list from the match; `DukeIndex` over the bundled asset (11.2); `SpeciesLookupRepository` consults `DukeIndex` for `Plantae`; `defaultPlantClass()`; the confirm card's kingdom display, kingdom toggle, kingdom-scoped class picker (taking over the filter slice 9 put in) and `UsesEditor`; the registrar and `UserSpecies` write and validate the new fields, with `uses`/`usesNote` under M21's user-owned rule; the details-pending default (`animal` / `other_invertebrate`) corrected on backfill. Fixtures: a captured GBIF payload for a plant ("Pacific Madrone" → *Arbutus menziesii*, `kingdom: Plantae`, `class: Magnoliopsida`), one for a conifer, and a twelve-taxon slice of `duke_ethnobot.json` holding yarrow (105, medicinal), Oregon grape under its *Mahonia* synonym (4, below threshold), an elder with `p: true`, and nothing for devil's club.
Done (JVM): the Duke's lookup finds Oregon grape through its synonym and nothing for devil's club without error; the medicinal toggle defaults on for yarrow and off for Oregon grape; a poison record pre-fills the caution; accepting a plant card writes `kingdom = plant`, the chosen class, uses and note; a hand-edited note survives a re-backfill; a mis-resolved kingdom toggled on the card writes the toggled kingdom with that kingdom's default class. Done (phone): "Trailing Blackberry" (*Rubus ursinus* — a real Pacific plant that is deliberately **not** on the 80-list) typed on the Register screen shows a plant card with the kingdom read as plant, the Shrubs form picked, Edible toggled by hand, a note typed; accepting creates U01 trailing the plants, outside both meters, whose detail shows the uses section. "Pacific Rhododendron" (*Rhododendron macrophyllum*, no uses) as a second add shows a plant detail with nothing in the slot.

**Slice 13 — The Register screen layout.** *Parallel-safe with 10, 11 and 12 (owns `ui/register/` only).*
Goal: the bug found on the phone is gone — search and actions are always on screen, and arriving from a detail screen shows the pre-selected species selected and in view.
Does: the `Scaffold` restructure, the `LazyColumn`, the deletion of `REGISTER_RESULT_LIMIT`, `preselectedIndex` in the pure state function, the one-shot scroll (11.4).
Done (JVM): with the full catalogue and an empty query, the state reports the pre-selected species' index; the results are uncapped. Done (phone): from an uncaught species' detail, "Register this species" opens with that row visible and selected and the button reading "Register — <name>" without scrolling; typing narrows the list while the photo row and button stay put; with an empty query the list scrolls through all 200 species and the Register button never leaves the bottom of the screen; the existing register-and-reveal path still works end to end.

---

**File ownership after slice 9** (the disjointness table):

| Slice | Owns | Never touches |
|---|---|---|
| 10 | `tools/catalogue/**`, `app/src/main/assets/catalogue/pacific.json` | anything under `app/src/*/kotlin` |
| 11 | `res/drawable/sil_*`, `ui/common/Silhouette.kt`, `ui/common/Meters.kt`, new `ui/common/UsesSection.kt`, `ui/grid/`, `ui/detail/`, `ui/stats/`, `ui/reveal/`, its fixture under `test/resources/` | `ui/register/`, `ui/addspecies/`, `data/**`, `domain/**` |
| 12 | `data/net/`, `data/catalogue/DukeIndex.kt` (new file only), `data/repo/AddSpeciesRegistrar.kt`, `data/repo/UserSpeciesStore.kt`, `domain/UserSpecies.kt`, `ui/addspecies/`, its fixtures | `ui/grid/`, `ui/detail/`, `ui/stats/`, `ui/register/`, `ui/common/`, `domain/Models.kt` |
| 13 | `ui/register/` | everything else |

`ui/common/DexComponents.kt` is the one file two slices might both want (11 for the tag chips, 12 for the editor's chips): slice 11 puts its chips in the new `UsesSection.kt`, and slice 12 reuses them from there or draws its own in `ui/addspecies/` — neither edits `DexComponents.kt`. `AppContainer.kt` and `NavGraph.kt` are not edited by 10–13 at all: no route changes, no new wiring (the lookup repository's kingdom branch is internal).

**Ordering summary**: 9 first, strictly. Then 10, 11, 12 and 13 in any order or all at once; the only cross-slice dependency after 9 is that slice 10's asset must be committed before slice 11's *phone* check (its JVM check does not wait), and slice 12's phone check is more convincing once 11 renders the uses section. Slice 13 can also run before 9 if it lands and is then swept up by 9's `git mv` — but there is no reason to, and one sequencing rule is simpler than two.

### 11.7 Risks specific to this expansion

**R10 — GBIF's plant taxonomy is inconsistent.** Many flowering plants come back with no `class` at all, or with `Magnoliopsida` for everything, and conifers sometimes with class `Pinopsida` and sometimes only order `Pinales`. The only automated decision that leans on it — conifer versus broadleaf silhouette — checks both and falls back to broadleaf, and the curator's `overrides.silhouetteRes` pins the rest. Growth form itself is curatorial precisely so this cannot mis-class a plant.

**R11 — A wrong edible note.** The pipeline can check that a note exists, not that it is true, and the edible notes are the one unsourced text in the plant list. Mitigation: the medicinal half is no longer written at all (Duke's supplies it); the caution set is decided by Duke's `Poison` records, not by the writer; the notes are short and limited to part and season; the disclaimer sits on every uses section (M30); and the app refuses to identify (D2). The residual risk is the user's, and the design says so rather than pretending the app carries it.

**R15 — The Duke's join misses through nomenclature.** Duke's keys on genus and species strings from its own era, so an accepted name that GBIF has since moved (Oregon grape *Berberis* ↔ *Mahonia*, elder *Sambucus cerulea* ↔ *S. nigra*) returns nothing on the first try. The synonym pass and the `dukeName` pin cover it, and the report's "no Duke's record" list is the check: a well-known medicinal plant on that list is a join miss, not a true absence. Duke's spelling variants (`Achillea millefolium` versus a hyphenated or misspelled row) are normalised by lower-casing and collapsing whitespace before the join; anything stranger is pinned.

**R12 — Wikipedia habitat prose for plants.** Plant articles more often lead with taxonomy and put range under "Distribution"; expect more lede fallbacks than the animal run had, and more `wikipediaTitle` pins for species GBIF lumps (elderberries are the known case: `Sambucus cerulea` versus `S. nigra ssp. caerulea`). The report quantifies it; it is a curating afternoon, not an engineering problem.

**R13 — Twelve silhouettes drawn by hand, on a phone, with no previews.** The five plant shapes are the visual difference between "plants are in the app" and "plants look like a slug". `adb exec-out screencap` after the first install of slice 11 is the review, and the mockup's symbols are the reference; expect one redraw.

**R14 — The interim between slices 9 and 11.** After 9, a user-added plant cannot yet exist and the asset has no plants, so nothing renders a `TREE` class; but if slice 10's asset were committed early it would render with the other-invertebrate silhouette. Harmless and temporary, and the commit-after-9 rule on the asset (slice 10) plus the fixture-based JVM check (slice 11) keep it from ever being what the user sees.

---

## 12. Feature removals

### 12.1 Bird-call playback, removed 2026-09-02

**The whole call feature is gone, deliberately.** A future reader who finds `CallPlayer`, `XenoCantoClient` or `callUrl` in this repository's git history is looking at a product decision, not an abandoned attempt.

**Why.** The app began as an animal dex, where a species' call sat alongside habitat and picture as one of three headline fields. It now covers two kingdoms — 120 animals and 80 plants — and a call is meaningless for a plant, a slug, a fish or most mammals. It also never worked: the Xeno-canto v3 API has required a per-account key since October 2025, the user chose not to create one, so every `callUrl` in every catalogue this project ever shipped was null and the player was never handed a URL on any machine. What was removed had therefore never executed on a phone.

**What went.** The `CallPlayerRow` composable and its static waveform; `CallRowState`, `CallPlayback`, `callRowState` and the `callRow` field on the detail state; `CallPlayer`/`ExoCallPlayer`; the Media3 `SimpleCache` audio cache (`AUDIO_CACHE_DIR`, its 200 MB cap and `MediaCache.kt` — the two image-cache constants moved into `ImageLoaders.kt`); the three Media3 dependencies and their version-catalog entries; `XenoCantoClient` and its place in `SpeciesLookupRepository`; the confirm card's call-found row; `BuildConfig.XC_API_KEY` and its `local.properties` plumbing in `app/build.gradle.kts`; the `callUrl` and `callAttribution` columns in Room, the catalogue asset, the domain models and the backup manifest; the pipeline's Xeno-canto step, its rate-limit delay and its report sections; the Settings screen's audio-cache row; and the Xeno-canto entry in `licenses.md`. Every test that existed only to exercise call behaviour was **deleted rather than adapted** — a test for a feature that no longer exists passes forever while checking nothing.

**What stayed, and why.** Coil's image disk cache is untouched; only audio went. `NetworkMonitor` and `EntryDetailUiState.online` stay: the hero image still needs to tell "not cached yet" from "failed". The `media/` package keeps its name and now holds the image loader, the image cache constants, the cache manager and the network monitor.

**No Room migration was written.** The app installs fresh under `dev.tlong.biodex` and the user holds no saved catches, so the two columns were dropped in place, the database stayed at `version = 1`, and the checked-in schema JSON regenerated. The rule that every later schema change ships a hand-written `Migration` (R7) resumes with the first real capture.

**One dependency detail.** `androidx.exifinterface` was pinned at 1.4.1 in the version catalog while Media3 pulled 1.4.2 transitively, so every build to date compiled against 1.4.2 (1.5). Removing Media3 would have silently dropped the graph to 1.4.1, so the catalog pin moved to **1.4.2** and the resolved graph is unchanged.

**Documents.** DESIGN.md is at v5: M06 and D4 are struck out in place rather than renumbered (the surviving requirements are referenced by number throughout), M04/M05/M17/M18/M19/M24/M27/S02 lost their call clauses, and D15 was rewritten — a plant's uses section no longer "stands where the call row was", it simply follows habitat. `mockup.html`'s frames 2 and 6 lost the call row.
