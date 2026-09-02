package dev.tlong.biodex.data.backup

import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.data.photo.resolvePhotoRef
import dev.tlong.biodex.domain.SpeciesSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules of S01's restore path: never destroy, never resurrect a grant, never leave
 * a row pointing at something that is not there.
 */
class ImportMergeTest {

    private fun archivedCapture(
        id: String,
        speciesId: String = "western-screech-owl",
        withPhoto: Boolean = true,
    ) = BackupCapture(
        id = id,
        speciesId = speciesId,
        photoUri = "content://otherphone/$id",
        takenAt = 10L,
        createdAt = 10L,
        thumbEntry = thumbnailArchiveEntry(id),
        photoEntry = if (withPhoto) photoArchiveEntry(id) else null,
        photoStatus = if (withPhoto) {
            PhotoDisposition.INCLUDED.name
        } else {
            PhotoDisposition.MISSING_REVOKED.name
        },
    )

    private fun userSpecies(id: String, dexNumber: Int) = BackupSpecies(
        id = id,
        source = "user",
        dexNumber = dexNumber,
        commonName = "Varied Thrush",
        taxClass = "bird",
        silhouetteRes = "sil_bird",
        ecosystemIds = listOf("coastal-rainforest", "not-a-real-ecosystem"),
    )

    private fun manifest(
        species: List<BackupSpecies> = emptyList(),
        entries: List<BackupEntry> = emptyList(),
        captures: List<BackupCapture> = emptyList(),
    ) = BackupManifest(
        exportedAt = 1L,
        regionId = "pacific",
        species = species,
        entries = entries,
        captures = captures,
    )

    private val freshInstall = LocalSnapshot(
        speciesSources = mapOf("western-screech-owl" to SpeciesSource.CURATED),
        ecosystemIds = setOf("coastal-rainforest", "urban-suburban"),
    )

    @Test
    fun `a fresh install restores captures, the entry and the user species`() {
        val plan = planImport(
            manifest(
                species = listOf(userSpecies("user-1", 1001)),
                entries = listOf(BackupEntry("western-screech-owl", 10L, "cap1")),
                captures = listOf(archivedCapture("cap1")),
            ),
            freshInstall,
        )

        assertEquals(1, plan.capturesAdded())
        assertEquals(1, plan.speciesToInsert.size)
        assertEquals(1001, plan.speciesToInsert.single().dexNumber)
        assertEquals("cap1", plan.entriesToWrite.single().favoriteCaptureId)
        // An ecosystem this install does not have would fail the foreign key and lose the
        // whole transaction, so it is dropped rather than carried.
        assertEquals(listOf("coastal-rainforest"), plan.memberships["user-1"])
    }

    @Test
    fun `a restored photo is a local copy and never a resurrected grant`() {
        val plan = planImport(manifest(captures = listOf(archivedCapture("cap1"))), freshInstall)
        val restored = plan.capturesToInsert.single().capture

        assertEquals("photos/cap1.jpg", restored.localCopyPath)
        // Resolution short-circuits to the local copy: nothing probes the archived URI, and
        // no persistable permission is taken for a photo that belonged to another phone.
        val ref = resolvePhotoRef(restored.photoUri, restored.localCopyPath) {
            error("an import must never probe the archived gallery URI")
        }
        assertTrue(ref is PhotoRef.LocalCopy)
    }

    @Test
    fun `a capture with no archived photo restores as a broken reference, not a lie`() {
        val plan = planImport(
            manifest(captures = listOf(archivedCapture("cap2", withPhoto = false))),
            freshInstall,
        )

        assertNull(plan.capturesToInsert.single().capture.localCopyPath)
        assertEquals(0, plan.report.photosRestored)
    }

    @Test
    fun `importing the same archive twice changes nothing the second time`() {
        val m = manifest(
            entries = listOf(BackupEntry("western-screech-owl", 10L, "cap1")),
            captures = listOf(archivedCapture("cap1")),
        )
        val second = planImport(
            m,
            freshInstall.copy(
                captureIds = setOf("cap1"),
                entries = mapOf("western-screech-owl" to LocalEntry(10L, "cap1")),
            ),
        )

        assertTrue(second.capturesToInsert.isEmpty())
        assertTrue(second.entriesToWrite.isEmpty())
        assertEquals(1, second.report.capturesAlreadyPresent)
    }

