package dev.tlong.biodex.ui.detail

/** What Coil has told us about the hero image so far. The adapter reports it; nothing else. */
enum class ImageLoadPhase { LOADING, LOADED, FAILED }

/** Why the hero is showing a shape rather than a photograph. Drives the message under it. */
enum class SilhouetteReason {
    /**
     * M05 / DESIGN.md §5: an uncaught species is withheld. Since D52 that is the *fallback*
     * for an uncaught hero rather than its normal state — the dimmed picture is what shows
     * when there is a picture to show.
     */
    NOT_CAUGHT,

    /** The catalogue row has no `imageUrl` (user-added species before backfill, mostly). */
    NO_IMAGE,

    /** Offline and never cached — D3's graceful degradation, not an error. */
    OFFLINE,

    /** Online, tried, failed. */
    LOAD_FAILED,
}

sealed interface HeroVisual {

    /** The reference image is rendering; the credit chip belongs on top of it (M17). */
    data class Reference(val url: String) : HeroVisual

    /**
     * D52: the same reference image on an uncaught species — greyed and faded, exactly as
     * the grid tile draws it (D41). No credit chip: the picture is not being presented as
     * this species' photograph yet, and the chip would read as a reward already given.
     */
    data class DimmedReference(val url: String) : HeroVisual

    /**
     * M46: the user's own thumbnail is the hero, because the entry prefers it. No credit
     * chip — it is their photograph — and no note, because nothing is being fetched.
     */
    data class OwnPhoto(val model: String) : HeroVisual

    /** Coil is fetching. The silhouette sits underneath as the placeholder, undimmed. */
    data class LoadingReference(val url: String) : HeroVisual

    data class Silhouette(val reason: SilhouetteReason) : HeroVisual
}

/**
 * The hero's whole decision, as a pure function over four facts (ARCHITECTURE.md 6.2's
 * pattern, and the only part of this slice a JVM test can reach).
 *
 * Being caught no longer decides *whether* there is a picture — D52 — it decides how the
 * picture is drawn. An uncaught species shows the same reference image greyed and faded,
 * which is what the grid has done since D41; "present, named, but withheld" is still the
 * engine of the collection, and a grey picture becoming a coloured one is the withholding.
 * The silhouette stays as the fallback for every case with no picture to draw.
 *
 * [ownPhotoModel] is the user's own thumbnail when the entry prefers it (M46) and that file
 * is still readable; the caller blanks it once the file fails to load, and the decision falls
 * through to the reference picture exactly as if there had been no preference.
 */
fun heroVisual(
    imageUrl: String?,
    caught: Boolean,
    phase: ImageLoadPhase,
    online: Boolean,
    ownPhotoModel: String? = null,
): HeroVisual = when {
    // Only a caught species has a thumbnail of its own, and only its entry can prefer one.
    caught && ownPhotoModel != null -> HeroVisual.OwnPhoto(ownPhotoModel)
    imageUrl == null -> HeroVisual.Silhouette(uncaughtOr(caught, SilhouetteReason.NO_IMAGE))
    phase == ImageLoadPhase.LOADED ->
        if (caught) HeroVisual.Reference(imageUrl) else HeroVisual.DimmedReference(imageUrl)
    phase == ImageLoadPhase.LOADING -> HeroVisual.LoadingReference(imageUrl)
    // Failed. Offline is the ordinary field case (S02's cache missed), not a fault worth
    // an error voice; online failure is.
    online -> HeroVisual.Silhouette(uncaughtOr(caught, SilhouetteReason.LOAD_FAILED))
    else -> HeroVisual.Silhouette(uncaughtOr(caught, SilhouetteReason.OFFLINE))
}

/**
 * An uncaught hero that falls back to the silhouette says nothing about why. The species is
 * withheld either way, and "Reference photo could not be loaded" under a shape the user was
 * never going to be shown is noise about a picture they have not earned.
 */
private fun uncaughtOr(caught: Boolean, reason: SilhouetteReason): SilhouetteReason =
    if (caught) reason else SilhouetteReason.NOT_CAUGHT

/** The line under the hero. Null where a message would be noise — the silhouette says it. */
fun heroNote(visual: HeroVisual): String? = when (visual) {
    is HeroVisual.Reference -> null
    is HeroVisual.DimmedReference -> null
    is HeroVisual.OwnPhoto -> null
    is HeroVisual.LoadingReference -> "Loading reference photo…"
    is HeroVisual.Silhouette -> when (visual.reason) {
        SilhouetteReason.NOT_CAUGHT -> null
        SilhouetteReason.NO_IMAGE -> null
        SilhouetteReason.OFFLINE -> "Reference photo not cached — connect to load it"
        SilhouetteReason.LOAD_FAILED -> "Reference photo could not be loaded"
    }
}
