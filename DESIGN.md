# Animal Dex — Design & Requirements (v3)

A personal Android app: a Pokédex for real animals. Product design pass, 2026-09-01. v2 incorporated the user's answers: Pacific region, ecosystems as a first-class dimension, no in-app identification, gallery-only photos referenced by URI, and user-added species. v3 designs the user-added species flow fully (auto-populate from public APIs behind one confirmation step) and settles the last two open questions.

## 1. Product summary

Animal Dex is a single-user Android app that turns a real-world wildlife life list into a Pokédex. The app ships with a curated catalogue for one region — the Pacific, roughly America west of the Rockies — organized into real ecosystems (coastal rainforest, high desert, alpine, and so on), each with its own completion meter. Each species starts as a silhouette and "unlocks" when the user registers a photo of it from their phone's gallery. An unlocked entry shows the species' habitat, its call (streamed from an external source), a canonical reference picture, an outbound link, and the user's own photos — which stay in the gallery and are linked, never copied. There is deliberately no in-app identification: the user identifies animals with Google Lens or their own knowledge, then registers by name. Anything photographed that isn't in the catalogue becomes the user's own entry, auto-populated from public sources behind a single confirmation step. Unlike a wildlife-ID app, the point is the collection, the ecosystem meters, and the small thrill of an unlock. Built for one person, offline-first, and intentionally simple.

## 2. Core concepts / domain model

**Region** is the top-level scope of a catalogue. The dex is designed as a set of regional dexes; v1 ships exactly one, "Pacific". The region is mostly invisible in v1 (there is nothing to switch to) but it keys the catalogue so a second region can be added later without a schema change.

**Ecosystem** is a first-class sub-grouping within a region — a real ecological community, not just a filter tag. The Pacific region ships seven: Coastal Rainforest, Rocky Shoreline & Kelp, Oak Woodland & Chaparral, Riparian & Wetland, High Desert & Sagebrush, Sierra/Cascade Alpine, Urban & Suburban. **A species can belong to more than one ecosystem** (a Coyote is at home in high desert, oak woodland, and the suburbs), and a caught species counts toward every ecosystem it belongs to. Ecosystem totals therefore sum to more than the catalogue size; each ecosystem's meter is internally consistent ("Coastal Rainforest 12/24") and that is what matters.

**Species** is one kind of animal in the catalogue — "Western Screech-Owl". A curated species carries reference data (names, habitat text, call URL, image URL, outbound link, ecosystem tags) that the app links to or streams but does not own. A **user-added species** is one the user registered that isn't in the base catalogue. The user supplies the two things only they have — the name and the photo — and the app auto-populates the rest from public sources when online: accepted scientific name and class from GBIF, habitat text and a canonical image from Wikipedia, a call from Xeno-canto (see D10). Nothing is saved until the user accepts a confirmation card, and ecosystem tags are the one field the user always picks by hand. User-added species get U-numbers after the catalogue, are visually marked, and do not count toward the region's completion meter (see D9).

**Capture** is one registration event: the user attached one gallery photo to a species at a moment in time. The photo is referenced by a persistable content URI into the gallery; the app stores a small thumbnail of its own but no full-size copy (see D6).

**Entry** is the user's relationship to a species: it exists once the species has at least one capture, i.e. the species is "caught". One species has zero or one entry; one entry has one or more captures.

**Dex** is the whole collection: the region's catalogue plus the user's entries and user-added species, with progress derived from caught-curated over total-curated, plus per-ecosystem and per-class breakdowns.

Relations in one line: Region 1—N Ecosystem; Region 1—N Species; Species N—M Ecosystem; Species 1—0..1 Entry; Entry 1—N Capture.

### Species

| Field | Type | Notes |
|---|---|---|
| id | string | Stable catalogue id, e.g. `western-screech-owl`; user-added get a generated id |
| regionId | string | `pacific` in v1 |
| dexNumber | int or U-number | Catalogue: 1–120 display order. User-added: U01, U02, … in order added, listed after the catalogue |
| source | enum | curated / user-added |
| detailsPending | bool | User-added only: created offline or lookup found nothing; backfill outstanding (M20) |
| commonName | string | "Western Screech-Owl" |
| scientificName | string? | "Megascops kennicottii"; auto-populated from GBIF on user-added |
| class | enum | bird / mammal / reptile / amphibian / insect / other invertebrate / fish; from GBIF on user-added |
| ecosystemIds | string[] | Curated: 1..n. User-added: user-picked on the confirmation card, optional, default none |
| habitatText | string? | Curated: 1-3 sentences shipped with the app. User-added: from Wikipedia's Habitat/Distribution section, editable |
| description | string? | Curated: shipped. User-added: Wikipedia summary lede, editable |
| imageUrl | url? | Canonical picture, streamed and cached; from Wikipedia on user-added |
| callUrl | url? | Call audio; Xeno-canto lookup on user-added; null for silent/no-result species |
| infoUrl | url? | Outbound link; user-editable on user-added species |
| attribution | string? | License + credit lines; auto-populated media carries them too (M17) |
| silhouetteAsset | asset ref? | Bundled vector silhouette; user-added use a generic marker |
| userEdited | field set | User-added only: which fields the user hand-edited; backfill never overwrites these (M21) |

