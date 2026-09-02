package dev.tlong.animaldex.data.backup

import dev.tlong.animaldex.data.photo.localCopyRelativePath
import dev.tlong.animaldex.data.photo.thumbnailRelativePath
import dev.tlong.animaldex.domain.Capture
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.USER_DEX_NUMBER_BASE

/**
 * The merge rules for restoring an S01 archive, as one pure function.
 *
 * Three properties matter more than anything else here, and each one is a unit test:
 *
 *  - **An import never destroys.** Nothing in an [ImportPlan] deletes or overwrites an
 *    existing species, entry or capture. A species id the database already knows is left
 *    exactly as it is; a capture id it already knows is skipped, so importing the same
 *    archive twice changes nothing the second time.
 *  - **An import never resurrects a grant.** No plan mentions a persistable URI permission.
 *    A restored photo is an app-owned local copy (S03's mechanism, reused); the archived
 *    `photoUri` is carried for provenance only, and on a new phone it will simply resolve
 *    as `Revoked` and offer a re-link — which is the truth about it.
 *  - **A restored entry never points at a capture that is not there.** `favoriteCaptureId`
 *    carries no foreign key (ARCHITECTURE.md 3.4), so the plan validates it itself.
 */

/** Everything the merge needs to know about the database it is merging into. */
data class LocalSnapshot(
    val speciesSources: Map<String, SpeciesSource> = emptyMap(),
    val usedUserDexNumbers: Set<Int> = emptySet(),
    val captureIds: Set<String> = emptySet(),
    val entries: Map<String, LocalEntry> = emptyMap(),
    /**
     * The seven ecosystems this install knows. An archived membership naming anything else
     * is dropped: `species_ecosystems` has a foreign key to `ecosystems`, so writing one
     * would fail the transaction and lose the whole import.
     */
    val ecosystemIds: Set<String> = emptySet(),
)

data class LocalEntry(val caughtAt: Long, val favoriteCaptureId: String?)

data class ImportPlan(
    /** User-added species the database has never seen, renumbered where necessary. */
    val speciesToInsert: List<BackupSpecies>,
    /** Ecosystem memberships, for inserted species only (D10: never touched otherwise). */
    val memberships: Map<String, List<String>>,
    val entriesToWrite: List<BackupEntry>,
    val capturesToInsert: List<PlannedCapture>,
    val report: ImportReport,
) {
    /** ZIP entry name → path relative to `filesDir`, for the extraction pass. */
    val filesToRestore: Map<String, String>
        get() = buildMap {
            capturesToInsert.forEach { planned ->
                planned.thumbEntry?.let { put(it, planned.capture.thumbPath) }
                planned.photoEntry?.let { entry ->
                    planned.capture.localCopyPath?.let { put(entry, it) }
                }
            }
        }
}

data class PlannedCapture(
    val capture: Capture,
    val thumbEntry: String?,
    val photoEntry: String?,
)

data class ImportReport(
    val speciesAdded: Int = 0,
    val entriesAdded: Int = 0,
    val entriesMerged: Int = 0,
    val capturesAdded: Int = 0,
    val capturesAlreadyPresent: Int = 0,
    /** Captures whose species is not in this install's catalogue and could not be created. */
    val capturesWithoutSpecies: Int = 0,
    val photosRestored: Int = 0,
    val thumbnailsRestored: Int = 0,
    /** Curated species the archive references that this install does not have. */
    val unknownCuratedSpecies: List<String> = emptyList(),
)

