# Catalogue pipeline

Builds the bundled Pacific USA catalogue asset the app ships with — 224 animals
and 30 fungi. (80 plants and a Dr. Duke's ethnobotanical join were part of it
from catalogue v2 to v7; they left in v8 — DESIGN.md D59, ARCHITECTURE.md 12.2.)

```
tools/catalogue/region.json            (header + the seven ecosystems)
tools/catalogue/curated_animals.json   (hand-authored input, 224 animals)
tools/catalogue/curated_fungi.json     (hand-authored input, 30 fungi)
        │
        ├─ GBIF        accepted scientific name, kingdom, rank, class
        ├─ Wikipedia   habitat prose, description lede, canonical image, page link
        └─ Commons     image license + author, for the attribution line
        ▼
app/src/main/assets/catalogue/pacific.json         (generated, committed to git)
```

The input is split so the fungus curator and an animal edit never touch the
same file. `region.json` owns `catalogueVersion`, `regionId`, `regionName` and
the ecosystems; the two species files own nothing else.

The app never runs this. It runs on the build machine, its output is committed,
and builds never touch the network.

## Running it

```bash
cd tools/catalogue
python3 build_catalogue.py                 # writes ../../app/src/main/assets/catalogue/pacific.json
python3 build_catalogue.py --out /tmp/x.json
python3 build_catalogue.py --refresh       # ignore the cache, re-fetch everything
python3 build_catalogue.py --only 5        # smoke run over the first five of each kingdom
python3 build_catalogue.py --fungi /tmp/edited.json --out /tmp/x.json
```

`--region`, `--animals` and `--fungi` each point the script at a different
input file. They exist so a validation rule can be exercised against a modified
copy without touching the real inputs — the fungal caution rule is tested that
way.

Standard library only — no venv, no `pip install`. (ARCHITECTURE.md 7 mentions
`requests`; this machine has no `requests`, so the script uses `urllib`. Nothing
to set up.)

A cold run takes roughly 25–30 minutes: it sleeps between requests to be polite
to Wikipedia (1 s) and GBIF (0.5 s).

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
   **fails the build**: an animal entry that resolves to another kingdom is a
   curator typo that must not ship. The class maps to the app's `taxClass` enum
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

## Fungi

The 30 fungi live in `curated_fungi.json` and take the GBIF, Wikipedia and
Commons steps unchanged — GBIF's backbone and Wikipedia both cover fungi well,
and every one of the 30 matched EXACT at species rank on the first run.

**No dataset stands behind a fungal caution.** (When the catalogue had plants,
Dr. Duke's `Poison` records decided which of them had to carry one; Duke's has
no fungal taxa, and the join left with the plants in v8.) Every fungal caution
is one person's sentence with nothing behind it, which is why this section is
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
  the old plant classes it is **growth form, not taxonomy** — a forager recognises a
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

Because no source decides the cautioned set here, the rule was at first
inverted and made unconditional: **every fungus must carry a `Caution:` sentence**,
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

Where a caution is written *from*, because nothing else sources one. Any
Wikipedia section whose title contains "Toxic", "Poison", "Similar",
"Look-alike", "Confus", "Identif", "Edib", "Safety" or "Uses" has its stripped
prose written there. Nothing in it reaches the asset verbatim.

The build report's **"FUNGI — EVERY CAUTION BELOW IS UNSOURCED"** block prints
all 30 cautions in dex order. That block is the review list; it is read by a
human or by nobody.

## What the script validates

Before writing, and exiting non-zero on any failure:

- every species the curated inputs list, and no more (224 animals and 30
  fungi today — the counts come from the inputs, not from literals);
- animal and fungus dex numbers exactly 1–N each, no duplicates in either,
  unique ids across both;
- every `ecosystemId` declared, and `commonName` / `scientificName` / `taxClass`
  / `silhouetteRes` / `kingdom` present on every record;
- the GBIF kingdom matches the declared kingdom (this one fails during the
  fetch, not at the end);
- `uses` ⊆ {`edible`}; a note with no use tag is a `Caution:` sentence or
  nothing, at most 240 characters; none of the three retired Duke's keys
  (`medicinalActivities`, `medicinalRecordCount`, `usesAttribution`) appears on
  any record;
- every animal carries no note (D48: the tag and nothing else);
- `silhouetteRes` consistent with the class and the kingdom;
- **and, for fungi, the rules no source can supply**: every fungus carries
  `uses: []`; its note, if any, is a `Caution:` sentence and nothing else; and
  no fungal note, description or habitat text uses an edibility word.

The caution rule and the app's rendering are the same rule on purpose.
`caution_split()` here is a character-for-character mirror of `UsesNote.cautionSplit`
in `domain/Models.kt`, so a note cannot pass the build and then render with its
warning buried in the body.

## Safety, and what is actually hand-written

The animals' edible tag (D48) and the fungal cautions are the only text in the
catalogue with no source behind them, and a caution is what a person might read
before eating something. A caution is one short sentence, written only when the
species itself is dangerous, and it never says anything is safe — the app makes
no edibility claim about a fungus at all (M35). See DESIGN.md D14/M30/M35 and
ARCHITECTURE.md R11.

If you are not confident about a species, **drop the tag or swap the species**.
A missing tag costs nothing.

## Tests

```bash
cd tools/catalogue && python3 -m unittest test_build_catalogue -v
```

Eighteen tests over the fungi builder, the fungal half of `validate` and the
range maps, standard library only and no network — GBIF and Wikipedia are
stubbed, because what is under test is the rule the pipeline applies to whatever
they return.

The fungal rules have no dataset behind them, so `uses: []`, the caution shape
and the edibility scrub are enforced by code alone — and code enforcing a safety
rule should be the kind you can watch fail.

The app-side twin is `FungiCatalogueTest`, which asserts the same rules against
the **shipped `pacific.json`** rather than against the builder, and imports it
through the real `CatalogueImporter`. Run it with
`./gradlew testDebugUnitTest --tests '*FungiCatalogueTest*'`.

## Changing the catalogue later

Editing an input file and re-running rewrites `pacific.json`. The app only
re-imports when `catalogueVersion` changes, so **bump `catalogueVersion` in
`region.json`** when the new asset should reach an existing install. The
importer never touches the user's entries, captures or user-added species, and
never deletes a caught species (ARCHITECTURE.md 3.3).

The asset numbers each kingdom from 1 — animals 1–224, fungi 1–30 — and the
app's importer applies the stored per-kingdom base (animals 1–224, fungi
4001–4030; the 2001–2080 block held the plants until v8 and stays empty), so the
curator never types 4007. The display prefixes are `#047` and `F007`.
