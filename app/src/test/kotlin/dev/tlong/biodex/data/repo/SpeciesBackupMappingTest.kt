package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an archive keeps of a species the user added themselves. An archive is the only
 * record of one, so a field the export drops is a field the restore invents.
 */
class SpeciesBackupMappingTest {

    private val thrush = SpeciesEntity(
        id = "user-1",
        regionId = "pacific",
        dexNumber = 9001,
        source = SpeciesSource.USER,
        commonName = "Varied Thrush",
        scientificName = "Ixoreus naevius",
        taxClass = TaxClass.BIRD,
        kingdom = Kingdom.ANIMAL,
        silhouetteRes = "sil_bird",
        lineageKingdom = "Animalia",
        lineagePhylum = "Chordata",
        lineageClass = "Aves",
        lineageOrder = "Passeriformes",
        lineageFamily = "Turdidae",
    )

    @Test
    fun `a classification survives export and import`() {
        val restored = thrush.toBackup(ecosystemIds = emptyList()).toEntity("pacific")

        assertEquals("Animalia", restored.lineageKingdom)
        assertEquals("Chordata", restored.lineagePhylum)
        assertEquals("Aves", restored.lineageClass)
        assertEquals("Passeriformes", restored.lineageOrder)
        assertEquals("Turdidae", restored.lineageFamily)
    }

    @Test
    fun `a rank GBIF never filled in stays empty rather than becoming a string`() {
        // Every ray-finned fish is in this state, and a restore that turned the gap into
        // "null" or "" would change the hop count around it.
        val salmon = thrush.copy(lineageClass = null, lineageFamily = "Salmonidae")

        val restored = salmon.toBackup(ecosystemIds = emptyList()).toEntity("pacific")

        assertEquals(null, restored.lineageClass)
        assertEquals("Salmonidae", restored.lineageFamily)
    }
}
