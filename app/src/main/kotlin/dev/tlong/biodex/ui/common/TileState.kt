package dev.tlong.biodex.ui.common

import dev.tlong.biodex.domain.SpeciesSummary

/**
 * §5.3.1's three tile states, decided away from Compose so the rules that matter can be
 * pinned in the JVM suite.
 *
 * Since D39 every caught tile draws the species' **reference picture** — the catalogue's
 * Wikimedia image — rather than the user's own photograph, which lives on the entry screen.
 * So a picture on the grid means one thing only: caught. An uncaught tile is a flat dark shape
 * on a grey field and never a picture, which is what keeps the two unconfusable at thumbnail
 * size. The states still differ in their chrome: a catch that keeps no photograph of the
 * user's own (M41) wears the accent chrome and the leaf, because that is a fact about the
 * catch worth reading off the grid, not about which picture happens to be drawn.
 */
enum class TileState {
    /** Any kingdom, not yet caught: the class silhouette on `silBg`. */
    UNCAUGHT,

    /**
     * Caught, with no photograph of the user's own (M41) — a plant from that release onward.
     * On an `accentSoft` ground with an `accent` hairline border and a leaf glyph.
     */
    CAUGHT_NO_OWN_PHOTO,

    /**
     * Caught, with the user's own photograph on the entry: animals, fungi, and every plant
     * registered before M41. The neutral card chrome it has always had.
     */
    CAUGHT_OWN_PHOTO,
}

fun tileStateFor(species: SpeciesSummary): TileState = when {
    !species.caught -> TileState.UNCAUGHT
    species.thumbPath != null -> TileState.CAUGHT_OWN_PHOTO
    else -> TileState.CAUGHT_NO_OWN_PHOTO
}

/** One picture a grid cell can try to draw, in the order [tileImageSources] returns them. */
sealed interface TileImage {
    /** The catalogue's reference picture, a URL fetched through the image cache. */
    data class Reference(val url: String) : TileImage

    /** The capture's own thumbnail (M11), a path under `filesDir`. */
    data class OwnThumbnail(val path: String) : TileImage
}

/**
 * **D39: the pictures a cell tries, in order.** A caught species leads with its reference
 * picture and keeps the user's own thumbnail as the fallback for when that picture has not
 * cached — the phone is offline, or the fetch failed — so the cell still shows *something*
 * before it gives up and draws the silhouette. An uncaught species tries nothing: the
 * reference picture is a picture of the species whether or not it has been caught, and
 * drawing it on an uncaught tile would end the silhouette-unlock mechanic the grid is for.
 *
 * Pure and ordered so the JVM suite can pin it: the composable walks the list and advances
 * on each load failure, but which pictures are eligible and which comes first is decided here.
 */
fun tileImageSources(species: SpeciesSummary): List<TileImage> {
    if (!species.caught) return emptyList()
    return listOfNotNull(
        species.imageUrl?.let { TileImage.Reference(it) },
        species.thumbPath?.let { TileImage.OwnThumbnail(it) },
    )
}

/**
 * **The rule §5.3.1 asks a test to pin.** Whether the tile wears the accent chrome depends
 * only on which state it is in — never on whether any picture was actually fetched.
 *
 * If no picture has cached and the phone is offline, the tile falls back to the
 * silhouette, but it keeps the `accentSoft` ground, the `accent` border and the glyph. The
 * chrome is what says *caught*, so a caught plant must not read as a still-missing one because
 * a network fetch failed. This is deliberately a function of the state alone: there is no
 * parameter here for whether the image loaded, because there must not be one.
 */
fun tileWearsAccentChrome(state: TileState): Boolean = state == TileState.CAUGHT_NO_OWN_PHOTO

/**
 * **The loud tick (M44/D38).** A caught species whose cell is drawing a shape rather than a
 * picture gets a filled green tick instead of the quiet outline one, so "caught, but there is
 * no photograph here" reads at thumbnail size instead of looking like a species still missing.
 *
 * Three different situations land here and the tick deliberately does not distinguish them:
 * a capture that never had a photograph of its own (M41), one whose gallery photo has been
 * deleted or had its permission withdrawn, and one whose reference image has not cached while
 * offline. What they have in common is the only thing the grid can say honestly — you have
 * this one, and the picture is not here — and the entry screen is where the difference
 * between them is explained.
 *
 * `showingSilhouette` is a fact about what the cell actually drew, which is why it is a
 * parameter rather than something derived from the state: whether a file is on disk or a fetch
 * succeeded is not knowable from a [SpeciesSummary]. That is the same reason
 * [tileWearsAccentChrome] refuses to take it — the *chrome* must never depend on a load, or a
 * caught species would demote itself to looking uncaught. The tick is the opposite case: it
 * exists precisely to mark the load having produced nothing.
 */
fun tileWearsLoudTick(state: TileState, showingSilhouette: Boolean): Boolean =
    state != TileState.UNCAUGHT && showingSilhouette

/** The glyph in the caption band; null for the two states that carry no mark. */
fun tileGlyph(state: TileState): String? =
    if (state == TileState.CAUGHT_NO_OWN_PHOTO) LEAF_GLYPH else null

/**
 * Path-neutral on purpose (§5.3). A plant typed in by name gets the same mark as one named
 * through Pl@ntNet, because the capture row does not record how it was named — a persistent
 * "identified with Pl@ntNet" mark would need a column the design declined to add (Q06).
 */
const val NO_OWN_PHOTO_MARK = "caught — no photo of your own"

private const val LEAF_GLYPH = "🍃"
