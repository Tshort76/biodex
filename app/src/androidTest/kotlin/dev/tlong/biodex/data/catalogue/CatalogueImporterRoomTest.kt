package dev.tlong.biodex.data.catalogue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tlong.biodex.data.db.AppDatabase
import dev.tlong.biodex.domain.FUNGUS_DEX_NUMBER_BASE
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.data.db.CaptureEntity
import dev.tlong.biodex.data.db.EntryEntity
import dev.tlong.biodex.data.db.MetaEntity
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.domain.SpeciesSource
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The importer against real Room, plus the real bundled asset when slice 2's catalogue is
 * present in the APK under test.
 *
 * NOT RUN as of slice 3 — no phone was attached. These execute the first time one is.
 */
@RunWith(AndroidJUnit4::class)
class CatalogueImporterRoomTest {

    private lateinit var db: AppDatabase

    /** Assets of the app under test — where slice 2's `catalogue/pacific.json` lives. */
    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    /** Assets of the test APK — where this slice's ten-species fixture lives. */
    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun fixtureImporter() = CatalogueImporter(
        assets = AndroidAssetReader(testContext),
        store = RoomCatalogueStore(db),
        assetPath = "catalogue/fixture-pacific.json",
    )

    @Test
    fun importWritesSpeciesEcosystemsJoinsAndVersionInOneTransaction() = runBlocking {
        val outcome = fixtureImporter().import()

        assertEquals(ImportOutcome.Imported(1, 10, 0, 7), outcome)
        assertEquals(10, db.speciesDao().speciesOnce("pacific").size)
        assertEquals(7, db.ecosystemDao().ecosystemsOnce("pacific").size)
        assertEquals("1", db.metaDao().value(MetaEntity.KEY_CATALOGUE_VERSION))

        // The joins resolve: the heron is in three ecosystems, and every link points at a
        // declared ecosystem row (a foreign key would have rejected it otherwise).
        val memberships = db.ecosystemDao().membershipsOnce("pacific")
        assertEquals(3, memberships.count { it.speciesId == "great-blue-heron" })
        assertTrue(memberships.isNotEmpty())
    }

    @Test
    fun aSecondImportAtTheSameVersionIsANoOp() = runBlocking {
        fixtureImporter().import()
        assertEquals(ImportOutcome.UpToDate, fixtureImporter().import())
        assertEquals(10, db.speciesDao().speciesOnce("pacific").size)
    }

    @Test
    fun importDoesNotDisturbEntriesCapturesOrUserSpecies() = runBlocking {
        fixtureImporter().import()

        db.captureDao().insert(
            CaptureEntity(
                id = "c1",
                speciesId = "great-blue-heron",
                photoUri = "content://media/external/images/media/1",
                thumbPath = "thumbnails/c1.jpg",
                takenAt = 100,
                createdAt = 100,
            ),
        )
        db.entryDao().upsert(EntryEntity("great-blue-heron", caughtAt = 100))

        // Force a re-import by clearing the recorded version.
        db.metaDao().put(MetaEntity(MetaEntity.KEY_CATALOGUE_VERSION, "0"))
        fixtureImporter().import()

        assertEquals(1, db.captureDao().countForSpecies("great-blue-heron"))
        assertEquals(100L, db.entryDao().entryOnce("great-blue-heron")?.caughtAt)
    }

    @Test
    fun aMissingAssetLeavesTheDatabaseEmptyRatherThanCrashing() = runBlocking {
        val outcome = CatalogueImporter(
            assets = AndroidAssetReader(testContext),
            store = RoomCatalogueStore(db),
            assetPath = "catalogue/there-is-no-such-file.json",
        ).import()

        assertEquals(ImportOutcome.AssetMissing, outcome)
        assertTrue(db.speciesDao().speciesOnce("pacific").isEmpty())
    }

    @Test
    fun theRepositoryReportsTheImportedCatalogueThroughItsFlows() = runBlocking {
        fixtureImporter().import()
        val repository = DexRepository(db)

        val progress = repository.dexProgress().first()
        assertEquals(10, progress.totalSpecies)
        assertEquals(0, progress.caughtCount)
        assertEquals(7, progress.perEcosystem.size)

        val summaries = repository.speciesSummaries().first()
        assertEquals(10, summaries.size)
        assertEquals("great-blue-heron", summaries.first().id)
        assertEquals(3, summaries.first().ecosystemIds.size)
        assertTrue(summaries.none { it.caught })
    }

    /**
     * The slice's on-phone gate, as an assertion: the real bundled catalogue imports to 120
     * curated species. Skipped rather than failed when slice 2's asset is not in the APK,
     * so this file is useful before that asset lands.
     */
    @Test
    fun theRealBundledCatalogueImportsEveryKingdom() = runBlocking {
        assumeTrue("assets/$CATALOGUE_ASSET_PATH is not bundled yet", bundledCatalogueExists())

        val outcome = CatalogueImporter(AndroidAssetReader(appContext), RoomCatalogueStore(db)).import()

        assertTrue("import failed: $outcome", outcome is ImportOutcome.Imported)
        val species = db.speciesDao().speciesOnce("pacific")
        assertEquals(230, species.size)
        assertTrue(species.all { it.source == SpeciesSource.CURATED })
        val animals = species.filter { it.kingdom == Kingdom.ANIMAL }
        val plants = species.filter { it.kingdom == Kingdom.PLANT }
        val fungi = species.filter { it.kingdom == Kingdom.FUNGUS }
        assertEquals(120, animals.size)
        assertEquals(80, plants.size)
        assertEquals(30, fungi.size)
        assertEquals((1..120).toList(), animals.map { it.dexNumber }.sorted())
        // Fungi are stored offset by FUNGUS_DEX_NUMBER_BASE and displayed as F001..F030.
        assertEquals(
            (1..30).map { FUNGUS_DEX_NUMBER_BASE + it },
            fungi.map { it.dexNumber }.sorted(),
        )
        // The rule the pipeline enforces and nothing else can: no mushroom carries a use
        // tag. A note is optional and, when present, is a caution and nothing else.
        assertTrue(fungi.all { it.uses.isEmpty() })
        assertTrue(fungi.any { it.usesNote?.contains("Caution:") == true })
        assertTrue(fungi.all { it.usesNote == null || it.usesNote!!.startsWith("Caution:") })
        assertEquals(7, db.ecosystemDao().ecosystemsOnce("pacific").size)
        assertTrue(db.ecosystemDao().membershipsOnce("pacific").isNotEmpty())
    }

    private fun bundledCatalogueExists(): Boolean =
        try {
            appContext.assets.open(CATALOGUE_ASSET_PATH).close()
            true
        } catch (e: IOException) {
            false
        }
}