### Capture

| Field | Type | Notes |
|---|---|---|
| id | uuid | |
| speciesId | string | FK to Species |
| photoUri | content URI | Persistable grant into the gallery (`takePersistableUriPermission`); not a copy |
| thumbPath | file path | App-owned small thumbnail, generated at registration |
| localCopyPath | file path? | Full-size copy, only when "keep a local copy" is on |
| takenAt | datetime | From the photo's EXIF; falls back to registration time |
| location | lat/lng? | From EXIF only, if present and not declined |
| locationLabel | string? | User-typed or reverse-geocoded, e.g. "Point Reyes" |
| note | string? | Free text |
| isFirst | bool | Derived: the capture that unlocked the entry |

### Entry

| Field | Type | Notes |
|---|---|---|
| speciesId | string | PK; an entry is keyed by its species |
| caughtAt | datetime | Timestamp of the first capture |
| favoriteCaptureId | uuid? | Photo shown as the entry's thumbnail; defaults to first |
| captureCount | int | Derived from captures |

### Dex (derived, not stored)

| Field | Type | Notes |
|---|---|---|
| totalSpecies | int | Curated catalogue size (user-added excluded) |
| caughtCount | int | Entries on curated species |
| userAddedCount | int | Shown separately, e.g. "+3 of your own" |
| perClassProgress | map | caught/total per class, curated only |
| perEcosystemProgress | map | caught/total per ecosystem; a multi-ecosystem species counts in each |

## 3. Requirements

Each requirement is testable as written.

### MUST

- **M01** — The dex screen shows every curated species as a grid in dex-number order — caught species with their photo thumbnail and name, uncaught with a silhouette and name — under a header naming the region and overall progress.
- **M02** — User-added species appear in the same grid after the curated catalogue, visually marked as user-added, and excluded from the progress numbers.
- **M03** — Tapping any species opens its detail screen.
- **M04** — The detail screen for a caught curated species shows: the canonical picture, common and scientific name, its ecosystem tags, habitat text, a call-playback control (when the species has a call), the user's own photos newest-first, and an outbound "Learn more" link.
- **M05** — The detail screen for an uncaught species shows the silhouette, name, ecosystems, habitat text, and call control, and offers a Register action.
- **M06** — Tapping the call control plays the species' call audio; tapping again stops it. If the audio cannot be loaded, the control shows an error state rather than failing silently.
- **M07** — The Register a Species screen searches the catalogue by common or scientific name substring, entirely offline, then lets the user attach one photo via the system photo picker and register. There is no in-app camera anywhere in the app.
- **M08** — Registering a name not found in the catalogue offers "Add your own species", which starts the user-added flow (M18–M21) with that name and the attached photo.
- **M09** — Registering an uncaught species creates the entry and plays the unlock reveal (silhouette resolves into the photo, progress counter increments). Registering an already-caught species appends the photo with only a brief acknowledgment.
- **M10** — A registered photo is stored as a persistable content-URI reference into the gallery (persisted with `takePersistableUriPermission`); the app writes no full-size copy unless "keep a local copy" is on, and makes no network write of any photo or location.
- **M11** — At registration the app generates and stores its own small thumbnail, so the grid and detail screens render without resolving gallery URIs.
- **M12** — If a photo's URI no longer resolves (deleted or moved in the gallery, or cloud-only and not downloaded), the entry stays caught: the app shows the stored thumbnail with a "full photo unavailable" state and offers to re-link a photo. It never shows a silent blank and never reverts the species.
- **M13** — Each capture records a timestamp from the photo's EXIF (falling back to registration time); location comes from EXIF only, and only if not declined.
- **M14** — Search finds species by name substring; filters narrow the grid by caught/uncaught, class, and ecosystem. Search and filters compose.
- **M15** — A stats screen shows overall progress, a per-ecosystem breakdown with a meter per ecosystem, and a per-class breakdown. A multi-ecosystem species counts in each of its ecosystems.
- **M16** — Browsing, search, filtering, the whole Register flow, and viewing entries (minus remote image/audio) work with no network connection.
- **M17** — Every third-party image and audio clip displays or links to its attribution and license on the detail screen — auto-populated media on user-added species (Wikipedia/Wikimedia image credit, Xeno-canto recordist and license) exactly as much as curated media.
- **M18** — When online, adding a user species looks the name up: GBIF's species-match endpoint resolves it to an accepted scientific name, taxonomic class, and a match confidence, with alternative candidates when the name is ambiguous; Wikipedia supplies habitat text (the article's "Habitat" or "Distribution and habitat" section when one exists, else the summary lede) and a canonical image; Xeno-canto is queried by the GBIF scientific name for a call. A species with no findable call simply has no call control — a normal state, not an error.
- **M19** — Nothing is written until the user accepts a confirmation card showing the matched name, scientific name, class, found image, found habitat text, and whether a call was found; from the card the user can pick a different GBIF candidate or edit any field by hand. Ecosystem tags are never auto-derived: the card offers a manual multi-select (optional, defaulting to none).
- **M20** — Offline, or when the lookup finds nothing, the species is created immediately from the name and photo alone and marked "details pending"; the app backfills automatically the next time it is online and the entry is opened, then presents the same confirmation card. Registration never blocks on the network.
- **M21** — Every auto-populated field remains editable afterward, and a field the user has edited is never overwritten by a later backfill.

