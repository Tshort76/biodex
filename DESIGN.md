# BioDex — Design & Requirements (v6)

A personal Android app: a Pokédex for real living things. Product design pass, 2026-09-01; v4 revised 2026-09-02. v2 incorporated the user's answers: Pacific region, ecosystems as a first-class dimension, no in-app identification, gallery-only photos referenced by URI, and user-added species. v3 designed the user-added species flow fully (auto-populate from public APIs behind one confirmation step). v4 renames the product to BioDex, makes the shipped catalogue the **Pacific USA BioDex**, and brings plants in — trees, fruit-bearing and edible plants, and medicinal/herbal plants — as a second kingdom counted beside the animals, with plant uses as a headline filter. v4 also folds in one real bug found on the phone (the Register screen's scroll layout). Everything in v1–v3 is built and shipped; v4 is the expansion designed from it. **v5 (2026-09-02) removes bird-call playback entirely** — the feature was designed when the app was an animal dex, a call is meaningless for a plant, a slug or a fish, and it never once worked for want of a Xeno-canto API key. M06 and D4 are struck out in place, and D15's "call slot" framing is rewritten; the numbering is left with holes rather than renumbered, because the surviving requirements are referenced by number throughout this document and in the code. **v6 (2026-09-02) folds in what shipped after v5**: fungi as a third kingdom, an in-app camera, plant identification through Pl@ntNet, and the plant that keeps no photograph of its own. M07, M10, M23, M24, M30, D2 and D14 are revised in place; M31–M42, S11–S15, C09–C11 and D19–D27 are new. v6 also records the caution pass of the same day, which cut the safety text back to one short sentence per dangerous species — see D14.

## 1. Product summary

BioDex is a single-user Android app that turns a real-world life list into a Pokédex. The app ships with a curated catalogue for one region — the Pacific USA, roughly America west of the Rockies — organized into real ecosystems (coastal rainforest, high desert, mountain conifer forest, and so on), each with its own completion meter. The catalogue holds three kingdoms in one regional dex: 120 animals, 80 plants and 30 fungi, counted separately so that each list is its own completable game. Each species starts as a silhouette and "unlocks" when the user registers a photo of it from their phone's gallery. An unlocked entry shows the species' habitat, a canonical reference picture, an outbound link, and the user's own photos — which stay in the gallery and are linked, never copied. Plants additionally carry their **uses** — whether the species is edible or medicinal, which part, and when — which is also a first-class filter, so the dex doubles as a map of what is worth foraging-for-the-camera in each ecosystem. Identification is optional, opt-in per photo, and only for plants: pressing *Identify* sends one downscaled copy of that photo to Pl@ntNet and shows its candidates as a list to choose from. The app never picks one, and never says a photograph *is* a species. For animals and fungi there is no provider — the user identifies with Google Lens or their own knowledge and registers by name. Anything photographed that isn't in the catalogue becomes the user's own entry, auto-populated from public sources behind a single confirmation step. Unlike a wildlife-ID or foraging app, the point is the collection, the ecosystem meters, and the small thrill of an unlock. Built for one person, offline-first, and intentionally simple.

## 2. Core concepts / domain model

**Region** is the top-level scope of a catalogue, and a regional dex is named `<Region> BioDex`. v4 ships exactly one, the **Pacific USA BioDex** (region id `pacific`, display name "Pacific USA"). The region is visible in the UI as a pill beside the product name — the app is called BioDex, the region says which BioDex you are looking at — and it keys the catalogue so a second region (an "Eastern USA BioDex", say) can be added later as content, not schema.

**Kingdom** is the coarsest split inside a region: **animal** or **plant**. Both kingdoms live in the same regional dex, share the same ecosystems, the same grid, the same register flow and the same detail screen, but they are **counted separately** — the region's header carries one meter per kingdom ("47/120 animals · 3/80 plants") and every ecosystem meter has an animal fraction and a plant fraction. The animal count the user has already started filling is untouched by the plants arriving beside it.

