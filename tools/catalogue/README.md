# Catalogue pipeline

Builds the bundled Pacific USA catalogue asset the app ships with — 120 animals,
80 plants and 30 fungi:

```
tools/catalogue/region.json            (header + the seven ecosystems)
tools/catalogue/curated_animals.json   (hand-authored input, 120 animals)
tools/catalogue/curated_plants.json    (hand-authored input, 80 plants)
tools/catalogue/curated_fungi.json     (hand-authored input, 30 fungi)
        │
        ├─ GBIF        accepted scientific name, kingdom, rank, class, synonyms
        ├─ Wikipedia   habitat prose, description lede, canonical image, page link
        ├─ Commons     image license + author, for the attribution line
        └─ Duke's      medicinal tag, activities, record count, poison flag —
                       PLANTS ONLY (one bulk CC0 download, no per-species calls)
        ▼
app/src/main/assets/catalogue/pacific.json         (generated, committed to git)
app/src/main/assets/catalogue/duke_ethnobot.json   (generated, committed to git)
```

The input is split four ways so the plant curator, the fungus curator and an
animal edit never touch the same file. `region.json` owns `catalogueVersion`,
`regionId`, `regionName` and the ecosystems; the three species files own nothing
else.

The app never runs this. It runs on the build machine, its output is committed,
and builds never touch the network.

## Running it

```bash
cd tools/catalogue
python3 build_catalogue.py                 # writes ../../app/src/main/assets/catalogue/pacific.json
python3 build_catalogue.py --out /tmp/x.json
python3 build_catalogue.py --refresh       # ignore the cache, re-fetch everything
python3 build_catalogue.py --only 5        # smoke run over the first five of each kingdom
python3 build_catalogue.py --plants /tmp/edited.json --out /tmp/x.json
python3 build_catalogue.py --fungi /tmp/edited.json --out /tmp/x.json
```

`--region`, `--animals`, `--plants` and `--fungi` each point the script at a
different input file. They exist so a validation rule can be exercised against a
modified copy without touching the real inputs — the poison-caution rule below
is tested that way, and so is the fungal caution rule.

`duke_ethnobot.json` is written beside `--out`, so a run into `/tmp` leaves the
committed asset alone.

Standard library only — no venv, no `pip install`. (ARCHITECTURE.md 7 mentions
`requests`; this machine has no `requests`, so the script uses `urllib`. Nothing
to set up.)

A cold run takes roughly 25–30 minutes: it sleeps between requests to be polite
to Wikipedia (1 s) and GBIF (0.5 s). Duke's is one 5.8 MB
download for the whole run, cached under `cache/duke/`.

**Every HTTP response is cached** under `cache/<sha1-of-url>.json`, so a second
run makes zero requests and finishes in seconds. `cache/` is disposable — delete
it for a genuinely cold run. It also holds `cache/report.txt`, the coverage
report from the last run.

> `cache/` is git-ignored by `tools/catalogue/.gitignore`, which lives in this
> directory rather than in the repository root — this slice does not own the
> root `.gitignore`.

## The curated animal input

`curated_animals.json` is one of the three hand-authored files, and it is where
editorial judgment about the animals lives. It carries the 120 species, each
with a dex number, a common name, a scientific name and one or more ecosystem
tags.

**Ecosystem tags are assigned by judgment, never fetched** — nothing in any API
maps species onto these seven ecosystems. Multi-tagging is expected and correct:
a Coyote is genuinely at home in high desert, oak woodland and the suburbs, and
counts toward each of those meters.

Dex numbers are grouped in field-guide order by class:

| Range | Class | Count |
|---|---|---|
| 1–52 | birds | 52 |
| 53–72 | mammals | 20 |
| 73–81 | reptiles | 9 |
| 82–88 | amphibians | 7 |
| 89–98 | fish | 10 |
| 99–113 | insects | 15 |
| 114–120 | other invertebrates | 7 |

The selection favors animals a person can realistically photograph around
Pacific-region towns, parks and coastline, with a tail of aspirational ones
(Tufted Puffin, Bobcat, Roosevelt Elk). Species names are the curator's; GBIF
still normalizes each one to the *accepted* name and supplies the class, so a
typo or an outdated name shows up in the report as a low-confidence or fuzzy
match rather than passing silently.

