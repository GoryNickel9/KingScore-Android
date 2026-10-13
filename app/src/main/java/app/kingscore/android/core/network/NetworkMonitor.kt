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
    private val state = MutableStateFlow(isValidated())
    override val connected: StateFlow<Boolean> = state

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = isValidated() }
            override fun onLost(network: Network) { state.value = isValidated() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                state.value = isValidated()
            }
        })
    }

    private fun isValidated(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
