package dev.tlong.biodex.data.photo

/**
 * D63. The system photo picker hands out `content://media/picker/<user>/<provider>/media/<id>`
 * and strips the GPS from what that URI opens (R3, D57). For a photo that lives on this phone,
 * `<id>` is the media store's own `_ID` — confirmed on the owner's phone by querying
 * `content://media/external/images/media/<id>` for a stored picker id and getting the same
 * file back — so the unredacted original is one `setRequireOriginal` away, provided the app
 * holds the photo-library permission. A cloud-only item comes from a different provider
 * authority and maps to nothing here.
 *
 * Pure, so the JVM suite pins the shape.
 */
fun mediaStoreIdFromPickerUri(uri: String): Long? {
    val match = PICKER_URI.matchEntire(uri) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private val PICKER_URI =
    Regex("""content://media/picker(?:_get_content)?/\d+/com\.android\.providers\.media\.photopicker/media/(\d+)""")

/**
 * D83. The media-store image a photo shared in from another app stands for, when its URI
 * says so: a media-store URI itself, a local picker URI, or a Google Photos provider URI,
 * which carries the media-store URI inside it, URL-encoded. Null for anything else — the
 * caller then looks the file up by name.
 */
fun mediaStoreUriInShare(uri: String): String? {
    val decoded = try {
        java.net.URLDecoder.decode(uri, "UTF-8")
    } catch (_: IllegalArgumentException) {
        uri
    }
    val id = MEDIA_URI.find(decoded)?.groupValues?.get(1)?.toLongOrNull() ?: mediaStoreIdFromPickerUri(uri)
    return id?.let { "$MEDIA_IMAGES/$it" }
}

/** The `_ID` of a `content://media/external/images/media/<id>` URI; null for any other. */
fun mediaStoreIdFromMediaUri(uri: String): Long? =
    MEDIA_URI.matchEntire(uri)?.groupValues?.get(1)?.toLongOrNull()

private const val MEDIA_IMAGES = "content://media/external/images/media"

private val MEDIA_URI = Regex("""content://media/external(?:_primary)?/images/media/(\d+)""")
