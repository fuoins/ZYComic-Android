package android.net

class Network(private val id: String = "net") {
    override fun toString() = id
}

class NetworkCapabilities {
    fun hasCapability(capability: Int) = true
    fun hasTransport(transport: Int) = false
    companion object {
        const val NET_CAPABILITY_INTERNET = 12
        const val NET_CAPABILITY_NOT_VPN = 15
        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_VPN = 4
    }
}

class ConnectivityManager {
    var activeNetwork: Network? = null
    var allNetworks: Array<Network> = emptyArray()
    fun getNetworkCapabilities(network: Network) = NetworkCapabilities()
}
