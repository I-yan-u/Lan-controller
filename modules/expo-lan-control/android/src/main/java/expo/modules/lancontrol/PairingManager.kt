package expo.modules.lancontrol

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit pairing, in two roles:
 *
 * Target (the phone that will be controlled) opens a time-limited pairing session and shows a QR
 * code with its fingerprint, address and a single-use token. Only while that session is open does
 * its server accept TLS clients it has never seen, and only for PAIR_REQUEST.
 *
 * Controller connects pinned to the target's fingerprint (from the QR code or the NSD record),
 * sends PAIR_REQUEST, and both screens show the same 6-digit code ([Security.sas]). The target's
 * user accepts; the controller's user confirms the codes match. Only then do both persist the peer.
 */
class PairingManager(private val core: LanControlCore) {
  companion object {
    const val SESSION_TTL_MS = 120_000L
    const val USER_DECISION_TIMEOUT_MS = 60_000L
    private const val PAIR_EXCHANGE_TIMEOUT_MS = 75_000
    private const val MAX_PENDING_INCOMING = 3
  }

  private class Session(val token: String, val expiresAt: Long) {
    @Volatile var tokenUsed = false
  }

  private class Outgoing(
    val peerId: String,
    val peerName: String,
    val host: String,
    val port: Int,
    val conn: PeerConnection,
    val response: Deferred<CommandResponse>,
  )

  @Volatile private var session: Session? = null
  private val pendingIncoming = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val outgoing = ConcurrentHashMap<String, Outgoing>()

  fun isOpen(): Boolean = session?.let { System.currentTimeMillis() < it.expiresAt } == true

  // --- Target role ---------------------------------------------------------------------------

  fun openSession(): Map<String, Any?> {
    val port = core.server.port ?: throw ProtocolException(ErrorCode.INTERNAL, "Turn on remote control first")
    val host = core.network.wifiIpv4 ?: throw ProtocolException(ErrorCode.UNREACHABLE, "Not connected to Wi-Fi")
    val s = Session(Security.randomToken(), System.currentTimeMillis() + SESSION_TTL_MS)
    session = s
    val uri = Uri.Builder()
      .scheme("lancontrol").authority("pair")
      .appendQueryParameter("v", Protocol.VERSION.toString())
      .appendQueryParameter("id", core.identity.fingerprint)
      .appendQueryParameter("name", core.settings.deviceName)
      .appendQueryParameter("host", host)
      .appendQueryParameter("port", port.toString())
      .appendQueryParameter("token", s.token)
      .build()
    return mapOf("payload" to uri.toString(), "expiresAt" to s.expiresAt.toDouble(), "host" to host, "port" to port)
  }

  fun closeSession() {
    session = null
    pendingIncoming.values.forEach { it.complete(false) }
  }

  fun respondToRequest(requestId: String, accept: Boolean): Boolean =
    pendingIncoming[requestId]?.complete(accept) ?: false

  suspend fun handlePairRequest(
    peerId: String,
    requestId: String,
    cmd: Command.PairRequest,
    remoteHost: String?,
  ): CommandResponse {
    val s = session?.takeIf { System.currentTimeMillis() < it.expiresAt }
      ?: return CommandResponse.fail(requestId, ErrorCode.PAIRING_REJECTED, "Pairing mode is not open on the other phone")
    if (cmd.token != null && (s.tokenUsed || !Security.constantTimeEquals(cmd.token, s.token))) {
      return CommandResponse.fail(requestId, ErrorCode.PAIRING_REJECTED, "This QR code is no longer valid. Refresh it and scan again.")
    }
    if (pendingIncoming.size >= MAX_PENDING_INCOMING) {
      return CommandResponse.fail(requestId, ErrorCode.RATE_LIMITED, "Too many pending pairing requests")
    }

    val promptId = Security.randomToken().take(12)
    val decision = CompletableDeferred<Boolean>()
    pendingIncoming[promptId] = decision
    val existing = core.peers.get(peerId)
    core.emit(
      "onPairingRequest",
      mapOf(
        "requestId" to promptId,
        "peerId" to peerId,
        "name" to cmd.name,
        "sas" to Security.sas(core.identity.fingerprint, peerId),
        "viaQr" to (cmd.token != null),
        "alreadyPaired" to (existing != null),
        "expiresAt" to (System.currentTimeMillis() + USER_DECISION_TIMEOUT_MS).toDouble(),
      ),
    )
    val accepted = withTimeoutOrNull(USER_DECISION_TIMEOUT_MS) { decision.await() } ?: false
    pendingIncoming.remove(promptId)
    core.emit("onPairingRequestResolved", mapOf("requestId" to promptId, "accepted" to accepted))

    if (!accepted) {
      core.recordEvent("PAIRING", peerId, cmd.name, false, ErrorCode.PAIRING_REJECTED, "Incoming request declined")
      core.emit("onPairingResult", mapOf("role" to "target", "success" to false, "peerId" to peerId, "name" to cmd.name))
      return CommandResponse.fail(requestId, ErrorCode.PAIRING_REJECTED, "Pairing was declined on the other phone")
    }
    if (cmd.token != null) s.tokenUsed = true

    val now = System.currentTimeMillis()
    // Accepting a pairing request means "let this device control me": grant the controller
    // defaults, without downgrading anything that was already granted explicitly.
    val policies = Capability.ALL.associateWith { cap ->
      val prev = existing?.policyFor(cap)
      if (prev == null || prev == Policy.DENY) Peer.CONTROLLER_DEFAULTS.getValue(cap) else prev
    }
    core.peers.put(
      (existing ?: Peer(peerId, cmd.name, policies, now)).copy(
        name = cmd.name, policies = policies, lastHost = remoteHost ?: existing?.lastHost, lastSeen = now,
      ),
    )
    core.recordEvent("PAIRING", peerId, cmd.name, true, null, "Paired as controller of this phone")
    core.emit("onPeersChanged", emptyMap())
    core.emit("onPairingResult", mapOf("role" to "target", "success" to true, "peerId" to peerId, "name" to cmd.name))
    return CommandResponse.ok(
      requestId,
      JSONObject()
        .put("deviceId", core.identity.fingerprint)
        .put("name", core.settings.deviceName)
        .put("capabilities", JSONArray(core.status.capabilities())),
    )
  }

