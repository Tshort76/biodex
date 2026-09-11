package dev.tlong.biodex.ui.common

import dev.tlong.biodex.domain.SpeciesSummary

/**
 * §5.3.1's three tile states, decided away from Compose so the rules that matter can be
 * pinned in the JVM suite.
 *
 * Since D39 every tile draws the species' **reference picture** — the catalogue's Wikimedia
 * image — and since D41 that includes the uncaught ones. What separates caught from uncaught
 * is no longer whether there is a picture but how it is drawn: a caught tile is the picture in
 * full colour with a tick, an uncaught one is the same picture **dimmed** — greyed and faded
 * on the grey field — the way a locked entry looks in any collecting game. The user's own
 * photograph lives on the entry screen. The states still differ in their chrome: a catch
 * that keeps no photograph of the user's own (M41) wears the accent chrome and the leaf,
 * because that is a fact about the catch worth reading off the grid, not about which picture
 * happens to be drawn.
 */
enum class TileState {
    /** Any kingdom, not yet caught: the reference picture dimmed on `silBg`, else the silhouette. */
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
 * before it gives up and draws the silhouette. An uncaught species (D41) tries the reference
 * picture alone — there is no thumbnail of a species never caught — and is drawn dimmed by
 * [tileDrawsDimmed]; it too falls back to the silhouette.
 *
 * **M46 / D44 turns the order round.** A caught species whose entry prefers the user's own
 * photograph leads with the thumbnail and keeps the reference picture as its fallback — the
 * same two pictures, the other way up, so a thumbnail whose file has gone still shows the
 * species rather than a silhouette.
 *
 * Pure and ordered so the JVM suite can pin it: the composable walks the list and advances
 * on each load failure, but which pictures are eligible and which comes first is decided here.
 */
fun tileImageSources(species: SpeciesSummary): List<TileImage> {
    val reference = species.imageUrl?.let { TileImage.Reference(gridThumbnailUrl(it)) }
    val own = species.thumbPath?.takeIf { species.caught }?.let { TileImage.OwnThumbnail(it) }
    return if (species.caught && species.preferOwnPhoto) {
        listOfNotNull(own, reference)
    } else {
        listOfNotNull(reference, own)
    }
}

/**
 * **The grid fetches a small rendition, not the catalogue's picture.** `imageUrl` is what the
 * pipeline found — usually the full-size original (up to 9 MB) or a 3840px thumbnail — which
 * is fine for one hero and hopeless for 230 tiles at once: on the first launch after D41 most
 * of the grid timed out and sat on silhouettes. Wikimedia Commons renders any file at a set of
 * widths on demand, and 960px is the smallest it accepts for these files (640px is refused);
 * at ~100–200 KB each the whole catalogue is ~30 MB, inside the image cache's 250 MB.
 *
 * Two shapes are rewritten, both under `upload.wikimedia.org/wikipedia/commons/`: an original
 * `…/commons/h/hh/Name.jpg` becomes `…/commons/thumb/h/hh/Name.jpg/960px-Name.jpg`, and a
 * thumbnail's `NNNNpx-` prefix is swapped for `960px-`. Anything else — the one `wikipedia/en`
 * picture, a user-added species' image from elsewhere — is returned unchanged.
 */
fun gridThumbnailUrl(imageUrl: String): String {
    val prefix = "https://upload.wikimedia.org/wikipedia/commons/"
    if (!imageUrl.startsWith(prefix)) return imageUrl
    val path = imageUrl.removePrefix(prefix)
    if (path.startsWith("thumb/")) {
        return prefix + path.replace(Regex("""/\d+px-([^/]+)$"""), "/${GRID_THUMB_WIDTH}px-$1")
    }
    val name = path.substringAfterLast('/')
    return "${prefix}thumb/$path/${GRID_THUMB_WIDTH}px-$name"
}

private const val GRID_THUMB_WIDTH = 960

/**
 * **D41: an uncaught tile's picture is dimmed, a caught one's is not.** This is the whole
 * distinction now that both draw the same picture, so it is a function of the state alone,
 * never of the load: the dimming is applied to whatever the cell draws, picture or
 * silhouette, and a caught tile is never dimmed for any reason.
 */
fun tileDrawsDimmed(state: TileState): Boolean = state == TileState.UNCAUGHT

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

/**
 * **Where the name sits (D46).** The tile is one picture with the name overlaid at its foot,
 * on a scrim — but a cell that ended up drawing a silhouette has no photograph to overlay,
 * and a dark band across a pale silhouette ground reads as damage. So the name falls back to
 * the tile's own surface exactly when the picture did.
 *
 * Like [tileWearsLoudTick] this takes the fact rather than deriving it: whether a fetch
 * succeeded or a thumbnail is still on disk is not knowable from a [SpeciesSummary].
 */
fun tileLabelOnPicture(showingSilhouette: Boolean): Boolean = !showingSilhouette

/** The glyph in the label; null for the two states that carry no mark. */
fun tileGlyph(state: TileState): String? =
    if (state == TileState.CAUGHT_NO_OWN_PHOTO) LEAF_GLYPH else null

/**
 * Path-neutral on purpose (§5.3). A plant typed in by name gets the same mark as one named
 * through Pl@ntNet, because the capture row does not record how it was named — a persistent
 * "identified with Pl@ntNet" mark would need a column the design declined to add (Q06).
 */
const val NO_OWN_PHOTO_MARK = "caught — no photo of your own"

private const val LEAF_GLYPH = "🍃"
