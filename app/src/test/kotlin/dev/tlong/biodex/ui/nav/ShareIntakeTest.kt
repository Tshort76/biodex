package dev.tlong.biodex.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M45's parsing rule. The cases are the shapes real senders actually produce, which is why
 * several of them are about *refusing* text: a search box seeded with a paragraph is worse
 * than an empty one, because the user has to clear it before they can type.
 */
class ShareIntakeTest {

    private val send = "android.intent.action.SEND"

    @Test
    fun `a shared photo arrives as an attachment`() {
        val intake = shareIntakeFrom(send, streamUri = "content://media/1", text = null)

        assertEquals("content://media/1", intake?.photoUri)
        assertNull(intake?.query)
    }

    @Test
    fun `a shared name arrives as a search`() {
        val intake = shareIntakeFrom(send, streamUri = null, text = "House Finch")

        assertEquals("House Finch", intake?.query)
        assertNull(intake?.photoUri)
    }

    @Test
    fun `a sender offering both gives both`() {
        // The best case, and the reason this returns a pair: the photo attaches and the name
        // fills the search box, so registering is one tap.
        val intake = shareIntakeFrom(send, streamUri = "content://media/1", text = "Steller's Jay")

        assertEquals("content://media/1", intake?.photoUri)
        assertEquals("Steller's Jay", intake?.query)
    }

    @Test
    fun `a name with a link under it keeps only the name`() {
        val intake = shareIntakeFrom(
            send,
            streamUri = null,
            text = "Oak Titmouse\nhttps://ebird.org/species/oaktit",
        )

        assertEquals("Oak Titmouse", intake?.query)
    }

    @Test
    fun `a bare link is not a name`() {
        assertNull(shareIntakeFrom(send, null, "https://ebird.org/species/oaktit"))
        assertNull(shareIntakeFrom(send, null, "www.allaboutbirds.org"))
    }

    @Test
    fun `a paragraph is not a name`() {
        val paragraph = "The California Thrasher is a large thrasher of the chaparral, " +
            "with a long decurved bill it uses to sweep leaf litter aside."

        assertNull(shareIntakeFrom(send, null, paragraph))
    }

    @Test
    fun `quotes copied along with the name are dropped`() {
        assertEquals("Lesser Goldfinch", shareIntakeFrom(send, null, "\"Lesser Goldfinch\"")?.query)
    }

    @Test
    fun `nothing usable is nothing at all`() {
        assertNull(shareIntakeFrom(send, streamUri = null, text = null))
        assertNull(shareIntakeFrom(send, streamUri = "", text = "   "))
    }

    @Test
    fun `only ACTION_SEND counts`() {
        // The launcher's own intent must never be read as a share, or every cold start would
        // open Register.
        assertNull(shareIntakeFrom("android.intent.action.MAIN", "content://media/1", "House Finch"))
        assertNull(shareIntakeFrom(null, "content://media/1", null))
    }
}
