package android.net
class Network
class NetworkCapabilities {
    companion object { const val NET_CAPABILITY_VALIDATED=1 }
    fun hasCapability(capability: Int) = true
}
class ConnectivityManager {
    companion object { val network = Network() }
    val activeNetwork: Network? get() = network
    fun getNetworkCapabilities(network: Network) = NetworkCapabilities()
    fun registerDefaultNetworkCallback(callback: NetworkCallback) {}
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {}
        open fun onLost(network: Network) {}
    }
}
