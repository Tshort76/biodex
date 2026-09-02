package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.backup.BackupSpecies
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UserSpeciesRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row mappers, checked without a device. `UserSpeciesRoomTest` walks the same pair through a
 * real database; these are the cheap half, and they exist because the bug they pin was a pairing
 * bug — a column present in one mapper and missing from the other — which nothing detects at the
 * moment it happens.
 */
class UserSpeciesMappersTest {

    private val elderberry = SpeciesFields(
        commonName = "Blue Elderberry",
        scientificName = "Sambucus cerulea",
        kingdom = Kingdom.PLANT,
        taxClass = TaxClass.SHRUB,
        uses = setOf(PlantUse.EDIBLE, PlantUse.MEDICINAL),
        usesNote = "Berries, late summer — cook them. Caution: raw berries are toxic.",
        medicinalActivities = listOf("Diaphoretic", "Diuretic", "Laxative"),
        medicinalRecordCount = 58,
        usesAttribution = "Dr. Duke's Databases · USDA ARS · CC0",
    )

    private fun record(fields: SpeciesFields) = UserSpeciesRecord(
        id = "user-1",
        regionId = "pacific",
        dexNumber = 9001,
        detailsPending = false,
        fields = fields,
    )

    @Test
    fun `a plant survives the round trip with every column it went in with`() {
        val out = record(elderberry).toEntity().toUserRecord().fields

        assertEquals(elderberry.kingdom, out.kingdom)
        assertEquals(elderberry.taxClass, out.taxClass)
        assertEquals(elderberry.uses, out.uses)
        assertEquals(elderberry.usesNote, out.usesNote)
        assertEquals(elderberry.medicinalActivities, out.medicinalActivities)
        assertEquals(elderberry.medicinalRecordCount, out.medicinalRecordCount)
        assertEquals(elderberry.usesAttribution, out.usesAttribution)
    }

    @Test
    fun `writing the same record twice changes nothing, which is the bug this pins`() {
        // The failure was silent: `toEntity` defaulted the plant columns, so the second write
        // of an unchanged record emptied the uses of a species that had them.
        val once = record(elderberry).toEntity().toUserRecord()
        val twice = once.toEntity().toUserRecord()

        assertEquals(once, twice)
        assertEquals(setOf(PlantUse.EDIBLE, PlantUse.MEDICINAL), twice.fields.uses)
    }

    @Test
    fun `the conifer pick survives, and is dropped when the form stops being a tree`() {
        val fir = elderberry.copy(
            commonName = "Douglas-fir",
            taxClass = TaxClass.TREE,
            silhouetteResOverride = "sil_tree_conifer",
        )

        assertEquals("sil_tree_conifer", record(fir).toEntity().toUserRecord().fields.silhouetteRes)
        assertEquals(
            "sil_shrub",
            record(fir.copy(taxClass = TaxClass.SHRUB)).toEntity().toUserRecord().fields.silhouetteRes,
        )
    }

    // -----------------------------------------------------------------------
    // Restoring an archive. A file the user could have hand-edited, so the
    // invariants are re-checked rather than trusted — but a restore must never
    // be the step that quietly loses a recorded toxicity.
    // -----------------------------------------------------------------------

    private fun archived(uses: List<String>, usesNote: String?) = BackupSpecies(
        id = "user-1",
        source = "user",
        dexNumber = 9001,
        commonName = "Bracken",
        taxClass = "fern",
        silhouetteRes = "sil_fern",
        kingdom = "plant",
        uses = uses,
        usesNote = usesNote,
    )

    @Test
    fun `a restored caution survives with no use tags`() {
        val entity = archived(
            uses = emptyList(),
            usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
        ).toEntity("pacific")

        assertEquals(
            "Caution: recorded as poisonous in Duke's ethnobotanical database.",
            entity.usesNote,
        )
        assertTrue(entity.uses.isEmpty())
    }

    @Test
    fun `a restored note with no caution and no tags is still dropped`() {
        assertNull(archived(uses = emptyList(), usesNote = "Fiddleheads in spring.").toEntity("pacific").usesNote)
    }

    @Test
    fun `a restored note keeps its whole text while the species is tagged`() {
        val whole = "Fiddleheads in spring. Caution: documented but carcinogenic."
        val entity = archived(uses = listOf("edible"), usesNote = whole).toEntity("pacific")

        assertEquals(whole, entity.usesNote)
        assertEquals(listOf("edible"), entity.uses)
    }
}
