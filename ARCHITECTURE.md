# Animal Dex — Technical Architecture (v1)

Companion to `DESIGN.md` (product requirements, approved) and `mockup.html` (visual design, approved). This document tells the implementing agents every cross-cutting decision so no slice has to invent one. Written 2026-09-01; every version number below was verified against release pages on that date.

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
| Media3 | `androidx.media3:media3-exoplayer`, `media3-datasource-okhttp`, `media3-database` | **1.10.0** |
| OkHttp | `com.squareup.okhttp3:okhttp` | **4.12.0** (shared by Coil, Media3 and the API clients) |
| kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.9.0** |
| kotlinx-coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.10.2** |
| ExifInterface | `androidx.exifinterface:exifinterface` | **1.4.1** |
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

Slice 1 built the stack for real. Everything in 1.1 held: Gradle 8.13, AGP 8.13.2, Kotlin 2.3.10, the matching Compose and serialization compiler plugins, and KSP `2.3.11` all resolved and compiled with no change. Room 2.8.4, Coil 3.5.0, Media3 1.10.0, OkHttp 4.12.0, kotlinx-serialization 1.9.0, kotlinx-coroutines 1.10.2 and activity-compose 1.13.0 also resolved as pinned, so risk R1's two named worries (the KSP plugin id and serialization 1.9.0) did not materialize.

Three androidx versions in 1.2 did not. Each of them declares a hard floor of **AGP 9.1.0 and compileSdk 37**, which `assembleDebug` enforces as a build failure, not a warning. Rather than move the whole toolchain to AGP 9 (the decision in 1.1 rejects that deliberately, and API 37 is not installed on the build machine), each library steps back to the newest release that builds against AGP 8.13.2 / compileSdk 36. The version catalog carries the same note at each entry.

| Library | Planned | Actual | Reason |
|---|---|---|---|
| Compose BOM | 2026.08.00 | **2026.06.01** | 2026.08.00 carries Compose 1.12.0, which requires AGP 9.1+ / compileSdk 37. 2026.06.01 carries Compose 1.11.4 and Material 3 in the same line. |
| Navigation Compose | 2.10.0 | **2.9.8** | Same AGP 9.1+ / compileSdk 37 floor. Type-safe `@Serializable` route objects have shipped since Navigation 2.8, so section 6.1's routing design is unaffected. |
| Lifecycle | 2.11.0 | **2.10.0** | Same AGP 9.1+ / compileSdk 37 floor. `collectAsStateWithLifecycle()` and the Compose ViewModel helpers behave identically. |

One further detail worth knowing rather than correcting: `androidx.exifinterface` is pinned at 1.4.1 but Media3 pulls 1.4.2, so Gradle resolves the graph to 1.4.2. Nothing depends on the difference.

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
        │       │   ├── net/                     # GbifClient, WikipediaClient, XenoCantoClient
        │       │   └── repo/                    # DexRepository, SpeciesLookupRepository
        │       ├── domain/                      # plain models the UI consumes (Species, Entry,
        │       │                                #   Capture, DexProgress) + mapping from entities
        │       ├── ui/
        │       │   ├── theme/                   # Color.kt, Type.kt, Theme.kt (section 6.4)
        │       │   ├── nav/NavGraph.kt          # all routes, one file
        │       │   ├── common/                  # shared composables: meters, chips, SpeciesCell,
        │       │   │                            #   AttributionLine, PhotoThumb (broken-state aware)
        │       │   ├── grid/                    # DexGridScreen + DexGridViewModel
        │       │   ├── detail/                  # EntryDetailScreen + ViewModel + CallPlayer
        │       │   ├── register/                # RegisterScreen + ViewModel
        │       │   ├── addspecies/              # ConfirmCardScreen + ViewModel (user-added flow)
        │       │   ├── reveal/                  # UnlockRevealOverlay (composable, not a route)
        │       │   ├── photoviewer/             # PhotoViewerScreen + ViewModel
        │       │   ├── stats/                   # StatsScreen + ViewModel
        │       │   └── settings/                # SettingsScreen + ViewModel, export/import
        │       └── media/                       # Media3 player wrapper + audio cache setup
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
| callUrl | TEXT nullable | |
| infoUrl | TEXT nullable | |
| imageAttribution | TEXT nullable | pre-formatted credit line, e.g. `Wikimedia Commons · CC BY-SA 4.0 · J. Doe` |
| callAttribution | TEXT nullable | e.g. `Xeno-canto XC123456 · CC BY-NC 4.0 · R. Smith` |
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
      "callUrl": "https://xeno-canto.org/…",
      "infoUrl": "https://en.wikipedia.org/wiki/Western_screech_owl",
      "imageAttribution": "Wikimedia Commons · CC BY-SA 4.0 · <author>",
      "callAttribution": "Xeno-canto XC123456 · CC BY-NC-SA 4.0 · <recordist>",
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