### SHOULD

- **S01** — Export produces a single archive via the share sheet: the collection metadata as JSON, all thumbnails, and — for every URI that still resolves at export time — a full-size copy of the photo, so the archive is self-contained on a new device. Import restores from such an archive, storing the imported full-size photos as local copies.
- **S02** — Canonical images and call audio are cached on first successful load, so a previously viewed entry works fully offline afterward.
- **S03** — A global "keep a local copy" setting (default off — confirmed by the user) makes registration also copy the full-size photo into app storage, as the durability escape hatch against gallery deletions.
- **S04** — The user can mark one photo per entry as the favorite; it becomes the grid thumbnail.
- **S05** — A capture can carry a free-text note, editable later; user-added species also have an editable name and outbound link.
- **S06** — The Register screen offers a "Not sure? Open in Google Lens" affordance that hands the picked photo to Lens via a share intent, matching the user's actual identification workflow.
- **S07** — Deleting a capture removes the reference and thumbnail (and local copy if any) but never touches the gallery photo; deleting the last capture of an entry reverts the species to uncaught after an explicit warning.
- **S08** — The stats screen shows a "recently caught" strip and the date of the last new catch.

### COULD

- **C01** — A map view plots captures that have EXIF locations.
- **C02** — Per-ecosystem completion badges (e.g. all of Coastal Rainforest) with a small celebratory moment.
- **C03** — A second regional dex (e.g. "Eastern") installable later; the region concept in the model exists to make this a content problem, not a schema change.
- **C04** — Home-screen widget showing progress and the most recent catch.
- **C05** — A periodic background check that flags captures whose URIs have stopped resolving, so re-linking can happen before the user notices in the field.
- **C06** — Promotion: a later catalogue update that includes a species the user had already added folds it into the curated 120, merging the user's captures onto the new curated entry and retiring the U-number.

## 4. The "catching" mechanic

Uncaught species render as flat dark silhouettes on the grid and in detail — present, named, but withheld. This makes the dex legible as a to-do list: you can see what is out there and what you are missing, which is the engine of the whole product. Ecosystem meters sharpen it: "High Desert 2/19" is an itinerary, not just a statistic.

Registering is species-first: you already know what you photographed (Lens told you, or you did), you search the name, attach the photo, register. The unlock moment is unchanged from v1: on first registration the silhouette cross-fades into the user's actual photo, the dex number stamps in, a short haptic tick fires, and the counter visibly increments. Quick (about a second and a half), skippable, restrained — a card flip and a soft glow, not confetti. The user's own photo becomes the entry's face.

Repeat registrations of a caught species are deliberately low-key: the photo slides into the entry's strip with a brief "+1" toast. Ceremony is reserved for firsts, so firsts stay special. User-added species get the same reveal, with their U-number in place of a dex number.

The tone target is "field naturalist's logbook with good game feel": tactile, satisfying, quiet. No XP, no streaks, no mascot.

## 5. Key product decisions