### Overriding a fetched field

Any species may carry an `overrides` object pinning any output field. Overrides
are applied last, after everything is fetched, and are recorded in the asset's
`provenance` as `"override"`:

```json
{ "dexNumber": 114, "commonName": "Banana Slug", "scientificName": "Ariolimax columbianus",
  "ecosystemIds": ["coastal-rainforest"],
  "overrides": {
    "habitatText": "Damp redwood and Douglas-fir forest floor, on leaf litter and rotting wood…"
  } }
```

Use it for the stubborn cases the report flags — an article with no usable
habitat prose, a bad image, a better outbound link.

### Pinning the Wikipedia article

An optional `wikipediaTitle` names the article to fetch, instead of letting the
script resolve one from GBIF's accepted name. This exists because GBIF
occasionally lumps a species into a broader one and the accepted name then
resolves to the wrong article entirely. Two live cases:

- **Roosevelt Elk** — GBIF's accepted name is `Cervus elaphus`, which is the
  *red deer*. Without the pin the entry got the red deer's European range as its
  habitat text. Pinned to `Roosevelt elk`, with `scientificName` overridden to
  `Cervus canadensis roosevelti`.
- **California Sister** — GBIF still lumps it into `Limenitis bredowii`. Pinned
  to `Adelpha californica`, with `scientificName` overridden to match.

Whenever the report flags a `SYNONYM` status, check which article the asset's
`provenance.wikipediaTitle` actually names before accepting the entry.

## What the script does per animal

1. **GBIF** `species/match?name=<scientific>&strict=false` → accepted name,
   kingdom, rank, class, confidence. A match whose kingdom is not `Animalia`
   **fails the build**: an animal entry that resolves to a plant is a curator
   typo that must not ship. The class maps to the app's `taxClass` enum
   (Aves→bird, Mammalia→mammal, Reptilia/Squamata/Testudines→reptile,
   Amphibia→amphibian, Insecta→insect, Actinopterygii/Chondrichthyes/
   Elasmobranchii→fish, everything else→other_invertebrate). GBIF's backbone
   carries **no class at all for ray-finned fishes** — salmon come back with an
   order (Salmoniformes) and nothing above it but the phylum — so a chordate
   with no class is mapped to fish. Birds, mammals, reptiles and amphibians all
   still carry a class, and sharks and rays carry Chondrichthyes /
   Elasmobranchii, so the rule cannot catch anything else. A non-exact match,
   a confidence below 95, a non-accepted status or a rank above species is
   **logged for the curator, never auto-fixed**.
2. **Wikipedia** — resolve the page by accepted scientific name, falling back to
   the common name. `action=parse&prop=sections` lists the sections; the first
   whose title contains "habitat" wins, then "distribution", then "range", then
   "ecology" ("Ecology" is last because on some articles it is about metabolism
   rather than where the animal lives). That section's wikitext is fetched by index, stripped of templates,
   refs, file links and markup, and cut to three sentences → `habitatText`. If
   there is no such section (or its prose is too thin), `habitatText` falls back
   to the summary lede and the report says so. The REST `page/summary` also
   supplies `description` (two sentences), `imageUrl` and `infoUrl`.
3. **Commons** `prop=imageinfo&iiprop=extmetadata` on the image file → the
   license short name and the author, formatted as
   `Wikimedia Commons · CC BY-SA 4.0 · <author>`.
4. Assemble the record with `silhouetteRes = sil_<taxClass>` and a `provenance`
   map naming the source of every fetched field.

## Plants

The 80 plants live in `curated_plants.json` and go through the same GBIF,
Wikipedia and Commons steps, plus Dr. Duke's.

### The entry shape

```json
{ "dexNumber": 47, "commonName": "Blue Elderberry", "scientificName": "Sambucus cerulea",
  "plantClass": "shrub",
  "ecosystemIds": ["riparian-wetland", "oak-chaparral", "urban-suburban"],
  "edible": true,
  "dukeName": "Sambucus nigra",
  "usesNote": "Berries, late summer to early autumn — cook or dry before eating; flowers for cordial and tea. Caution: raw berries, leaves, stems and bark are toxic; red-berried elders are not this species." }
```

