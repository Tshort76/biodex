package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Kingdom

/**
 * M40/D26's decisions, as pure functions — everything about the in-app camera that is a
 * *choice* rather than a call into Android.
 *
 * **The camera writes to app cache and the photo is promoted at registration.** The
 * alternative, writing straight into the gallery through a `MediaStore` `EXTRA_OUTPUT`, has
 * one fatal property for this app: a plant's photograph is not kept (M41), so a shot the user
 * took only to identify a plant would land in their gallery and the app would then have to
 * delete it — which on API 29+ can prompt them. Capturing to cache means a plant's photo never
 * touches the gallery at all, which is the property the design asks for, and it means one
 * camera path rather than one per kingdom.
 *
 * **No `CAMERA` permission is declared, and that is deliberate.** Verified against the
 * `MediaStore.ACTION_IMAGE_CAPTURE` reference: an app that *declares* `CAMERA` without holding
 * it gets a `SecurityException` from this intent on API 23+, while an app that does not declare
 * it may fire the intent freely — the system camera app holds the permission, not this one. So
 * the manifest stays at `INTERNET` + `ACCESS_NETWORK_STATE` and there is no runtime prompt.
 */

/** Where a camera shot lands before anything decides whether it is kept. */
const val CAMERA_CACHE_DIR = "capture"

fun cameraCaptureFileName(id: String): String = "$id.jpg"

fun cameraCacheRelativePath(id: String): String = "$CAMERA_CACHE_DIR/${cameraCaptureFileName(id)}"

/**
 * Which kingdoms keep the user's own photograph (M41).
 *
 * A plant does not: its photo exists to identify it, and its tile is the species' reference
 * image instead (D25). Animals and fungi do, exactly as today — the user wanted pictures of
 * their mushrooms, and an animal was never in question.
 */
fun keepsOwnPhoto(kingdom: Kingdom): Boolean = kingdom != Kingdom.PLANT

/**
 * Whether a camera shot needs promoting into the gallery before registration.
 *
 * Only a *cache* photo can be promoted, and only for a kingdom that keeps photos. A gallery
 * photo the user picked is already where it belongs, and a plant's is about to be deleted.
 */
fun shouldPromoteToGallery(source: PhotoSourceKind, kingdom: Kingdom): Boolean =
    source == PhotoSourceKind.CAMERA_CACHE && keepsOwnPhoto(kingdom)

/**
 * Whether the cache file should be swept once the screen is done with it — which is *always*
 * for a camera shot, whether it was registered, discarded or promoted. The cache directory is
 * also swept on app start, so an abandoned Register screen leaves nothing behind either.
 */
fun shouldDeleteCacheFile(source: PhotoSourceKind): Boolean =
    source == PhotoSourceKind.CAMERA_CACHE

/**
 * Where the attached photo came from. It matters at exactly two moments — promotion and
 * cleanup — and at neither of them can the URI string be trusted to say: a `FileProvider` URI
 * and a picker URI are both `content://`.
 */
enum class PhotoSourceKind { GALLERY_PICKER, CAMERA_CACHE }