    @Test
    fun `an existing entry keeps its favorite and only moves earlier`() {
        val plan = planImport(
            manifest(
                entries = listOf(BackupEntry("western-screech-owl", 5L, "cap-from-archive")),
                captures = listOf(archivedCapture("cap1")),
            ),
            freshInstall.copy(
                captureIds = setOf("local-cap"),
                entries = mapOf("western-screech-owl" to LocalEntry(9L, "local-cap")),
            ),
        )

        val entry = plan.entriesToWrite.single()
        assertEquals(5L, entry.caughtAt)
        assertEquals("local-cap", entry.favoriteCaptureId)
        assertEquals(1, plan.report.entriesMerged)
    }

    @Test
    fun `a later archived catch date never overwrites an earlier local one`() {
        val plan = planImport(
            manifest(entries = listOf(BackupEntry("western-screech-owl", 99L, null))),
            freshInstall.copy(entries = mapOf("western-screech-owl" to LocalEntry(9L, null))),
        )

        assertTrue(plan.entriesToWrite.isEmpty())
    }

    @Test
    fun `a favorite whose capture did not arrive is nulled rather than left dangling`() {
        val plan = planImport(
            manifest(
                entries = listOf(BackupEntry("western-screech-owl", 10L, "cap-never-exported")),
                captures = listOf(archivedCapture("cap1")),
            ),
            freshInstall,
        )

        assertNull(plan.entriesToWrite.single().favoriteCaptureId)
    }

    @Test
    fun `a curated species this install does not have takes its captures with it`() {
        val plan = planImport(
            manifest(
                species = listOf(
                    BackupSpecies(
                        id = "sea-otter",
                        source = "curated",
                        dexNumber = 44,
                        commonName = "Sea Otter",
                        taxClass = "mammal",
                        silhouetteRes = "sil_mammal",
                    ),
                ),
                entries = listOf(BackupEntry("sea-otter", 10L, null)),
                captures = listOf(archivedCapture("cap9", speciesId = "sea-otter")),
            ),
            freshInstall,
        )

        assertTrue(plan.speciesToInsert.isEmpty())
        assertTrue(plan.capturesToInsert.isEmpty())
        assertTrue(plan.entriesToWrite.isEmpty())
        assertEquals(1, plan.report.capturesWithoutSpecies)
        assertEquals(listOf("sea-otter"), plan.report.unknownCuratedSpecies)
    }

    @Test
    fun `a user species keeps its U-number when free and takes the next when taken`() {
        val plan = planImport(
            manifest(species = listOf(userSpecies("user-1", 1001), userSpecies("user-2", 1002))),
            freshInstall.copy(usedUserDexNumbers = setOf(1001)),
        )

        assertEquals(1002, plan.speciesToInsert.first { it.id == "user-1" }.dexNumber)
        // 1002 has now been taken by the renumbering, so the second falls to 1003.
        assertEquals(1003, plan.speciesToInsert.first { it.id == "user-2" }.dexNumber)
    }

    @Test
    fun `a species the database already has is left exactly as it is`() {
        val plan = planImport(
            manifest(species = listOf(userSpecies("user-1", 1001).copy(commonName = "Renamed"))),
            freshInstall.copy(speciesSources = mapOf("user-1" to SpeciesSource.USER)),
        )

        assertTrue(plan.speciesToInsert.isEmpty())
        assertTrue(plan.memberships.isEmpty())
    }

    @Test
    fun `an entry with no capture on either side is not invented`() {
        val plan = planImport(
            manifest(entries = listOf(BackupEntry("western-screech-owl", 10L, null))),
            freshInstall,
        )

        assertTrue(plan.entriesToWrite.isEmpty())
    }

    @Test
    fun `a photo that failed to extract does not leave a local copy claim behind`() {
        val plan = planImport(manifest(captures = listOf(archivedCapture("cap1"))), freshInstall)
        val applied = withRestoredFiles(plan, setOf(thumbnailArchiveEntry("cap1")))

        assertNull(applied.capturesToInsert.single().capture.localCopyPath)
        assertEquals(0, applied.report.photosRestored)
        assertEquals(1, applied.report.thumbnailsRestored)
    }

    private fun ImportPlan.capturesAdded() = capturesToInsert.size
}
