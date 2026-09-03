package dev.tlong.biodex.ui.register

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M41 on the Register screen: a plant may be registered with no photo, and whenever an
 * attached photo *would* be discarded the screen says so before the user presses the button.
 */
class PhotolessRegisterTest {

    private fun species(kingdom: Kingdom) = SpeciesSummary(
        id = "s1",
        regionId = "pacific",
        dexNumber = 1,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = "Salal",
        scientificName = "Gaultheria shallon",
        taxClass = if (kingdom == Kingdom.PLANT) TaxClass.SHRUB else TaxClass.BIRD,
        kingdom = kingdom,
        silhouetteRes = "sil_shrub",
        ecosystemIds = emptyList(),
        caughtAt = null,
        thumbPath = null,
        captureCount = 0,
    )

    private val photo = PickedPhoto("content://photo/1")

    @Test
    fun `a plant can be registered with no photo at all`() {
        val state = RegisterUiState(selected = species(Kingdom.PLANT), photo = null)

        assertTrue(state.canRegister)
    }

    @Test
    fun `an animal still needs one`() {
        // For an animal the photograph *is* the catch, so nothing here changes for it.
        assertFalse(RegisterUiState(selected = species(Kingdom.ANIMAL), photo = null).canRegister)
        assertTrue(RegisterUiState(selected = species(Kingdom.ANIMAL), photo = photo).canRegister)
    }

    @Test
    fun `a fungus still needs one`() {
        // The user asked for pictures of their mushrooms specifically.
        assertFalse(RegisterUiState(selected = species(Kingdom.FUNGUS), photo = null).canRegister)
    }

    @Test
    fun `nothing registers without a species`() {
        assertFalse(RegisterUiState(selected = null, photo = photo).canRegister)
    }

    // -----------------------------------------------------------------------
    // §5.2 rule 10. The warning keys off the *selection's* kingdom, not off
    // anything the identification did.
    // -----------------------------------------------------------------------

    @Test
    fun `an attached photo on a plant says it will not be kept`() {
        val state = RegisterUiState(selected = species(Kingdom.PLANT), photo = photo)

        val warning = state.photoNotKeptWarning
        assertNotNull(warning)
        assertTrue(warning!!, warning.contains("not kept"))
    }

    @Test
    fun `the typed path gets the warning too, with no identification anywhere near it`() {
        // The path this rule exists for: attach a photo, type "salal", register. Keying the
        // warning off the identification instead would let the photo go silently.
        val state = RegisterUiState(
            query = "salal",
            selected = species(Kingdom.PLANT),
            photo = photo,
            identification = IdentificationState.Idle,
        )

        assertNotNull(state.photoNotKeptWarning)
    }

    @Test
    fun `no warning where the photo is kept, and none where there is no photo`() {
        assertNull(RegisterUiState(selected = species(Kingdom.ANIMAL), photo = photo).photoNotKeptWarning)
        assertNull(RegisterUiState(selected = species(Kingdom.PLANT), photo = null).photoNotKeptWarning)
        // Nothing selected yet: the app does not know the kingdom, so it makes no promise.
        assertNull(RegisterUiState(selected = null, photo = photo).photoNotKeptWarning)
    }
}
