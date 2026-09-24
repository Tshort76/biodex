package dev.tlong.biodex.data.repo

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.LookupFields
import dev.tlong.biodex.domain.SpeciesUse
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UserSpeciesRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory `UserSpeciesStore`; the whole user-added write path runs against it. */
class FakeUserSpeciesStore : UserSpeciesStore {

    val species = linkedMapOf<String, UserSpeciesRecord>()
    val memberships = mutableMapOf<String, List<String>>()

    override suspend fun maxUserDexNumber(regionId: String): Int? =
        species.values.filter { it.regionId == regionId }.maxOfOrNull { it.dexNumber }

    override suspend fun userSpecies(speciesId: String): UserSpeciesRecord? = species[speciesId]

    override suspend fun upsertUserSpecies(record: UserSpeciesRecord, ecosystemIds: List<String>?) {
        species[record.id] = record
        if (ecosystemIds != null) memberships[record.id] = ecosystemIds
    }
}

class AddSpeciesRegistrarTest {

    private val store = FakeUserSpeciesStore()

    private val registrar = AddSpeciesRegistrar(
        store = store,
        newSpeciesId = { "user-${store.species.size + 1}" },
    )

    private val thrush = SpeciesFields(
        commonName = "Varied Thrush",
        scientificName = "Ixoreus naevius",
        taxClass = TaxClass.BIRD,
        habitatText = "Dense coniferous forest.",
    )

    // -----------------------------------------------------------------------
    // Accepting the card (M19), and the U-number that trails the catalogue (M02).
    // -----------------------------------------------------------------------

    @Test
    fun `accepting the card writes the species and its ecosystems, and catches nothing (D69)`() = runBlocking {
        val created = registrar.create(
            fields = thrush,
            ecosystemIds = listOf("coastal-rainforest", "urban-suburban"),
        )

        val record = store.species.getValue(created.speciesId)
        assertEquals(9001, created.dexNumber)
        assertEquals("Ixoreus naevius", record.fields.scientificName)
        assertEquals("sil_bird", record.fields.silhouetteRes)
        assertEquals(listOf("coastal-rainforest", "urban-suburban"), store.memberships[created.speciesId])
        // The registrar has no capture door at all any more; the Room test pins that no
        // capture or entry row appears.
    }

    @Test
    fun `user dex numbers climb, so U01 keeps its place`() = runBlocking {
        val first = registrar.create(thrush, emptyList())
        val second = registrar.create(thrush.copy(commonName = "Something else"), emptyList())

        assertEquals(9001, (first).dexNumber)
        assertEquals(9002, (second).dexNumber)
    }

    @Test
    fun `an offline add is created immediately and marked details pending`() = runBlocking {
        val result = registrar.create(
            fields = SpeciesFields(commonName = "Varied Thrush"),
            ecosystemIds = emptyList(),
        )

        val record = store.species.getValue((result).speciesId)
        assertTrue(record.detailsPending)
        assertEquals("Varied Thrush", record.fields.commonName)
        assertEquals("sil_other_invertebrate", record.fields.silhouetteRes)
    }

