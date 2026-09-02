package dev.tlong.biodex.media

/**
 * What the player is doing, right now, for the whole app — there is one player and one
 * playing call at a time (ARCHITECTURE.md 5.3, M06).
 *
 * The URL is carried in every non-idle state on purpose: two detail screens can be alive at
 * once in the back stack, and each has to be able to ask "is *my* call the one playing?"
 * without a second source of truth.
 */
sealed interface CallPlayback {

    data object Idle : CallPlayback

    /** Buffering the first bytes, or seeking. The row shows this rather than a dead button. */
    data class Loading(val url: String) : CallPlayback

    data class Playing(val url: String) : CallPlayback

    /** M06: "the control shows an error state rather than failing silently". */
    data class Failed(val url: String) : CallPlayback

    /** Named apart from the subclasses' `url` so the states stay plain data classes. */
    val activeUrl: String?
        get() = when (this) {
            Idle -> null
            is Loading -> url
            is Playing -> url
            is Failed -> url
        }
}

/** Everything [dev.tlong.biodex.ui.common.CallPlayerRow] needs, and nothing else. */
data class CallRowState(
    /** False when there is no call at all — the row still renders (slice 4's rule). */
    val enabled: Boolean,
    val playing: Boolean,
    val loading: Boolean,
    val failed: Boolean,
    val label: String,
)

/**
 * The whole decision the call row makes, as a pure function (the slice pattern: platform code
 * is an adapter, the state machine is a JVM test).
 *
 * Every `callUrl` in the shipped catalogue is null today because there is no Xeno-canto key,
 * so the only state a phone can currently reach is the first branch. The rest is what comes
 * alive, with no code change, the moment the pipeline fills `callUrl` in (5.4).
 */
fun callRowState(
    callUrl: String?,
    callAttribution: String?,
    playback: CallPlayback,
    online: Boolean,
): CallRowState {
    if (callUrl == null) {
        return CallRowState(
            enabled = false,
            playing = false,
            loading = false,
            failed = false,
            label = "No call available",
        )
    }
    val credit = callAttribution ?: "Xeno-canto"
    // A playback state belonging to a different species' call says nothing about this row.
    val mine = playback.takeIf { it.activeUrl == callUrl } ?: CallPlayback.Idle
    return when (mine) {
        CallPlayback.Idle -> CallRowState(
            enabled = true,
            playing = false,
            loading = false,
            failed = false,
            label = if (online) "Tap to play · $credit" else "Cached or offline · $credit",
        )

        is CallPlayback.Loading -> CallRowState(
            enabled = true,
            playing = false,
            loading = true,
            failed = false,
            label = "Loading… · $credit",
        )

        is CallPlayback.Playing -> CallRowState(
            enabled = true,
            playing = true,
            loading = false,
            failed = false,
            label = "Playing · tap to stop\n$credit",
        )

        is CallPlayback.Failed -> CallRowState(
            enabled = true,
            playing = false,
            loading = false,
            failed = true,
            label = if (online) {
                "Call could not be loaded · tap to retry"
            } else {
                "Call not cached — connect to play"
            },
        )
    }
}
