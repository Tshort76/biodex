package dev.tlong.biodex.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Is there a network" — ARCHITECTURE.md 5.3 is explicit that this is a probe, not reachability
 * engineering. Its only job in this slice is to let the hero say "not cached — connect to load
 * it" instead of "could not be loaded" when the phone is in airplane mode.
 *
 * Defaults to online: a wrong "offline" would slander a genuine load failure, while a wrong
 * "online" degrades to the ordinary error message.
 */
interface NetworkMonitor {
    val online: StateFlow<Boolean>
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(probe())
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        // The callback keeps a screen already on-screen honest when airplane mode is toggled;
        // without it the hero would keep the message it was born with.
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                }

                override fun onLost(network: Network) {
                    _online.value = probe()
                }
            })
        }
    }

    private fun probe(): Boolean {
        val active = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
