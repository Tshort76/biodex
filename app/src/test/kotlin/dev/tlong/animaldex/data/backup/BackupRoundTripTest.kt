package dev.tlong.animaldex.data.backup

import dev.tlong.animaldex.data.photo.PhotoRef
import dev.tlong.animaldex.domain.SpeciesSource
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real export through a real `ZipOutputStream`, read back through a real `ZipInputStream`
 * and imported into a second (empty) install — all of it in memory.
 *
 * This is the test that would catch the failure S01 exists to prevent: an archive that the
 * user believes holds their photos and does not.
 */
class BackupRoundTripTest {

    private val owl = BackupSpecies(
        id = "western-screech-owl",
        source = "curated",
        dexNumber = 21,
        commonName = "Western Screech-Owl",
        taxClass = "bird",
        silhouetteRes = "sil_bird",
    )

    private val thrush = BackupSpecies(
        id = "user-1",
        source = "user",
        dexNumber = 1001,
        commonName = "Varied Thrush",
        taxClass = "bird",
        silhouetteRes = "sil_bird",
        ecosystemIds = listOf("coastal-rainforest"),
    )

    /** One resolvable gallery photo, one local copy, one revoked reference. */
    private fun exportSide(): Pair<FakeBackupStore, FakeBackupGateway> {
        val live = capture("live", speciesId = "western-screech-owl")
        val copied = capture(
            "copied",
            speciesId = "user-1",
            localCopyPath = "photos/copied.jpg",
        )
        val broken = capture("broken", speciesId = "western-screech-owl")

        val gateway = FakeBackupGateway(
            ownedFiles = mutableMapOf(
                "thumbnails/live.jpg" to "thumb-live".toByteArray(),
                "thumbnails/copied.jpg" to "thumb-copied".toByteArray(),
                "thumbnails/broken.jpg" to "thumb-broken".toByteArray(),
                "photos/copied.jpg" to "full-copied".toByteArray(),
            ),
            gallery = mutableMapOf(live.photoUri to "full-live".toByteArray()),
            refs = mutableMapOf(
                "live" to PhotoRef.Available(live.photoUri),
                "copied" to PhotoRef.LocalCopy("photos/copied.jpg"),
                "broken" to PhotoRef.Revoked,
            ),
        )
        val store = FakeBackupStore(
            snapshot = BackupSnapshot(
                regionId = "pacific",
                species = listOf(owl, thrush),
                entries = listOf(
                    BackupEntry("western-screech-owl", 100L, "live"),
                    BackupEntry("user-1", 200L, "copied"),
                ),
                captures = listOf(live, copied, broken),
            ),
        )
        return store to gateway
    }

    @Test
    fun `export writes every resolvable photo and reports the one it could not`() = runBlocking {
        val (store, gateway) = exportSide()
        val result = BackupService(store, gateway) { 1_700_000_000_000L }.export()

        val success = result as BackupService.ExportResult.Success
        val names = entryNames(gateway.archives.values.single())

        assertTrue("photos/live.jpg" in names)
        assertTrue("photos/copied.jpg" in names)
        assertFalse("the revoked photo cannot be in the archive", "photos/broken.jpg" in names)
        assertEquals(setOf("thumbnails/live.jpg", "thumbnails/copied.jpg", "thumbnails/broken.jpg"),
            names.filter { it.startsWith("thumbnails/") }.toSet())

        assertEquals(2, success.report.fullSizeIncluded)
        assertEquals(1, success.report.missingRevoked)
        assertEquals(3, success.report.thumbnailsIncluded)
        assertFalse(success.report.complete)
        assertTrue(success.shareUri.startsWith("archive://animaldex-backup-"))
        assertTrue(success.fileName.endsWith(".zip"))
    }

    @Test
    fun `a photo that fails mid-copy is reported, not silently claimed`() = runBlocking {
        val (store, gateway) = exportSide()
        // The reference resolved a moment ago and the bytes are gone now.
        gateway.gallery[store.snapshot.captures.first().photoUri] = null

        val result = BackupService(store, gateway).export() as BackupService.ExportResult.Success
        val names = entryNames(gateway.archives.values.single())
        val manifest = manifestOf(gateway.archives.values.single())

        assertFalse("photos/live.jpg" in names)
        assertNull(manifest.captures.first { it.id == "live" }.photoEntry)
        assertEquals(
            PhotoDisposition.MISSING_UNREADABLE.name,
            manifest.captures.first { it.id == "live" }.photoStatus,
        )
        assertEquals(1, result.report.missingUnreadable)
    }

