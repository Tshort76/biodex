# Catalogue pipeline

Builds the bundled Pacific catalogue asset the app ships with:

```
tools/catalogue/curated_species.json   (hand-authored input, 120 species)
        │
        ├─ GBIF        accepted scientific name, rank, class, match confidence
        ├─ Wikipedia   habitat prose, description lede, canonical image, page link
        ├─ Commons     image license + author, for the attribution line
        └─ Xeno-canto  a call recording (needs an API key — see below)
        ▼
app/src/main/assets/catalogue/pacific.json   (generated, committed to git)
```

The app never runs this. It runs on the build machine, its output is committed,
and builds never touch the network.

## Running it

```bash
cd tools/catalogue
python3 build_catalogue.py                 # writes ../../app/src/main/assets/catalogue/pacific.json
python3 build_catalogue.py --out /tmp/x.json
python3 build_catalogue.py --refresh       # ignore the cache, re-fetch everything
python3 build_catalogue.py --only 5        # smoke run over the first five species
```

Standard library only — no venv, no `pip install`. (ARCHITECTURE.md 7 mentions
`requests`; this machine has no `requests`, so the script uses `urllib`. Nothing
to set up.)

A cold run takes roughly 10–15 minutes: it sleeps between requests to be polite
to Wikipedia (1 s), GBIF (0.5 s) and Xeno-canto (4 s).

**Every HTTP response is cached** under `cache/<sha1-of-url>.json`, so a second
run makes zero requests and finishes in seconds. `cache/` is disposable — delete
it for a genuinely cold run. It also holds `cache/report.txt`, the coverage
report from the last run.

> `cache/` is git-ignored by `tools/catalogue/.gitignore`, which lives in this
> directory rather than in the repository root — this slice does not own the
> root `.gitignore`.

## The Xeno-canto API key

Since October 2025, Xeno-canto's v3 API requires a per-account key (free; rate
limit about 1,000 requests/hour). To get one: create an account at
<https://xeno-canto.org>, then copy the key from your account page.

```bash
export XC_API_KEY=...
python3 build_catalogue.py --refresh
```

**Without the key the script runs normally.** It skips Xeno-canto entirely,
writes `callUrl: null` and `callAttribution: null` for every species, and the
report says how many calls are missing because the key was absent (as opposed to
because no recording exists). A missing key is a normal condition, not an error.

Until someone creates the key and re-runs this with `--refresh`, no species in
the shipped asset has a call, so the detail screen's call control has nothing to
play. Expect roughly half the catalogue to end up with a call even once the key
exists: Xeno-canto is strong for birds, thin for frogs and insects, and absent
for mammals and everything marine.

## The curated input

`curated_species.json` is the only hand-authored data, and it is where editorial
judgment lives. It carries the seven Pacific ecosystems and the 120 species, each
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
    "habitatText": "Damp redwood and Douglas-fir forest floor, on leaf litter and rotting wood…",
    "callUrl": null
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

## What the script does per species

1. **GBIF** `species/match?name=<scientific>&strict=false` → accepted name,
   rank, class, confidence. The class maps to the app's `taxClass` enum
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
4. **Xeno-canto** v3, queried by the accepted scientific name, best quality
   recording first → `callUrl` and
   `Xeno-canto XC123456 · CC BY-NC-SA 4.0 · <recordist>`.
5. Assemble the record with `silhouetteRes = sil_<taxClass>` and a `provenance`
   map naming the source of every fetched field.

Before writing, the script validates: exactly 120 species, dex numbers exactly
1–120 with no duplicates, unique ids, every `ecosystemId` declared, and
`commonName` / `scientificName` / `taxClass` / `silhouetteRes` present on every
record. It exits non-zero if validation fails or a species failed entirely.

## Changing the catalogue later

Editing `curated_species.json` and re-running rewrites `pacific.json`. The app
only re-imports when `catalogueVersion` changes, so **bump `catalogueVersion` in
`curated_species.json`** when the new asset should reach an existing install.
The importer never touches the user's entries, captures or user-added species,
and never deletes a caught species (ARCHITECTURE.md 3.3).