**Ecosystem** is a first-class sub-grouping within a region — a real ecological community, not just a filter tag. The Pacific USA region ships seven: Coastal Rainforest, Shoreline, Dunes & Kelp, Oak Woodland & Chaparral, Riparian & Wetland, High Desert & Sagebrush, Sierra & Cascade Mountains, Urban & Suburban. Two of these were deliberately renamed in v4 — "Rocky Shoreline & Kelp" and "Sierra/Cascade Alpine" in v3 — so that they describe where a plant grows as well as where an animal lives; their ids are unchanged (see D16). **A species can belong to more than one ecosystem** (a Coyote is at home in high desert, oak woodland, and the suburbs; a Ponderosa Pine spans the mountains and the high desert's edge), and a caught species counts toward every ecosystem it belongs to. Ecosystem totals therefore sum to more than the catalogue size; each ecosystem's meter is internally consistent ("Coastal Rainforest 12/24 · 2/15") and that is what matters.

**Class** is the grouping under a kingdom that the stats screen's bars and the filter chips use. For animals it is the taxonomic class the app has always used: bird, mammal, reptile, amphibian, fish, insect, other invertebrate. For plants it is **growth form, not taxonomy**: tree, shrub, herb, fern (see D13). Every class belongs to exactly one kingdom, so choosing a class chip implicitly chooses a kingdom.

**Species** is one kind of living thing in the catalogue — "Western Screech-Owl", "Pacific Madrone". A curated species carries reference data (names, habitat text, image URL, outbound link, ecosystem tags, and for plants uses) that the app links to or streams but does not own. A **user-added species** is one the user registered that isn't in the base catalogue. The user supplies the two things only they have — the name and the photo — and the app auto-populates the rest from public sources when online: kingdom, accepted scientific name and class from GBIF, and habitat text and a canonical image from Wikipedia (see D10). Nothing is saved until the user accepts a confirmation card. Ecosystem tags, and for plants the growth form and the uses, are fields the user always picks by hand. User-added species get U-numbers after the catalogue, are visually marked, and do not count toward either kingdom's completion meter (see D9).

**Uses** are a plant's documented human uses — **edible**, **medicinal**, both, or neither — plus a short curated note naming which part is used and when ("Berries, late summer — cook first") and carrying any caution. The two tags have different provenance and the app never presents them identically. **Medicinal is sourced**: Dr. Duke's Phytochemical and Ethnobotanical Databases (USDA, CC0 — one downloadable file of 82,873 ethnobotanical use records over 13,010 taxa) is joined at build time on the GBIF-accepted name and its synonyms, and the entry shows the record count and the activities Duke's lists, attributed. **Edible is curated**: Duke's holds almost no food records, so the fruit-bearing and edible plants stay editorial judgment in the input list, as ecosystem tags are. Duke's `Poison` records are used at build time as a checklist — every species with one must carry a `Caution:` sentence — rather than shown as a tag (see D14). Uses are plant-only in v4, and both tags are filterable from the chip row like ecosystems and classes.

**Capture** is one registration event: the user attached one gallery photo to a species at a moment in time. The photo is referenced by a persistable content URI into the gallery; the app stores a small thumbnail of its own but no full-size copy (see D6).

**Entry** is the user's relationship to a species: it exists once the species has at least one capture, i.e. the species is "caught". One species has zero or one entry; one entry has one or more captures.

**Dex** is the whole collection: the region's catalogue plus the user's entries and user-added species, with progress derived per kingdom as caught-curated over total-curated, plus per-ecosystem and per-class breakdowns.

Relations in one line: Region 1—N Ecosystem; Region 1—N Species; Species N—M Ecosystem; Species 1—0..1 Entry; Entry 1—N Capture. Kingdom and class are attributes of Species, not tables.

### Species

| Field | Type | Notes |
|---|---|---|
| id | string | Stable catalogue id, e.g. `western-screech-owl`, `pacific-madrone`; user-added get a generated id |
| regionId | string | `pacific` in v4 |
| kingdom | enum | animal / plant. Curated: from the input list, confirmed against GBIF. User-added: from GBIF's match; defaults to animal while details are pending |
| dexNumber | int, displayed per kingdom | Animals `#001`–`#120`, plants `P001`–`P080`, each kingdom in its own field-guide order. User-added: U01, U02, … in order added, listed after both catalogues regardless of kingdom |
| source | enum | curated / user-added |
| detailsPending | bool | User-added only: created offline or lookup found nothing; backfill outstanding (M20) |
| commonName | string | "Western Screech-Owl", "Pacific Madrone" |
| scientificName | string? | "Megascops kennicottii", "Arbutus menziesii"; auto-populated from GBIF on user-added |
| class | enum | Animals: bird / mammal / reptile / amphibian / fish / insect / other invertebrate — from GBIF. Plants: tree / shrub / herb / fern — curated by hand, defaulted from GBIF on user-added and always a manual pick on the card |
| ecosystemIds | string[] | Curated: 1..n. User-added: user-picked on the confirmation card, optional, default none |
| uses | enum set | Plants only: any of edible, medicinal; empty for every animal and for a plant with no recorded use. `edible` is curated by hand; `medicinal` is derived from Duke's (three or more distinct recorded activities), and the curator may pin it either way. Both editable on user-added plants |
| usesNote | string? | Curated. Required for an edible plant (the part used and the season) and for any plant Duke's records as poisonous (a sentence beginning `Caution:`, which the UI renders emphasised); optional otherwise. A medicinal-only plant with no poison record needs no note — its Duke's line is its text. Never present without a use tag |
| medicinalActivities | string[] | Plants: up to eight of the activities Duke's records for the species ("Astringent", "Diuretic", "Wound"), most-cited first; empty when Duke's has nothing. From the bundled Duke's index on user-added plants |
| medicinalRecordCount | int | How many Duke's use records the species has (0 for every animal and about a fifth of plants) — shown so a 105-record yarrow and a 4-record Oregon grape read differently |
| usesAttribution | string? | "Dr. Duke's Phytochemical and Ethnobotanical Databases · USDA ARS · CC0" when the Duke's fields are populated |
| habitatText | string? | Curated: 1-3 sentences shipped with the app. User-added: from Wikipedia's Habitat/Distribution section, editable |
| description | string? | Curated: shipped. User-added: Wikipedia summary lede, editable |
| imageUrl | url? | Canonical picture, streamed and cached; from Wikipedia on user-added |
| infoUrl | url? | Outbound link; user-editable on user-added species |
| attribution | string? | License + credit lines; auto-populated media carries them too (M17) |
| silhouetteAsset | asset ref? | Bundled vector silhouette: one per animal class, five for plants (conifer, broadleaf, shrub, herb, fern); user-added use their class's |
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
| animals | meter | caught / total over curated animals (120) |
| plants | meter | caught / total over curated plants (~80) |
| userAddedCount | int | Shown separately, e.g. "+3 of your own"; one number across both kingdoms |
| perClassProgress | map | caught/total per class, curated only; the stats screen groups the classes under their kingdom |
| perEcosystemProgress | map | per ecosystem, an animal meter and a plant meter; a multi-ecosystem species counts in each |

## 3. Requirements

Each requirement is testable as written. M01–M21 and S01–S08 are shipped; v4 adds M22–M30, S09–S10 and C07–C08, and amends the shipped ones where plants change what they mean.

### MUST

- **M01** — The dex screen shows every curated species as a grid in dex-number order — animals first, then plants; caught species with their photo thumbnail and name, uncaught with a silhouette and name — under a header naming the product, the region, and one completion meter per kingdom.
- **M02** — User-added species appear in the same grid after both curated lists, visually marked as user-added, and excluded from both kingdoms' progress numbers.
- **M03** — Tapping any species opens its detail screen.
- **M04** — The detail screen for a caught curated species shows: the canonical picture, common and scientific name, its ecosystem tags, habitat text, the user's own photos newest-first, and an outbound "Learn more" link. A plant additionally shows its uses section (M24).
- **M05** — The detail screen for an uncaught species shows the silhouette, name, ecosystems, habitat text, a plant's uses section, and offers a Register action.
- **M06** — *Removed in v5.* Call playback: "tapping the call control plays the species' call audio". The whole feature is gone (see the header note).
- **M07** — The Register a Species screen searches the catalogue — all three kingdoms — by common or scientific name substring, entirely offline, then lets the user attach one photo via the system photo picker **or take one with the in-app camera (M40)** and register. **A plant registers without a photograph of its own (M41)**: the photo, when there is one, exists to identify the plant and is not kept. Animals and fungi keep theirs.
- **M08** — Registering a name not found in the catalogue offers "Add your own species", which starts the user-added flow (M18–M21) with that name and the attached photo.
- **M09** — Registering an uncaught species creates the entry and plays the unlock reveal (silhouette resolves into the photo, the kingdom's progress counter increments). Registering an already-caught species appends the photo with only a brief acknowledgment.
- **M10** — A registered photo is stored as a persistable content-URI reference into the gallery (persisted with `takePersistableUriPermission`); the app writes no full-size copy unless "keep a local copy" is on, and makes no network write of any photo or location **except a downscaled, metadata-free copy of a single photo when the user presses Identify on it (M36). Nothing is uploaded automatically, and neither the original bytes nor the EXIF location is ever sent.**
- **M11** — At registration the app generates and stores its own small thumbnail, so the grid and detail screens render without resolving gallery URIs.
- **M12** — If a photo's URI no longer resolves (deleted or moved in the gallery, or cloud-only and not downloaded), the entry stays caught: the app shows the stored thumbnail with a "full photo unavailable" state and offers to re-link a photo. It never shows a silent blank and never reverts the species.
- **M13** — Each capture records a timestamp from the photo's EXIF (falling back to registration time); location comes from EXIF only, and only if not declined.
- **M14** — Search finds species by name substring; filters narrow the grid by caught/uncaught, kingdom, use, class, and ecosystem. Search and filters compose (AND).
- **M15** — A stats screen shows overall progress per kingdom, a per-ecosystem breakdown with an animal meter and a plant meter per ecosystem, and a per-class breakdown grouped under Animals and Plants. A multi-ecosystem species counts in each of its ecosystems.
- **M16** — Browsing, search, filtering, the whole Register flow, and viewing entries (minus the remote image) work with no network connection.
- **M17** — Every third-party image displays or links to its attribution and license on the detail screen — auto-populated media on user-added species (the Wikipedia/Wikimedia image credit) exactly as much as curated media.
- **M18** — When online, adding a user species looks the name up: GBIF's species-match endpoint resolves it to a kingdom, an accepted scientific name, a class, and a match confidence, with alternative candidates when the name is ambiguous; Wikipedia supplies habitat text (the article's "Habitat" or "Distribution and habitat" section when one exists, else the summary lede) and a canonical image; for plants only, the bundled Duke's index is looked up **offline** by the accepted name and its GBIF synonyms for medicinal activities and any poison record. A species with no Duke's record simply lacks that line — a normal state, not an error.
- **M19** — Nothing is written until the user accepts a confirmation card showing the matched name, scientific name, kingdom, class, found image and found habitat text; from the card the user can pick a different GBIF candidate or edit any field by hand. Ecosystem tags are never auto-derived: the card offers a manual multi-select (optional, defaulting to none). For a plant the card also offers the growth-form pick (defaulted from GBIF's class), the two use toggles and the uses note, none of which are auto-derived beyond the default (M27).
- **M20** — Offline, or when the lookup finds nothing, the species is created immediately from the name and photo alone and marked "details pending"; the app backfills automatically the next time it is online and the entry is opened, then presents the same confirmation card. Registration never blocks on the network.
- **M21** — Every auto-populated field remains editable afterward, and a field the user has edited is never overwritten by a later backfill.
- **M22** — Every species carries a kingdom. The grid header shows one completion meter per kingdom — animals `47/120`, plants `3/80` — and a species counts only toward its own kingdom's meter. The plant meter is hidden while the catalogue holds no plants; user-added species count toward neither.
- **M23** — The filter chip row carries kingdom chips (Animals, Plants, Fungi) and use chips (Food source, Medicinal) alongside the caught, class and ecosystem chips. Class chips shown are those of the selected kingdom, or all classes when no kingdom is selected. A use chip matches a plant tagged with that use (a plant tagged both matches either); animals and fungi never match a use chip. A kingdom chip is offered only for a kingdom the loaded region actually holds, so a catalogue with no fungi shows no Fungi chip. All five dimensions compose with each other and with search. The wire name behind the *Food source* chip is unchanged (`edible`) in the asset, the database and the backup format (M42).
- **M24** — A **plant or fungus** with something to say shows a section between the habitat text and the photo strip; an animal never does. It shows, in this order: the use tags; any `Caution:` sentence emphasised in the stop colour **above** the rest of the note; the curated note; when the plant is medicinal, one line naming the source, the record count and the activities ("Duke's records 105 traditional uses: astringent, diuretic, wound…"); and the disclaimer of M30. The *Food source* tag and the note are visibly the app's curated content; the medicinal line is visibly a source's, in the attribution register.
  A species with **neither a use tag nor a caution** shows nothing in that slot: habitat is followed directly by the photo strip. A species with a **caution and no uses** — every fungus that carries one, and a plant such as Western Wild Ginger — shows the warning alone: the section is headed "Caution" rather than "Uses", and carries no disclaimer, because a line about documented uses says nothing where none are shown.
- **M25** — Uncaught plants render one of five plant silhouettes — conifer tree, broadleaf tree, shrub, herb, fern — chosen by class, with conifer/broadleaf decided per species by the pipeline from GBIF's taxonomic class. Animals keep their seven class silhouettes.
- **M26** — Plants are numbered `P001` onward in their own field-guide order (trees, then shrubs, herbs, ferns); animals keep `#001`–`#120`; the number is the visible kingdom mark on grid cells and detail headers.
- **M27** — For a user-added species GBIF's kingdom decides which extra fields the confirmation card shows. A plant gets a growth-form picker (tree / shrub / herb / fern, defaulted from GBIF's class: conifers and other woody classes to tree, ferns to fern, else herb), an edible toggle (off by default), a medicinal toggle **defaulted from the bundled Duke's index** by the same three-activity rule the pipeline uses with the Duke's activities shown read-only beside it, and a uses note that is **pre-filled with "Caution: recorded as poisonous in Duke's ethnobotanical database."** when the index carries a `Poison` record for the species. The Duke's lookup is offline and needs no network. A details-pending species defaults to animal until backfill resolves its kingdom.
- **M28** — On the Register a Species screen the search field is pinned at the top, the photo row and the Register / Add-your-own actions are pinned at the bottom, and only the results list scrolls between them. With an empty query the list shows the whole catalogue in dex order, uncapped. Arriving with a pre-selected species, the list is scrolled so that species is visible and it is shown selected, with the Register button already naming it.
- **M29** — The app is named BioDex: launcher label, app bar title, share-sheet titles, backup archive identification and the About text all say BioDex, and the app bar shows the product name and the region name as two distinct elements — the title "BioDex" and a region pill "Pacific USA". Reading them together gives the regional dex's name, "Pacific USA BioDex".
- **M30** — The app's only claim about a plant's uses is that the use is *documented for the species* — by the curator (**food source**, the note) or by Duke's (medicinal), and the screen says which. A detail screen **that shows a use** carries a one-line disclaimer beneath the section ("Documented uses of the species — not advice. Do your own research before eating or using anything."), and the Settings About section repeats it. A caution-only section carries no disclaimer (M24). The app never states that a photographed individual is that species — an identification candidate is always shown as a named service's suggestion, and is never auto-selected — never states a part is safe, and carries no "safe to eat" wording anywhere.
  The longer statement is not in the app at all. It sits once at the top of the README: *this is intended as a fun activity, and nothing in it should be construed as medical or dietary advice.* Repeating a paragraph of it on three screens was ceremony, and it was cut on 2026-09-02 at the user's direction.
- **M31** — The Register screen offers, for an attached photo in a plant context, an *Identify* action naming the service it will call. It is hidden for animals and fungi, which have no provider. Pressing it uploads a reduced copy of that photo (M36) and shows the service's candidates as a list the user chooses from. The app never selects a candidate itself, and the typed-name path of M07 still works exactly as it does today for every kingdom.
- **M32** — Every candidate name is resolved through `GbifClient.match` before display, and only an `EXACT` or `FUZZY` match at species or subspecies rank survives — a `HIGHERRANK` match is treated as unresolved. A name that does not survive is not shown; the panel states how many were dropped. A candidate whose GBIF kingdom contradicts the context is dropped.
- **M33** — Catalogue matching is on the GBIF-accepted scientific name — genus and specific epithet, case-folded — never on common name. A matched candidate shows its dex number; an unmatched one offers "add as your own species", which opens the existing confirmation card (M19) with the GBIF lookup pre-filled.
- **M34** — The candidate panel is headed with the provider's name and states what kind of confidence it shows: a classifier's score as a percentage labelled as such; a language model's ranking, should one ever be added, as an ordered list with no number.
- **M35** — Fungi carry **no uses** — no *Food source* line and no medicinal line on any mushroom — and no identification. A fungal entry's only use-adjacent text is a curator's hand-written caution in `usesNote`, written only where the species itself is dangerous: ten of the thirty carry one, and the rest read like an animal. When there is one it renders (M24). Duke's is an ethnobotany database with no fungal taxa, so nothing sources these sentences and nothing can decide which species need them, which is why the app makes no claim about a mushroom beyond what the curator wrote by hand.
- **M36** — Upload is opt-in per photo (only on pressing Identify), and what is sent is a JPEG re-encoded from a decoded bitmap at reduced size, so it carries no EXIF and no location. The original file bytes are never sent. The licenses page, the About text and the README all say this.
- **M37** — Identification is counted per calendar month against a hard in-app cap (100, chosen as twice the expected volume and under every free tier; editable in Settings), the count is visible in Settings and on the disabled button, and the cap disables the action rather than warning past it.
- **M38** — Offline, without a key, or at the cap, the Identify action is disabled with the specific reason inline; nothing else on the screen changes. Identification outcomes are three-way: candidates, no candidates (ordinary, calm), could-not-ask (the only error state).
- **M39** — API keys are entered by the user in Settings and stored in app-private storage; no key is compiled into the build or committed to the repository. The field is masked with a reveal toggle, because a key that cannot be read back turns a typo into a mystery.
- **M40** — The Register screen offers an in-app camera via `ACTION_IMAGE_CAPTURE` writing to an app-cache `FileProvider` URI; the flow rejoins at `PickedPhoto`. Because the system camera app holds the permission, the app declares no `CAMERA` permission and shows no runtime prompt — verified on the phone. A photo for a kingdom that keeps photos is promoted into the gallery at registration; a plant's is deleted after registration or cancel and never enters the gallery.
- **M41** — A plant capture stores no photograph and no thumbnail; plant captures made before this shipped keep theirs. A photoless plant's grid tile and detail hero show the species' reference image with a path-neutral "caught — no photo of your own" mark, on accent chrome that distinguishes it from an uncaught tile whether or not the reference image loads. A plant registers with or without a photo on both the typed and the identified path, **and on the add-your-own path**, and whenever an attached photo would be discarded the screen says so before the user registers. A photoless capture is never offered a photo viewer or a re-link, is never counted as a missing photo in an export, and takes no URI grant.
  The rule is enforced in `AddSpeciesRegistrar` and in the Register screen rather than on one screen alone, so no door into the store can violate it. The capture row is still written — a caught species is caught — it simply has no photo.
- **M42** — The plant uses section labels the curated use **"Food source"**; the wire name `edible` is unchanged in the asset, the database and the backup format.

### SHOULD

- **S11** — A capture registered from an identified candidate has its note prefilled with the provider and the score, editable and deletable.
- **S12** — The candidate panel offers the S06 Lens share and "type a name" as fallbacks on every outcome including no-candidates.
- **S13** — Settings shows the provider's own remaining quota or credit balance where an endpoint exists (Pl@ntNet `GET /v2/quota`), beside the app's monthly count.
- **S14** — A further provider (iNaturalist for fungi or animals; Gemini for animals) is addable behind `SpeciesIdentifier` without a Register-screen change.
- **S15** — The fungi catalogue's first cut is 30 curated Pacific USA species built through the existing pipeline with no Duke's join.
- **S01** — Export produces a single archive via the share sheet: the collection metadata as JSON, all thumbnails, and — for every URI that still resolves at export time — a full-size copy of the photo, so the archive is self-contained on a new device. Import restores from such an archive, storing the imported full-size photos as local copies. Archive metadata carries kingdom and uses for user-added species.
- **S02** — Canonical images are cached on first successful load, so a previously viewed entry works fully offline afterward.
- **S03** — A global "keep a local copy" setting (default off — confirmed by the user) makes registration also copy the full-size photo into app storage, as the durability escape hatch against gallery deletions.
- **S04** — The user can mark one photo per entry as the favorite; it becomes the grid thumbnail.
- **S05** — A capture can carry a free-text note, editable later; user-added species also have an editable name and outbound link.
- **S06** — The Register screen offers a "Not sure? Open in Google Lens" affordance that hands the picked photo to Lens via a share intent, matching the user's actual identification workflow.
- **S07** — Deleting a capture removes the reference and thumbnail (and local copy if any) but never touches the gallery photo; deleting the last capture of an entry reverts the species to uncaught after an explicit warning.
- **S08** — The stats screen shows a "recently caught" strip and the date of the last new catch, across both kingdoms.
- **S09** — A `Caution:` sentence in a uses note is rendered in the stop colour with a leading warning glyph, on the detail screen and on the confirmation card, so a lookalike or preparation hazard is never visually equal to "berries, late summer".
- **S10** — The unlock reveal's counter names the kingdom it incremented ("4 / 80 plants"), and the reveal for a plant uses its plant silhouette in the halo.

### COULD

- **C09** — A per-kingdom variant of S03 ("keep a thumbnail of my plant photos") for users who want a trace of their own find without a gallery photo; adds nothing to the data model because `thumbPath` is already nullable.
- **C10** — A Pl@ntNet species-page link on user-added plants, once the URL is verified public, stable and constructible from the identify response.
- **C11** — Synonyms emitted by the pipeline into the asset and stored on the species row, consulted by the resolver after the accepted name.
- **C12** — Dropping a plant's photograph on the offline add-your-own path. Offline there is no lookup and so no kingdom to test, so such a plant keeps its photo where an online one would not. A known corner, left alone deliberately: closing it means deleting a capture's photo long after the user has forgotten attaching it.
- **C01** — A map view plots captures that have EXIF locations.
- **C02** — Per-ecosystem completion badges (e.g. all of Coastal Rainforest) with a small celebratory moment; per-kingdom badges follow the same pattern.
- **C03** — A second regional dex (e.g. "Eastern USA BioDex") installable later; the region concept in the model exists to make this a content problem, not a schema change.
- **C04** — Home-screen widget showing progress and the most recent catch.
- **C05** — A periodic background check that flags captures whose URIs have stopped resolving, so re-linking can happen before the user notices in the field.
- **C06** — Promotion: a later catalogue update that includes a species the user had already added folds it into the curated list, merging the user's captures onto the new curated entry and retiring the U-number.
- **C07** — Further kingdoms: fungi, and the kelps and seaweeds of the shoreline (which are not plants). The kingdom enum is designed to grow; each new kingdom brings its own meter, silhouettes and classes.
- **C08** — A "naturalized, not native" marker on species such as Himalayan Blackberry, Mullein and Dandelion, shown as a small line on the detail screen.

## 4. The "catching" mechanic

Uncaught species render as flat dark silhouettes on the grid and in detail — present, named, but withheld. This makes the dex legible as a to-do list: you can see what is out there and what you are missing, which is the engine of the whole product. Ecosystem meters sharpen it: "High Desert 2/19 · 1/12" is an itinerary, not just a statistic — and with plants in the dex, the itinerary now works in every season: the trees and shrubs are there when the birds are not.

Registering is species-first: you already know what you photographed (Lens told you, or you did), you search the name, attach the photo, register. The unlock moment is unchanged: on first registration the silhouette cross-fades into the user's actual photo, the dex number stamps in, a short haptic tick fires, and the counter for that kingdom visibly increments. Quick (about a second and a half), skippable, restrained — a card flip and a soft glow, not confetti. The user's own photo becomes the entry's face.

Repeat registrations of a caught species are deliberately low-key: the photo slides into the entry's strip with a brief "+1" toast. Ceremony is reserved for firsts, so firsts stay special. User-added species get the same reveal, with their U-number in place of a dex number.

Plants change the rhythm of the game more than its rules. A tree is caught on a walk with no luck at all; the plant list is the reliable half of the dex and the animal list the lucky half. Two separate meters are what let both feel like progress rather than the plants diluting an animal count the user has already started filling.

The tone target is "field naturalist's logbook with good game feel": tactile, satisfying, quiet. No XP, no streaks, no mascot.

## 5. Key product decisions

**D1 — Seed the dex with curated regional lists (120 animals, ~80 plants), not a full taxonomy.**
Why: a dex must feel completable to work as a game. A full taxonomy makes progress read as 0% forever and the unlock loop dies. ~120 animals the user can realistically encounter west of the Rockies — common backyard and regional-park animals plus a tail of aspirational ones, invertebrates included — keeps every silhouette a plausible goal. The plant list follows the same rule at ~80: roughly 40 trees (the region has few enough that "most trees" is a real, finishable list), and among the rest the fruit-bearing, edible and medicinal species the user actually wants to find. The region is a first-class concept so more regions can ship later (C03).
Rejected: full taxonomy (unwinnable); dynamic list from an API (network dependency, unstable dex numbers, no offline guarantee); a plant list of every wildflower (hundreds of near-identical entries nobody would finish).

**D2 — Identification is a suggestion service the user invokes; the app never identifies.** *(v6; superseded "No in-app identification, deliberately.")*
Why: the typed-name path is unchanged (M07) and remains the whole story for animals and fungi. What v6 adds is a camera and an *Identify* button that send **this** photo to a named third party and show its candidates as a list to choose from — ranked, with the classifier's score shown as such (D22), validated through GBIF (M32) and matched to the catalogue on scientific name (M33). The app never auto-selects (D20) and never renders "this is *X*". The claim it makes is "Pl@ntNet suggested these", in the same attribution register as "Duke's records these uses": a source's statement, never the app's. There is no fungus or animal provider, so the button exists only for plants.
The original argument still holds where it applied — the app must not be the thing that told the user what a plant was — and is satisfied by the app never issuing a verdict rather than by refusing to ask.
Rejected: auto-selecting the top match (D10's argument, made stronger by a photo being less reliable than a typed name); an on-device classifier (heavyweight, and still no species-level model); a compare-against-reference confirmation gate on plants (the first draft of the identification design had one — cut as ceremony the user declined).

**D3 — Habitat text, descriptions, uses, the Duke's index and silhouettes ship with the app; canonical images are streamed then cached.**
Why: text is tiny and must work offline day one, so it is bundled — and uses text especially, since it is read standing in front of the plant. Duke's ethnobotanical file is CC0 and a couple of megabytes compacted, so the whole index ships in the APK and a user-added plant gets its medicinal activities and poison check with no network at all. Media is large and third-party, so it is loaded from source URLs and cached on first view (S02) — the field-use case degrades gracefully (placeholder + text) rather than failing.
Rejected: bundling all media (app bloat, and redistributing licensed media crosses from linking into copying); streaming everything always (dead in the field).

**D4 — *Removed in v5.*** Call audio streaming with cache-on-first-play. Gone with the rest of the call feature (see the header note); the app now streams no audio of any kind, and the Media3 player and its audio cache were deleted.

**D5 — Third-party media is linked/streamed with visible attribution; nothing third-party is redistributed.**
Why: sources like Wikimedia Commons are CC-licensed with attribution requirements, and some (e.g. Macaulay Library) do not permit redistribution at all. Streaming from source with an attribution line on the detail screen (M17) satisfies the licenses. Cached copies are private device caches, not redistribution.
Rejected: baking media into the APK (license risk, size); no attribution (violates CC-BY terms).

**D6 — Photos are referenced from the gallery by persistable content URI; the app stores no copy by default.**
Why: this is what the user asked for ("a link to Google Photos would be better than storing the photo"), and Android's own photo picker delivers it with no Google API at all: `PickVisualMedia` returns a content URI, and `takePersistableUriPermission()` with the read flag makes the grant survive reboots. The Google Photos API cannot do this — the `photoslibrary.readonly` scope was removed in April 2025, and its replacement Picker API returns only a `baseUrl` that expires after 60 minutes, with no durable link back to the library item — and it would have required OAuth and a Cloud project besides.
Tradeoffs, stated honestly: if the user deletes or moves the photo in the gallery the reference breaks — the entry stays caught with a "photo unavailable" placeholder and a re-link offer (M12), never a silent blank. A photo that lives only in Google Photos cloud storage and isn't on the device may not resolve offline. Android caps persistable grants at 5,000 per app, far above a personal life list but real. The app keeps its own thumbnails (M11) so the collection always renders, and the "keep a local copy" setting (S03, default off) is the durability escape hatch — default off because linking, not storing, is the point.
Rejected: Google Photos API links (technically impossible since April 2025, see above); copying every photo into app storage (duplicates the user's library and contradicts the ask); referencing without persisting the grant (breaks on every reboot).

**D7 — An entry's identity is the species, unlocked once; repeats accumulate under it.**
Why: this is the Pokédex frame — the list is of species, not of sightings. Sightings-as-primary is a journal (eBird and iNaturalist already exist); species-as-primary is a collection. Captures remain first-class underneath, so no data is lost.
Rejected: sighting-first journal model (different product); per-location or per-season re-unlocks (badges C02 can cover this itch later).

**D8 — The unlock reveal is restrained by design.**
Why: the user is an adult using this for years; a quiet, tactile reveal stays satisfying on the 90th unlock where fanfare would curdle.
Rejected: full-screen celebration animations, sound effects, mascot commentary.

**D9 — Ecosystems are a first-class dimension; species can belong to several; user-added species sit outside the meters.**
Why: ecosystems make the collection a map of places to go, and per-ecosystem meters give the dex seven small winnable games inside the big one. Multi-membership reflects reality (a Coyote genuinely spans three ecosystems) and costs nothing beyond the honest caveat that ecosystem totals sum past the catalogue — each meter is consistent within itself, which is what the user reads. User-added species are an addendum ("+3 of your own"), not part of either kingdom's count: letting them count would let the meter inflate itself and make "complete" meaningless.
A user-added species with ecosystem tags shows on that ecosystem's meter as an addendum ("12/24 +1"), leaving the curated fraction intact — same treatment as the overall meter's "+3 of your own". With two kingdoms, every ecosystem meter is two fractions, one per kingdom, each with its own addendum.
Rejected: single-ecosystem assignment (forces false choices for wide-ranging species); counting user-added species toward completion (destroys the meter); ecosystem as a mere filter tag (loses the per-ecosystem progress that makes it fun).

**D10 — User-added species auto-populate from public APIs, behind one confirmation step.**
Why: the user supplies the two things only they have — the name and the photo — and free public APIs supply the rest. GBIF's species-match endpoint is the spine: it decides which species the name means, returning the kingdom, the accepted scientific name, the class, and a confidence score, with alternatives when the name is ambiguous. Wikipedia supplies the prose and picture, preferring the article's "Habitat" or "Distribution and habitat" section over the generic lede, so an auto-added entry reads like the hand-written curated ones. The confirmation card exists because common names are ambiguous ("sparrow" matches dozens of species; "cedar" is two families) and a silent wrong pick corrupts the entry permanently: nothing is saved until the user accepts, swaps candidates, or edits. Ecosystem tags are a field no API can supply — nothing maps species onto these seven Pacific ecosystems — so they stay a manual multi-select on the card; for plants, growth form and uses are manual for the same reason (D13, D14).
Identity and travel: user-added species take U-numbers (U01, U02, …) in the order added and sit outside both meters. A holiday catch from outside the region lands here as an ordinary user-added entry with no out-of-region flag — the addendum is already outside the meter, so a flag would add complexity without changing anything the user sees. If one turns out to be a genuine missing Pacific native, a later catalogue update can promote it (C06).
Rejected: silent auto-accept of the top match (wrong often enough on ambiguous names to be destructive); fully manual entry (v2's design — undersold what the free APIs deliver); blocking registration on the lookup (the offline-first rule governs here too, hence "details pending" + backfill, M20).

**D11 — The product is BioDex; a regional dex is `<Region> BioDex`; the UI shows the two names as two things.**
Why: the app is no longer about animals, and the user wants the Pokémon shape — one product, regional dexes inside it. The app bar had a title ("Animal Dex") and a region pill ("PACIFIC") that read as one label; now the title is the product name, "BioDex", and the pill is the region's display name, "Pacific USA", so the pair reads "Pacific USA BioDex" without the title having to change per region. The region's display name comes from the catalogue (a `regions` table seeded from the asset), not from code, so an Eastern USA BioDex is a content drop. The app bar also carries one progress pill per kingdom, so it is now five elements wide; the rules for narrow screens are that pills drop their inner spaces (`47/120`), the plant pill is hidden while the catalogue has no plants, and if it still does not fit the title ellipsises before any pill does — the counts are the information, the title is the brand.
Rejected: "Pacific USA BioDex" as one long title (does not survive a second region and leaves no room for the meters); dropping the region pill because there is only one region (the point is to make the region legible as a thing that could be different); naming the animal half "Animal Dex" inside BioDex (two brands for one app).

**D12 — One regional dex, two kingdoms, counted separately; plants get their own number sequence.**
Why: the user has started filling a 120-animal meter, and folding 80 plants into it would turn "47/120" into "47/200" overnight — destroying a number that already means something. Two meters also match how the two halves are played (§4): the plant list is finishable in a season of walks, the animal list is a years-long luck game, and a single blended percentage would hide both facts. The kingdoms nonetheless share everything else — grid, ecosystems, register flow, detail screen, stats screen — because a Douglas-fir and a Douglas Squirrel are found on the same walk in the same ecosystem, and two separate apps-inside-an-app would double every screen. Plants are numbered `P001`–`P080` in their own field-guide order (trees, shrubs, herbs, ferns) so the number itself marks the kingdom on a grid cell; animals keep `#001`–`#120` exactly as they are.
Rejected: one blended 200-species meter (see above); separate tabs or a kingdom switcher (splits an ecosystem walk into two screens, and doubles the stats); numbering plants `#121`–`#200` (implies one list and would renumber if the animal list ever grows).

**D13 — Plant classes are growth forms — tree, shrub, herb, fern — and kingdom is stored on every species.**
Why: the stats screen's class bars and the filter chips must group plants in a way the user thinks in, and the user thinks "trees" — nobody filters for Magnoliopsida. Growth form is also what a silhouette can depict: a conifer, a broadleaf tree, a shrub, a forb and a fern are five recognisable shapes, where "dicot" is not a shape. Because growth form is not taxonomy, GBIF cannot supply it: like ecosystem tags it is curatorial input in the plant list, and a manual pick (with a sensible default) on the confirmation card. The one thing GBIF *can* decide is conifer versus broadleaf within trees, so the pipeline picks that silhouette automatically from GBIF's class (Pinopsida → conifer). Kingdom is stored as its own field rather than derived from class, because it is what the header meters, the kingdom chips and the pipeline's validation key on, and because a details-pending species has a kingdom before it has a class. Every class belongs to exactly one kingdom and the app enforces that pairing.
Rejected: taxonomic plant classes (meaningless to the user, and GBIF's plant classification is unstable — it has no consistent class for many flowering plants); three growth forms with ferns folded into herbs (a fern is the most recognisable plant silhouette there is, and the pipeline can tell ferns apart from GBIF's class Polypodiopsida for free); deriving kingdom from class at runtime (leaves a pending species classless *and* kingdomless).

**D14 — Uses are two differently-sourced tags and one curated note, with Duke's poison records driving which species must carry a caution — and the app is explicit about which claim is whose.**
Why: food source and medicinal are the two things the user actually cares about in a plant, and they must be a filter, not a footnote, or the "which of these can I forage for the camera today" question the user is asking goes unanswered. The two halves have different provenance and the design refuses to blur them.
*Medicinal is sourced.* Dr. Duke's Phytochemical and Ethnobotanical Databases (USDA Agricultural Research Service) is public domain (CC0), a single 5.8 MB download rather than a per-species API, and its `ETHNOBOT` table holds 82,873 use records over 13,010 taxa keyed by genus and species with an activity name per record. Coverage against this region's plants was measured, not assumed: yarrow 105 records, nettle 79, elderberry 60, Douglas-fir 11, western redcedar 7, salmonberry 5, Oregon grape 4, devil's club 0, evergreen huckleberry 0 — roughly a fifth of the plants have nothing, which is a normal state. So the medicinal tag is *derived*: a species is tagged medicinal when Duke's records three or more distinct activities for it (a threshold, because Duke's is broad enough that a single stray record would tag nearly every plant), the curator can pin the tag either way, and the entry shows the record count and the activities so a 105-record yarrow and a 4-record Oregon grape read differently. It is presented the way the app already presents Wikipedia habitat text: fetched, attributed, bundled, shown as what a source records, never as the app's own claim. This replaces the v4 draft's hand-written medicinal notes, which would in practice have been written by an implementing agent from model knowledge — fluent, plausible, unverifiable, about something the user may put in their mouth.
*Food source is curated.* The tag was labelled "Edible" until 2026-09-02; the user renamed it because the app was over-weighting the edibility question, and the wire name `edible` is unchanged everywhere below the label (M42). Duke's is overwhelmingly medicinal — 15 `Food` and 14 `Fruit` records in the entire file — and no other public source is both structured and trustworthy. So the fruit-bearing and edible plants stay editorial judgment in the curated list, exactly as ecosystem tags are, with a short curated note naming the part and the season, because "edible" alone is useless in front of a plant whose leaves are eaten and whose berries are not. The pipeline pulls Wikipedia's "Uses" and "Culinary" sections into a curator review file to help write those notes, and never into the asset.
*Poison records drive the cautions, as a checklist, not a badge.* This is the one part of the caution machinery that survived the 2026-09-02 trim intact, because it is decided by a source rather than by a curator's mood. The same Duke's file carries 1,654 `Poison` records. A "toxic" tag on some species would imply the untagged ones are safe — the wrong inference for a foraging app — so there is no tag. Instead the pipeline treats a `Poison` record as a build-time requirement: every such species must carry a `Caution:` sentence in its note or the build fails, so the set of cautioned species is decided by a source rather than by whoever wrote the notes, and a plant with both a traditional use and a poison record — precisely the case a hand-written list misses — cannot ship without one. The curator still adds cautions Duke's cannot express (a lethal lookalike, a preparation hazard, a documented use the modern literature says not to follow). On a user-added plant, where no curator exists, the bundled index pre-fills the same caution sentence (M27).
The safety dimension, stated plainly: an app that says "food source" carries real-world risk. BioDex therefore claims only that a use is *documented for the species*, says by whom, does not identify the plant in the photo — it shows a named service's suggestions and never picks one (D2) — does not say a part is safe, and shows a one-line disclaimer under any section that displays a use (M30). Uses are plant-only: whether an animal is edible is a hunting and fishing regulation question, not a field-guide fact, and the app stays out of it. Fungi carry no uses either, for a different reason (M35).

*How much of this text there should be, decided 2026-09-02.* The first cut of the fungi catalogue gave every one of the 30 species a mandatory `Caution:` paragraph, and the plant notes averaged 28 words of look-alike keys and preparation hazards. The user's judgment, given twice: this is a collecting game, entries are a sentence or two, and the safety apparatus was overdone. So a note is now written **only when the species itself is dangerous**, in one short sentence, and it says what the thing does to a person rather than what it is mistaken for — a look-alike key belongs in a field guide and reads as noise here. Twenty of the thirty fungi carry nothing at all and read like an animal. The Duke's rule below is untouched by this, because it is source-driven and now costs four words; the rule that *required* a caution on every fungus is gone, and it is what had put a warning on the turkey tail and forced a sentence to be invented where no source said anything. The long-form statement moved to the top of the README (M30).
Rejected: hand-written medicinal notes (the v4 draft — see above); fetching edible from Wikipedia prose (unreliable, and puts free text behind a safety claim); a curated or sourced "toxic" tag or badge (see above); tagging medicinal on any single Duke's record (nearly every plant would qualify); per-species queries against a live ethnobotany service (there is none, and a bulk CC0 file is the better shape for an offline app anyway); Duke's phytochemical tables (chemistry, not use — out of scope).

**D15 — A plant's uses sit between the habitat text and the photo strip, and a plant with no recorded use shows nothing there.**
Why: uses are the kingdom-specific fact about a plant, and they belong with the rest of what the species *is*, above the user's own photos. A plant with no recorded use renders nothing rather than "No uses recorded" — an empty section on a Douglas-fir tells the reader nothing, and the plant's habitat text and photos are the whole of its story. (In v4 this decision was framed as the uses section standing where an animal's call row was; v5 removed calls, so the section simply follows habitat for every plant and animals have nothing there.)
Rejected: a "No uses recorded" line (see above); putting uses in the habitat paragraph (loses the tags, the caution styling and the filter's visual anchor).

**D16 — Two ecosystems are renamed so they describe where a plant grows, not only where an animal lives.**
Why: the seven ecosystems were named from the animal list. Checked against the plant list, five hold as written — Coastal Rainforest, Oak Woodland & Chaparral, Riparian & Wetland, High Desert & Sagebrush and Urban & Suburban all describe plant communities as well as animal habitats. Two do not. "Sierra/Cascade Alpine" names only the zone above treeline, and the montane conifer forest below it — Ponderosa Pine, Sugar Pine, Lodgepole, Red Fir, Giant Sequoia, Whitebark Pine, most of the forty trees — had no ecosystem at all; it becomes **Sierra & Cascade Mountains**, montane forest through alpine. "Rocky Shoreline & Kelp" describes the animal habitat by its cover and leaves out the dunes and bluffs where the shore plants (Beach Strawberry, Salal on the bluffs) actually grow; it becomes **Shoreline, Dunes & Kelp**. Kelp stays in the name because the kelp forest is still where the sea otters and rockfish are tagged, even though kelps themselves are not plants (C07). Ecosystem ids (`alpine`, `rocky-shore-kelp`) do not change — they are never shown — so the animal list's 120 tag sets are untouched.
Rejected: adding an eighth "Montane Forest" ecosystem (splits a single mountain walk into two meters, and the animal list's alpine species would need re-tagging); leaving the names alone and tagging the conifers "alpine" (wrong in a way a user who has stood in a ponderosa forest would notice).

**D17 — The rename installs fresh: no Room migration, no data carried over.**
Why: the package changes from `dev.tlong.animaldex` to `dev.tlong.biodex`, which to Android is a different app with its own sandbox, and the only data in the old one is a single throwaway test capture. So the v4 schema is designed as if starting from an empty database — new columns, a new table and a moved user-added number base — and ships as schema version 1 of the new app. The old app is uninstalled by hand. The implementers must **not** build migration machinery for this change: no `Migration` objects, no `fallbackToDestructiveMigration`, no import of the old database. The rule that every later schema change ships a real migration resumes the moment the first real capture lands in the new app.
Rejected: keeping the package and migrating (a migration for one throwaway row, and the applicationId would still say animaldex forever); an in-app "import from Animal Dex" (there is nothing to import).

**D18 — The Register screen pins search and actions and scrolls only the list.**
Why: the shipped screen is one scroll view — title, search, results, photo row, buttons — so with the empty-query behaviour listing the catalogue, the Register button sits 120 rows down, and arriving from a detail screen's "Register this species" puts the pre-selected species 40 rows down and out of sight: it looks like nothing happened. The fix is the layout every search-and-commit screen uses: search pinned at the top, the photo row and the two action buttons docked at the bottom, a lazy list scrolling between them, scrolled on arrival so the pre-selected species is on screen and marked selected. With the actions docked, the list no longer needs the 25-row cap that existed only to keep the buttons reachable, so the whole 200-species catalogue lists under an empty query. This bug would have bitten twice as hard with plants — the list is now 200 rows.
Rejected: keeping one scroll view and scrolling it to the button (the user still cannot see search and the selected species at once); collapsing the results when a species is pre-selected (hides the way to change the pick).

**D19 — One provider, Pl@ntNet, for plants only; no kingdom chip.**
Why: fungi and animals have no provider, so there is no routing choice to put in front of the user. A registry keyed by `Kingdom` keeps the shape for a later provider without asking for anything now. Google Lens was the original request and turned out to be impossible as framed — it has no public API, and its share intent is one-way — so the choice was a real classifier or nothing. Pl@ntNet is free for non-commercial use, returns scored candidates with scientific names, and in a 2023 comparison identified plants correctly far more often than Lens.
Rejected: a kingdom chip on the Register screen (a control with one working setting); shipping fungal identification (no provider we trust, and see D23).

**D20 — Candidates are chosen, never auto-selected.**
Why: D10's argument, strengthened by a photo being less reliable than a typed name.
Rejected: preselecting the top match with a "change" affordance (the preselection *is* the app's statement); auto-registering above a score threshold.

**D21 — One interface, `LookupResult` outcomes, a query producer.**
Why: identification yields names, and everything downstream already keys off a name.
Rejected: a new outcome type; an identification screen of its own; caching results across sessions.

**D22 — Confidence is rendered by kind.**
Why: a classifier score is a probability the user can weigh; a language model's "confidence" is a token it emitted. Every provider today is a classifier, so every score is a percentage; the self-report rendering stays specified for a future provider.
Rejected: a uniform "high / medium / low"; hiding scores altogether.

**D23 — No identification for fungi, and none for animals yet.**
Why: for fungi, a wrong mushroom name is the one wrong answer in this app with a serious consequence, and nothing here sources fungal claims (M35). For animals, no provider has been chosen. Neither is a permanent decision; both are the absence of a provider rather than a refusal to build the mechanism.
Rejected: a confirmation gate that would let fungal identification ship (an earlier draft had one, cut with the feature); the same gate on plants (cut at the user's direction as over-weighting plant edibility).

**D24 — Keys live in Settings, not the build.**
Why: the repository is public and an APK is unpackable, so a compiled-in key is a published key. The cost is that the feature ships dark until the user pastes one (R16).
Rejected: a build-time key from a git-ignored properties file (still ends up in the APK); a proxy service (a backend, which this app does not have).

**D25 — A plant capture keeps no photograph, and its tile is the species' reference image.**
Why: the user's own framing — for a plant the photo exists to identify it, not to be kept, and a catalogue reference picture is a better tile than a snapshot of a shrub. The hazard is that a caught plant rendered with the reference image alone looks like a *rich uncaught tile*, so the colour carries the state rather than the picture: accent chrome and a leaf glyph, applied whether or not the image actually loaded.
Rejected: keeping the photo (the user asked for the opposite); an animated generic leaf in place of a picture (loses the species); a "no photo" placeholder (reads as broken).

**D26 — The camera writes to app cache and promotes at registration.**
Why: a shot must not enter the gallery before the user commits, and for a plant it must never enter at all — but the kingdom is not known until a species is chosen. So the file waits in cache, and the screen that knows the kingdom decides: promote, or drop and sweep. On the add-your-own path that screen is the confirmation card, not the Register screen.
Rejected: promoting immediately on capture (puts a plant's photo in the gallery, and litters it on cancel); writing straight to the gallery via `MediaStore` (same problem, plus a permission).

**D27 — Fungi are a third kingdom in the catalogue, not a flavour of plant.**
Why: they are a different GBIF kingdom, a different provider, a different risk class and a different silhouette set. Folding them into `PLANT` would put mushrooms behind the Plant chip, in the plant fraction and under the plant provider, which does not identify them.
Rejected: a `taxClass = mushroom` under `PLANT` (every one of those consequences); shipping fungal identification against an empty catalogue (every candidate reads "not in dex", which is the feature not working).

## 6. Screens

1. **Dex Grid** (home) — the Pacific USA catalogue as one grid, animals then plants, caught thumbnails and uncaught silhouettes, user-added species trailing; search; a chip row with caught, kingdom (Animals / Plants), use (Edible / Medicinal), class and ecosystem chips; a header with the BioDex title, the region pill and one progress pill per kingdom.
2. **Entry Detail** — everything about one species: canonical picture (or silhouette), ecosystems, habitat, then — for a plant with uses — the uses section with tags, note, caution and disclaimer, and nothing there for an animal or a plant without uses; then the user's photo strip, attribution, outbound link, Register button.
3. **Register a Species** — search pinned at the top, the catalogue (both kingdoms) scrolling beneath it, and the gallery photo row, Register button and "Add your own species" docked at the bottom; opens scrolled to a pre-selected species; optional Lens hand-off.
4. **Add Species — Confirm** — the confirmation card for a user-added species: the GBIF match with alternatives and its kingdom, found image and habitat text, the manual ecosystem multi-select; for a plant the growth-form pick, use toggles and uses note; accept, pick a different match, or edit by hand.
5. **Unlock Reveal** — the transient first-catch moment (overlay, not a destination); its counter names the kingdom.
6. **Photo Viewer** — one photo full-screen (resolved from its URI, or the unavailable state with re-link); date, place, note; delete and set-favorite live here.
7. **Stats** — an overall meter per kingdom, per-ecosystem rows with an animal and a plant meter each, class bars grouped under Animals and Plants, recent catches.
8. **Settings** — export/import, "keep a local copy" toggle, cache management, licenses, About (with the foraging disclaimer).

Navigation: Dex Grid is home. Grid → Entry Detail (tap a cell). A floating Register button on the grid and a Register action on uncaught detail screens → Register a Species → (Unlock Reveal →) Entry Detail of the registered species. Register → Add Species Confirm when the name isn't in the catalogue — and again later when a "details pending" entry backfills (M20). Detail → Photo Viewer (tap a photo). Stats and Settings are reachable from the grid's top bar. Back always returns to the previous screen; after registering, back from the detail screen returns to the grid.

## 7. Out of scope for v4

- Cloud sync, accounts, or any backend; any Google Photos API integration.
- Identification of animals and fungi — deferred until a provider is chosen; Google Lens remains the external tool. The app never tells the user what a mushroom is (D19).
- An on-device classifier of any kind: identification is a named third party's suggestion or it does not happen (D2).
- Foraging guidance beyond the documented-use tags, notes and the sourced medicinal line: no recipes, no dosages, no preparation instructions, no "is this safe" answers. Duke's phytochemical tables are not used.
- Video.
- Multi-user support, sharing, social features, leaderboards, trading.
- A second region (the model and the naming support it; the content doesn't ship).
- Further kingdoms beyond the three that ship — kelps and seaweeds (C07).
- In-app editing of the curated catalogue (user-added species are separate).
- A tablet or Wear OS layout; iOS.
- Gamification beyond the dex itself: XP, levels, streaks, daily quests.

## 8. Naming

- **BioDex** is the product. Written as one word with a capital B and capital D everywhere the user can read it: launcher label, app bar, share sheets, backup archive, About.
- A regional dex is **`<Region> BioDex`**: the shipped one is the **Pacific USA BioDex**. In the UI this is the "BioDex" title plus the "Pacific USA" region pill, never a single concatenated string, so a second region changes one pill.
- Kingdoms are **animals** and **plants** in the UI (chips, meters, stats headings), lower-case in prose, capitalised as chip labels.
- Plant classes are **trees, shrubs, herbs, ferns** as chip and bar labels; animal classes keep their existing labels.
- Uses are **Edible** and **Medicinal** as chips and tags; the section heading is **Uses**.
- Numbers: animals `#001`, plants `P001`, user-added `U01`.
- Internally the package is `dev.tlong.biodex`, the database `biodex.db`, and the HTTP User-Agent `BioDex/1.0`; "Animal Dex" and `animaldex` do not survive anywhere in the codebase after the rename.

## 9. Open questions for the user

None that block the design. The two ecosystem renames (D16) and the plant list's composition — 40 trees / 18 shrubs / 19 herbs / 3 ferns as growth forms, with edible and medicinal as overlapping tags (ARCHITECTURE.md §11.3) — were both confirmed after the v4 draft. The one judgment worth a look once the pipeline report exists is the medicinal threshold of three distinct Duke's activities (D14): the report says how many of the 80 it tags, and the number can move without touching the design.
