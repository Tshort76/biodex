package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.FakeCaptureStore
import dev.tlong.biodex.data.photo.FakePhotoGateway
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.LookupFields
import dev.tlong.biodex.domain.PlantUse
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

    override suspend fun deleteUserSpecies(speciesId: String) {
        species.remove(speciesId)
        memberships.remove(speciesId)
    }
}

class AddSpeciesRegistrarTest {

    private val captureStore = FakeCaptureStore()
    private val gateway = FakePhotoGateway()
    private val store = FakeUserSpeciesStore()
    private var nextId = 0

    private val registrar = AddSpeciesRegistrar(
        store = store,
        captures = CaptureRegistrar(
            store = captureStore,
            photos = gateway,
            newCaptureId = { "capture-${nextId++}" },
            now = { 1_000L },
        ),
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
    fun `accepting the card writes the species, its ecosystems and the photo together`() = runBlocking {
        val result = registrar.create(
            fields = thrush,
            ecosystemIds = listOf("coastal-rainforest", "urban-suburban"),
            photoUri = "content://photo/1",
        )

        val created = result as AddSpeciesRegistrar.CreateResult.Created
        val record = store.species.getValue(created.speciesId)
        assertEquals(9001, record.dexNumber)
        assertEquals("Ixoreus naevius", record.fields.scientificName)
        assertEquals("sil_bird", record.fields.silhouetteRes)
        assertEquals(listOf("coastal-rainforest", "urban-suburban"), store.memberships[created.speciesId])
        assertEquals(1, captureStore.captures.size)
        assertTrue("a new user species is caught by definition", captureStore.entries.isNotEmpty())
    }

    @Test
    fun `user dex numbers climb, so U01 keeps its place`() = runBlocking {
        val first = registrar.create(thrush, emptyList(), "content://photo/1")
        val second = registrar.create(thrush.copy(commonName = "Something else"), emptyList(), "content://photo/2")

        assertEquals(9001, (first as AddSpeciesRegistrar.CreateResult.Created).dexNumber)
        assertEquals(9002, (second as AddSpeciesRegistrar.CreateResult.Created).dexNumber)
    }

    @Test
    fun `an unreadable photo leaves nothing behind — not even the species row`() = runBlocking {
        gateway.thumbnailWorks = false

        val result = registrar.create(thrush, listOf("alpine"), "content://photo/1")

        assertEquals(AddSpeciesRegistrar.CreateResult.PhotoUnreadable, result)
        assertTrue("the species must not survive its own failed registration", store.species.isEmpty())
        assertTrue(captureStore.captures.isEmpty())
    }

    // -----------------------------------------------------------------------
    // The offline path (M20).
    // -----------------------------------------------------------------------

    @Test
    fun `an offline add is created immediately and marked details pending`() = runBlocking {
        val result = registrar.create(
            fields = SpeciesFields(commonName = "Varied Thrush"),
            ecosystemIds = emptyList(),
            photoUri = "content://photo/1",
        )

        val record = store.species.getValue((result as AddSpeciesRegistrar.CreateResult.Created).speciesId)
        assertTrue(record.detailsPending)
        assertEquals("Varied Thrush", record.fields.commonName)
        assertEquals("sil_other_invertebrate", record.fields.silhouetteRes)
        assertEquals(1, captureStore.captures.size)
    }

    @Test
    fun `a backfill that resolves the species clears details pending`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Varied Thrush"),
            emptyList(),
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

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
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

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
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

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
            "content://photo/1",
            userEditedFields = listOf(SpeciesField.SCIENTIFIC_NAME),
        ) as AddSpeciesRegistrar.CreateResult.Created

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
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

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
    // User-added plants (slice 12).
    // -----------------------------------------------------------------------

    private val elderberry = SpeciesFields(
        commonName = "Blue Elderberry",
        scientificName = "Sambucus cerulea",
        kingdom = Kingdom.PLANT,
        taxClass = TaxClass.SHRUB,
        uses = setOf(PlantUse.EDIBLE, PlantUse.MEDICINAL),
        usesNote = "Berries, late summer — cook them. Caution: raw berries are toxic.",
        medicinalActivities = listOf("Diaphoretic", "Diuretic", "Laxative"),
        medicinalRecordCount = 60,
        usesAttribution = "Dr. Duke's Phytochemical and Ethnobotanical Databases · USDA ARS · CC0",
    )