**D1 — Seed the dex with a curated Pacific list (~120 species), not a full taxonomy.**
Why: a dex must feel completable to work as a game. A full taxonomy makes progress read as 0% forever and the unlock loop dies. ~120 species the user can realistically encounter west of the Rockies — common backyard and regional-park animals plus a tail of aspirational ones, invertebrates included — keeps every silhouette a plausible goal. The region is a first-class concept so more regions can ship later (C03).
Rejected: full taxonomy (unwinnable); dynamic list from an API (network dependency, unstable dex numbers, no offline guarantee).

**D2 — No in-app identification, deliberately.**
Why: the user's workflow is Google Lens on the same phone, then registering by a name they already have. An on-device classifier or even a suggestion heuristic adds complexity, model weight, and wrong answers to a step the user has already solved. The app's job starts at "I know what this is." A small "Open in Google Lens" share affordance (S06) bridges the one gap without building anything.
Rejected: on-device classifier (heavyweight, and redundant with Lens); suggestion shortlists (v1 of this design had them — cut as cleverness the user explicitly declined).

**D3 — Habitat text, descriptions, and silhouettes ship with the app; canonical images and calls are streamed then cached.**
Why: text is tiny and must work offline day one, so it is bundled. Media is large and third-party, so it is loaded from source URLs and cached on first view (S02) — the field-use case degrades gracefully (placeholder + text) rather than failing.
Rejected: bundling all media (app bloat, and redistributing licensed media crosses from linking into copying); streaming everything always (dead in the field).

**D4 — Call audio is streamed with cache-on-first-play, not pre-downloaded.**
Why: audio files are the largest reference asset and most calls will never be played. Caching on first play means the calls the user actually cares about become offline for free.
Rejected: bundle all calls (tens of MB for mostly-unplayed audio); a "download all for offline" bulk switch is a fine later addition.

**D5 — Third-party media is linked/streamed with visible attribution; nothing third-party is redistributed.**
Why: sources like Wikimedia Commons and Xeno-canto are CC-licensed with attribution requirements, and some (e.g. Macaulay Library) do not permit redistribution at all. Streaming from source with an attribution line on the detail screen (M17) satisfies the licenses. Cached copies are private device caches, not redistribution.
Rejected: baking media into the APK (license risk, size); no attribution (violates CC-BY terms).

