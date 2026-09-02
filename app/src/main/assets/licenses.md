# Licenses and attribution

Animal Dex is a personal, non-commercial app. Everything below is what it uses and under
what terms. Photographs you register are yours; they never leave the device.

## Species text and images — Wikipedia and Wikimedia Commons

Habitat descriptions, summaries and reference photographs in the bundled catalogue come from
Wikipedia articles and from Wikimedia Commons, and are used under CC BY-SA (Creative Commons
Attribution-ShareAlike; most articles are CC BY-SA 4.0, and individual images carry their own
CC licence, which may be CC BY, CC BY-SA, CC0 or public domain).

Each species entry shows the credit line for its own image — the author and the licence as
Commons reports them — and every entry links to its source article. Text reused from an
article remains under that article's licence.

- Wikipedia: https://en.wikipedia.org
- Wikimedia Commons: https://commons.wikimedia.org
- CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/

## Names and taxonomy — GBIF

Accepted scientific names, taxonomic class and match candidates come from the GBIF Backbone
Taxonomy through the GBIF API. GBIF's backbone is published under CC BY 4.0.

- GBIF: https://www.gbif.org
- API terms: https://www.gbif.org/terms

## Calls — Xeno-canto

Call recordings come from Xeno-canto. Each recording carries its own Creative Commons
licence (commonly CC BY-NC-SA or CC BY-NC) and its own recordist credit, which the app shows
beside the player.

No recordings ship with this build: the Xeno-canto API has required a per-account key since
October 2025 and none is configured, so every entry currently reads "No call available".
Adding a key to `local.properties` and re-running the catalogue pipeline fills the calls in;
this section is declared now because the code that fetches them is already here.

- Xeno-canto: https://xeno-canto.org
- Terms: https://xeno-canto.org/about/terms

## Silhouettes

The seven class silhouettes are drawn for this app and are not derived from any third-party
artwork.

## Third-party libraries

All of these are open source. Everything from AndroidX (Jetpack Compose, Navigation,
Lifecycle, Activity, Room, Media3, ExifInterface) is licensed under the Apache License 2.0,
as are the Kotlin libraries and the rest of the list.

- Jetpack Compose (UI, Foundation, Material 3) — Apache 2.0
- AndroidX Activity Compose, Navigation Compose, Lifecycle — Apache 2.0
- AndroidX Room — Apache 2.0
- AndroidX Media3 (ExoPlayer, OkHttp data source, database) — Apache 2.0
- AndroidX ExifInterface — Apache 2.0
- Kotlin standard library and coroutines, JetBrains — Apache 2.0
- kotlinx.serialization, JetBrains — Apache 2.0
- Coil 3, Coil Contributors — Apache 2.0
- OkHttp, Square Inc. — Apache 2.0
- JUnit 4 (tests only), JUnit team — Eclipse Public License 1.0

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

## Fonts

No fonts are bundled. The app uses the device's own serif and sans-serif families.
