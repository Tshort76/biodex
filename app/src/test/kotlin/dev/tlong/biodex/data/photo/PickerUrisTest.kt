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
