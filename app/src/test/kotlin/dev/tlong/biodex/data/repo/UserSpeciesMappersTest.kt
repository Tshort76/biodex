package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.backup.BackupSpecies
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesUse
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

    private val flyAgaric = SpeciesFields(
        commonName = "Fly Agaric",
        scientificName = "Amanita muscaria",
        kingdom = Kingdom.FUNGUS,
        taxClass = TaxClass.MUSHROOM,
        uses = setOf(SpeciesUse.EDIBLE),
        usesNote = "Caution: hallucinogenic and toxic raw.",
    )

    private fun record(fields: SpeciesFields) = UserSpeciesRecord(
        id = "user-1",
        regionId = "pacific",
        dexNumber = 9001,
        detailsPending = false,
        fields = fields,
    )

    @Test
    fun `a species survives the round trip with every column it went in with`() {
        val out = record(flyAgaric).toEntity().toUserRecord().fields

        assertEquals(flyAgaric.kingdom, out.kingdom)
        assertEquals(flyAgaric.taxClass, out.taxClass)
        assertEquals(flyAgaric.uses, out.uses)
        assertEquals(flyAgaric.usesNote, out.usesNote)
        assertEquals("sil_mushroom", out.silhouetteRes)
    }

    @Test
    fun `writing the same record twice changes nothing, which is the bug this pins`() {
        // The failure was silent: `toEntity` defaulted the uses columns, so the second write
        // of an unchanged record emptied the uses of a species that had them.
        val once = record(flyAgaric).toEntity().toUserRecord()
        val twice = once.toEntity().toUserRecord()

        assertEquals(once, twice)
        assertEquals(setOf(SpeciesUse.EDIBLE), twice.fields.uses)
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
        commonName = "Fly Agaric",
        taxClass = "mushroom",
        silhouetteRes = "sil_mushroom",
        kingdom = "fungus",
        uses = uses,
        usesNote = usesNote,
    )

    @Test
    fun `a restored caution survives with no use tags`() {
        val entity = archived(
            uses = emptyList(),
            usesNote = "Caution: hallucinogenic and toxic raw.",
        ).toEntity("pacific")

        assertEquals("Caution: hallucinogenic and toxic raw.", entity.usesNote)
        assertTrue(entity.uses.isEmpty())
    }

    @Test
    fun `a restored note with no caution and no tags is still dropped`() {
        assertNull(archived(uses = emptyList(), usesNote = "Under birches in autumn.").toEntity("pacific").usesNote)
    }

    @Test
    fun `a restored note keeps its whole text while the species is tagged`() {
        val whole = "Under birches in autumn. Caution: hallucinogenic and toxic raw."
        val entity = archived(uses = listOf("edible"), usesNote = whole).toEntity("pacific")

        assertEquals(whole, entity.usesNote)
        assertEquals(listOf("edible"), entity.uses)
    }

    @Test
    fun `a stored medicinal tag reads back as no use at all`() {
        // D59: `medicinal` was Duke's-derived and left with the plants. A row that still
        // carries the word is dropped by the closed vocabulary, never guessed at.
        val entity = archived(uses = listOf("medicinal"), usesNote = null).toEntity("pacific")
        assertTrue(entity.uses.isEmpty())
    }
}
