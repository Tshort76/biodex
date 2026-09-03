package dev.tlong.biodex.ui.common

import dev.tlong.biodex.domain.SpeciesSummary

/**
 * §5.3.1's three tile states, decided away from Compose so the one rule that matters can be
 * pinned in the JVM suite.
 *
 * The hazard this exists to avoid is specific. The catalogue's reference image is a picture of
 * the species whether or not the user has caught it, so a caught plant rendered with it and
 * nothing else would look like a *rich uncaught tile*. **The colour is what carries the state,
 * not the picture** — a caught plant is a full-colour photograph inside teal-accented chrome,
 * an uncaught one is a flat dark shape on a grey field, and they are not confusable at
 * thumbnail size.
 */
enum class TileState {
    /** Any kingdom, not yet caught: the class silhouette on `silBg`. */
    UNCAUGHT,

    /**
     * Caught, with no photograph of the user's own (M41) — a plant from this release onward.
     * The species' own reference image at full colour, on an `accentSoft` ground with an
     * `accent` hairline border and a leaf glyph.
     */
    CAUGHT_REFERENCE_IMAGE,

    /**
     * Caught, with the user's own photograph: animals, fungi, and every plant registered
     * before M41. Unchanged — the neutral card chrome it has always had.
     */
    CAUGHT_OWN_PHOTO,
}

fun tileStateFor(species: SpeciesSummary): TileState = when {
    !species.caught -> TileState.UNCAUGHT
    species.thumbPath != null -> TileState.CAUGHT_OWN_PHOTO
    else -> TileState.CAUGHT_REFERENCE_IMAGE
}

/**
 * **The rule §5.3.1 asks a test to pin.** Whether the tile wears the accent chrome depends
 * only on which state it is in — never on whether the reference image was actually fetched.
 *
 * If the picture has not cached and the phone is offline, the tile falls back to the
 * silhouette, but it keeps the `accentSoft` ground, the `accent` border and the glyph. The
 * chrome is what says *caught*, so a caught plant must not read as a still-missing one because
 * a network fetch failed. This is deliberately a function of the state alone: there is no
 * parameter here for whether the image loaded, because there must not be one.
 */
fun tileWearsAccentChrome(state: TileState): Boolean = state == TileState.CAUGHT_REFERENCE_IMAGE

/** The glyph in the caption band; null for the two states that carry no mark. */
fun tileGlyph(state: TileState): String? =
    if (state == TileState.CAUGHT_REFERENCE_IMAGE) LEAF_GLYPH else null

/**
 * Path-neutral on purpose (§5.3). A plant typed in by name gets the same mark as one named
 * through Pl@ntNet, because the capture row does not record how it was named — a persistent
 * "identified with Pl@ntNet" mark would need a column the design declined to add (Q06).
 */
const val NO_OWN_PHOTO_MARK = "caught — no photo of your own"

private const val LEAF_GLYPH = "🍃"
