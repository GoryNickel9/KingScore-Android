package app.kingscore.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface NetworkMonitor {
    val connected: StateFlow<Boolean>
}

/** System validation is a prerequisite; Auth requests also verify backend reachability. */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val tracked = ValidatedDefaultNetwork()
    override val connected: StateFlow<Boolean> = tracked.connected

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { tracked.available(network.networkHandle) }
            override fun onLost(network: Network) { tracked.lost(network.networkHandle) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                tracked.capabilities(network.networkHandle,
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        })
    }
}

/** ConnectivityManager serializes default-network callbacks; never re-query its racing snapshot. */
internal class ValidatedDefaultNetwork {
    private var current: Long? = null
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state
    fun available(handle: Long) { current = handle; state.value = false }
    fun capabilities(handle: Long, validated: Boolean) {
        if (current == handle) state.value = validated
    }
    fun lost(handle: Long) {
        if (current == handle) { current = null; state.value = false }
    }
}
