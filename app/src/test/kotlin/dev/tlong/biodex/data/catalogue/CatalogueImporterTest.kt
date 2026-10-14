package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.db.MetaEntity
import dev.tlong.biodex.domain.SpeciesSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The importer end-to-end on the JVM: real asset parsing, the real reconciler, and an
 * in-memory store that cascades deletes the way SQLite does.
 *
 * `runBlocking` rather than `kotlinx-coroutines-test` on purpose — none of these tests
 * needs virtual time, and it keeps the version catalog untouched (ARCHITECTURE.md 9 asks
 * later slices not to edit it).
 */
class CatalogueImporterTest {

    private val fixturePath = "catalogue/fixture-pacific.json"

    private fun classpathReader() = AssetReader { path ->
        javaClass.classLoader?.getResourceAsStream(path)
    }

    private fun textReader(path: String, text: String) = AssetReader {
        if (it == path) ByteArrayInputStream(text.toByteArray()) else null
    }

    private fun fixtureDocument(): CatalogueDocument {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(fixturePath))
        return catalogueJson.decodeFromString(stream.use { it.readBytes().decodeToString() })
    }

    private fun importerFor(store: FakeCatalogueStore) =
        CatalogueImporter(classpathReader(), store, fixturePath)

    @Test
    fun `first run imports the whole fixture and records the version`() = runBlocking {
        val store = FakeCatalogueStore()

        val outcome = importerFor(store).import()

        assertEquals(ImportOutcome.Imported(1, 10, 0, 7), outcome)
        assertEquals(10, store.species.size)
        assertEquals(7, store.ecosystems.size)
        assertEquals("1", store.meta[MetaEntity.KEY_CATALOGUE_VERSION])
        assertNotNull(store.meta[MetaEntity.KEY_SCHEMA_SEEDED_AT])
        // The heron's three ecosystem tags became three join rows.
        assertEquals(3, store.memberships.count { it.first == "great-blue-heron" })
    }

    /** D94: the fixture moved to a second region, ids prefixed the way the pipeline does it. */
    private fun argentinaText(version: Int = 1): String {
        val pacific = fixtureDocument()
        val document = pacific.copy(
            catalogueVersion = version,
            regionId = "argentina",
            regionName = "Argentina",
            ecosystems = pacific.ecosystems.map { it.copy(id = "ar-" + it.id) },
            species = pacific.species.map { s ->
                s.copy(id = "ar-" + s.id, ecosystemIds = s.ecosystemIds.map { "ar-$it" })
            },
        )
        return catalogueJson.encodeToString(document)
    }

    @Test
    fun `a second region imports beside the first under its own version (D94)`() = runBlocking {
        val store = FakeCatalogueStore()
        importerFor(store).import()

        val argentina = CatalogueImporter(
            textReader("catalogue/argentina.json", argentinaText()),
            store,
            "catalogue/argentina.json",
            regionSortOrder = 1,
        ).import()

        assertEquals(ImportOutcome.Imported(1, 10, 0, 7), argentina)
        assertEquals(10, store.species.values.count { it.regionId == "pacific" })
        assertEquals(10, store.species.values.count { it.regionId == "argentina" })
        assertEquals("1", store.meta[MetaEntity.KEY_CATALOGUE_VERSION])
        assertEquals("1", store.meta[MetaEntity.catalogueVersionKey("argentina")])
        assertEquals(listOf("pacific" to 0, "argentina" to 1), store.regions.values.map { it.id to it.sortOrder })
        // The Pacific's version is its own, so the second region's import left it current.
        assertEquals(ImportOutcome.UpToDate, importerFor(store).import())
    }

    @Test
    fun `every shipped region imports beside the others without a shared id (D94)`() = runBlocking {
        val store = FakeCatalogueStore()
        val reader = RealCatalogueAsset.allRegionsReader()

        val outcomes = CATALOGUE_ASSET_PATHS.mapIndexed { index, path ->
            CatalogueImporter(reader, store, path, regionSortOrder = index).import()
        }

        val imported = outcomes.map { it as ImportOutcome.Imported }
        // A shared id would have overwritten a row, so the store would hold fewer than were sent.
        assertEquals(imported.sumOf { it.speciesUpserted }, store.species.size)
        assertEquals(imported.sumOf { it.ecosystems }, store.ecosystems.size)
        assertEquals(listOf("pacific", "argentina"), store.regions.values.map { it.id })
        assertTrue(store.species.values.filter { it.regionId == "argentina" }.all { it.id.startsWith("ar-") })
        assertTrue(store.ecosystems.values.filter { it.regionId == "argentina" }.all { it.id.startsWith("ar-") })
    }

    @Test
    fun `a second run at the same version writes nothing`() = runBlocking {
        val store = FakeCatalogueStore()
        importerFor(store).import()

        val outcome = importerFor(store).import()

        assertEquals(ImportOutcome.UpToDate, outcome)
        assertEquals(1, store.applyCount)
    }

    /**
     * The data-safety test this slice exists for. A catalogue update that renames a species,
     * drops a caught one and drops an uncaught one must leave every entry, every capture and
     * every user-added species exactly as it found them.
     */
    @Test
    fun `a catalogue update never destroys entries, captures or user-added species`() = runBlocking {
        val store = FakeCatalogueStore()
        importerFor(store).import()

        // The user catches two curated species and adds one of their own.
        store.seedCaught("great-blue-heron")
        store.seedCaught("coho-salmon")
        store.seedSpecies(userSpecies("user-1", dexNumber = 1001, commonName = "Varied Thrush"))
        store.seedCaught("user-1")
        store.memberships += "user-1" to "urban-suburban"

        val v2 = fixtureDocument().let { v1 ->
            v1.copy(
                catalogueVersion = 2,
                species = v1.species
                    // Drop one caught species and one uncaught one.
                    .filterNot { it.id == "coho-salmon" || it.id == "banana-slug" }
                    .map {
                        if (it.id == "great-blue-heron") {
                            it.copy(habitatText = "Rewritten habitat text.")
                        } else {
                            it
                        }
                    },
            )
        }
        val v2Importer = CatalogueImporter(
            textReader("v2.json", catalogueJson.encodeToString(v2)),
            store,
            "v2.json",
        )

        val outcome = v2Importer.import()

        assertEquals(ImportOutcome.Imported(2, 8, 1, 7), outcome)

        // Entries and captures survive untouched.
        assertEquals(setOf("great-blue-heron", "coho-salmon", "user-1"), store.entries.keys)
        assertEquals(3, store.captures.size)

        // The caught species dropped from the asset is kept, and keeps its links.
        assertTrue(store.species.containsKey("coho-salmon"))
        assertEquals(2, store.memberships.count { it.first == "coho-salmon" })

        // The uncaught species dropped from the asset is the only row removed.
        assertTrue(!store.species.containsKey("banana-slug"))
        assertEquals(0, store.memberships.count { it.first == "banana-slug" })

        // The user-added species is byte-for-byte what it was, links included.
        val user = store.species.getValue("user-1")
        assertEquals(SpeciesSource.USER, user.source)
        assertEquals("Varied Thrush", user.commonName)
        assertEquals(listOf("habitatText"), user.userEditedFields)
        assertTrue(store.memberships.contains("user-1" to "urban-suburban"))

        // Curated prose is overwritten wholesale, which is the point of the update.
        assertEquals("Rewritten habitat text.", store.species.getValue("great-blue-heron").habitatText)
    }

    @Test
    fun `re-importing the same species replaces its ecosystem links rather than accumulating them`() =
        runBlocking {
            val store = FakeCatalogueStore()
            importerFor(store).import()

            val v2 = fixtureDocument().let { v1 ->
                v1.copy(
                    catalogueVersion = 2,
                    species = v1.species.map {
                        if (it.id == "great-blue-heron") {
                            it.copy(ecosystemIds = listOf("riparian-wetland"))
                        } else {
                            it
                        }
                    },
                )
            }
            CatalogueImporter(
                textReader("v2.json", catalogueJson.encodeToString(v2)),
                store,
                "v2.json",
            ).import()

            assertEquals(
                setOf("great-blue-heron" to "riparian-wetland"),
                store.memberships.filter { it.first == "great-blue-heron" }.toSet(),
            )
        }

    @Test
    fun `a missing asset leaves the database empty instead of crashing`() = runBlocking {
        val store = FakeCatalogueStore()

        val outcome = CatalogueImporter(AssetReader { null }, store, "catalogue/pacific.json").import()

        assertEquals(ImportOutcome.AssetMissing, outcome)
        assertEquals(0, store.applyCount)
        assertTrue(store.species.isEmpty())
        assertTrue(store.meta.isEmpty())
    }

    @Test
    fun `a malformed asset leaves the database untouched instead of crashing`() = runBlocking {
        val store = FakeCatalogueStore()
        importerFor(store).import()
        val before = store.species.toMap()

        val outcome = CatalogueImporter(
            textReader("broken.json", "{ not json at all"),
            store,
            "broken.json",
        ).import()

        assertTrue(outcome is ImportOutcome.ParseFailed)
        assertEquals(1, store.applyCount)
        assertEquals(before, store.species)
    }

    @Test
    fun `an asset missing a required field is a parse failure, not a partial import`() = runBlocking {
        val store = FakeCatalogueStore()

        val outcome = CatalogueImporter(
            textReader("partial.json", """{"regionId":"pacific","regionName":"Pacific"}"""),
            store,
            "partial.json",
        ).import()

        assertTrue(outcome is ImportOutcome.ParseFailed)
        assertTrue(store.species.isEmpty())
    }
}
