# Changelog

What changed in the app, newest first. The version numbers are `DESIGN.md`'s — that document has carried a numbered history since the first design pass, so the changelog follows it rather than inventing a second scheme. There are no releases or tags: this is a single-user app that ships by `make install`, so a version marks a body of work rather than a build anyone downloaded.

Requirement and decision ids (`M##`, `D##`) point into `DESIGN.md`, where the reasoning lives.

---

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
