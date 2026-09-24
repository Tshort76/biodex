# Changelog

What changed in the app, newest first. The version numbers are `DESIGN.md`'s — that document has carried a numbered history since the first design pass, so the changelog follows it rather than inventing a second scheme. There are no releases or tags: this is a single-user app that ships by `make install`, so a version marks a body of work rather than a build anyone downloaded.

Requirement and decision ids (`M##`, `D##`) point into `DESIGN.md`, where the reasoning lives.

---

## v23 — 2026-09-24

- **Added** — the "Where was this?" prompt autocompletes. It opens on the places your collection already uses, and as you type it searches a bundled list of 44,620 towns, parks, trails, beaches and landmarks across California, Oregon and Washington. Everything is on the phone, so it works with no signal. Tap a suggestion to fill the field. The field is still free text, so somewhere with no name on any list is still a place — but a name the list *does* hold is stored the list's way, so "bear valley,  california" lands as "Bear Valley, California" and two catches there read as one place. (`M13` revised, `D68`)
- **Added** — `make places` rebuilds the place list from the GeoNames dump, and `make check` now runs its tests too.

## v22.6 — 2026-09-20

- **Removed** — the one-time place backfill, now that it has run. Re-linking a photo still fills that sighting's place. (`D67` revised)

## v22.5 — 2026-09-20

- **Added** — sightings registered before the app could read a photo's location get it filled in once: on the first start with photo access, the app re-reads every placeless sighting whose photo is still linked and writes the coordinates and a place name (a place you typed is kept). Re-linking a photo does the same for that sighting. Photos that were deleted from the phone after registering cannot be read and stay as they are. (`D67`)

## v22.4 — 2026-09-20

- **Changed** — the ball on the unlock reveal wears a rainbow over its upper half instead of red, in the same colours as the ring and the confetti. (`D62` revised, `D66`)

## v22.3 — 2026-09-20

- **Fixed** — the unlock reveal now plays on a phone whose developer setting "Animator duration scale" is off. That setting made every step of the reveal finish instantly, so it had shown its last frame only — the photo and the rainbow ring — since it was first built. The reveal runs on its own clock now; the rest of the app still follows the setting. (`D65`)

## v22.2 — 2026-09-20

- **Changed** — the "Where was this?" line is gone from the Register screen. When the photo carries its location the screen says so in one small line; when it does not, tapping Register asks for the place in a small prompt — type it to continue, or Cancel to go back. Add-your-own works the same way. (`M13`, `D60` revised, `D64`)

## v22.1 — 2026-09-20

- **Changed** — a photo picked from the gallery now fills in its own place. The first time you tap the gallery row the app asks for photo access (choose **Allow all**); from then on the place is read from the photo's original and the "Where was this?" line is optional again. If you said no, a line under the field offers to ask again. Locations that Google Photos only *estimated* are not in the file and still need typing. (`M13` revised, `D57` superseded for local photos, `D63`)

## v22 — 2026-09-20

- **Changed** — a capture needs a place. If the photo carries no GPS (the gallery picker strips it), the "Where was this?" line is required and the Register button waits for it; a photo from the Files picker or the camera that does carry GPS fills it in and the field says "Place read from the photo ✓". Nothing is written without one or the other. (`M13` revised, `D60`)
- **Added** — "Unlink photo, keep the sighting" in the photo viewer. The app's link and thumbnail go, the photo stays in your gallery, and the sighting keeps its date, place and note — the species stays caught. A sighting without a photo shows a 📍 and can still be opened and deleted. (`M07`, `M12`, `S07` revised, `D61`)
- **Changed** — the unlock reveal opens with a catch: a ball closes around the silhouette, clicks, rocks itself still, and opens on your photo. About four seconds, still skippable with a tap. (`M09` revised, `D62`)

## v21 — 2026-09-13

- **Removed** — plants. The 80 curated plants, every plant you added yourself, their uses and Duke's data, and the Pl@ntNet identification button and API key are gone; the app is an animal-and-fungus dex. **Upgrading deletes every plant entry and capture on the phone** — that was the request. The Food source filter stays for the animals that carry it, fungal cautions stay, and a backup from before v21 restores everything except its plants (the import says how many it skipped). Every kingdom keeps its own photograph again. (`D59`; M22–M27, M30, M35, M42, S10, S15 revised; M31–M34, M36–M39, M41, S13, C09, C10, C12 struck)

## v20.2 — 2026-09-13