    @Test
    fun `the manifest names only entries the archive actually contains`() = runBlocking {
        val (store, gateway) = exportSide()
        BackupService(store, gateway).export()
        val bytes = gateway.archives.values.single()
        val names = entryNames(bytes)

        manifestOf(bytes).captures.forEach { archived ->
            archived.thumbEntry?.let { assertTrue("$it is claimed but absent", it in names) }
            archived.photoEntry?.let { assertTrue("$it is claimed but absent", it in names) }
        }
    }

    @Test
    fun `an archive imports onto a fresh install with its photos`() = runBlocking {
        val (store, gateway) = exportSide()
        val exported = BackupService(store, gateway).export()
        val shareUri = (exported as BackupService.ExportResult.Success).shareUri

        // A different phone: the same catalogue, nothing caught, no user species.
        val fresh = FakeBackupStore(
            local = LocalSnapshot(
                speciesSources = mapOf("western-screech-owl" to SpeciesSource.CURATED),
                ecosystemIds = setOf("coastal-rainforest"),
            ),
        )
        val target = FakeBackupGateway()
        target.archives += gateway.archives

        val imported = BackupService(fresh, target).import(shareUri)
        val report = (imported as BackupService.ImportResult.Success).report
        val plan = fresh.applied!!

        assertEquals(3, report.capturesAdded)
        assertEquals(2, report.photosRestored)
        assertEquals(3, report.thumbnailsRestored)
        assertEquals(1, report.speciesAdded)
        assertEquals(2, report.entriesAdded)

        // The bytes really landed in the new install's own storage.
        assertEquals("full-live", String(target.ownedFiles.getValue("photos/live.jpg")))
        assertEquals("full-copied", String(target.ownedFiles.getValue("photos/copied.jpg")))
        assertEquals("thumb-broken", String(target.ownedFiles.getValue("thumbnails/broken.jpg")))

        // The capture whose photo was already gone restores as thumbnail-only, with no
        // false claim of a local copy.
        assertNull(plan.capturesToInsert.first { it.capture.id == "broken" }.capture.localCopyPath)
    }

    @Test
    fun `importing the same archive twice adds nothing the second time`() = runBlocking {
        val (store, gateway) = exportSide()
        val shareUri =
            (BackupService(store, gateway).export() as BackupService.ExportResult.Success).shareUri

        val fresh = FakeBackupStore(
            local = LocalSnapshot(
                speciesSources = mapOf("western-screech-owl" to SpeciesSource.CURATED),
            ),
        )
        val target = FakeBackupGateway()
        target.archives += gateway.archives
        val service = BackupService(fresh, target)

        service.import(shareUri)
        // Pretend the first import landed: the same ids are now local.
        fresh.local = fresh.local.copy(
            captureIds = setOf("live", "copied", "broken"),
            speciesSources = fresh.local.speciesSources + ("user-1" to SpeciesSource.USER),
            entries = mapOf(
                "western-screech-owl" to LocalEntry(100L, "live"),
                "user-1" to LocalEntry(200L, "copied"),
            ),
        )
        val second = service.import(shareUri) as BackupService.ImportResult.Success

        assertEquals(0, second.report.capturesAdded)
        assertEquals(0, second.report.speciesAdded)
        assertEquals(3, second.report.capturesAlreadyPresent)
        assertTrue(fresh.applied!!.entriesToWrite.isEmpty())
    }

    @Test
    fun `a file that is not an archive is refused rather than half-applied`() = runBlocking {
        val gateway = FakeBackupGateway()
        gateway.archives["junk"] = "not a zip at all".toByteArray()
        val store = FakeBackupStore()

        val result = BackupService(store, gateway).import("archive://junk")

        assertTrue(result is BackupService.ImportResult.Failed)
        assertNull(store.applied)
    }

    @Test
    fun `an empty collection is not exported as an empty archive`() = runBlocking {
        val result = BackupService(FakeBackupStore(), FakeBackupGateway()).export()
        assertEquals(BackupService.ExportResult.NothingToExport, result)
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun entryNames(zipBytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun manifestOf(zipBytes: ByteArray): BackupManifest {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return json.decodeFromString(BackupManifest.serializer(), text)
                }
                entry = zip.nextEntry
            }
        }
        error("no manifest in the archive")
    }
}
