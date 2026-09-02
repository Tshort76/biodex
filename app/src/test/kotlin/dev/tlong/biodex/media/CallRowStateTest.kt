package dev.tlong.biodex.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call row's state machine. Nothing on the phone can reach most of these today — every
 * `callUrl` in the shipped catalogue is null for want of a Xeno-canto key — so this suite is
 * the only evidence that the player comes alive correctly when the pipeline fills them in.
 */
class CallRowStateTest {

    private val url = "https://xeno-canto.org/123456/download"
    private val other = "https://xeno-canto.org/999999/download"
    private val credit = "Xeno-canto XC123456 · CC BY-NC 4.0 · R. Smith"

    private fun row(
        callUrl: String? = url,
        attribution: String? = credit,
        playback: CallPlayback = CallPlayback.Idle,
        online: Boolean = true,
    ) = callRowState(callUrl, attribution, playback, online)

    @Test
    fun `no call url is a disabled row, never a hidden one`() {
        val state = row(callUrl = null)
        assertFalse(state.enabled)
        assertEquals("No call available", state.label)
    }

    @Test
    fun `a species with no call stays disabled even while another call is playing`() {
        val state = row(callUrl = null, playback = CallPlayback.Playing(other))
        assertFalse(state.enabled)
        assertFalse(state.playing)
        assertEquals("No call available", state.label)
    }

    @Test
    fun `a call url makes the row playable, and the credit is always shown`() {
        val state = row()
        assertTrue(state.enabled)
        assertFalse(state.playing)
        assertTrue(state.label.contains(credit))
    }

    @Test
    fun `a missing attribution degrades to the source name rather than to nothing`() {
        assertTrue(row(attribution = null).label.contains("Xeno-canto"))
    }

    @Test
    fun `playback of this url reads as playing, and of another url does not`() {
        assertTrue(row(playback = CallPlayback.Playing(url)).playing)

        val neighbour = row(playback = CallPlayback.Playing(other))
        assertFalse(neighbour.playing)
        assertTrue(neighbour.enabled)
    }

    @Test
    fun `buffering shows as loading rather than as an idle or dead button`() {
        val state = row(playback = CallPlayback.Loading(url))
        assertTrue(state.loading)
        assertFalse(state.playing)
        assertFalse(state.failed)
    }

    @Test
    fun `another species' failure never marks this row failed`() {
        assertFalse(row(playback = CallPlayback.Failed(other)).failed)
    }

    @Test
    fun `a failed call shows an error state and stays tappable for a retry (M06)`() {
        val state = row(playback = CallPlayback.Failed(url))
        assertTrue(state.failed)
        assertTrue(state.enabled)
        assertTrue(state.label.contains("retry"))
    }

    @Test
    fun `offline changes the failure message from fault to not-cached, not the state`() {
        val state = row(playback = CallPlayback.Failed(url), online = false)
        assertTrue(state.failed)
        assertTrue(state.label.contains("connect"))
    }

    @Test
    fun `activeUrl is what lets two detail screens tell whose call is playing`() {
        assertEquals(null, CallPlayback.Idle.activeUrl)
        assertEquals(url, CallPlayback.Loading(url).activeUrl)
        assertEquals(url, CallPlayback.Playing(url).activeUrl)
        assertEquals(url, CallPlayback.Failed(url).activeUrl)
    }
}