- **Added** — a 📁 button beside the camera on the Register screen opens the phone's Files picker. A photo chosen that way arrives with its GPS intact, so the sighting's place is filled in and named ("San Diego, California") with nothing typed. The first tap asks for the media-location permission (Android words it as access to photos and videos); saying no still opens the picker, just without the place. The gallery picker stays the default and never asks. (`M13` revised, `D57` revised, `D58`, `R3`)

## v20.1 — 2026-09-13

- **Removed** — the media-location permission added in v20. On the phone it raised the full "access photos and videos" prompt, and the photo picker refused to hand over the unredacted file anyway, so the app is back to asking for nothing. Typing the place still works; a photo's GPS will arrive only once the picker's own location switch rolls out. (`D56` revised, `D57`, `R3`)

## v20 — 2026-09-12

- **Changed** — search forgives a typo. Case, accents, hyphens and spaces no longer matter, and a query of five or more characters may be a letter or two off ("westrn", "screechowl", "kenicottii" all find the Western Screech-Owl). Short queries stay exact. (`M14` revised, `D54`)
- **Fixed** — the "Add … as your own species" button no longer looks disabled when it is not: it is drawn in accent once it has a name and a photo, and stays faint only while it is waiting for one of them. (`M08` revised, `D55`)
- **Added** — every entry lists its sightings under the photo strip, newest first: the date, the time of day and the place. The Register screen gains an optional "Where was this?" line, carried into the add-your-own flow too; a photo that still carries GPS is used, with the place named through the phone's geocoder when nothing was typed and shown as coordinates when that fails. The app asks for the media-location permission the first time the picker opens. All of it lives on the capture row, so deleting the photograph from the gallery afterwards changes nothing. (`M13` revised, `D56`, `R3` mitigated)

## v19.2 — 2026-09-11

- **Fixed** — an uncaught species' page no longer glitches as it loads. It used to open as a hard dark silhouette captioned *Loading reference photo…*, then swap to the pale dimmed photo while the caption vanished and pulled the whole page up a line. The placeholder is now drawn at the weight the picture arrives at, and an uncaught hero stays silent while it loads. A caught entry still says it is loading. (`D52` revised, `D53`)

## v19.1 — 2026-09-11

- **Fixed** — opening an uncaught species no longer replaces its picture with a silhouette. The entry's hero now draws the same greyed, faded reference picture the grid tile has drawn since v15, from the same two constants, so the unlock reads as one picture gaining colour on both screens. The silhouette stays as the fallback where there is no picture to draw. (`M05` and `D41` revised, `D52`)

## v19 — 2026-09-11

**The animal list nearly doubles: 120 species to 224.**

- **Added** — 104 more common Pacific animals, `#121`–`#224`. They are picked by how often you actually meet them rather than by charisma: 32 birds (dabbling ducks, grebes, egrets, gulls, the small woodpeckers, the everyday sparrows and finches), 14 mammals, 14 fish, 6 reptiles, 5 amphibians, 18 insects and 15 other invertebrates — the mussel, the barnacle, the sea star, the pill bug. The catalogue is now 334 species: 224 animals, 80 plants, 30 fungi. (`D1` revised, `D50`)
- **Added** — 26 of the new animals carry the *Food source* tag under the same rule, taking the tagged set from 23 to 49; most of the growth is fish and shellfish. (`D48` revised)
- **Changed** — the catalogue build's size checks now come from the curated input files instead of hardcoded totals, so an expansion no longer fails the build on its own success. The two tests that pinned 230 species and 120 animals were changed the same way. (`D51`)

## v18 — 2026-09-11

**Game is a food source, and the caches ask before they go.**

- **Added** — 23 animals now carry the *Food source* tag, so the Uses filter finds them: game birds and mammals, the salmon and trout, Dungeness crab and purple sea urchin, and the three insects eaten as brood or grubs. The rule is a licensed season in CA/OR/WA plus commonly eaten. Medicinal stays plant-only. It is a tag, not advice — the entry carries the same disclaimer the plants do. (`M24` and `D14` revised, `D48`)
- **Added** — *Clear reference caches* now asks for confirmation, naming how much it would throw away and what it would not touch. (`D49`)
- **Fixed** — the catalogue build now refuses to write an asset whose content changed under an unchanged `catalogueVersion`. A phone that had already imported that version ignored the new asset, which is how the food-source tags shipped without appearing.

## v17.1 — 2026-09-11

- **Added** — Settings → Grid → *Sort the dex by*, Name (A–Z) or Dex number. The grid's own Sort control writes the same preference, so the order you pick is the order the dex opens in next time. (`M01` revised, `D47`)

## v17 — 2026-09-11

**The grid cell is the photograph.**