---

## 5. Network layer

### 5.1 What happens when

| Call | Build time (pipeline) | Runtime (app) |
|---|---|---|
| GBIF `GET /v1/species/match?name=` | every curated species | user-added lookup + backfill (M18/M20) |
| Wikipedia parse API (sections → habitat), REST summary (lede + image) | every curated species | user-added lookup + backfill |
| Xeno-canto `GET /api/3/recordings?query=sp:"..."` (API key) | every curated species | user-added lookup + backfill |
| Wikimedia image bytes | — | streamed by Coil on view, disk-cached (S02) |
| Xeno-canto audio bytes | — | streamed by Media3 on play, disk-cached (D4/S02) |

Everything else the app shows is bundled. There is no other network traffic; photos and locations never leave the device (M10).

### 5.2 Clients

One `OkHttpClient` singleton in `AppContainer`, with:

- `User-Agent: AnimalDex/1.0 (personal Android app; tlong@unified.health)` — Wikipedia requires a descriptive UA and this is also good citizenship at GBIF/XC.
- An HTTP response cache (`Cache(cacheDir/http, 20 MB)`) — makes repeated lookups and backfill retries cheap.
- 10 s connect / 20 s read timeouts.

Three thin clients in `data/net/`, each a class with suspend functions returning a sealed `LookupResult<T>` (`Found`, `NotFound`, `Failed(cause)`), parsing with kotlinx-serialization (`ignoreUnknownKeys = true` everywhere — these APIs add fields freely):

