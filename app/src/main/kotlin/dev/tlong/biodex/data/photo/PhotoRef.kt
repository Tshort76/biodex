package dev.tlong.biodex.data.photo

import java.io.FileNotFoundException
import java.io.IOException

/**
 * The three states a gallery reference can be in (ARCHITECTURE.md 4.2). The distinction is
 * not cosmetic: [Revoked] is permanent and offers a re-link, [Unavailable] is transient and
 * asks the user to reconnect. Neither ever un-catches the species (M12).
 */
sealed interface PhotoRef {

    /** `openInputStream` succeeded. [uri] is what the full-size renderer should load. */
    data class Available(val uri: String) : PhotoRef

    /**
     * The grant is gone — the photo was deleted from the gallery, or Android revoked the
     * persistable permission. Permanent: show the stored thumbnail, a "full photo
     * unavailable" banner, and a Re-link button.
     */
    data object Revoked : PhotoRef

    /**
     * The grant is intact but the bytes are not here — typically a cloud-only Google Photos
     * item with the device offline. Transient: show the thumbnail and say so; retry on the
     * next view, do not offer a re-link.
     */
    data object Unavailable : PhotoRef

    /** A local copy exists (S03), so nothing about the gallery reference matters. */
    data class LocalCopy(val relativePath: String) : PhotoRef

    val isFullSizeShowable: Boolean get() = this is Available || this is LocalCopy
}

/**
 * The one place the exception-to-state mapping of 4.2 lives, kept pure so the JVM suite pins
 * it. `SecurityException` means the grant is gone; a missing file or any other I/O failure
 * with the grant intact is the cloud-only case.
 *
 * This mapping is an assertion about what Android throws, and nobody has run it on a phone
 * yet. When a device refines it, this function is the only thing that changes.
 */
fun classifyResolveFailure(cause: Throwable): PhotoRef = when (cause) {
    is SecurityException -> PhotoRef.Revoked
    is FileNotFoundException -> PhotoRef.Unavailable
    is IOException -> PhotoRef.Unavailable
    else -> PhotoRef.Unavailable
}

/**
 * Resolution as a decision, separated from the act of opening a stream: a capture with a
 * local copy never touches the gallery at all (4.2's short-circuit).
 */
fun resolvePhotoRef(
    photoUri: String,
    localCopyPath: String?,
    probe: (String) -> Throwable?,
): PhotoRef {
    if (localCopyPath != null) return PhotoRef.LocalCopy(localCopyPath)
    val failure = probe(photoUri) ?: return PhotoRef.Available(photoUri)
    return classifyResolveFailure(failure)
}
