package expo.modules.lancontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

typealias EventListener = (name: String, payload: Map<String, Any?>) -> Unit

/**
 * Process-wide owner of the LAN control stack. Lives independently of the JS runtime so the
 * foreground service can keep receiving commands when no React Native UI exists.
 */
class LanControlCore private constructor(val context: Context) :
  LanDiscoveryManager.Listener, NetworkMonitor.Listener {

  companion object {
    const val HEARTBEAT_INTERVAL_MS = 10_000L
    const val PING_TIMEOUT_MS = 4_000
    /** Long enough for a remote "ask" prompt (30 s) plus the wake verification. */
    const val COMMAND_TIMEOUT_MS = 45_000
    const val STALE_DISCOVERY_MS = 30_000L
    const val BACKGROUND_IDLE_CLOSE_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30_000L
    /** Errors meaning the peer could not be reached at all (vs. reached but refused). */
    private val UNREACHABLE_ERRORS = setOf(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT)

    @Volatile private var instance: LanControlCore? = null

    fun get(context: Context): LanControlCore =
      instance ?: synchronized(this) { instance ?: LanControlCore(context.applicationContext).also { instance = it } }
  }

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val settings = LanSettings(context)
  val identity = DeviceIdentity()
  val peers = PeerStore(File(context.filesDir, "lancontrol-peers.json"))
  val history = CommandHistory(File(context.filesDir, "lancontrol-history.json"))
  val notifications = Notifications(context).also { it.ensureChannels() }
  val torch = TorchController(context)
  val vibration = VibrationController(context)
  val sound = FindSoundController(context, notifications)
  val status = StatusProvider(context, torch, vibration, sound)
  val wake = ScreenWakeController(context, notifications)
  val prompts = PromptManager(this)
  val pairing = PairingManager(this)
  val server = CommandServer(this)
  val client = CommandClient(this)
  val executor = CommandExecutor(this)
  val discovery = LanDiscoveryManager(context, { identity.fingerprint }, this)
  val network = NetworkMonitor(context, this)

  private val listeners = CopyOnWriteArraySet<EventListener>()
  private val discovered = ConcurrentHashMap<String, DiscoveredDevice>()
  private val lostAt = ConcurrentHashMap<String, Long>()
  private val reachability = ConcurrentHashMap<String, Reachability>()

  data class Reachability(
    val reachable: Boolean,
    /** False when the peer answered but refused our certificate (it forgot us). */
    val trusted: Boolean,
    val lastSeen: Long?,
    val failures: Int,
    val nextAttemptAt: Long,
  )

  @Volatile var receiverRunning = false
    private set
  @Volatile private var uiActive = false
  private var heartbeatJob: Job? = null
  private var idleCloseJob: Job? = null

  init {
    network.start()
  }

  // --- Events --------------------------------------------------------------------------------

  fun addListener(l: EventListener) = listeners.add(l)
  fun removeListener(l: EventListener) = listeners.remove(l)

  fun emit(name: String, payload: Map<String, Any?>) {
    listeners.forEach { runCatching { it(name, payload) } }
  }

  // --- Receiver (this phone being controlled) ----------------------------------------------

  /** Called once the JS app starts: resume the receiver if the user left it enabled. */
  fun onAppStarted() {
    if (settings.receiverEnabled && !receiverRunning) {
      runCatching { DeviceService.start(context) }
    }
  }

  fun setReceiverEnabled(enabled: Boolean) {
    settings.receiverEnabled = enabled
    if (enabled) DeviceService.start(context) else DeviceService.stop(context)
  }

  /** Runs inside [DeviceService]. Returns the server port. */
  @Synchronized
  fun startReceiver(): Int {
    val port = server.start()
    if (network.isWifiConnected) discovery.advertise(port, settings.deviceName)
    receiverRunning = true
    emitServiceState()
    return port
  }

  @Synchronized
  fun stopReceiver() {
    discovery.stopAdvertising()
    pairing.closeSession()
    server.stop()
    sound.stopFromAnyThread()
    receiverRunning = false
    emitServiceState()
  }

  fun setDeviceName(name: String) {
    settings.deviceName = name
    server.port?.let { if (receiverRunning && network.isWifiConnected) discovery.advertise(it, settings.deviceName) }
  }

  fun serviceState(): Map<String, Any?> = mapOf(
    "receiverEnabled" to settings.receiverEnabled,
    "receiverRunning" to receiverRunning,
    "startOnBoot" to settings.startOnBoot,
    "port" to server.port,
    "advertising" to discovery.isAdvertising,
  )

  private fun emitServiceState() = emit("onServiceStateChanged", serviceState())

  // --- Discovery and reachability (this phone as controller) ---------------------------------

  /** The UI is in the foreground: discover peers and heartbeat paired ones. */
  fun setUiActive(active: Boolean) {
    uiActive = active
    if (active) {
      idleCloseJob?.cancel()
      discovery.startDiscovery()
      startHeartbeat()
    } else {
      discovery.stopDiscovery()
      heartbeatJob?.cancel()
      heartbeatJob = null
      idleCloseJob?.cancel()
      idleCloseJob = scope.launch {
        delay(BACKGROUND_IDLE_CLOSE_MS)
        if (!uiActive) client.closeAll()
      }
    }
  }

  fun discoveredDevice(id: String): DiscoveredDevice? = discovered[id]

  fun discoveredDevices(): List<Map<String, Any?>> = discovered.values.map { it.toMap() }

  fun addressOf(peerId: String): Pair<String, Int>? {
    discovered[peerId]?.let { return it.host to it.port }
    val p = peers.get(peerId) ?: return null
    val host = p.lastHost ?: return null
    val port = p.lastPort ?: return null
    return host to port
  }

  private fun DiscoveredDevice.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "host" to host,
    "port" to port,
    "paired" to peers.contains(id),
    "lastSeen" to lastSeen.toDouble(),
  )

  override fun onDeviceFound(device: DiscoveredDevice) {
    lostAt.remove(device.id)
    discovered[device.id] = device
    if (peers.contains(device.id)) {
      // Only the address is persisted here; lastSeen is kept in memory to avoid constant writes.
      peers.update(device.id) { it.copy(lastHost = device.host, lastPort = device.port) }
      client.invalidate(device.id, device.host, device.port)
      if (uiActive) scope.launch { heartbeat(device.id, force = true) }
    }
    emit("onDeviceFound", device.toMap())
  }

  override fun onDeviceLost(id: String) {
    // NSD loss is flappy; only drop the entry if it stays gone.
    val t = System.currentTimeMillis()
    lostAt[id] = t
    scope.launch {
      delay(STALE_DISCOVERY_MS)
      if (lostAt.remove(id, t)) {
        discovered.remove(id)
        emit("onDeviceLost", mapOf("id" to id))
      }
    }
  }

  override fun onDiscoveryError(message: String) {
    emit("onDiscoveryError", mapOf("message" to message))
  }

  override fun onWifiAvailable() {
    if (receiverRunning) server.port?.let { discovery.advertise(it, settings.deviceName) }
    if (uiActive) discovery.restartDiscovery()
    emitServiceState()
  }

  override fun onWifiLost() {
    discovery.stopAdvertising()
    client.closeAll()
    discovered.keys.toList().forEach { emit("onDeviceLost", mapOf("id" to it)) }
    discovered.clear()
    lostAt.clear()
    reachability.keys.toList().forEach { markReachable(it, false, hard = true) }
    emitServiceState()
  }

  private fun startHeartbeat() {
    if (heartbeatJob?.isActive == true) return
    heartbeatJob = scope.launch {
      while (isActive) {
        peers.all().forEach { p -> launch { heartbeat(p.id, force = false) } }
        delay(HEARTBEAT_INTERVAL_MS)
      }
    }
  }

  private suspend fun heartbeat(peerId: String, force: Boolean) {
    val r = reachability[peerId]
    if (!force && r != null && System.currentTimeMillis() < r.nextAttemptAt) return
    if (addressOf(peerId) == null) return
    val resp = client.send(peerId, Command.Ping, PING_TIMEOUT_MS)
    markReachable(peerId, resp.error !in UNREACHABLE_ERRORS, trusted = resp.error != ErrorCode.HANDSHAKE_FAILED)
  }

  fun markReachable(peerId: String, ok: Boolean, trusted: Boolean = true, hard: Boolean = false) {
    val now = System.currentTimeMillis()
    val prev = reachability[peerId]
    val next = if (ok) {
      Reachability(true, trusted, now, 0, 0)
    } else {
      val failures = (prev?.failures ?: 0) + 1
      // One missed ping is tolerated; after two the peer is shown as unreachable.
      val stillReachable = !hard && prev?.reachable == true && failures < 2
      val backoff = if (failures < 2) 0L else minOf(MAX_BACKOFF_MS, 5_000L shl (failures - 2).coerceAtMost(4))
      Reachability(stillReachable, prev?.trusted ?: true, prev?.lastSeen, failures, now + backoff)
    }
    reachability[peerId] = next
    if (prev == null || prev.reachable != next.reachable || prev.trusted != next.trusted || ok) {
      emit(
        "onReachabilityChanged",
        mapOf(
          "id" to peerId,
          "reachable" to next.reachable,
          "trusted" to next.trusted,
          "lastSeen" to next.lastSeen?.toDouble(),
        ),
      )
    }
  }

  /**
   * The peer sent us something. That updates "last seen" but not reachability: reachability means
   * *we* can reach *its* receiver, which may be switched off even while it controls this phone.
   */
  fun onPeerContact(peerId: String, remoteHost: String?) {
    val now = System.currentTimeMillis()
    val prev = reachability[peerId]
    reachability[peerId] = prev?.copy(lastSeen = now) ?: Reachability(false, true, now, 0, 0)
    emit(
      "onReachabilityChanged",
      mapOf("id" to peerId, "reachable" to (prev?.reachable ?: false), "trusted" to (prev?.trusted ?: true), "lastSeen" to now.toDouble()),
    )
    if (remoteHost != null) peers.update(peerId) { if (it.lastHost == remoteHost) it else it.copy(lastHost = remoteHost) }
  }

  // --- Paired devices ------------------------------------------------------------------------

  fun pairedDevices(): List<Map<String, Any?>> = peers.all().map { p ->
    val r = reachability[p.id]
    mapOf(
      "id" to p.id,
      "name" to p.name,
      "pairedAt" to p.pairedAt.toDouble(),
      "policies" to p.policies.mapValues { it.value.wire },
      "capabilities" to p.capabilities,
      "lastHost" to p.lastHost,
      "lastPort" to p.lastPort,
      "lastSeen" to (r?.lastSeen ?: p.lastSeen)?.toDouble(),
      "reachable" to (r?.reachable ?: false),
      "trusted" to (r?.trusted ?: true),
      "visible" to discovered.containsKey(p.id),
    )
  }

  fun setPolicy(peerId: String, capability: String, policy: Policy) {
    if (capability !in Capability.ALL) throw ProtocolException(ErrorCode.INVALID, "Unknown capability $capability")
    peers.update(peerId) { it.copy(policies = it.policies + (capability to policy)) }
      ?: throw ProtocolException(ErrorCode.UNPAIRED, "Unknown device")
    emit("onPeersChanged", emptyMap())
  }

  suspend fun forget(peerId: String) {
    val peer = peers.get(peerId) ?: return
    // Best effort: tell the other phone to drop us too. Ignore failures (it may be offline).
    client.send(peerId, Command.Unpair, PING_TIMEOUT_MS)
    client.close(peerId)
    peers.remove(peerId)
    reachability.remove(peerId)
    recordEvent("UNPAIR", peer.id, peer.name, true, null, "Forgotten on this phone")
    emit("onPeersChanged", emptyMap())
  }

  fun onRemoteUnpair(peer: Peer) {
    peers.remove(peer.id)
    client.close(peer.id)
    reachability.remove(peer.id)
    recordEvent("UNPAIR", peer.id, peer.name, true, null, "The other phone removed this pairing")
    emit("onPeersChanged", emptyMap())
  }

  // --- Commands ------------------------------------------------------------------------------

  suspend fun sendCommand(peerId: String, command: Command): CommandResponse {
    val peer = peers.get(peerId)
      ?: return CommandResponse.fail("local", ErrorCode.UNPAIRED, "This device is not paired")
    val resp = client.send(peerId, command, COMMAND_TIMEOUT_MS)
    markReachable(peerId, resp.error !in UNREACHABLE_ERRORS, trusted = resp.error != ErrorCode.HANDSHAKE_FAILED)
    if (resp.success && command is Command.GetStatus) {
      val caps = resp.data?.optJSONArray("capabilities")
      if (caps != null) {
        val list = (0 until caps.length()).map { caps.getString(it) }
        peers.update(peerId) { it.copy(capabilities = list, lastSeen = System.currentTimeMillis()) }
      }
    }
    history.record(
      HistoryEntry(
        System.currentTimeMillis(), "out", peer.id, peer.name, command.type, resp.success, resp.error,
        resp.message ?: resp.data?.optString("state")?.ifEmpty { null },
      ),
    )
    return resp
  }

  fun recordIncoming(peer: Peer, cmd: Command, resp: CommandResponse) {
    val detail = resp.message ?: resp.data?.optString("state")?.ifEmpty { null }
    history.record(HistoryEntry(System.currentTimeMillis(), "in", peer.id, peer.name, cmd.type, resp.success, resp.error, detail))
    emit(
      "onCommandReceived",
      mapOf(
        "peerId" to peer.id,
        "peerName" to peer.name,
        "command" to cmd.type,
        "success" to resp.success,
        "error" to resp.error,
        "detail" to detail,
      ),
    )
  }

  fun recordEvent(command: String, peerId: String?, peerName: String?, success: Boolean, error: String?, detail: String?) {
    history.record(HistoryEntry(System.currentTimeMillis(), "event", peerId, peerName, command, success, error, detail))
  }

  fun statusJson(): JSONObject = status.status(identity.fingerprint, settings.deviceName, receiverRunning)

  // --- Diagnostics ---------------------------------------------------------------------------

  fun diagnostics(): Map<String, Any?> {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val targetSdk = context.applicationInfo.targetSdkVersion
    return mapOf(
      "notificationsEnabled" to notifications.enabled,
      "overlayPermission" to wake.canLaunchWakeActivity,
      "ignoringBatteryOptimizations" to pm.isIgnoringBatteryOptimizations(context.packageName),
      // Android 17 makes local-network access a runtime permission for apps targeting it.
      "localNetworkPermissionRequired" to (targetSdk >= 37 && Build.VERSION.SDK_INT >= 37),
      "wifiConnected" to network.isWifiConnected,
      "wifiIp" to network.wifiIpv4,
      "discovering" to discovery.isDiscovering,
      "multicastLockHeld" to discovery.multicastLockHeld,
      "torchSupported" to torch.isSupported,
      "vibratorSupported" to vibration.isSupported,
      "androidSdk" to Build.VERSION.SDK_INT,
      "targetSdk" to targetSdk,
      "manufacturer" to Build.MANUFACTURER,
      "model" to Build.MODEL,
    ) + serviceState()
  }

  fun openSettings(kind: String) {
    val pkg = Uri.parse("package:${context.packageName}")
    val intent = when (kind) {
      "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
      "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      "notifications" -> if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
      }
      "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
      else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

/** Converts org.json values into the plain maps/lists Expo modules can send to JS. */
fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { jsonValue(get(it)) }

private fun jsonValue(v: Any?): Any? = when (v) {
  JSONObject.NULL, null -> null
  is JSONObject -> v.toMap()
  is JSONArray -> (0 until v.length()).map { jsonValue(v.get(it)) }
  is Long -> v.toDouble()
  else -> v
}