fun planImport(manifest: BackupManifest, local: LocalSnapshot): ImportPlan {
    val assignedDexNumbers = local.usedUserDexNumbers.toMutableSet()
    val speciesToInsert = mutableListOf<BackupSpecies>()
    val memberships = mutableMapOf<String, List<String>>()
    val unknownCurated = mutableListOf<String>()

    manifest.species.forEach { species ->
        if (local.speciesSources.containsKey(species.id)) return@forEach
        if (SpeciesSource.fromWireName(species.source) == SpeciesSource.USER) {
            val dexNumber = nextFreeUserDexNumber(species.dexNumber, assignedDexNumbers)
            assignedDexNumbers += dexNumber
            speciesToInsert += species.copy(dexNumber = dexNumber)
            memberships[species.id] =
                species.ecosystemIds.distinct().filter { it in local.ecosystemIds }
        } else {
            // A curated species belongs to the bundled catalogue, which owns its dex number
            // and its text. Inventing one here would create a species this build's asset
            // does not have, which the next catalogue import would then be unable to
            // reconcile. Its captures are reported as unrestorable instead.
            unknownCurated += species.id
        }
    }

    val availableSpecies = local.speciesSources.keys + speciesToInsert.map { it.id }

    var alreadyPresent = 0
    var withoutSpecies = 0
    val capturesToInsert = manifest.captures.mapNotNull { archived ->
        when {
            archived.id in local.captureIds -> {
                alreadyPresent++
                null
            }

            archived.speciesId !in availableSpecies -> {
                withoutSpecies++
                null
            }

            else -> PlannedCapture(
                capture = Capture(
                    id = archived.id,
                    speciesId = archived.speciesId,
                    // Provenance only: the URI belonged to another device's gallery. No
                    // grant is taken for it, and resolution short-circuits to the local
                    // copy whenever the archive carried one.
                    photoUri = archived.photoUri,
                    thumbPath = thumbnailRelativePath(archived.id),
                    localCopyPath = archived.photoEntry?.let { localCopyRelativePath(archived.id) },
                    takenAt = archived.takenAt,
                    lat = archived.lat,
                    lng = archived.lng,
                    locationLabel = archived.locationLabel,
                    note = archived.note,
                    createdAt = archived.createdAt,
                ),
                thumbEntry = archived.thumbEntry,
                photoEntry = archived.photoEntry,
            )
        }
    }

    val insertedCaptureIds = capturesToInsert.map { it.capture.id }.toSet()
    val captureIdsAfterMerge = local.captureIds + insertedCaptureIds
    val capturedSpeciesAfterMerge = capturesToInsert.map { it.capture.speciesId }.toSet()

    var entriesAdded = 0
    var entriesMerged = 0
    val entriesToWrite = manifest.entries.mapNotNull { archived ->
        if (archived.speciesId !in availableSpecies) return@mapNotNull null
        val existing = local.entries[archived.speciesId]
        if (existing != null) {
            // Keep the local entry's identity and its favorite; the only thing an archive
            // can legitimately improve is the catch date, and only by making it earlier.
            val caughtAt = minOf(existing.caughtAt, archived.caughtAt)
            if (caughtAt == existing.caughtAt) return@mapNotNull null
            entriesMerged++
            BackupEntry(archived.speciesId, caughtAt, existing.favoriteCaptureId)
        } else {
            // An entry with no captures on either side would render as a caught species
            // with nothing behind it; skip it rather than invent a catch.
            if (archived.speciesId !in capturedSpeciesAfterMerge) return@mapNotNull null
            entriesAdded++
            BackupEntry(
                speciesId = archived.speciesId,
                caughtAt = archived.caughtAt,
                favoriteCaptureId = archived.favoriteCaptureId?.takeIf { it in captureIdsAfterMerge },
            )
        }
    }

    return ImportPlan(
        speciesToInsert = speciesToInsert,
        memberships = memberships,
        entriesToWrite = entriesToWrite,
        capturesToInsert = capturesToInsert,
        report = ImportReport(
            speciesAdded = speciesToInsert.size,
            entriesAdded = entriesAdded,
            entriesMerged = entriesMerged,
            capturesAdded = capturesToInsert.size,
            capturesAlreadyPresent = alreadyPresent,
            capturesWithoutSpecies = withoutSpecies,
            photosRestored = capturesToInsert.count { it.photoEntry != null },
            thumbnailsRestored = capturesToInsert.count { it.thumbEntry != null },
            unknownCuratedSpecies = unknownCurated,
        ),
    )
}

/**
 * The import counterpart of `buildManifest`'s honesty rule: rows are written from what was
 * actually extracted, not from what the manifest promised. A capture whose full-size photo
 * failed to extract loses its `localCopyPath`, so nothing in the database claims a local
 * copy that is not on disk — the capture simply restores as thumbnail-plus-broken-reference,
 * which is a state M12 already handles.
 */
fun withRestoredFiles(plan: ImportPlan, restoredEntries: Set<String>): ImportPlan {
    val captures = plan.capturesToInsert.map { planned ->
        val photoRestored = planned.photoEntry != null && planned.photoEntry in restoredEntries
        val thumbRestored = planned.thumbEntry != null && planned.thumbEntry in restoredEntries
        planned.copy(
            capture = planned.capture.copy(
                localCopyPath = if (photoRestored) planned.capture.localCopyPath else null,
            ),
            photoEntry = planned.photoEntry.takeIf { photoRestored },
            thumbEntry = planned.thumbEntry.takeIf { thumbRestored },
        )
    }
    return plan.copy(
        capturesToInsert = captures,
        report = plan.report.copy(
            photosRestored = captures.count { it.photoEntry != null },
            thumbnailsRestored = captures.count { it.thumbEntry != null },
        ),
    )
}

/**
 * U-numbers are unique per region by a database index, so an archive's numbering cannot be
 * trusted against a database that already has user species of its own. The archived number
 * is kept when it is free — an import into an empty install therefore preserves U01, U02, …
 * exactly — and otherwise the species takes the next free one.
 */
private fun nextFreeUserDexNumber(preferred: Int, taken: Set<Int>): Int {
    if (preferred > USER_DEX_NUMBER_BASE && preferred !in taken) return preferred
    var candidate = maxOf(USER_DEX_NUMBER_BASE, taken.maxOrNull() ?: USER_DEX_NUMBER_BASE) + 1
    while (candidate in taken) candidate++
    return candidate
}
