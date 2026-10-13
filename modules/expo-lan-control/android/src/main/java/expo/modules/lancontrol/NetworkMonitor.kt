package expo.modules.lancontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/** Tracks the Wi-Fi network so discovery and the server can be restarted when it changes. */
class NetworkMonitor(context: Context, private val listener: Listener) {
  interface Listener {
    fun onWifiAvailable()
    fun onWifiLost()
  }

  private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  @Volatile private var wifi: Network? = null
  @Volatile private var linkProperties: LinkProperties? = null
  private var registered = false

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      val changed = wifi != network
      wifi = network
      linkProperties = cm.getLinkProperties(network)
      if (changed) listener.onWifiAvailable()
    }

    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
      if (network == wifi) linkProperties = lp
    }

    override fun onLost(network: Network) {
      if (network == wifi) {
        wifi = null
        linkProperties = null
        listener.onWifiLost()
      }
    }
  }

  val isWifiConnected: Boolean get() = wifi != null

  /** This device's IPv4 address on Wi-Fi, used in the pairing QR code. */
  val wifiIpv4: String?
    get() = linkProperties?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress

  @Synchronized
  fun start() {
    if (registered) return
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    cm.registerNetworkCallback(request, callback)
    registered = true
  }

  @Synchronized
  fun stop() {
    if (!registered) return
    runCatching { cm.unregisterNetworkCallback(callback) }
    registered = false
  }
}
