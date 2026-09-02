package dev.tlong.animaldex.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tlong.animaldex.data.db.AppDatabase
import dev.tlong.animaldex.data.db.EcosystemEntity
import dev.tlong.animaldex.data.db.SpeciesEntity
import dev.tlong.animaldex.domain.LookupFields
import dev.tlong.animaldex.domain.SpeciesField
import dev.tlong.animaldex.domain.SpeciesFields
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.TaxClass
import dev.tlong.animaldex.domain.UserSpeciesRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user-added write path against **real Room**. The JVM suite pins every decision against an
 * in-memory fake; this runs the two claims that are about SQL rather than about logic — that
 * `MAX(dexNumber)` allocates U-numbers correctly beside a curated catalogue, and that replacing
 * one user species' ecosystem memberships leaves every other species' rows alone.
 *
 * Has never executed: no device has been connected to this project (risk R6).
 */
@RunWith(AndroidJUnit4::class)
class UserSpeciesRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DexRepository

    @Before
    fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = DexRepository(db)
        db.ecosystemDao().upsertAll(
            listOf(
                EcosystemEntity("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
                EcosystemEntity("urban-suburban", "pacific", "Urban & Suburban", 7),
            ),
        )
        db.speciesDao().upsert(
            SpeciesEntity(
                id = "western-screech-owl",
                regionId = "pacific",
                dexNumber = 21,
                source = SpeciesSource.CURATED,
                commonName = "Western Screech-Owl",
                taxClass = TaxClass.BIRD,
                silhouetteRes = "sil_bird",
            ),
        )
    }

    @After
    fun close() {
        db.close()
    }

    private fun record(id: String, dexNumber: Int, fields: SpeciesFields, pending: Boolean = false) =
        UserSpeciesRecord(
            id = id,
            regionId = "pacific",
            dexNumber = dexNumber,
            detailsPending = pending,
            fields = fields,
        )

    @Test
    fun userNumbersIgnoreTheCuratedCatalogue() = runBlocking {
        assertNull(repository.maxUserDexNumber("pacific"))

        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "Varied Thrush")),
            listOf("coastal-rainforest"),
        )

        assertEquals(1001, repository.maxUserDexNumber("pacific"))
    }

    @Test
    fun aUserSpeciesReadsBackWithItsEcosystemsAndTrailsTheCatalogue() = runBlocking {
        repository.upsertUserSpecies(
            record(
                "user-1",
                1001,
                SpeciesFields(
                    commonName = "Varied Thrush",
                    scientificName = "Ixoreus naevius",
                    taxClass = TaxClass.BIRD,
                ),
            ),
            listOf("coastal-rainforest", "urban-suburban"),
        )

        val summaries = repository.speciesSummaries().first()
        val added = summaries.last()
        assertEquals("user-1", added.id)
        assertEquals("U01", added.displayNumber)
        assertEquals(SpeciesSource.USER, added.source)
        assertEquals(setOf("coastal-rainforest", "urban-suburban"), added.ecosystemIds.toSet())
    }

    @Test
    fun aUserSpeciesIsExcludedFromTheCuratedMeter() = runBlocking {
        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "Varied Thrush")),
            listOf("coastal-rainforest"),
        )

        val progress = repository.dexProgress().first()

        // M02/D9: user-added species are an addendum, never part of the 120.
        assertEquals(1, progress.totalSpecies)
        assertEquals(1, progress.userAddedCount)
    }

    @Test
    fun replacingOneSpeciesMembershipsLeavesOthersAlone() = runBlocking {
        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "One")),
            listOf("coastal-rainforest"),
        )
        repository.upsertUserSpecies(
            record("user-2", 1002, SpeciesFields(commonName = "Two")),
            listOf("urban-suburban"),
        )

        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "One")),
            listOf("urban-suburban"),
        )

        val memberships = db.ecosystemDao().membershipsOnce("pacific")
        assertEquals(
            setOf("user-1" to "urban-suburban", "user-2" to "urban-suburban"),
            memberships.map { it.speciesId to it.ecosystemId }.toSet(),
        )
    }

    @Test
    fun aNullEcosystemListLeavesTheUsersPickAlone() = runBlocking {
        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "One"), pending = true),
            listOf("coastal-rainforest"),
        )

        val stored = repository.userSpecies("user-1")!!
        repository.upsertUserSpecies(
            stored.copy(fields = stored.fields.copy(scientificName = "Ixoreus naevius")),
            ecosystemIds = null,
        )

        assertEquals(
            listOf("coastal-rainforest"),
            db.ecosystemDao().membershipsOnce("pacific")
                .filter { it.speciesId == "user-1" }
                .map { it.ecosystemId },
        )
    }

    @Test
    fun backfillRoundTripsThroughRoomAndKeepsTheUsersEdit() = runBlocking {
        val registrar = AddSpeciesRegistrar(
            store = repository,
            captures = throwingRegistrar(),
            newSpeciesId = { "user-1" },
        )
        repository.upsertUserSpecies(
            record("user-1", 1001, SpeciesFields(commonName = "Varied Thrush"), pending = true),
            emptyList(),
        )

        registrar.backfill(
            speciesId = "user-1",
            lookup = LookupFields(scientificName = "Ixoreus naevius", taxClass = TaxClass.BIRD),
            edits = AddSpeciesRegistrar.FieldEdits(
                values = SpeciesFields(commonName = "Varied Thrush", habitatText = "Behind the shed."),
                fields = listOf(SpeciesField.HABITAT_TEXT),
            ),
        )
        registrar.backfill(
            speciesId = "user-1",
            lookup = LookupFields(
                scientificName = "Ixoreus naevius",
                taxClass = TaxClass.BIRD,
                habitatText = "Moist coniferous forest.",
                description = "A thrush of the Pacific slope.",
            ),
        )

        val stored = repository.userSpecies("user-1")!!
        // The `userEditedFields` JSON converter survives the round trip, which is the only
        // reason M21 can hold across sessions at all.
        assertTrue(SpeciesField.HABITAT_TEXT in stored.userEditedFields)
        assertEquals("Behind the shed.", stored.fields.habitatText)
        assertEquals("A thrush of the Pacific slope.", stored.fields.description)
        assertFalse(stored.detailsPending)
    }

    @Test
    fun aCuratedSpeciesIsNotReachableAsAUserSpecies() = runBlocking {
        assertNull(repository.userSpecies("western-screech-owl"))
    }

    /** The photo half is slice 5's and has its own Room test; nothing here registers one. */
    private fun throwingRegistrar() = dev.tlong.animaldex.data.photo.CaptureRegistrar(
        store = repository,
        photos = object : dev.tlong.animaldex.data.photo.PhotoGateway {
            override fun persistGrant(uri: String) = false
            override fun releaseGrant(uri: String) = Unit
            override fun persistedGrantCount() = 0
            override fun readExif(uri: String) = dev.tlong.animaldex.data.photo.ExifFacts.None
            override fun writeThumbnail(captureId: String, uri: String): String? = null
            override fun writeLocalCopy(captureId: String, uri: String): String? = null
            override fun deleteOwnedFile(relativePath: String) = Unit
            override fun resolve(
                photoUri: String,
                localCopyPath: String?,
            ) = dev.tlong.animaldex.data.photo.PhotoRef.Revoked
            override fun displayName(uri: String): String? = null
        },
    )
}