    @Test
    fun `accepting a plant card writes the kingdom, the form, the uses and no call`() = runBlocking {
        val created = registrar.create(
            fields = elderberry,
            ecosystemIds = listOf("riparian-wetland"),
            photoUri = "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

        val fields = store.species.getValue(created.speciesId).fields
        assertEquals(Kingdom.PLANT, fields.kingdom)
        assertEquals(TaxClass.SHRUB, fields.taxClass)
        assertEquals(setOf(PlantUse.EDIBLE, PlantUse.MEDICINAL), fields.uses)
        assertTrue(fields.usesNote!!.contains("Caution:"))
        assertEquals(60, fields.medicinalRecordCount)
        assertNull("a plant has no call in any state (M24)", fields.callUrl)
    }

    @Test
    fun `a plant with no use tag is saved without its note, however it arrived`() = runBlocking {
        val created = registrar.create(
            fields = elderberry.copy(uses = emptySet()),
            ecosystemIds = emptyList(),
            photoUri = "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

        assertNull(store.species.getValue(created.speciesId).fields.usesNote)
    }

    @Test
    fun `a mis-resolved kingdom toggled on the card writes that kingdom's default class`() = runBlocking {
        // GBIF read it as an animal; the user said plant. The class it came with is an animal's
        // and must not survive the correction.
        val created = registrar.create(
            fields = SpeciesFields(
                commonName = "Salal",
                scientificName = "Gaultheria shallon",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.BIRD,
            ),
            ecosystemIds = emptyList(),
            photoUri = "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

        val fields = store.species.getValue(created.speciesId).fields
        assertEquals(Kingdom.PLANT, fields.kingdom)
        assertEquals(TaxClass.HERB, fields.taxClass)
    }

    @Test
    fun `a hand-edited note survives a re-backfill while the tags keep tracking Duke's`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Blue Elderberry"),
            emptyList(),
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

        // The user accepts the card, writing their own note over the pre-filled caution.
        registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Sambucus cerulea",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.SHRUB,
                uses = setOf(PlantUse.MEDICINAL),
                usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
                medicinalRecordCount = 60,
            ),
            edits = AddSpeciesRegistrar.FieldEdits(
                values = elderberry.copy(usesNote = "Berries only, and cook them. Caution: not raw."),
                fields = listOf(SpeciesField.USES_NOTE),
            ),
        )

        // Months later, the index has been regenerated and the backfill runs again.
        val second = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Sambucus cerulea",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.SHRUB,
                uses = setOf(PlantUse.MEDICINAL),
                usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
                medicinalActivities = listOf("Diaphoretic", "Diuretic", "Laxative", "Emetic"),
                medicinalRecordCount = 61,
            ),
        )!!

        assertEquals("Berries only, and cook them. Caution: not raw.", second.fields.usesNote)
        assertTrue(SpeciesField.USES_NOTE in second.userEditedFields)
        // The tags and the source columns were never claimed, so they follow the newest lookup.
        assertEquals(setOf(PlantUse.MEDICINAL), second.fields.uses)
        assertEquals(61, second.fields.medicinalRecordCount)
        assertEquals(4, second.fields.medicinalActivities.size)
    }

    @Test
    fun `a plant backfilled onto a pending animal stops being an animal`() = runBlocking {
        val created = registrar.create(
            SpeciesFields(commonName = "Pacific Rhododendron"),
            emptyList(),
            "content://photo/1",
        ) as AddSpeciesRegistrar.CreateResult.Created

        // 5.6's details-pending default is animal / other-invertebrate, corrected on backfill.
        assertEquals(Kingdom.ANIMAL, store.species.getValue(created.speciesId).fields.kingdom)

        val updated = registrar.backfill(
            speciesId = created.speciesId,
            lookup = LookupFields(
                scientificName = "Rhododendron macrophyllum",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.SHRUB,
                uses = emptySet(),
            ),
        )!!

        assertEquals(Kingdom.PLANT, updated.fields.kingdom)
        assertEquals(TaxClass.SHRUB, updated.fields.taxClass)
        assertEquals("sil_shrub", updated.fields.silhouetteRes)
    }
}
