package dev.tlong.biodex.ui.register

import dev.tlong.biodex.data.identify.ScoreKind
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.1's table and the panel's headings, pinned with no device.
 *
 * Two of these are the ones worth having. **Hidden versus disabled** is the difference between
 * a control that can never work here and one that can be made to work right now, and the app
 * says which by whether it draws the button at all. And **the reason a disabled button gives**
 * is the entire user-facing half of M38 — a button that just greys out tells a user standing in
 * a forest nothing about whether to walk toward a signal or open Settings.
 */
class IdentifyStateTest {

    private val plants = setOf(Kingdom.PLANT)

    private fun species(id: String, kingdom: Kingdom) = SpeciesSummary(
        id = id,
        regionId = "pacific",
        dexNumber = 1,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = id,
        scientificName = "Genus species",
        taxClass = if (kingdom == Kingdom.PLANT) TaxClass.SHRUB else TaxClass.BIRD,
        kingdom = kingdom,
        silhouetteRes = "sil_shrub",
        ecosystemIds = emptyList(),
        caughtAt = null,
        thumbPath = null,
        captureCount = 0,
    )

    private fun state(
        selected: SpeciesSummary? = null,
        photo: PickedPhoto? = PickedPhoto("content://photo/1"),
        online: Boolean = true,
        hasKey: Boolean = true,
        used: Int = 0,
        cap: Int = 100,
        identification: IdentificationState = IdentificationState.Idle,
    ) = RegisterUiState(
        selected = selected,
        photo = photo,
        identification = identification,
        identifiableKingdoms = plants,
        identifyProviderName = "Pl@ntNet",
        online = online,
        hasIdentifyKey = hasKey,
        identificationsUsed = used,
        identificationCap = cap,
    )

    // -----------------------------------------------------------------------
    // Hidden: there is no provider for this kingdom, and no press on this
    // screen can ever change that (D19, §3.3).
    // -----------------------------------------------------------------------

    @Test
    fun `it is hidden for a selected animal`() {
        assertFalse(state(selected = species("owl", Kingdom.ANIMAL)).identifyVisible)
    }

    @Test
    fun `it is hidden for a selected fungus`() {
        // The app never suggests what a mushroom is (§4.2), and the missing registry entry is
        // the whole mechanism — there is no separate flag to keep in step.
        assertFalse(state(selected = species("chanterelle", Kingdom.FUNGUS)).identifyVisible)
    }

    @Test
    fun `it is shown for a selected plant`() {
        assertTrue(state(selected = species("salal", Kingdom.PLANT)).identifyVisible)
    }

    @Test
    fun `it is shown when nothing is selected yet`() {
        // Identification is *how* the user learns what they are looking at. Requiring them to
        // select a plant first would make the button useless on the walk it exists for.
        assertTrue(state(selected = null).identifyVisible)
    }

    @Test
    fun `it is hidden with no photo attached`() {
        assertFalse(state(photo = null).identifyVisible)
    }

    @Test
    fun `it is hidden entirely when the registry has no providers at all`() {
        val noProviders = state().copy(identifiableKingdoms = emptySet())

        assertFalse(noProviders.identifyVisible)
    }

    // -----------------------------------------------------------------------
    // Disabled, with the specific reason inline (M38). Each row of §5.1.
    // -----------------------------------------------------------------------

    @Test
    fun `with everything in place it is enabled and gives no reason`() {
        val ready = state()

        assertTrue(ready.canIdentify)
        assertNull(ready.identifyDisabledReason)
        assertEquals("Identify with Pl@ntNet ↑", ready.identifyLabel)
    }

    @Test
    fun `no key sends the user to Settings`() {
        val reason = state(hasKey = false).identifyDisabledReason

        assertEquals("Add a Pl@ntNet key in Settings to identify plants", reason)
        assertFalse(state(hasKey = false).canIdentify)
        // Still drawn, because pasting a key is something the user can go and do.
        assertTrue(state(hasKey = false).identifyVisible)
    }

    @Test
    fun `offline says so rather than failing on the press`() {
        assertEquals("Identify needs a connection", state(online = false).identifyDisabledReason)
    }

    @Test
    fun `the cap disables the action and names the count`() {
        val reason = state(used = 100, cap = 100).identifyDisabledReason

        assertEquals("Identification paused: 100 of 100 this month", reason)
        assertFalse(state(used = 100, cap = 100).canIdentify)
        // M37: a hard stop, not a warning past it — one under the cap is still live.
        assertNull(state(used = 99, cap = 100).identifyDisabledReason)
    }

    @Test
    fun `a missing key is reported ahead of being offline`() {
        // Both are true in airplane mode with no key. The one the user can act on right now is
        // the one worth saying.
        val reason = state(hasKey = false, online = false).identifyDisabledReason

        assertTrue(reason!!, reason.contains("Settings"))
    }

    @Test
    fun `a run in progress cannot be started again`() {
        val running = state(identification = IdentificationState.Running("Pl@ntNet"))

        assertFalse(running.canIdentify)
        assertEquals("Identifying…", running.identifyDisabledReason)
    }

    // -----------------------------------------------------------------------
    // What the panel says. The provider is named every time (M34), and the
    // dropped names are counted out loud rather than quietly discarded (M32).
    // -----------------------------------------------------------------------

    @Test
    fun `the heading names the provider and counts what survived`() {
        assertEquals(
            "Pl@ntNet suggests — 3 candidates",
            candidatePanelHeading("Pl@ntNet", shown = 3, dropped = 0),
        )
        assertEquals(
            "Pl@ntNet suggests — 3 candidates, 1 unrecognised name dropped",
            candidatePanelHeading("Pl@ntNet", shown = 3, dropped = 1),
        )
        assertEquals(
            "Pl@ntNet suggests — 1 candidate, 2 unrecognised names dropped",
            candidatePanelHeading("Pl@ntNet", shown = 1, dropped = 2),
        )
    }

    @Test
    fun `a calibrated score renders as a percentage, a self-reported one as no number`() {
        // D22: a classifier's score is a probability the user can weigh; a language model's
        // "confidence" is a token it emitted, and rendering it as a percentage would launder
        // one into the other. Nothing ships the second branch — it is here so a future
        // provider cannot be added without meeting it.
        assertEquals("72%", scoreLabel(0.72434, ScoreKind.CALIBRATED))
        assertEquals("6%", scoreLabel(0.06188, ScoreKind.CALIBRATED))
        assertNull(scoreLabel(0.9, ScoreKind.SELF_REPORTED))
        assertNull(scoreLabel(null, ScoreKind.CALIBRATED))

        assertEquals(
            "Scores are Pl@ntNet's classifier confidence.",
            scoreCaption("Pl@ntNet", ScoreKind.CALIBRATED),
        )
        assertTrue(
            scoreCaption("Gemini", ScoreKind.SELF_REPORTED).contains("no confidence"),
        )
    }

    @Test
    fun `the context kingdom is the selection's, and a plant when nothing is selected`() {
        assertEquals(Kingdom.PLANT, identifyContextKingdom(null))
        assertEquals(Kingdom.PLANT, identifyContextKingdom(Kingdom.PLANT))
        assertEquals(Kingdom.ANIMAL, identifyContextKingdom(Kingdom.ANIMAL))
        assertEquals(Kingdom.FUNGUS, identifyContextKingdom(Kingdom.FUNGUS))
    }
}