- **Changed** — a grid cell is now one picture filling the whole tile with the species' name written across the foot of it, over a soft gradient. The caption band is gone, so the photograph gets the 30dp it was using — about 40% more picture. (`M01` revised, `D46`)
- **Fixed** — a tile crops from the top of the photograph instead of the middle, which was cutting the heads off birds. (`D46`)
- **Removed** — the dex number on a grid cell. It is still on the entry screen, on the unlock reveal and in search results. (`M26` revised, `D46`)

## v16.1 — 2026-09-11

- **Changed** — species you added before v16 are respelled the same way the next time the app starts ("brown pelican" becomes "Brown Pelican"). Only the names change; a name you hand-edited stays locked. (`D45`)

## v16 — 2026-09-11

**Name order, tidy names, and your own photo where you want it.**

- **Changed** — the grid opens in name order. Dex number is still in the Sort dropdown. (`M01`, `D32` revised, `D42`)
- **Added** — a species you add has its names spelled the catalogue's way before they are shown or saved: "brown pelican" becomes "Brown Pelican", "red-tailed hawk" becomes "Red-tailed Hawk", "pelecanus OCCIDENTALIS" becomes "Pelecanus occidentalis". The confirm card previews the result; a field you are typing in shows what you type. Species already in your dex keep their names until you next accept a details card for them *(v16.1 respells them on start)*. (`M19` revised, `M45`, `D43`)
- **Added** — a *Show: Stock photo / My photo* toggle under the picture on a caught species' entry. Choosing your photo puts it on the grid tile and in the entry's picture frame, with the stock picture as the fallback; the stock picture stays the default. The choice is saved per species and included in a backup. Database schema 5. (`M01` revised, `M46`, `D44`)

## v15 — 2026-09-11

**Uncaught species show their picture too, dimmed.**

- **Changed** — an uncaught grid cell now draws the species' reference picture drained to grey and faded, where a caught one draws it in full colour with the tick. The unlock becomes a grey picture turning to colour. The silhouette remains the fallback for either when no picture is available; the entry screen still withholds an uncaught species' full-size picture. (`M01`, `D39` revised, `D41`)
- **Fixed** — the grid fetches a 960px rendition of each reference picture instead of the catalogue's original (up to 9 MB) or 3840px thumbnail, and a cell whose picture failed to load retries once. On the first launch after the change above, most of the grid sat on silhouettes because the phone was queuing 230 full-size downloads. (`D39`)

## v14 — 2026-09-11

**The stock picture on the grid, and a party when you catch one.**

- **Changed** — a caught grid cell now draws the species' reference picture from the catalogue instead of the user's own photograph, which keeps the entry screen, the photo viewer and the reveal. The user's thumbnail becomes the fallback for when the picture has not cached, so an offline grid still shows a picture where it can; the silhouette and the filled tick remain the last resort. Uncaught cells are unchanged — they never draw a picture. (`M01`, `M11` revised, `D39`)
- **Changed** — the unlock reveal gains confetti thrown up from the halo as the photograph lands, a warm wash behind it, a rainbow ring turning around the picture, and an amber "NEW SPECIES" label. The sequence, the counter tick and the fixed scatter are as before; it runs two hundred milliseconds longer so the confetti can fall. This overrules the no-fanfare rule the reveal was built under. (`D8`, `D33` revised, `D40`)

## v13 — 2026-09-06

**A caught species with no picture now says so.**

- **Added** — the tick on a grid cell goes filled and green when the cell is drawing a silhouette rather than a photograph, and stays a quiet outline when it draws a picture. One mark covers all three causes: a plant that keeps no photograph of its own, a gallery photo deleted or with its permission withdrawn, and a reference image not yet cached. (`M44`, `D38`)
- **Fixed** — a caught species whose thumbnail file was missing rendered its silhouette stretched to fill the tile, because the fallback went through the image loader's error slot and inherited the photo's crop scaling. It read as an enlarged uncaught card. The cell now draws the fallback the same way the no-photo case does, sized and centred.
- **Repo** — `make test-device` refuses to run while the phone holds registered photos. It uninstalls the app when it finishes, which costs the thumbnails the grid draws *and* the gallery permissions Android holds per installed package; no backup restores the latter, so every photo has to be re-linked by hand. `make test-device CONFIRM=uninstall` proceeds.

## v12 — 2026-09-06

**The launcher icon carries a real edge.**

- **Fixed** — the white rim added in v10 rendered as a square photograph on a white tile, with wedges at the corners and a hairline along the sides. Two errors: a square inside a rounded mask cannot have an even border, and `inset` resolved its 21dp against the layer bounds the launcher supplies rather than the canvas. The art now keeps its authored scale with white painted outside a superellipse fitted to the mask, sized against the 72dp the mask keeps. (`D35` revised, `D37`)
- **Added** — `tools/icon/build_icon.py` generates all five densities from one source image and records how the mask was measured.

