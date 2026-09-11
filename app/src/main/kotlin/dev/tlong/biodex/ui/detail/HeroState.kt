package dev.tlong.biodex.ui.detail

/** What Coil has told us about the hero image so far. The adapter reports it; nothing else. */
enum class ImageLoadPhase { LOADING, LOADED, FAILED }

/** Why the hero is showing a shape rather than a photograph. Drives the message under it. */
enum class SilhouetteReason {
    /** M05 / DESIGN.md §5: an uncaught species is withheld — silhouette, deliberately. */
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
 * The caught rule is a product rule, not a loading optimisation: M05 says an uncaught detail
 * screen shows the silhouette, and DESIGN.md §5 explains why — "present, named, but withheld"
 * is the engine of the collection. So an uncaught species never requests the image at all.
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
    !caught -> HeroVisual.Silhouette(SilhouetteReason.NOT_CAUGHT)
    ownPhotoModel != null -> HeroVisual.OwnPhoto(ownPhotoModel)
    imageUrl == null -> HeroVisual.Silhouette(SilhouetteReason.NO_IMAGE)
    phase == ImageLoadPhase.LOADED -> HeroVisual.Reference(imageUrl)
    phase == ImageLoadPhase.LOADING -> HeroVisual.LoadingReference(imageUrl)
    // Failed. Offline is the ordinary field case (S02's cache missed), not a fault worth
    // an error voice; online failure is.
    online -> HeroVisual.Silhouette(SilhouetteReason.LOAD_FAILED)
    else -> HeroVisual.Silhouette(SilhouetteReason.OFFLINE)
}

/** The line under the hero. Null where a message would be noise — the silhouette says it. */
fun heroNote(visual: HeroVisual): String? = when (visual) {
    is HeroVisual.Reference -> null
    is HeroVisual.OwnPhoto -> null
    is HeroVisual.LoadingReference -> "Loading reference photo…"
    is HeroVisual.Silhouette -> when (visual.reason) {
        SilhouetteReason.NOT_CAUGHT -> null
        SilhouetteReason.NO_IMAGE -> null
        SilhouetteReason.OFFLINE -> "Reference photo not cached — connect to load it"
        SilhouetteReason.LOAD_FAILED -> "Reference photo could not be loaded"
    }
}