- `plantClass` is required and is one of `tree` / `shrub` / `herb` / `fern`. It
  is **growth form, not taxonomy** (DESIGN.md D13) and it is editorial judgment,
  never fetched — which is exactly why GBIF's inconsistent plant classes cannot
  mis-class a plant.
- `edible` is optional, defaults to `false`, and is **the curator's only use
  tag**.
- **`medicinal` is not an input field.** It is derived from Duke's, below. A
  curator who disagrees with the derivation pins it with
  `"overrides": { "medicinal": true }`, and the asset's `provenance.uses`
  records the pin as `override`. Yerba Santa is the live case: a well-known
  regional medicinal with one Duke's activity, which the pin fixes for that one
  species rather than moving the threshold for all eighty.
- `dukeName` optionally pins the Duke's join for a species Duke's files under an
  older name.
- `usesNote` is at most 240 characters. It is **required** when `edible` is true
  (name the part and the season) and whenever Duke's records the species as a
  poison (then it must contain a sentence beginning `Caution:`). A species with
  **no use tag** may carry a note, but only a `Caution:` sentence and nothing
  else: the app reduces an untagged plant's note to its caution (`keptUsesNote`
  in `domain/UserSpecies.kt`, applied by the curated importer too), because the
  rest of a note describes a use the entry no longer claims while a warning
  outlives the tags it arrived with. Writing prose there is a build failure
  rather than a silent truncation.
- `wikipediaTitle` and `overrides` work exactly as they do for animals.

**Edible is curated; medicinal is sourced.** Duke's holds essentially no food
records — 15 `Food` and 14 `Fruit` rows in the entire 82,873-row file — so the
fruit-bearing and edible plants stay editorial judgment. The medicinal tag, the
activity names, the record count and the poison check all come from Duke's.

### The Duke's join

Dr. Duke's Phytochemical and Ethnobotanical Databases (USDA ARS), **CC0**. The
script takes one bulk download rather than per-species queries.

The data.gov and Ag Data Commons landing pages **return 403 to a plain fetch**,
so the script resolves the download through the figshare API:

```
https://api.figshare.com/v2/articles/24660351/files
   → Duke-Source-CSV.zip (5.8 MB) at https://ndownloader.figshare.com/files/43363335
```

The zip is cached under `cache/duke/`, so a re-run makes no request for it.
Inside, `ETHNOBOT.csv` holds 82,873 records over 13,010 taxa with `GENUS`,
`SPECIES` and `ACTIVITY` columns.

Per plant, the lookup tries, in order:

1. the GBIF-accepted binomial,
2. each synonym GBIF returns for the match (`species/{key}/synonyms`, one extra
   cached request per plant),
3. the curator's `dukeName` pin.

The first hit wins and `provenance.duke` records which name matched —
`duke:accepted`, `duke:synonym:Mahonia aquifolium`, `duke:pinned:Sambucus nigra`
or `duke:none`. **The synonym pass is not optional**: Oregon grape has 4 records
as *Mahonia aquifolium* and none as *Berberis aquifolium*, which is the accepted
name GBIF returns.

From a hit: `medicinalRecordCount` is the number of records excluding `Poison`;
`medicinalActivities` is the distinct non-`Poison` activities ordered by record
count, capped at eight and title-cased; the **medicinal tag is set when there
are three or more distinct non-`Poison` activities**; and a `Poison` record
anywhere for the taxon raises the poison flag.

**No hit is an ordinary state.** About a fifth of the list has nothing —
evergreen huckleberry and devil's club among them — and the report lists them
under "no Duke's record" without counting them as failures. The check on that
list is judgment: a famous medicinal plant appearing there is a join miss, not a
true absence, and wants a `dukeName` pin.

### `duke_ethnobot.json`

The same table, reduced and committed beside `pacific.json` so the app can look
a user-added plant up offline:

```json
{ "format": "biodex-duke-1", "license": "CC0",
  "taxa": { "achillea millefolium": { "a": ["Astringent", "…"], "n": 105, "p": false } } }
```

Keys are lower-cased `genus species`. `a` is the eight most-cited non-`Poison`
activities, `n` the non-`Poison` record count, `p` the poison flag. Activity
names are stored inline rather than through a shared string table: the file
lands near 1.1 MB either way, and an inline map is something a small test
fixture can be cut out of by hand. `LICENSE-duke.txt` beside it records the
source and the CC0 dedication.

