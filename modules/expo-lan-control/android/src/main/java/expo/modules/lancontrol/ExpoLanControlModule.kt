package expo.modules.lancontrol

import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

/** Thin JS bridge over [LanControlCore]. All state lives in the core, not in this module. */
class ExpoLanControlModule : Module() {
  companion object {
    /** Commands the UI may send; PAIR_REQUEST/UNPAIR/PING are internal to the protocol. */
    private val USER_COMMANDS = setOf("GET_STATUS", "TORCH_SET", "PLAY_FIND_SOUND", "STOP_FIND_SOUND", "VIBRATE", "WAKE_SCREEN")
  }

  private val core: LanControlCore
    get() = LanControlCore.get(appContext.reactContext ?: throw Exceptions.ReactContextLost())

  private var listener: EventListener? = null

  private fun coded(e: ProtocolException) = CodedException(e.code, e.message, e)

  private inline fun <T> mapErrors(block: () -> T): T = try {
    block()
  } catch (e: ProtocolException) {
    throw coded(e)
  }

  override fun definition() = ModuleDefinition {
    Name("ExpoLanControl")

    Events(
      "onDeviceFound",
      "onDeviceLost",
      "onDiscoveryError",
      "onReachabilityChanged",
      "onPeersChanged",
      "onPairingRequest",
      "onPairingRequestResolved",
      "onPairingResult",
      "onCommandReceived",
      "onPermissionPrompt",
      "onPermissionPromptResolved",
      "onServiceStateChanged",
    )

    OnCreate {
      val l: EventListener = { name, payload -> sendEvent(name, payload) }
      listener = l
      core.addListener(l)
      core.onAppStarted()
    }

    OnDestroy {
      listener?.let { core.removeListener(it) }
      listener = null
    }

    // --- Identity & service ---

    AsyncFunction("getIdentity") {
      val id = core.identity.fingerprint
      mapOf("deviceId" to id, "shortId" to id.take(8), "name" to core.settings.deviceName)
    }

    AsyncFunction("setDeviceName") { name: String ->
      if (name.isBlank()) throw CodedException(ErrorCode.INVALID, "Name cannot be empty", null)
      core.setDeviceName(name)
    }

    AsyncFunction("setReceiverEnabled") { enabled: Boolean ->
      try {
        core.setReceiverEnabled(enabled)
      } catch (e: Exception) {
        core.settings.receiverEnabled = false
        throw CodedException("START_REFUSED", "Android refused to start the service: ${e.message}", e)
      }
      core.serviceState()
    }

    AsyncFunction("setStartOnBoot") { enabled: Boolean ->
      core.settings.startOnBoot = enabled
      core.serviceState()
    }

    AsyncFunction("getServiceState") { core.serviceState() }

    // --- Discovery ---

    AsyncFunction("setUiActive") { active: Boolean -> core.setUiActive(active) }
    AsyncFunction("getDiscoveredDevices") { core.discoveredDevices() }
    AsyncFunction("getPairedDevices") { core.pairedDevices() }

    // --- Pairing ---

    AsyncFunction("openPairingSession") { mapErrors { core.pairing.openSession() } }
    AsyncFunction("closePairingSession") { core.pairing.closeSession() }

    AsyncFunction("respondToPairingRequest") { requestId: String, accept: Boolean ->
      core.pairing.respondToRequest(requestId, accept)
    }

    AsyncFunction("startPairingWithQr") Coroutine { payload: String ->
      mapErrors { core.pairing.startWithQr(payload) }
    }

    AsyncFunction("startPairingWithDevice") Coroutine { deviceId: String ->
      mapErrors { core.pairing.startWithDiscovered(deviceId) }
    }

    AsyncFunction("finishPairing") Coroutine { sessionId: String, codesMatch: Boolean ->
      core.pairing.finish(sessionId, codesMatch)
    }

    AsyncFunction("forgetDevice") Coroutine { deviceId: String -> core.forget(deviceId) }

    AsyncFunction("setPolicy") { peerId: String, capability: String, policy: String ->
      mapErrors { core.setPolicy(peerId, capability, Policy.from(policy)) }
    }

    // --- Commands ---

    AsyncFunction("sendCommand") Coroutine { peerId: String, command: Map<String, Any?> ->
      val parsed = mapErrors {
        val json = JSONObject(command)
        if (json.optString("type") !in USER_COMMANDS) {
          throw ProtocolException(ErrorCode.INVALID, "Unsupported command ${json.optString("type")}")
        }
        Protocol.parseCommand(json)
      }
      core.sendCommand(peerId, parsed).toJson().toMap()
    }

    AsyncFunction("respondToPrompt") { promptId: String, allow: Boolean -> core.prompts.respond(promptId, allow) }

    // --- History & diagnostics ---

    AsyncFunction("getHistory") { core.history.list().map { it.toJson().toMap() } }
    AsyncFunction("clearHistory") { core.history.clear() }
    AsyncFunction("getLocalStatus") { core.statusJson().toMap() }
    AsyncFunction("getDiagnostics") { core.diagnostics() }
    AsyncFunction("openSettings") { kind: String -> core.openSettings(kind) }
  }
}
