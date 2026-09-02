package dev.tlong.animaldex.data.photo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tlong.animaldex.data.db.AppDatabase
import dev.tlong.animaldex.data.db.SpeciesEntity
import dev.tlong.animaldex.data.repo.DexRepository
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.TaxClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same invariants `CaptureRegistrarTest` pins in the JVM suite, run once against **real
 * Room** — because the fake store reproduces the DAO's favorite-then-earliest fallback by
 * hand, and a claim about SQL is only worth what SQL says.
 *
 * The gateway is still faked: this is about the transactions, not about the gallery.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRegistrarRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DexRepository
    private lateinit var photos: FakeGateway
    private lateinit var registrar: CaptureRegistrar
    private var ids = 0
    private var clock = 1_000L

    @Before
    fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = DexRepository(db)
        photos = FakeGateway()
        registrar = CaptureRegistrar(
            store = repository,
            photos = photos,
            newCaptureId = { "cap-${++ids}" },
            now = { clock },
        )
        db.speciesDao().upsertAll(
            listOf(speciesRow("western-screech-owl", 21), speciesRow("great-blue-heron", 3)),
        )
    }

    @After
    fun close() = db.close()

    private fun speciesRow(id: String, dexNumber: Int) = SpeciesEntity(
        id = id,
        regionId = "pacific",
        dexNumber = dexNumber,
        source = SpeciesSource.CURATED,
        commonName = id,
        scientificName = null,
        taxClass = TaxClass.BIRD,
        silhouetteRes = "sil_bird",
    )

    @Test
    fun firstRegistrationCreatesTheEntryAndTheGridSeesTheThumbnail() = runBlocking {
        val result = registrar.register("western-screech-owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered

        assertTrue(result.isFirst)
        val status = db.entryDao().observeEntryStatuses().first().single()
        assertEquals("western-screech-owl", status.speciesId)
        assertEquals(1, status.captureCount)
        assertEquals(thumbnailRelativePath(result.captureId), status.thumbPath)
    }

    @Test
    fun deletingTheFavoriteLeavesNoDanglingColumnAndTheGridFallsBack() = runBlocking {
        val first = (registrar.register("western-screech-owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        clock = 2_000L
        val second = (registrar.register("western-screech-owl", "content://photos/2")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.setFavorite("western-screech-owl", first)

        registrar.deleteCapture(first)

        assertNull(db.entryDao().entryOnce("western-screech-owl")!!.favoriteCaptureId)
        val status = db.entryDao().observeEntryStatuses().first().single()
        assertEquals(thumbnailRelativePath(second), status.thumbPath)
    }

    @Test
    fun deletingTheLastCaptureRevertsTheSpeciesButKeepsTheSpeciesRow() = runBlocking {
        val only = (registrar.register("western-screech-owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered).captureId

        registrar.deleteCapture(only)

        assertNull(db.entryDao().entryOnce("western-screech-owl"))
        assertEquals(0, db.captureDao().countForSpecies("western-screech-owl"))
        assertNotNull(
            "the catalogue row itself is never touched",
            db.speciesDao().observeSpeciesById("western-screech-owl").first(),
        )
    }

    @Test
    fun theSameGalleryPhotoRegisteredTwiceIsCountedByUri() = runBlocking {
        registrar.register("western-screech-owl", "content://photos/shared")
        registrar.register("great-blue-heron", "content://photos/shared")

        assertEquals(2, db.captureDao().countForUri("content://photos/shared"))
        assertTrue("the shared grant must survive one deletion", photos.released.isEmpty())

        registrar.deleteCapture("cap-1")
        assertTrue(photos.released.isEmpty())

        registrar.deleteCapture("cap-2")
        assertEquals(listOf("content://photos/shared"), photos.released)
    }

    @Test
    fun reLinkingUpdatesTheReferenceInPlace() = runBlocking {
        val id = (registrar.register("western-screech-owl", "content://photos/old")
            as CaptureRegistrar.RegisterResult.Registered).captureId

        registrar.relink(id, "content://photos/new")

        val row = db.captureDao().captureOnce(id)!!
        assertEquals("content://photos/new", row.photoUri)
        assertEquals(1_000L, row.createdAt)
        assertNotNull(db.entryDao().entryOnce("western-screech-owl"))
    }

    /** Only the gallery is faked; the store under test is real Room. */
    private class FakeGateway : PhotoGateway {
        val released = mutableListOf<String>()
        override fun persistGrant(uri: String) = true
        override fun releaseGrant(uri: String) { released += uri }
        override fun persistedGrantCount() = released.size
        override fun readExif(uri: String) = ExifFacts.None
        override fun writeThumbnail(captureId: String, uri: String) =
            thumbnailRelativePath(captureId)
        override fun writeLocalCopy(captureId: String, uri: String) = null
        override fun deleteOwnedFile(relativePath: String) = Unit
        override fun resolve(photoUri: String, localCopyPath: String?) =
            PhotoRef.Available(photoUri)
        override fun displayName(uri: String) = null
    }
}