### `cache/plant_uses_review.txt`

A curator aid, written on every run and **never** read back into the asset. Any
Wikipedia section whose title contains "Uses", "Culinary", "Edib", "Medicin" or
"Ethnobot" has its stripped prose (first 600 characters) written there, so the
hand-written `usesNote` can be checked against the article. The asset's
`provenance.uses.edible` is always `curated`.

## Fungi

The 30 fungi live in `curated_fungi.json` and take the GBIF, Wikipedia and
Commons steps unchanged — GBIF's backbone and Wikipedia both cover fungi well,
and every one of the 30 matched EXACT at species rank on the first run.

**What they do not take is Dr. Duke's.** It is an *ethnobotanical* database: it
has no fungal taxa at all. So a fungus has no medicinal join, no
`medicinalActivities`, no `medicinalRecordCount`, no `usesAttribution` — and,
the part worth stating plainly, **no `Poison` record**. The rule that makes the
plant cautions source-driven (a Duke's poison record forces a `Caution:`
sentence or the build fails) cannot fire here at all. Every fungal caution is
one person's sentence with nothing behind it, which is why this section is
longer than the list is.

### The entry shape

```json
{ "dexNumber": 9, "commonName": "Western Jack-o'-Lantern", "scientificName": "Omphalotus olivascens",
  "fungusClass": "mushroom",
  "ecosystemIds": ["oak-chaparral", "urban-suburban"],
  "usesNote": "Caution: poisonous — not lethal, but it causes very severe cramps, vomiting and diarrhoea. It is the chanterelle's look-alike: true blade-like gills rather than ridges, growing straight on wood, and faintly bioluminescent." }
```

- `fungusClass` is required and is one of `mushroom` (cap and stem) / `bracket`
  (shelf and conk) / `other_fungus` (cup, coral, puffball, jelly, morel). Like
  the plant classes it is **growth form, not taxonomy** — a forager recognises a
  shelf fungus on a trunk long before they can name its order.
- `usesNote` is required on **every** fungus, is at most 240 characters, and
  must be a sentence beginning `Caution:` and nothing else.
- `wikipediaTitle` and `overrides` work as they do for the other kingdoms,
  except that any override of a use field is refused.

### Fungi carry no uses, and the build enforces it

DESIGN-identification.md M35: a fungal entry has no uses section at all — no
"Food source" line, no medicinal line — and its only use-adjacent text is the
curator's caution. `build_fungus` writes `uses: []` unconditionally and then
asserts it after the overrides have been applied, so there is no input, no
override and no derivation that can put a tag on a mushroom. A row that tries —
an `edible` flag, a `uses` list, a `dukeName`, an override of any of them —
fails the build by name rather than having the claim quietly dropped.

The same rule covers the *fetched* half. A mushroom article's lede routinely
opens "… is an edible mushroom", so `drop_edibility_sentences` removes any
sentence of the fetched `description` or `habitatText` that uses an edibility
word before the asset is written, and the report lists every sentence it took
out. Whole sentences go rather than being reworded: what survives is still
Wikipedia's words, just fewer of them. Six sentences were dropped on the first
full run (chanterelle, king bolete, honey mushroom, fairy ring, matsutake,
lobster).

### Every fungus carries a caution

Because Duke's cannot decide the cautioned set here, the plant rule is inverted
and made unconditional: **every fungus must carry a `Caution:` sentence**,
harmless ones included. It costs a sentence on the turkey tail, and it buys the
one thing that matters — the kingdom has no row where the *absence* of a warning
could be read as reassurance.

A caution says what the species does to a person and what it is mistaken for,
written from the Wikipedia toxicity / similar-species / identification text the
run collects into `cache/fungi_caution_review.txt`. It never says a mushroom is
edible, safe, choice or good eating — not even the chanterelle. The app makes no
edibility claim about a fungus, by design (M35), and the validator fails the
build on those words appearing in a note, a description or a habitat text.

### `cache/fungi_caution_review.txt`

The fungal twin of `plant_uses_review.txt`, and the more important of the two:
it is where a caution is written *from*, because nothing else sources one. Any
Wikipedia section whose title contains "Toxic", "Poison", "Similar",
"Look-alike", "Confus", "Identif", "Edib", "Safety" or "Uses" has its stripped
prose written there. Nothing in it reaches the asset verbatim.

