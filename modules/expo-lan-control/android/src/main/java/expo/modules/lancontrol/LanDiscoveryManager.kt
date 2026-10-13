package expo.modules.lancontrol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

data class DiscoveredDevice(
  val id: String,
  val name: String,
  val host: String,
  val port: Int,
  val serviceName: String,
  val lastSeen: Long = System.currentTimeMillis(),
)

/**
 * Advertises this device as `_lancontrol._tcp` and discovers peers via Android NSD (mDNS/DNS-SD).
 * Discovery only says a service exists; trust comes from pairing (see [PairingManager]).
 */
class LanDiscoveryManager(
  context: Context,
  private val selfId: () -> String,
  private val listener: Listener,
) {
  interface Listener {
    fun onDeviceFound(device: DiscoveredDevice)
    fun onDeviceLost(id: String)
    fun onDiscoveryError(message: String)
  }

  companion object {
    const val SERVICE_TYPE = "_lancontrol._tcp"
    private const val TAG = "LanDiscovery"
  }

  private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
  private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
    .createMulticastLock("lancontrol-discovery").apply { setReferenceCounted(false) }
  private val main = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { main.post(it) }

  private var registrationListener: NsdManager.RegistrationListener? = null
  private var discoveryListener: NsdManager.DiscoveryListener? = null

  /** serviceName → device id, so `onServiceLost` (which carries no TXT record) can be mapped. */
  private val serviceToId = HashMap<String, String>()
  private val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
  private val resolveQueue = ArrayDeque<NsdServiceInfo>()
  private var resolving = false

  @Volatile var isAdvertising = false
    private set
  @Volatile var isDiscovering = false
    private set
  val multicastLockHeld: Boolean get() = multicastLock.isHeld

  // --- Advertising -------------------------------------------------------------------------

  fun advertise(port: Int, deviceName: String) = main.post {
    stopAdvertisingInternal()
    val id = selfId()
    val info = NsdServiceInfo().apply {
      serviceName = "lancontrol-${id.take(8)}"
      serviceType = SERVICE_TYPE
      this.port = port
      setAttribute("id", id)
      setAttribute("name", deviceName.take(Protocol.MAX_NAME_LENGTH))
      setAttribute("v", Protocol.VERSION.toString())
    }
    val l = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) { isAdvertising = true }
      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        isAdvertising = false
        listener.onDiscoveryError("NSD registration failed ($errorCode)")
      }
      override fun onServiceUnregistered(info: NsdServiceInfo) { isAdvertising = false }
      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { isAdvertising = false }
    }
    registrationListener = l
    try {
      nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    } catch (e: Exception) {
      registrationListener = null
      listener.onDiscoveryError("NSD registration failed: ${e.message}")
    }
  }

  fun stopAdvertising() = main.post { stopAdvertisingInternal() }

  private fun stopAdvertisingInternal() {
    registrationListener?.let { runCatching { nsd.unregisterService(it) } }
    registrationListener = null
    isAdvertising = false
  }

  // --- Discovery ---------------------------------------------------------------------------

  fun startDiscovery() = main.post {
    if (discoveryListener != null) return@post
    multicastLock.acquire()
    val l = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) { isDiscovering = true }
      override fun onDiscoveryStopped(serviceType: String) { isDiscovering = false }
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        main.post {
          isDiscovering = false
          discoveryListener = null
          listener.onDiscoveryError("NSD discovery failed to start ($errorCode)")
        }
      }
      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isDiscovering = false }
      override fun onServiceFound(info: NsdServiceInfo) { main.post { onFound(info) } }
      override fun onServiceLost(info: NsdServiceInfo) { main.post { onLost(info.serviceName) } }
    }
    discoveryListener = l
    try {
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    } catch (e: Exception) {
      discoveryListener = null
      listener.onDiscoveryError("NSD discovery failed: ${e.message}")
    }
  }

  fun stopDiscovery() = main.post {
    discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
    discoveryListener = null
    isDiscovering = false
    if (Build.VERSION.SDK_INT >= 34) {
      infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }
    infoCallbacks.clear()
    resolveQueue.clear()
    resolving = false
    serviceToId.values.toList().forEach { listener.onDeviceLost(it) }
    serviceToId.clear()
    if (multicastLock.isHeld) multicastLock.release()
  }

  /** Re-announce and re-scan, e.g. after the Wi-Fi network changed. */
  fun restartDiscovery() {
    stopDiscovery()
    startDiscovery()
  }

  private fun onFound(info: NsdServiceInfo) {
    if (info.serviceName.startsWith("lancontrol-${selfId().take(8)}")) return
    if (Build.VERSION.SDK_INT >= 34) {
      watchServiceInfo(info)
    } else {
      resolveQueue.addLast(info)
      drainResolveQueue()
    }
  }

  private fun onLost(serviceName: String) {
    if (Build.VERSION.SDK_INT >= 34) {
      infoCallbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }
    serviceToId.remove(serviceName)?.let { listener.onDeviceLost(it) }
  }

  @RequiresApi(34)
  private fun watchServiceInfo(info: NsdServiceInfo) {
    if (infoCallbacks.containsKey(info.serviceName)) return
    val cb = object : NsdManager.ServiceInfoCallback {
      override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
        main.post { infoCallbacks.remove(info.serviceName) }
      }
      override fun onServiceUpdated(updated: NsdServiceInfo) {
        val host = pickAddress(updated.hostAddresses) ?: return
        main.post { publish(updated, host) }
      }
      override fun onServiceLost() { main.post { onLost(info.serviceName) } }
      override fun onServiceInfoCallbackUnregistered() = Unit
    }
    infoCallbacks[info.serviceName] = cb
    try {
      nsd.registerServiceInfoCallback(info, mainExecutor, cb)
    } catch (e: Exception) {
      infoCallbacks.remove(info.serviceName)
      Log.w(TAG, "registerServiceInfoCallback failed", e)
    }
  }

  /** Before API 34 NsdManager can only resolve one service at a time, so resolves are queued. */
  @Suppress("DEPRECATION")
  private fun drainResolveQueue() {
    if (resolving) return
    val next = resolveQueue.removeFirstOrNull() ?: return
    resolving = true
    nsd.resolveService(next, object : NsdManager.ResolveListener {
      override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
        main.post {
          resolving = false
          if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
            main.postDelayed({ resolveQueue.addLast(info); drainResolveQueue() }, 250)
          } else {
            drainResolveQueue()
          }
        }
      }
      override fun onServiceResolved(info: NsdServiceInfo) {
        main.post {
          resolving = false
          info.host?.let { pickAddress(listOf(it)) }?.let { publish(info, it) }
          drainResolveQueue()
        }
      }
    })
  }

  private fun publish(info: NsdServiceInfo, host: String) {
    val attrs = info.attributes
    val id = attrs["id"]?.let { String(it, Charsets.UTF_8) }?.lowercase() ?: return
    if (id == selfId() || !id.matches(Regex("[0-9a-f]{64}"))) return
    val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }?.take(Protocol.MAX_NAME_LENGTH) ?: info.serviceName
    serviceToId[info.serviceName] = id
    listener.onDeviceFound(DiscoveredDevice(id, name, host, info.port, info.serviceName))
  }

  private fun pickAddress(addresses: List<InetAddress>): String? =
    (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
}
