package dev.tlong.biodex.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D63. The picker id is the media-store id only for the local provider; nothing else maps. */
class PickerUrisTest {

    @Test
    fun `a local picker URI yields its media-store id`() {
        assertEquals(
            1000046677L,
            mediaStoreIdFromPickerUri(
                "content://media/picker/0/com.android.providers.media.photopicker/media/1000046677",
            ),
        )
        assertEquals(
            "the GET_CONTENT flavour is the same picker",
            42L,
            mediaStoreIdFromPickerUri(
                "content://media/picker_get_content/0/com.android.providers.media.photopicker/media/42",
            ),
        )
    }

    @Test
    fun `a shared URI names its gallery image when it carries one (D83)`() {
        val media = "content://media/external/images/media/1000047103"
        mapOf(
            media to media,
            "content://com.google.android.apps.photos.contentprovider/-1/1/content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F1000047103/ORIGINAL/NONE/image%2Fjpeg/123" to media,
            "content://media/picker/0/com.android.providers.media.photopicker/media/1000047103" to media,
            "content://com.google.android.apps.photos.contentprovider/0/1/mediakey%3A%2Flocal%253Aabc/ORIGINAL/NONE/1" to null,
        ).forEach { (shared, expected) -> assertEquals(shared, expected, mediaStoreUriInShare(shared)) }
    }

    @Test
    fun `cloud items, documents, camera files and media-store rows map to nothing`() {
        listOf(
            "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/abc123",
            "content://com.android.providers.media.documents/document/image:1000046677",
            "content://dev.tlong.biodex.files/capture/1.jpg",
            "content://media/external/images/media/1000046677",
            "",
        ).forEach { assertNull(it, mediaStoreIdFromPickerUri(it)) }
    }
}