The build report's **"FUNGI — EVERY CAUTION BELOW IS UNSOURCED"** block prints
all 30 cautions in dex order. That block is the review list; it is read by a
human or by nobody.

## What the script validates

Before writing, and exiting non-zero on any failure:

- exactly 230 species: 120 animals, 80 plants and 30 fungi;
- animal dex numbers exactly 1–120, plant 1–80, fungus 1–30, no duplicates in
  any of them, unique ids across all three;
- every `ecosystemId` declared, and `commonName` / `scientificName` / `taxClass`
  / `silhouetteRes` / `kingdom` present on every record;
- the GBIF kingdom matches the declared kingdom (this one fails during the
  fetch, not at the end);
- `uses` ⊆ {`edible`, `medicinal`}; `usesNote` present when `edible`, absent
  when there is no use tag, at most 240 characters;
- `medicinal` set ⇔ three or more `medicinalActivities`, unless the curator
  pinned it;
- `medicinalActivities` empty ⇒ `usesAttribution` null;
- every animal carries `uses: []` and empty Duke's fields;
- `silhouetteRes` consistent with the class and the kingdom;
- `duke_ethnobot.json` parses and contains every name a plant joined on;
- **the poison rule: every plant Duke's records as poisonous must have a
  sentence beginning `Caution:` in its note** — tagged or untagged, no
  exemption. This is what makes the cautioned set a decision of the source
  rather than of whoever wrote the notes.
- **and, for fungi, the rules Duke's cannot supply**: every fungus carries
  `uses: []` and empty Duke's fields; every fungus carries a `Caution:` sentence
  and nothing else in its note; and no fungal note, description or habitat text
  uses an edibility word.

To see the poison rule bite, delete the `Caution:` sentence from a species the
report lists under "DUKE'S — POISON RECORDED" and run against the copy:

```bash
cp curated_plants.json /tmp/broken.json   # then edit /tmp/broken.json
python3 build_catalogue.py --plants /tmp/broken.json --out /tmp/x.json
# VALIDATION FAILED: … its usesNote has no 'Caution:' sentence   (exit 1)
```

A poison-flagged species with no use tag ships as a caution and nothing else —
Monterey Cypress is the one in this list — and the report shows them under
"DUKE'S POISON, CAUTION ONLY". Western Wild Ginger is the same shape without a
Duke's poison record: aristolochic acid is a nephrotoxin and a carcinogen, and
that warning is worth an entry of its own even though the plant claims no use.

The caution rule and the app's rendering are the same rule on purpose.
`caution_split()` here is a character-for-character mirror of `UsesNote.cautionSplit`
in `domain/Models.kt`, so a note cannot pass the build and then render with its
warning buried in the body.

## Safety, and what is actually hand-written

The edible tag and the note are the only text in the plant list with no source
behind it, and they are what a person might read before eating something. They
are kept to a part and a season, they never say a plant is safe, and every
species with a toxic part or a dangerous lookalike carries a `Caution:` sentence
whether or not Duke's flags it — the elderberry's raw fruit, the death-camas
lookalike beside camas and nodding onion, water hemlock in the same stream as
watercress, iris beside cattail, yew beside the spruce tips. See DESIGN.md
D14/M30 and ARCHITECTURE.md R11.

If you are not confident about a species' edibility or its lookalikes, **drop
the tag or swap the species**. A missing tag costs nothing. Bracken is the
worked example: fiddleheads are genuinely eaten in several cuisines, but
ptaquiloside is an established carcinogen, so the species stays in the dex with
a caution and no edible tag. A tag whose own note tells the reader not to eat
the plant should not exist.

## Changing the catalogue later

Editing an input file and re-running rewrites `pacific.json`. The app only
re-imports when `catalogueVersion` changes, so **bump `catalogueVersion` in
`region.json`** when the new asset should reach an existing install. The
importer never touches the user's entries, captures or user-added species, and
never deletes a caught species (ARCHITECTURE.md 3.3).

The asset numbers each kingdom from 1 — animals 1–120, plants 1–80, fungi 1–30 —
and the app's importer applies the stored per-kingdom base (animals 1–120,
plants 2001–2080, fungi 4001–4030), so the curator never types 2047. The display
prefixes are `#047`, `P047` and `F007`.
