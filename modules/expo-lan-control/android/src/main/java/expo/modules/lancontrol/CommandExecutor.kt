package expo.modules.lancontrol

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Validates, authorizes and executes commands received by the [CommandServer]. */
class CommandExecutor(private val core: LanControlCore) {
  private val pm = core.context.getSystemService(Context.POWER_SERVICE) as PowerManager

  suspend fun handle(peerId: String, text: String, limiter: RateLimiter, remoteHost: String?): CommandResponse {
    val request = try {
      Protocol.parseRequest(text)
    } catch (e: ProtocolException) {
      return CommandResponse.fail(Protocol.requestIdOrUnknown(text), e.code, e.message)
    }
    if (!limiter.tryAcquire()) {
      return CommandResponse.fail(request.requestId, ErrorCode.RATE_LIMITED, "Too many commands; slow down")
    }
    val cmd = request.command
    if (cmd is Command.PairRequest) return core.pairing.handlePairRequest(peerId, request.requestId, cmd, remoteHost)

    val peer = core.peers.get(peerId)
      ?: return CommandResponse.fail(request.requestId, ErrorCode.UNPAIRED, "This device is not paired")
    core.onPeerContact(peer.id, remoteHost)

    when (cmd) {
      Command.Ping -> return CommandResponse.ok(request.requestId, JSONObject().put("name", core.settings.deviceName))
      Command.Unpair -> {
        core.onRemoteUnpair(peer)
        return CommandResponse.ok(request.requestId)
      }
      else -> Unit
    }

    val response = authorizeAndExecute(request.requestId, peer, cmd)
    core.recordIncoming(peer, cmd, response)
    return response
  }

  private suspend fun authorizeAndExecute(requestId: String, peer: Peer, cmd: Command): CommandResponse {
    val capability = cmd.capability
      ?: return CommandResponse.fail(requestId, ErrorCode.INVALID, "Command ${cmd.type} cannot be executed")
    if (capability !in core.status.capabilities()) {
      return CommandResponse.fail(requestId, ErrorCode.UNSUPPORTED, "This phone does not support $capability")
    }
    when (peer.policyFor(capability)) {
      Policy.DENY -> return CommandResponse.fail(
        requestId, ErrorCode.DENIED, "${core.settings.deviceName} has not allowed ${describe(cmd)} for this device",
      )
      Policy.ASK -> when (core.prompts.ask(peer, cmd)) {
        true -> Unit
        false -> return CommandResponse.fail(requestId, ErrorCode.DENIED, "Declined on ${core.settings.deviceName}")
        null -> return CommandResponse.fail(requestId, ErrorCode.TIMEOUT, "Nobody answered the permission prompt")
      }
      Policy.ALLOW -> Unit
    }

    val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lancontrol:command")
    wakeLock.acquire(60_000)
    return try {
      CommandResponse.ok(requestId, execute(peer, cmd))
    } catch (e: ProtocolException) {
      CommandResponse.fail(requestId, e.code, e.message)
    } catch (e: Exception) {
      CommandResponse.fail(requestId, ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName)
    } finally {
      if (wakeLock.isHeld) wakeLock.release()
    }
  }

  private suspend fun execute(peer: Peer, cmd: Command): JSONObject = when (cmd) {
    Command.GetStatus -> core.statusJson()
    is Command.TorchSet -> {
      core.torch.set(cmd.enabled)
      JSONObject().put("torchOn", cmd.enabled)
    }
    Command.PlayFindSound -> {
      core.sound.play(peer.name)
      JSONObject().put("playing", true).put("autoStopMs", FindSoundController.AUTO_STOP_MS)
    }
    Command.StopFindSound -> {
      core.sound.stop()
      JSONObject().put("playing", false)
    }
    is Command.Vibrate -> {
      core.vibration.vibrate(cmd.durationMs)
      JSONObject().put("durationMs", cmd.durationMs)
    }
    Command.WakeScreen -> core.wake.wake(peer.name)
    else -> throw ProtocolException(ErrorCode.INVALID, "Unexpected command ${cmd.type}")
  }
}

fun describe(cmd: Command): String = when (cmd) {
  Command.GetStatus -> "read this phone's status"
  is Command.TorchSet -> if (cmd.enabled) "turn on the flashlight" else "turn off the flashlight"
  Command.PlayFindSound -> "ring this phone"
  Command.StopFindSound -> "stop the ringing"
  is Command.Vibrate -> "vibrate this phone"
  Command.WakeScreen -> "wake the screen"
  else -> cmd.type
}

/** Implements the "ask" policy: a notification with Allow/Deny plus an in-app prompt event. */
class PromptManager(private val core: LanControlCore) {
  companion object {
    const val TIMEOUT_MS = 30_000L
  }

  private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val slots = AtomicInteger()

  /** Returns true (allowed), false (denied) or null (nobody answered). */
  suspend fun ask(peer: Peer, cmd: Command): Boolean? {
    val id = Security.randomToken().take(12)
    val slot = slots.getAndIncrement() % 10
    val decision = CompletableDeferred<Boolean>()
    pending[id] = decision
    val description = describe(cmd)
    core.notifications.showPrompt(id, peer.name, description, slot)
    core.emit(
      "onPermissionPrompt",
      mapOf(
        "promptId" to id,
        "peerId" to peer.id,
        "peerName" to peer.name,
        "command" to cmd.type,
        "capability" to cmd.capability,
        "description" to description,
        "expiresAt" to (System.currentTimeMillis() + TIMEOUT_MS).toDouble(),
      ),
    )
    val result = withTimeoutOrNull(TIMEOUT_MS) { decision.await() }
    pending.remove(id)
    core.notifications.cancelPrompt(slot)
    core.emit("onPermissionPromptResolved", mapOf("promptId" to id, "allowed" to result))
    return result
  }

  fun respond(promptId: String, allow: Boolean): Boolean = pending[promptId]?.complete(allow) ?: false
}
