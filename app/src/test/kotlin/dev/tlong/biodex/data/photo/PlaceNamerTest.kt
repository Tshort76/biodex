package dev.tlong.biodex.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D56: what a geocoder answer is trimmed to before it becomes a capture's label. */
class PlaceNamerTest {

    @Test
    fun `locality and region when both are known`() {
        assertEquals("Point Reyes Station, California", shortPlaceName("Point Reyes Station", "California", "US"))
    }

    @Test
    fun `whichever of the two is known on its own`() {
        assertEquals("California", shortPlaceName(null, "California", "US"))
        assertEquals("Olema", shortPlaceName("Olema", "  ", "US"))
    }

    @Test
    fun `the country only when nothing finer came back, and null when nothing did`() {
        assertEquals("US", shortPlaceName(null, null, "US"))
        assertNull(shortPlaceName("", null, ""))
        assertNull(shortPlaceName(null, null, null))
    }
}
