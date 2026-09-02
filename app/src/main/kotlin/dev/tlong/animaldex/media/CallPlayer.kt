package dev.tlong.animaldex.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One call at a time, app-wide (M06). The interface exists so the detail screen depends on a
 * state flow and two verbs rather than on ExoPlayer; [ExoCallPlayer] is the only implementation
 * and is deliberately thin — every decision worth testing is in `callRowState`.
 */
interface CallPlayer {

    val playback: StateFlow<CallPlayback>

    /**
     * M06's whole gesture: tapping a playing call stops it, tapping anything else starts it.
     * Stop, not pause — a ten-second bird call has no meaningful resume.
     */
    fun toggle(url: String)

    fun stop()
}

/**
 * ExoPlayer over the cache-backed [dataSourceFactory] built in `AppContainer`, so the first
 * play streams and writes through to `SimpleCache` and later plays are local (D4/S02).
 *
 * **Main thread only.** ExoPlayer asserts on its creation thread for every call, and the app
 * only ever reaches this from a composable or a ViewModel, both of which are on it.
 */
@OptIn(UnstableApi::class)
class ExoCallPlayer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
) : CallPlayer {

    private val _playback = MutableStateFlow<CallPlayback>(CallPlayback.Idle)
    override val playback: StateFlow<CallPlayback> = _playback.asStateFlow()

    private var player: ExoPlayer? = null

    /** Built on first play, not at container construction: most sessions never play a call. */
    private fun requirePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    val url = _playback.value.activeUrl ?: return
                    when (state) {
                        Player.STATE_BUFFERING -> _playback.value = CallPlayback.Loading(url)
                        Player.STATE_READY ->
                            if (playWhenReady) _playback.value = CallPlayback.Playing(url)

                        Player.STATE_ENDED -> {
                            _playback.value = CallPlayback.Idle
                            stop()
                        }

                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "call playback failed", error)
                    val url = _playback.value.activeUrl
                    _playback.value = if (url == null) {
                        CallPlayback.Idle
                    } else {
                        CallPlayback.Failed(url)
                    }
                }
            })
            player = this
        }

    override fun toggle(url: String) {
        val current = _playback.value
        if (current is CallPlayback.Playing && current.url == url) {
            stop()
            return
        }
        val exo = requirePlayer()
        // Loading first: the listener reads the target URL back off this state, and a slow
        // network would otherwise leave the row idle while bytes are on the way.
        _playback.value = CallPlayback.Loading(url)
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun stop() {
        player?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        _playback.value = CallPlayback.Idle
    }

    /** Process-lifetime object; only `AppContainer` would ever call this. */
    fun release() {
        player?.release()
        player = null
        _playback.value = CallPlayback.Idle
    }

    private companion object {
        const val TAG = "ExoCallPlayer"
    }
}
