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
        assertTrue("it leans one way", samples.any { it > 3f })
        assertTrue("and the other", samples.any { it < -3f })
        val early = samples.take(33).maxOf { abs(it) }
        val late = samples.drop(66).maxOf { abs(it) }
        assertTrue("the last rock is smaller than the first", late < early / 3f)
    }
}
