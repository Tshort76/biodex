package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WebLinkTest {

    @Test
    fun `a typed web address is stored with a scheme, or refused`() {
        val cases = listOf(
            "https://www.inaturalist.org/taxa/48662" to "https://www.inaturalist.org/taxa/48662",
            "  inaturalist.org/taxa/48662 " to "https://inaturalist.org/taxa/48662",
            "HTTP://butterfliesofamerica.com/x.htm" to "HTTP://butterfliesofamerica.com/x.htm",
            "localhost:8080/x" to null,
            "example.com:8080/x" to "https://example.com:8080/x",
            "ftp://example.com/x" to null,
            "javascript:alert(1)" to null,
            "mailto:me@example.com" to null,
            "user@example.com" to null,
            "Danaus plexippus" to null,
            "monarch" to null,
            "" to null,
        )
        cases.forEach { (typed, stored) -> assertEquals(typed, stored, normalizedWebLink(typed)) }
    }
}
