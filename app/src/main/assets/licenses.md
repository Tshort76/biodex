# Licenses and attribution

BioDex is a personal, non-commercial app. Everything below is what it uses and under
what terms.

Photographs you register are yours. They stay on the device, with one exception you control:
pressing **Identify** on an attached photo sends a reduced copy of that single photo to the
Pl@ntNet API so it can suggest what the plant is. The copy is re-encoded before it is sent,
which strips the EXIF metadata and with it the location the camera recorded. Nothing else is
uploaded — not your other photos, not where or when you caught anything, not your collection.
No photo leaves the device unless you press that button, and the button exists only for plants.

## Identification suggestions — Pl@ntNet

When you press Identify, the photo goes to the Pl@ntNet API (Pl@ntNet, a consortium including
Cirad, INRAE, Inria and IRD) and the candidate species it returns are Pl@ntNet's suggestions,
shown with the confidence score it reports. They are suggestions about a photograph, not an
identification by this app, and you choose which — if any — is right.

Every suggested name is checked against the GBIF backbone before it is shown, so a name that
does not resolve to a real species is dropped rather than displayed.

- Pl@ntNet: https://plantnet.org
- API and terms: https://my.plantnet.org/terms_of_use

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

## Plant uses — Dr. Duke's Phytochemical and Ethnobotanical Databases

A plant's medicinal tag, its recorded activities and its record count come from Dr. Duke's
Phytochemical and Ethnobotanical Databases (Agricultural Research Service, United States
Department of Agriculture), released under **CC0 1.0** — a public-domain dedication. The
whole ethnobotanical table ships with the app as `catalogue/duke_ethnobot.json` so that
looking a plant up needs no network, and `catalogue/LICENSE-duke.txt` records the source and
the dedication in full.

Duke's is a record of documented and traditional uses, not medical advice and not a safety
assessment. Its `Poison` records are used when the catalogue is built as a checklist: a
species Duke's records as poisonous must carry a "Caution:" sentence, or the build fails.
The edible tag and the short note naming a part and a season are the app's own curated
text, not Duke's, and the entry screen shows which claim is whose.

- Dr. Duke's Databases: https://agdatacommons.nal.usda.gov/articles/dataset/Dr_Duke_s_Phytochemical_and_Ethnobotanical_Databases/24660351
- DOI: 10.15482/USDA.ADC/1239279
- CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/

## Silhouettes

The seven class silhouettes are drawn for this app and are not derived from any third-party
artwork.

## Third-party libraries

All of these are open source. Everything from AndroidX (Jetpack Compose, Navigation,
Lifecycle, Activity, Room, ExifInterface) is licensed under the Apache License 2.0,
as are the Kotlin libraries and the rest of the list.

- Jetpack Compose (UI, Foundation, Material 3) — Apache 2.0
- AndroidX Activity Compose, Navigation Compose, Lifecycle — Apache 2.0
- AndroidX Room — Apache 2.0
- AndroidX ExifInterface — Apache 2.0
- Kotlin standard library and coroutines, JetBrains — Apache 2.0
- kotlinx.serialization, JetBrains — Apache 2.0
- Coil 3, Coil Contributors — Apache 2.0
- OkHttp, Square Inc. — Apache 2.0
- JUnit 4 (tests only), JUnit team — Eclipse Public License 1.0

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

## Fonts

No fonts are bundled. The app uses the device's own serif and sans-serif families.