## v11 — 2026-09-06

**Nearest: how close is this to everything else I have?**

- **Added** — every species carries its GBIF classification (kingdom, phylum, class, order, family), taken from the match the catalogue pipeline already made, so it cost no new network requests. A screen reachable from the grid's top bar and from any entry names the three species in the catalogue closest to it and the hops between them: 2 the same family, 4 the same order, 6 the same class, 8 the same phylum, 10 the same kingdom, 12 nothing but life. Douglas Squirrel to Black-tailed Jackrabbit is 6. (`M43`, `D36`)
- **Added** — schema v4 and catalogue asset v5. All 230 catalogue species classified, with 17 gaps GBIF itself leaves: nine reptiles with no order, eight ray-finned fish with no class.
- **Fixed** — a species added by its common name never received a classification, because that path goes through GBIF's vernacular search rather than the match endpoint.
- **Fixed** — a species added before this version could never acquire a classification: the backfill only ran while an entry was still missing its details, and one that already had its picture and habitat was not eligible. It now re-runs when the classification is missing.
- **Fixed** — the backup archive did not carry the classification, which matters most there: for a species you added yourself the archive is the only record of it.
- **Fixed** — an uncaught neighbour was listed as `? ? ?` while its scientific name was printed directly underneath. The grid names every silhouette; what an uncaught species withholds is the picture, not its identity.

## v10 — 2026-09-06

- **Added** — every entry carries a world range map: the cells where GBIF holds records of that species, shaded over one land outline shared by the whole region. The shading is recorded occurrences rather than a field guide's range polygon, and the caption says so. (`M04`/`M05` revised, `D34`)
- **Added** — a white rim on the launcher icon, which was disappearing into a dark home screen. (`D35`; superseded by v12)

## v9 — 2026-09-05

- **Added** — the grid can be ordered by name as well as by dex number. (`M01` revised, `D32`)
- **Fixed** — the unlock reveal had never animated at all, because `animateFloatAsState` starts at its target. It is now a real sequence: the silhouette resolves into the photo, two rings push outward, the naming lines rise in one at a time, and the kingdom counter ticks. (`D8` revised, `D33`)

## v8 — 2026-09-05

Three things the owner asked for from the phone.

- **Changed** — a species you added yourself counts in the meters, on both sides of the fraction: `2/122`, not `0/120` with two extras. (`M02` revised, `D29`)
- **Changed** — reference photographs are fitted into their frame instead of cropped to fill it. (`D30`)
- **Changed** — the grid's Register button moved from a floating action button into the top bar. (`M15` revised, `D31`)

## v7 — 2026-09-03

- **Changed** — the grid's single scrolling chip row became the caught chips over three dropdowns: Class, Ecosystem and Uses. The kingdom filter went entirely, since a class already names its kingdom. (`M23` revised, `D28`)

## v6 — 2026-09-02

- **Added** — fungi as a third kingdom, with its own meter and silhouettes.
- **Added** — an in-app camera, so a catch can be registered without going through the gallery first.
- **Added** — plant identification through Pl@ntNet: opt-in, per photo, plants only. One reduced and re-encoded copy of that one photo leaves the phone, and the app never claims the thing in your photo *is* a species.
- **Changed** — a plant keeps no photograph of its own; its tile shows the species' reference picture. (`M41`)
- **Changed** — safety text cut back to one short sentence per genuinely dangerous species. (`D14`)

## v5 — 2026-09-02

- **Removed** — bird-call playback. It was designed when the app was an animal dex, a call is meaningless for a plant, a slug or a fish, and it never once worked for want of an API key. `M06` and `D4` are struck out in place; the numbering keeps its holes, because surviving requirements are cited by number from the code.

## v4 — 2026-09-02

- **Changed** — the product became **BioDex** and the shipped catalogue the Pacific USA BioDex.
- **Added** — plants as a second kingdom counted beside the animals: trees, fruit-bearing and edible plants, and medicinal and herbal plants, with plant uses as a headline filter.
- **Fixed** — the Register screen's scroll layout.

## v1–v3 — 2026-09-01

The first working dex.

- 120 curated Pacific USA animals as a grid of silhouettes that unlock when you register a photo, with per-kingdom and per-ecosystem meters.
- Photos referenced from the gallery by URI and never copied out of it; the app keeps a small thumbnail of its own.
- Anything you photograph that the catalogue lacks becomes your own entry, filled in from GBIF and Wikipedia behind a single confirmation step.
- Export and import of the whole collection as one ZIP.
