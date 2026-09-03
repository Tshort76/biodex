package dev.tlong.biodex.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Identification section's state, and the two sentences it is responsible for.
 *
 * The privacy text is worth a test for the same reason the export summary is: it is a promise
 * the user cannot check for themselves. `licenses.md` and the README say the same thing, and
 * if this drifts from them the app has three different accounts of what it uploads.
 */
class IdentificationSettingsTest {

    @Test
    fun `an empty key is no key`() {
        assertFalse(SettingsUiState().hasPlantNetKey)
        assertFalse(SettingsUiState(plantNetKey = "   ").hasPlantNetKey)
        assertTrue(SettingsUiState(plantNetKey = "2b10abc").hasPlantNetKey)
    }

    @Test
    fun `the count line names both numbers`() {
        val state = SettingsUiState(identificationsUsed = 12, identificationCap = 100)

        assertEquals("12 of 100 identifications used this month.", state.identificationLine)
        assertFalse(state.identificationCapReached)
    }

    @Test
    fun `the cap reads as reached only at the cap`() {
        assertFalse(SettingsUiState(identificationsUsed = 99).identificationCapReached)
        assertTrue(SettingsUiState(identificationsUsed = 100).identificationCapReached)
    }

    @Test
    fun `the privacy text states the three things the user cannot verify themselves`() {
        // Nothing goes without a press; what goes is a reduced copy of that one photo; the
        // location does not go with it (M36).
        assertTrue(IDENTIFICATION_PRIVACY_TEXT.contains("unless you press Identify"))
        assertTrue(IDENTIFICATION_PRIVACY_TEXT.contains("reduced copy"))
        assertTrue(IDENTIFICATION_PRIVACY_TEXT.contains("no location"))
    }

    @Test
    fun `the key text says why the key is a human step`() {
        // M39/D24: the reason there is no built-in key is that the source is public, and
        // saying so is what stops the human step reading as an oversight.
        assertTrue(IDENTIFICATION_KEY_TEXT.contains("public"))
        assertTrue(IDENTIFICATION_KEY_TEXT.contains("stored on this phone"))
    }
}
