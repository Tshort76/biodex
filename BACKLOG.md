# Backlog

Work that is not started. Nothing here is half-built — the tree is clean and everything designed so far is shipped (`ARCHITECTURE.md` §11.8 says what that is).

Items keep the register ids they already have in `DESIGN.md`, because those ids are cited from the code and are how a requirement is discussed. An item with no id is a new want and gets one when it is designed rather than when it is listed.

## Deferred by design — the `C##` wants

These were considered during a design pass, judged worth doing eventually, and cut from the version being built. `DESIGN.md` carries each one's full text.

| Id | What | Why it is worth doing |
|---|---|---|
| `C03` | **A second region.** | The largest coherent feature left, and the schema was built for it — the `regions` table, `regionId` on every species, and the region pill in the header all exist and are exercised by the one region that ships. What it needs is a second curated input set and a pipeline run, not a schema change. |
| `C09`–`C11` | See `DESIGN.md` §5. | Recorded with their reasoning at the point they were cut. |
| `C12` | **The offline add-your-own plant keeps its photo.** | A known inconsistency with `M41`, deliberately left: offline there is no lookup, so no kingdom, so the rule cannot be applied at that moment. The owner's answer was to photograph offline and add the species later online, which closes it in practice. Only worth building if that stops being true. |

## Documentation debt

Small, and each is marked in place rather than silently wrong:

- **`ARCHITECTURE.md` §11.4** has one superseded paragraph — the chip row the dropdowns replaced. Marked with a pointer to `M23`/`D28`; left standing because it records what slice 11 built.
- **`ARCHITECTURE.md` §2's directory tree** still uses the pre-rename package (`dev/tlong/animaldex`, `pokedex-animals/`). The rename is recorded in §11.5; the real package is `dev.tlong.biodex`.
- Anything else stale found in §11 should be marked the same way — a dated "superseded, see X" line — rather than rewritten. The sections are a record of what each slice built, and rewriting them loses that.

## Ideas without a design

Not committed to, and none of them has a register id yet. Listed so they are not rediscovered from scratch.

- **Per-species silhouettes.** Every species currently resolves to its class silhouette; the schema already supports a per-species one (`silhouetteRes`), so this is an art problem rather than an engineering one. `ARCHITECTURE.md` §2 explains why 230 bespoke silhouettes were cut from v1.
- **Turning R8 on for release.** Deliberately off today because it fails silently at run time in the serialization paths. Doing it means a phone in hand and both the *add your own species* fetch and a backup import exercised against a shrunk build — that is the whole task, and it is not large.
- **An on-device model that identifies the species in a photograph.** The owner's framing was "training an object detection model for species classification", which is really two jobs: *detection* — find the animal or plant in the frame, which would also give the reference-photo crop something better than a centred fit — and *classification* — decide which of the 230 it is. This does not contradict `D23`, which ruled out third-party identification providers for animals and fungi; it reopens the same want by a different route, since a first-party model answers to our own catalogue instead of a global one and can honestly say "not in dex". What makes it large is not the training loop: it is a labelled image corpus we are allowed to use (the owner's own registrations number in the dozens), inference that runs on the phone because there is no backend and there will not be one, and the model's weight inside an APK that ships a 793K catalogue today. Worth designing only against a named corpus and a size budget.
- **Nearest five.** One screen naming the five catalogue species closest to whichever one you are looking at, with the distance to each in hops through the taxonomy: 2 for the same family, 4 the same order, 6 the same class, 8 phylum, 10 kingdom, 12 across kingdoms. Douglas Squirrel to Black-tailed Jackrabbit is 6. Reached from a binary-tree glyph in the grid's top bar and from a link on a species' entry page. Two earlier designs — browsing the whole tree of life, then a per-species cladogram with a lineage chain — were mocked up and turned down for answering a question that was not asked. The data cost is near zero: GBIF's `species/match` response already carries kingdom, phylum, class, order and family for every species and is already in `tools/catalogue/cache/`, so no new request is needed. Two rules the mockup proved necessary. A rank both species lack is skipped rather than treated as a mismatch, or two salmon come out 8 apart while sharing a family, since GBIF gives ray-finned fish no class. And equal distances break on the app's own class first, or every distant species shows the same five low-numbered birds.
- **A screenshot or UI test layer.** There is none by choice, and the cost shows up as UI defects that only a device pass finds. Worth revisiting only if the phone loop stops being practical.

## Not going to happen

Recorded so nobody proposes them again as if they were oversights. Each has its reasoning in `DESIGN.md` §7 or the decision registers.

- Cloud sync, accounts, or any backend; any Google Photos API integration.
- Identification for animals or fungi — no provider worth trusting, and against the fungal catalogue every candidate reads "not in dex", which is the feature not working (`D23`).
- Bird-call playback. Removed, not deferred: `ARCHITECTURE.md` §12.1 explains why, and `M06`/`D4` are struck out in place so the numbering still lines up with the code.
