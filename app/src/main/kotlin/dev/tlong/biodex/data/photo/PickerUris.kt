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