    @Test
    fun `a backfill that resolves the species clears details pending`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Varied Thrush"),
            emptyList(),
        )

        val updated = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Ixoreus naevius",
                taxClass = TaxClass.BIRD,
                habitatText = "Breeds in moist coniferous forest.",
            ),
        )!!

        assertFalse(updated.detailsPending)
        assertEquals("Ixoreus naevius", updated.fields.scientificName)
        assertEquals(TaxClass.BIRD, updated.fields.taxClass)
        assertEquals("Breeds in moist coniferous forest.", updated.fields.habitatText)
    }

    @Test
    fun `a backfill that resolves nothing leaves the entry pending for the next try`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Varied Thrush"),
            emptyList(),
        )

        val updated = registrar.backfill(created.speciesId, lookup = null)!!

        assertTrue(updated.detailsPending)
    }

    // -----------------------------------------------------------------------
    // M21 through the write path, not just through the pure merge.
    // -----------------------------------------------------------------------

    @Test
    fun `a field edited on the card survives every later backfill`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Varied Thrush"),
            emptyList(),
        )

        // The user opens the card and rewrites the habitat in their own words.
        registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(scientificName = "Ixoreus naevius", taxClass = TaxClass.BIRD),
            edits = AddSpeciesRegistrar.FieldEdits(
                values = SpeciesFields(
                    commonName = "Varied Thrush",
                    habitatText = "The big fir behind the shed.",
                ),
                fields = listOf(SpeciesField.HABITAT_TEXT),
            ),
        )

        // Months later, another backfill runs with a full Wikipedia payload.
        val second = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Ixoreus naevius",
                taxClass = TaxClass.BIRD,
                habitatText = "Breeds in moist coniferous forest from Alaska to California.",
                description = "A thrush of the Pacific slope.",
            ),
        )!!

        assertEquals("The big fir behind the shed.", second.fields.habitatText)
        assertEquals("A thrush of the Pacific slope.", second.fields.description)
        assertTrue(SpeciesField.HABITAT_TEXT in second.userEditedFields)
    }

    @Test
    fun `the edited-field set is remembered, not re-supplied by the caller`() = runBlocking {
        val created = registrar.create(
            thrush,
            emptyList(),
            userEditedFields = listOf(SpeciesField.SCIENTIFIC_NAME),
        )

        val updated = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(scientificName = "Something else entirely"),
        )!!

        assertEquals("Ixoreus naevius", updated.fields.scientificName)
    }

    @Test
    fun `a backfill never touches the ecosystem tags the user picked`() = runBlocking {
        val created = registrar.create(
            thrush,
            listOf("coastal-rainforest"),
        )

        registrar.backfill(created.speciesId, LookupFields(habitatText = "Anywhere."))

        // D10: no API maps species onto these seven ecosystems, so nothing automatic may write
        // them. `null` is the write path's way of saying "leave them alone".
        assertEquals(listOf("coastal-rainforest"), store.memberships[created.speciesId])
    }

    @Test
    fun `a backfill of a species that no longer exists is a no-op, not a crash`() = runBlocking {
        assertNull(registrar.backfill("user-gone", LookupFields(scientificName = "X")))
    }

    // -----------------------------------------------------------------------
    // User-added fungi: the second kingdom goes through the same door.
    // -----------------------------------------------------------------------

    private val chanterelle = SpeciesFields(
        commonName = "Golden Chanterelle",
        scientificName = "Cantharellus formosus",
        kingdom = Kingdom.FUNGUS,
        taxClass = TaxClass.MUSHROOM,
    )

    @Test
    fun `accepting a fungus card writes the kingdom and the form, and nothing it must not`() = runBlocking {
        // A use tag and a note are curated fields (D48); no source pre-fills them for a
        // user-added species any more (D59), so the door drops whatever the card carried.
        val created = registrar.create(
            fields = chanterelle.copy(
                uses = setOf(SpeciesUse.EDIBLE),
                usesNote = "Caution: only with a confident identification.",
            ),
            ecosystemIds = listOf("coastal-rainforest"),
        )

        val fields = store.species.getValue(created.speciesId).fields
        assertEquals(Kingdom.FUNGUS, fields.kingdom)
        assertEquals(TaxClass.MUSHROOM, fields.taxClass)
        assertEquals("sil_mushroom", fields.silhouetteRes)
        assertEquals(emptySet<SpeciesUse>(), fields.uses)
        assertNull(fields.usesNote)
    }

    @Test
    fun `a mis-resolved kingdom toggled on the card writes that kingdom's default class`() = runBlocking {
        // GBIF read it as an animal; the user said fungus. The class it came with is an
        // animal's and must not survive the correction.
        val created = registrar.create(
            fields = chanterelle.copy(taxClass = TaxClass.BIRD),
            ecosystemIds = emptyList(),
        )

        val fields = store.species.getValue(created.speciesId).fields
        assertEquals(Kingdom.FUNGUS, fields.kingdom)
        assertEquals(TaxClass.OTHER_FUNGUS, fields.taxClass)
    }

    @Test
    fun `a fungus backfilled onto a pending animal stops being an animal`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Golden Chanterelle"),
            emptyList(),
        )

        // 5.6's details-pending default is animal / other-invertebrate, corrected on backfill.
        assertEquals(Kingdom.ANIMAL, store.species.getValue(created.speciesId).fields.kingdom)

        val updated = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Cantharellus formosus",
                kingdom = Kingdom.FUNGUS,
                taxClass = TaxClass.MUSHROOM,
            ),
        )!!

        assertEquals(Kingdom.FUNGUS, updated.fields.kingdom)
        assertEquals(TaxClass.MUSHROOM, updated.fields.taxClass)
        assertEquals("sil_mushroom", updated.fields.silhouetteRes)
    }

    // -----------------------------------------------------------------------
    // Names are spelled the catalogue's way at the door (M45).
    // -----------------------------------------------------------------------

    @Test
    fun `a created species stores its names formatted`() = runBlocking {
        val result = registrar.create(
            fields = SpeciesFields(commonName = "brown pelican", scientificName = "pelecanus OCCIDENTALIS"),
            ecosystemIds = emptyList(),
        )

        val record = store.species.getValue(result.speciesId)
        assertEquals("Brown Pelican", record.fields.commonName)
        assertEquals("Pelecanus occidentalis", record.fields.scientificName)
    }

    @Test
    fun `a hand-edited name is formatted on backfill and still locked`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Varied Thrush"),
            emptyList(),
        )

        val updated = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(scientificName = "Ixoreus naevius", taxClass = TaxClass.BIRD),
            edits = AddSpeciesRegistrar.FieldEdits(
                values = SpeciesFields(commonName = "pacific varied thrush"),
                fields = listOf(SpeciesField.COMMON_NAME),
            ),
        )!!

        assertEquals("Pacific Varied Thrush", updated.fields.commonName)
        assertTrue(SpeciesField.COMMON_NAME in updated.userEditedFields)
    }
}
