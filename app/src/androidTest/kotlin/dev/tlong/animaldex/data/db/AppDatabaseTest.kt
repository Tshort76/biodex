package dev.tlong.animaldex.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * The two things a JVM cannot honestly fake (ARCHITECTURE.md section 8): that the schema
 * actually builds, and that Room's DAO round-trips and cascades behave as 3.1 claims.
 *
 * NOT RUN as of slice 3 — no phone was attached. These execute the first time one is.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun species(id: String, dexNumber: Int, source: SpeciesSource = SpeciesSource.CURATED) =
        SpeciesEntity(
            id = id,
            regionId = "pacific",
            dexNumber = dexNumber,
            source = source,
            commonName = id,
            scientificName = "Genus $id",
            taxClass = TaxClass.BIRD,
            silhouetteRes = "sil_bird",
            userEditedFields = if (source == SpeciesSource.USER) listOf("habitatText") else emptyList(),
        )

    private fun capture(id: String, speciesId: String, createdAt: Long) = CaptureEntity(
        id = id,
        speciesId = speciesId,
        photoUri = "content://media/external/images/media/$id",
        thumbPath = "thumbnails/$id.jpg",
        takenAt = createdAt,
        createdAt = createdAt,
    )

    @Test
    fun speciesRoundTripsThroughEveryConverter() = runBlocking {
        val row = species("user-1", 1001, SpeciesSource.USER).copy(
            taxClass = TaxClass.OTHER_INVERTEBRATE,
            detailsPending = true,
            userEditedFields = listOf("habitatText", "infoUrl"),
        )
        db.speciesDao().upsert(row)

        val read = db.speciesDao().observeSpeciesById("user-1").first()
        assertEquals(row, read)
    }

    @Test
    fun speciesAreOrderedByDexNumberSoUserAddedTrailTheCatalogue() = runBlocking {
        db.speciesDao().upsertAll(
            listOf(
                species("user-1", 1001, SpeciesSource.USER),
                species("owl", 21),
                species("heron", 3),
            ),
        )

        val ids = db.speciesDao().observeSpecies("pacific").first().map { it.id }
        assertEquals(listOf("heron", "owl", "user-1"), ids)
    }

    @Test
    fun entryStatusReportsCaptureCountAndTheFavoriteThumbnail() = runBlocking {
        db.speciesDao().upsert(species("heron", 3))
        db.captureDao().insert(capture("c1", "heron", createdAt = 100))
        db.captureDao().insert(capture("c2", "heron", createdAt = 200))
        db.entryDao().upsert(EntryEntity("heron", caughtAt = 100))

        val firstStatus = db.entryDao().observeEntryStatuses().first().single()
        assertEquals(2, firstStatus.captureCount)
        // No favorite set yet: falls back to the earliest capture (S04's "defaults to first").
        assertEquals("thumbnails/c1.jpg", firstStatus.thumbPath)

        db.entryDao().setFavoriteCapture("heron", "c2")
        val favoriteStatus = db.entryDao().observeEntryStatuses().first().single()
        assertEquals("thumbnails/c2.jpg", favoriteStatus.thumbPath)
    }

    @Test
    fun deletingASpeciesCascadesToItsEntryCapturesAndEcosystemLinks() = runBlocking {
        db.ecosystemDao().upsertAll(
            listOf(EcosystemEntity("riparian-wetland", "pacific", "Riparian & Wetland", 4)),
        )
        db.speciesDao().upsert(species("heron", 3))
        db.ecosystemDao().upsertMemberships(
            listOf(SpeciesEcosystemCrossRef("heron", "riparian-wetland")),
        )
        db.captureDao().insert(capture("c1", "heron", createdAt = 100))
        db.entryDao().upsert(EntryEntity("heron", caughtAt = 100))

        db.speciesDao().deleteByIds(listOf("heron"))

        assertNull(db.entryDao().entryOnce("heron"))
        assertEquals(0, db.captureDao().countForSpecies("heron"))
        assertTrue(db.ecosystemDao().membershipsOnce("pacific").isEmpty())
    }

    @Test
    fun deletingACaptureLeavesTheEntryCaught() = runBlocking {
        // S07's mechanism: the entry survives until its last capture goes, and reverting
        // an entry is an explicit app-level decision, not a database side effect.
        db.speciesDao().upsert(species("heron", 3))
        db.captureDao().insert(capture("c1", "heron", createdAt = 100))
        db.captureDao().insert(capture("c2", "heron", createdAt = 200))
        db.entryDao().upsert(EntryEntity("heron", caughtAt = 100, favoriteCaptureId = "c2"))

        db.captureDao().deleteById("c2")

        assertNotNull(db.entryDao().entryOnce("heron"))
        assertEquals(1, db.captureDao().countForSpecies("heron"))
        // favoriteCaptureId carries no foreign key (an entries/captures cycle), so it now
        // dangles: slice 5 must null it when deleting the favorite capture.
        assertEquals("c2", db.entryDao().entryOnce("heron")?.favoriteCaptureId)
    }

    @Test
    fun membershipsAreScopedToTheirRegion() = runBlocking {
        db.ecosystemDao().upsertAll(
            listOf(
                EcosystemEntity("riparian-wetland", "pacific", "Riparian & Wetland", 4),
                EcosystemEntity("piedmont", "eastern", "Piedmont", 1),
            ),
        )
        db.speciesDao().upsertAll(
            listOf(species("heron", 3), species("cardinal", 3).copy(regionId = "eastern")),
        )
        db.ecosystemDao().upsertMemberships(
            listOf(
                SpeciesEcosystemCrossRef("heron", "riparian-wetland"),
                SpeciesEcosystemCrossRef("cardinal", "piedmont"),
            ),
        )

        assertEquals(listOf("heron"), db.ecosystemDao().membershipsOnce("pacific").map { it.speciesId })
    }

    @Test
    fun metaStoresAndReadsBackTheCatalogueVersion() = runBlocking {
        db.metaDao().put(MetaEntity(MetaEntity.KEY_CATALOGUE_VERSION, "7"))
        assertEquals("7", db.metaDao().value(MetaEntity.KEY_CATALOGUE_VERSION))

        db.metaDao().put(MetaEntity(MetaEntity.KEY_CATALOGUE_VERSION, "8"))
        assertEquals("8", db.metaDao().value(MetaEntity.KEY_CATALOGUE_VERSION))
        assertNull(db.metaDao().value("never-written"))
    }

    @Test
    fun theNextUserDexNumberIsDerivedFromTheHighestExistingOne() = runBlocking {
        assertNull(db.speciesDao().maxUserDexNumber("pacific"))

        db.speciesDao().upsertAll(
            listOf(
                species("heron", 3),
                species("user-1", 1001, SpeciesSource.USER),
                species("user-2", 1002, SpeciesSource.USER),
            ),
        )

        assertEquals(1002, db.speciesDao().maxUserDexNumber("pacific"))
    }
}