- **`GbifClient.match(name)`** → accepted scientific name, rank, class, confidence, and the `alternatives` list (M18's candidate picker).
- **`WikipediaClient.habitatAndSummary(title)`** → two requests: `action=parse&prop=sections` to find a section titled `Habitat` or `Distribution and habitat` (case-insensitive contains "habitat"), then `&section=N&prop=wikitext` for its prose (stripped of markup — a small regex pass is acceptable for v1: drop templates `{{…}}`, refs `<ref…>`, links keep label). Fallback: REST `page/summary` lede. Also from the summary: `originalimage.source` as the canonical image URL and the page URL as `infoUrl`.
- **`XenoCantoClient.bestCall(scientificName)`** → API v3 with key (5.4), query `sp:"Genus species"`, prefer quality `A` then `B`, take the first hit's file URL, recordist and license into the attribution line. Empty result is `NotFound`, which the confirm card renders as the normal "no call" state, not an error (M18).

`SpeciesLookupRepository` composes the three: GBIF first (it supplies the scientific name the other two are keyed by — Wikipedia is queried by scientific name, falling back to the user's common name if the page is missing), then Wikipedia and Xeno-canto in parallel. Total failure of any one source degrades to that field being empty and editable on the confirm card; total failure of GBIF offers "save with details pending" (M20 path) or hand-editing.

### 5.3 Caching and offline behavior

- **Reference images**: Coil's default memory cache plus an explicit disk cache (`cacheDir/coil_images`, 250 MB max). Cache hit = offline works (S02). Miss while offline = the composable's error slot shows the silhouette — the D3 graceful degradation.
- **Call audio**: Media3 `CacheDataSource` over a `SimpleCache` (`cacheDir/media_audio`, 200 MB LRU, `StandaloneDatabaseProvider`) wrapping an `OkHttpDataSource`. First play streams and writes through; later plays are local (D4). Offline miss = the player control's error state (M06).
- **API lookups**: no offline queue. Offline user-add takes the M20 path (create with `detailsPending = true`); backfill triggers when a pending entry's detail screen opens with connectivity present (checked via `ConnectivityManager.activeNetwork` — a simple "is there a network" probe, not reachability engineering).
- Settings' cache management screen shows the three cache sizes and offers "clear reference caches" (images + audio; never thumbnails, never local copies).

### 5.4 The Xeno-canto API key

Since October 2025, Xeno-canto's API (v3) requires a per-account API key (free, from the user's XC account page; rate limit ~1,000 requests/hour). Two consumers:

- The **pipeline** reads `XC_API_KEY` from the environment.
- The **app** needs it for user-added lookups: it is read from `local.properties` (`xc.api.key=…`, git-ignored) into `BuildConfig.XC_API_KEY` in `app/build.gradle.kts`. Baking a personal key into a debug APK that never leaves the user's own phone is acceptable and stated here deliberately. If the key is absent, the build still succeeds with an empty string and the app treats Xeno-canto as permanently `NotFound` (calls are skipped) — the pipeline README tells the user how to create the key.

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
- **Component vocabulary** in `ui/common/`, each matching a mockup element: `SpeciesCell` (grid cell: art area with photo thumb or silhouette on `silBg`, `#NNN` number in faint, name line), `EcosystemMeter` (warn-colored fill bar, `12/24` tabular value, the `+1` user-added addendum), `ClassMeter` (accent fill), `FilterChip` row (outlined; selected = accent on accentSoft), `CallPlayerRow` (round accent play button, static waveform bars, source/attribution column), `AttributionLine` (faint small text), `ProgressPill` (accent on accentSoft, `47 / 120`). The eco meters use warn, class meters use accent — that distinction is in the mockup and is intentional.
- **Silhouette treatment**: uncaught art areas are `silBg` with the class silhouette tinted `sil` (a `ColorFilter.tint`); the unlock reveal cross-fades silhouette → thumbnail with a scale from 0.96 and a soft accentSoft radial glow, plus one `HapticFeedbackType.LongPress` tick — restrained per D8.

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

`python3 build_catalogue.py --out ../../app/src/main/assets/catalogue/pacific.json` (with `XC_API_KEY` in the environment). Per species:

1. **GBIF** `species/match?name=<scientific>&strict=false` → accepted `species` (canonical name), `class` → mapped to the app's `taxClass` enum (Aves→bird, Mammalia→mammal, Reptilia (incl. Squamata/Testudines)→reptile, Amphibia→amphibian, Insecta→insect, fish classes (Actinopterygii, Chondrichthyes, Elasmobranchii)→fish, everything else→other_invertebrate). A non-exact match or a class contradiction is **logged as a warning, not auto-fixed** — the run report lists them for the curator.
2. **Wikipedia**: resolve the page by scientific name (fall back to common name); `action=parse&prop=sections` → find the habitat section → fetch and strip its wikitext to 1–3 sentences for `habitatText`; REST `page/summary` → `description` (lede, first 2 sentences) and `originalimage.source` → `imageUrl`; page URL → `infoUrl`. Image attribution: Commons `imageinfo` (`prop=imageinfo&iiprop=extmetadata`) → license + artist → `imageAttribution`.
3. **Xeno-canto** v3 `recordings?query=sp:"<accepted name>"&key=…` → best quality-A/B recording → `callUrl`, `callAttribution` (XC number, recordist, license). No result → `callUrl: null` (normal for most non-birds).
4. Assemble the output record with `silhouetteRes` = `sil_<taxClass>` and a `provenance` map naming the source of every fetched field (`"habitatText": "wikipedia:section:Distribution and habitat"` or `"override"`).

Cross-cutting behavior:

- **Cache**: every HTTP response is written to `cache/<sha1-of-url>.json` and reused on re-run (`--refresh` bypasses). A full re-run against a warm cache makes zero requests.
- **Rate limiting**: ≥ 1 s between Wikipedia requests, ≥ 4 s between Xeno-canto requests (120 species ≈ 120 XC requests — comfortably inside 1,000/hour even cold). Descriptive `User-Agent` with the user's email on every request.
- **Report**: the run ends with a summary — species with no habitat section (lede fallback used), no image, no call, GBIF fuzzy matches — written to `cache/report.txt` for curator review. Exit non-zero if any species failed entirely.
- **Validation**: asserts 120 species, unique dexNumbers 1–120, every `ecosystemId` declared, every record has `commonName`, `scientificName`, `taxClass`.

The output lands directly in `app/src/main/assets/catalogue/pacific.json` and **is committed to git** — builds must not depend on the network or on re-running the pipeline. Bumping `catalogueVersion` in the input is manual and only done when the output should reach existing installs (section 3.3).

---

## 8. Testing strategy

Realistic for one person; nothing here needs a CI farm.

**JVM unit tests (`app/src/test/`, run with `./gradlew testDebugUnitTest`, no device)** — the highest-value layer, covering the logic that could silently corrupt data or lie in the UI:

- Catalogue reconciliation: importer invariants from 3.3 (fake DAO or in-memory maps behind the repository interface — the invariants, not Room, are under test).
- Search + filter composition (M14) over an in-memory species list.
- Progress math (M15/D9): multi-ecosystem counting, user-added addenda excluded from curated fractions.
- Asset and API JSON parsing against fixture files checked into `test/resources/` (one real captured response per API, plus edge fixtures: GBIF ambiguous match, Wikipedia page with no habitat section, empty Xeno-canto result).
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
Creates: `ui/common/` component vocabulary (6.4), `ui/grid/*`, `ui/detail/*` (hero shows silhouette or reference image placeholder; habitat, ecosystems, attribution, disabled call row, Register button on uncaught), class silhouette VectorDrawables in `res/drawable/`. Edits: `NavGraph.kt` (replace grid/detail placeholders).
Done: on the phone — grid shows 120 silhouetted species in dex order under "Pacific · 0 / 120"; search "western" narrows live; class and ecosystem chips compose with search (M14); tapping opens detail (M03/M05 minus call playback).
Depends on: slice 3.

**Slice 5 — Register, photos, unlock.** *Sequential (edits `NavGraph.kt`, `AppContainer.kt`, `ui/detail/`).*
Goal: the core loop works — pick a species, attach a gallery photo, unlock it, see your photo everywhere it should appear.
Creates: `data/photo/*` (PhotoStore, thumbnailer, EXIF reader, resolution states per section 4), `ui/register/*`, `ui/reveal/*`, `ui/photoviewer/*` (including the Revoked re-link and Unavailable states, S07 delete, S04 favorite), Lens share affordance (S06). Edits: `NavGraph.kt` (register/photoviewer routes, unlock arg on detail), detail screen (photo strip), grid cell (thumbnail rendering).
Done on the phone: register an uncaught species with a gallery photo → reveal plays, counter reads 1/120, grid cell shows the thumbnail; reboot the phone → photo still opens (grant persisted); delete the gallery photo → detail shows thumbnail + "full photo unavailable" + re-link, entry stays caught (M09–M13 checks).
Depends on: slice 4.

**Slice 6 — Reference media: images and calls.** *Sequential (edits the hot files `AppContainer.kt` and `ui/detail/*`; slice 7 edits `AppContainer.kt` too, so 6 and 7 are strictly ordered — do not run them in parallel).*
Goal: detail screens stream and cache the canonical image and the call, with attribution and error states.
Creates: `media/*` (ExoPlayer wrapper, SimpleCache per 5.3), `CallPlayerRow` wiring, Coil `ImageLoader` with the disk cache, offline placeholder behavior. Edits: `ui/detail/*`, `AppContainer.kt`.
Done on the phone: a caught species' detail shows the Wikimedia image with credit; play streams the call, tap stops it (M06); airplane mode after one play → call still plays, image still shows (S02); airplane mode on a never-viewed species → silhouette placeholder and error state, no crash.
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

**R4 — Xeno-canto: API key and non-bird coverage.** The v3 API requires a registered account's key (5.4) — a human step no agent can do; the pipeline README must surface it loudly, and the pipeline treats a missing key as "no calls fetched, warn per species" so the build still completes. Coverage: Xeno-canto is strong for birds, spotty for frogs/insects (grasshoppers, cicadas), absent for mammals-that-don't-sing and everything marine. Expect roughly half the catalogue to have `callUrl: null` — the design treats no-call as a normal state (M18), and the pipeline report quantifies it so the curator can hand-pin `overrides` where a good recording exists.

**R5 — Wikipedia habitat-section variance.** Section titles vary ("Habitat", "Distribution and habitat", "Habitat and distribution", "Ecology"), wikitext stripping is heuristic, and some articles (especially invertebrates) have thin prose. Mitigation: the section-finder matches any title containing "habitat" (falling back to "Ecology", then the lede); the pipeline report flags every fallback; `overrides` lets the curator hand-write the stubborn ones. Runtime user-adds share the same fallback chain and are always editable (M19/M21).

**R6 — No Android Studio: no Compose previews, no layout inspector.** All visual verification is on the phone. Mitigation: every UI slice's done-check is a phone check; `adb exec-out screencap -p > shot.png` gives agents screenshots for self-review; the theme is fully token-driven so visual fixes are palette-file edits, not archaeology. Compose `@Preview` annotations may still be written (they compile fine) but nothing renders them.

**R7 — Room schema regret after real data exists.** The point of no return is the first real capture on the user's phone (slice 5). Before it: change schema freely, reinstall. After it: hand-written `Migration` objects only, `exportSchema` history in git making them writable. The full-schema-in-slice-3 rule exists to keep post-v1 migrations rare.

**R8 — Grant revocation en masse.** Android can revoke persistable grants if the provider app (Google Photos) is updated/cleared in unusual ways. The design already survives it (thumbnails + Revoked state + re-link, C05's periodic checker stays a future option). No preemptive engineering.

**R9 — The build machine's SDK install is in flight.** Slice 1 assumes `platforms;android-36` and `build-tools;36.0.0` finished installing and `local.properties` points at `/opt/homebrew/share/android-commandlinetools`. If `installDebug` fails on license acceptance, run `sdkmanager --licenses`. Also install `platform-tools` for `adb` and enable USB debugging on the phone — slice 1's done-check is the first moment all of this is exercised.