**D6 — Photos are referenced from the gallery by persistable content URI; the app stores no copy by default.**
Why: this is what the user asked for ("a link to Google Photos would be better than storing the photo"), and Android's own photo picker delivers it with no Google API at all: `PickVisualMedia` returns a content URI, and `takePersistableUriPermission()` with the read flag makes the grant survive reboots. The Google Photos API cannot do this — the `photoslibrary.readonly` scope was removed in April 2025, and its replacement Picker API returns only a `baseUrl` that expires after 60 minutes, with no durable link back to the library item — and it would have required OAuth and a Cloud project besides.
Tradeoffs, stated honestly: if the user deletes or moves the photo in the gallery the reference breaks — the entry stays caught with a "photo unavailable" placeholder and a re-link offer (M12), never a silent blank. A photo that lives only in Google Photos cloud storage and isn't on the device may not resolve offline. Android caps persistable grants at 5,000 per app, far above a personal life list but real. The app keeps its own thumbnails (M11) so the collection always renders, and the "keep a local copy" setting (S03, default off) is the durability escape hatch — default off because linking, not storing, is the point.
Rejected: Google Photos API links (technically impossible since April 2025, see above); copying every photo into app storage (duplicates the user's library and contradicts the ask); referencing without persisting the grant (breaks on every reboot).

**D7 — An entry's identity is the species, unlocked once; repeats accumulate under it.**
Why: this is the Pokédex frame — the list is of species, not of sightings. Sightings-as-primary is a journal (eBird already exists); species-as-primary is a collection. Captures remain first-class underneath, so no data is lost.
Rejected: sighting-first journal model (different product); per-location or per-season re-unlocks (badges C02 can cover this itch later).

**D8 — The unlock reveal is restrained by design.**
Why: the user is an adult using this for years; a quiet, tactile reveal stays satisfying on the 90th unlock where fanfare would curdle.
Rejected: full-screen celebration animations, sound effects, mascot commentary.

**D9 — Ecosystems are a first-class dimension; species can belong to several; user-added species sit outside the meter.**
Why: ecosystems make the collection a map of places to go, and per-ecosystem meters give the dex seven small winnable games inside the big one. Multi-membership reflects reality (a Coyote genuinely spans three ecosystems) and costs nothing beyond the honest caveat that ecosystem totals sum past 120 — each meter is consistent within itself, which is what the user reads. User-added species are an addendum ("+3 of your own"), not part of the 120: letting them count would let the meter inflate itself and make "complete" meaningless.
A user-added species with ecosystem tags shows on that ecosystem's meter as an addendum ("12/24 +1"), leaving the curated fraction intact — same treatment as the overall meter's "+3 of your own".
Rejected: single-ecosystem assignment (forces false choices for wide-ranging species); counting user-added species toward completion (destroys the meter); ecosystem as a mere filter tag (loses the per-ecosystem progress that makes it fun).

**D10 — User-added species auto-populate from public APIs, behind one confirmation step.**
Why: the user supplies the two things only they have — the name and the photo — and free public APIs supply the rest. GBIF's species-match endpoint is the spine: it decides which species the name means, returning the accepted scientific name, class, and a confidence score, with alternatives when the name is ambiguous. Wikipedia supplies the prose and picture, preferring the article's "Habitat" or "Distribution and habitat" section (fetched by section index via the parse API) over the generic lede, so an auto-added entry reads like the hand-written curated ones. Xeno-canto supplies a call keyed by the GBIF scientific name — mostly for birds; finding nothing is normal, not a failure. The confirmation card exists because common names are ambiguous ("sparrow" matches dozens of species) and a silent wrong pick corrupts the entry permanently: nothing is saved until the user accepts, swaps candidates, or edits. Ecosystem tags are the one field no API can supply — nothing maps species onto these seven Pacific ecosystems — so they stay a manual multi-select on the card.
Identity and travel: user-added species take U-numbers (U01, U02, …) in the order added and sit outside the 120 meter. A holiday catch from outside the Pacific region lands here as an ordinary user-added entry with no out-of-region flag — the addendum is already outside the meter, so a flag would add complexity without changing anything the user sees. If one turns out to be a genuine missing Pacific native, a later catalogue update can promote it (C06).
Rejected: silent auto-accept of the top match (wrong often enough on ambiguous names to be destructive); fully manual entry (v2's design — undersold what the free APIs deliver); blocking registration on the lookup (the offline-first rule governs here too, hence "details pending" + backfill, M20).

## 6. Screens

1. **Dex Grid** (home) — the Pacific catalogue as a grid, caught thumbnails and uncaught silhouettes, user-added species trailing; search, ecosystem/class filter chips, region + progress header.
2. **Entry Detail** — everything about one species: canonical picture (or silhouette), ecosystems, habitat, call playback, the user's photo strip, attribution, outbound link, Register button.
3. **Register a Species** — search the catalogue by name, pick the species, attach one gallery photo via the system picker, register; "Add your own species" for names not found; optional Lens hand-off.
4. **Add Species — Confirm** — the confirmation card for a user-added species: the GBIF match with alternatives, found image and habitat text, call-found indicator, the manual ecosystem multi-select; accept, pick a different match, or edit by hand.
5. **Unlock Reveal** — the transient first-catch moment (overlay, not a destination).
6. **Photo Viewer** — one photo full-screen (resolved from its URI, or the unavailable state with re-link); date, place, note; delete and set-favorite live here.
7. **Stats** — overall progress, per-ecosystem meters, per-class bars, recent catches.
8. **Settings** — export/import, "keep a local copy" toggle, cache management, licenses.

Navigation: Dex Grid is home. Grid → Entry Detail (tap a cell). A floating Register button on the grid and a Register action on uncaught detail screens → Register a Species → (Unlock Reveal →) Entry Detail of the registered species. Register → Add Species Confirm when the name isn't in the catalogue — and again later when a "details pending" entry backfills (M20). Detail → Photo Viewer (tap a photo). Stats and Settings are reachable from the grid's top bar. Back always returns to the previous screen; after registering, back from the detail screen returns to the grid.

## 7. Out of scope for v1

- Cloud sync, accounts, or any backend; any Google Photos API integration.
- In-app identification of any kind — no classifier, no suggestions (Google Lens is the external tool for this).
- An in-app camera; video.
- Multi-user support, sharing, social features, leaderboards, trading.
- A second region (the model supports it; the content doesn't ship).
- In-app editing of the curated catalogue (user-added species are separate).
- A tablet or Wear OS layout; iOS.
- Gamification beyond the dex itself: XP, levels, streaks, daily quests.

## 8. Open questions for the user

None. The seven ecosystems are approved as written, "keep a local copy" is settled at default-off, and the user-added species flow is designed to your description (name + photo in, the rest auto-populated behind one confirmation). Nothing currently open needs a decision.
