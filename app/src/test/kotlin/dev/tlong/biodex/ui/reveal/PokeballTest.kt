package dev.tlong.biodex.ui.reveal

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D62. The rock is the one piece of the ball with arithmetic in it, so its shape is pinned. */
class PokeballTest {

    @Test
    fun `the ball is upright before the rock and after it`() {
        assertEquals(0f, wobbleAngle(0f), 0f)
        assertEquals(0f, wobbleAngle(1f), 0f)
    }

    @Test
    fun `the rock leans both ways and dies away`() {
        val samples = (1..99).map { wobbleAngle(it / 100f) }
        assertTrue("it leans one way", samples.any { it > 2f })
        assertTrue("and the other", samples.any { it < -2f })
        val early = samples.take(33).maxOf { abs(it) }
        val late = samples.drop(66).maxOf { abs(it) }
        assertTrue("the last rock is smaller than the first", late < early / 3f)
    }

    @Test
    fun `the rainbow spans the upper half of the sweep only (D66)`() {
        val red = androidx.compose.ui.graphics.Color.Red
        val green = androidx.compose.ui.graphics.Color.Green
        val blue = androidx.compose.ui.graphics.Color.Blue
        val stops = rainbowStops(listOf(red, green, blue))
        assertEquals(listOf(0f, 0.5f, 0.5f, 0.75f, 1f), stops.map { it.first })
        assertEquals("the hidden lower half wears the first colour", red, stops[0].second)
        assertEquals("the left horizon starts the arc", red, stops[2].second)
        assertEquals("the right horizon ends it", blue, stops.last().second)
    }
}
