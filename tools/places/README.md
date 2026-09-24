# The place list

`build_places.py` generates `app/src/main/assets/places/pacific.tsv` — the offline list of
place names, with their coordinates, that the app's "Where was this?" prompt suggests from
(`DESIGN.md` D68). Like the catalogue, it is **generated and committed**, so no build and no
registration touches the network.

```bash
make places          # rebuild the asset
make places-test     # run the tests below
```

## What it does

It downloads the [GeoNames](https://www.geonames.org/) dump for the United States (71 MB,
cached under `cache/` so a re-run makes zero requests) and keeps the rows that `places.json`
asks for: three states, and a whitelist of feature codes in three tiers.

- **Tier 0** — towns and cities.
- **Tier 1** — the places you go on purpose: parks, reserves, campgrounds, trails, beaches.
- **Tier 2** — the natural features you end up at: summits, capes, islands, cliffs, lakes,
  bays, estuaries, marshes.

The tier is the only ranking the app's search has beyond how well the name matches, so a town
called Bear Valley is offered above a hill of the same name.

Everything else in the dump is dropped, and the biggest omissions are deliberate. Streams
(`H.STM`) alone are 28,000 rows across the three states and nobody names a sighting after an
unnamed creek; schools, buildings, churches, hotels, valleys, springs and road junctions go
for the same reason.

Rows are then deduplicated on **name and state**, because the label a sighting stores is
"Bear Valley, California" — five parks called City Park in California are one suggestion. Where
two rows share a name and a state, the better tier wins, and so do its coordinates. That leaves
44,620 places in 1.7 MB, one per line as `name<TAB>state<TAB>tier<TAB>lat<TAB>lng`.

Coordinates are rounded to four decimals, about 11 metres. The app writes them onto a sighting
whose photo had no GPS, so a place picked from the list is mappable; a photo's own GPS always
wins over them.

## Editing it

`places.json` is the whole configuration: the states and the tiers. Adding a state or a
feature code is a one-line change plus `make places`; the asset's size is the thing to watch,
since the app parses all of it the first time the prompt is opened.

There is **no version number** on this asset and nothing imports it into the database — the
app reads the file directly, so a regenerated list reaches an install with the APK and needs
no `catalogueVersion`-style bump.

## Tests

`test_build_places.py` pins the filter (which states, which codes, how duplicates collapse)
and the shipped asset itself — every line is three fields, every state is one the config
names, every tier is 0, 1 or 2, and every point falls inside a box around the three states. A malformed line is skipped by the app rather than crashing
it, but it is still a bug here.

## Licence

GeoNames is CC BY 4.0, so the attribution in `README.md`, `LICENSE` and
`app/src/main/assets/licenses.md` is a condition of shipping this file.