  // --- Controller role -----------------------------------------------------------------------

  data class QrTarget(val id: String, val name: String, val host: String, val port: Int, val token: String)

  fun parseQr(payload: String): QrTarget {
    val uri = Uri.parse(payload.trim())
    if (uri.scheme != "lancontrol" || uri.authority != "pair") {
      throw ProtocolException(ErrorCode.INVALID, "This is not a LAN Controller pairing code")
    }
    if (uri.getQueryParameter("v") != Protocol.VERSION.toString()) {
      throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "The other phone runs an incompatible version")
    }
    val id = uri.getQueryParameter("id")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    val host = uri.getQueryParameter("host")
    val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
    val token = uri.getQueryParameter("token")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    if (id == null || host.isNullOrBlank() || port == null || token == null) {
      throw ProtocolException(ErrorCode.INVALID, "Pairing code is incomplete")
    }
    val name = uri.getQueryParameter("name")?.take(Protocol.MAX_NAME_LENGTH)?.ifBlank { null } ?: "Android device"
    return QrTarget(id, name, host, port, token)
  }

  suspend fun startWithQr(payload: String): Map<String, Any?> {
    val t = parseQr(payload)
    if (t.id == core.identity.fingerprint) throw ProtocolException(ErrorCode.INVALID, "That is this phone's own code")
    return start(t.id, t.name, t.host, t.port, t.token)
  }

  suspend fun startWithDiscovered(deviceId: String): Map<String, Any?> {
    val d = core.discoveredDevice(deviceId)
      ?: throw ProtocolException(ErrorCode.UNREACHABLE, "That device is no longer visible on the network")
    return start(d.id, d.name, d.host, d.port, null)
  }

  private suspend fun start(peerId: String, name: String, host: String, port: Int, token: String?): Map<String, Any?> {
    val conn = withContext(Dispatchers.IO) {
      try {
        core.client.open(host, port, peerId)
      } catch (e: Exception) {
        val err = CommandClient.errorResponse("pair", e)
        throw ProtocolException(err.error ?: ErrorCode.INTERNAL, err.message ?: "Could not connect")
      }
    }
    val request = CommandRequest(CommandClient.newRequestId(), Command.PairRequest(token, core.settings.deviceName))
    val response = core.scope.async {
      try {
        conn.exchange(request, PAIR_EXCHANGE_TIMEOUT_MS)
      } catch (e: Exception) {
        CommandClient.errorResponse(request.requestId, e)
      } finally {
        conn.close()
      }
    }
    val sessionId = Security.randomToken().take(12)
    outgoing[sessionId] = Outgoing(peerId, name, host, port, conn, response)
    return mapOf(
      "sessionId" to sessionId,
      "peerId" to peerId,
      "peerName" to name,
      "sas" to Security.sas(core.identity.fingerprint, peerId),
      "viaQr" to (token != null),
    )
  }

  /** [codesMatch] is the local user's confirmation that both screens show the same code. */
  suspend fun finish(sessionId: String, codesMatch: Boolean): Map<String, Any?> {
    val o = outgoing.remove(sessionId)
      ?: return mapOf("success" to false, "error" to ErrorCode.INVALID, "message" to "Pairing session expired")
    if (!codesMatch) {
      o.conn.close()
      o.response.cancel()
      core.recordEvent("PAIRING", o.peerId, o.peerName, false, "CANCELLED", "Codes did not match / cancelled")
      return mapOf("success" to false, "error" to "CANCELLED", "message" to "Pairing cancelled")
    }
    val r = o.response.await()
    if (!r.success) {
      core.recordEvent("PAIRING", o.peerId, o.peerName, false, r.error, r.message)
      return mapOf("success" to false, "error" to r.error, "message" to r.message)
    }
    val name = r.data?.optString("name")?.take(Protocol.MAX_NAME_LENGTH)?.ifBlank { null } ?: o.peerName
    val capsJson = r.data?.optJSONArray("capabilities") ?: JSONArray()
    val caps = (0 until capsJson.length()).map { capsJson.getString(it) }
    val now = System.currentTimeMillis()
    val existing = core.peers.get(o.peerId)
    core.peers.put(
      (existing ?: Peer(o.peerId, name, Peer.TARGET_DEFAULTS, now)).copy(
        name = name, lastHost = o.host, lastPort = o.port, lastSeen = now, capabilities = caps,
      ),
    )
    core.recordEvent("PAIRING", o.peerId, name, true, null, "Paired; this phone can now control it")
    core.emit("onPeersChanged", emptyMap())
    core.emit("onPairingResult", mapOf("role" to "controller", "success" to true, "peerId" to o.peerId, "name" to name))
    core.markReachable(o.peerId, true)
    return mapOf("success" to true, "peerId" to o.peerId, "name" to name)
  }
}
