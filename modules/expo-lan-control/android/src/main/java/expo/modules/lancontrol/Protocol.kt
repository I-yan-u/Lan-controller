package expo.modules.lancontrol

import org.json.JSONException
import org.json.JSONObject

/** Wire protocol v1. Every frame is one JSON object (see [FrameCodec]). */
object ErrorCode {
  const val INVALID = "INVALID"
  const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
  const val UNPAIRED = "UNPAIRED"
  const val DENIED = "DENIED"
  const val UNSUPPORTED = "UNSUPPORTED"
  const val RATE_LIMITED = "RATE_LIMITED"
  const val TIMEOUT = "TIMEOUT"
  const val HARDWARE_BUSY = "HARDWARE_BUSY"
  const val UNREACHABLE = "UNREACHABLE"
  const val HANDSHAKE_FAILED = "HANDSHAKE_FAILED"
  const val PAIRING_REJECTED = "PAIRING_REJECTED"
  const val INTERNAL = "INTERNAL"
}

/** Capabilities a peer may be granted. Commands map onto exactly one (or none). */
object Capability {
  const val STATUS = "status.read"
  const val TORCH = "torch.control"
  const val SOUND = "sound.play"
  const val VIBRATE = "vibrate"
  const val WAKE = "screen.wake.best_effort"

  val ALL = listOf(STATUS, TORCH, SOUND, VIBRATE, WAKE)
}

class ProtocolException(val code: String, message: String) : Exception(message)

sealed class Command(val type: String, val capability: String?) {
  object GetStatus : Command("GET_STATUS", Capability.STATUS)
  data class TorchSet(val enabled: Boolean) : Command("TORCH_SET", Capability.TORCH)
  object PlayFindSound : Command("PLAY_FIND_SOUND", Capability.SOUND)
  object StopFindSound : Command("STOP_FIND_SOUND", Capability.SOUND)
  data class Vibrate(val durationMs: Long) : Command("VIBRATE", Capability.VIBRATE)
  object WakeScreen : Command("WAKE_SCREEN", Capability.WAKE)
  object Ping : Command("PING", null)
  object Unpair : Command("UNPAIR", null)
  /** Only command an unpaired peer may send; [token] is null for the code-comparison path. */
  data class PairRequest(val token: String?, val name: String) : Command("PAIR_REQUEST", null)

  fun toJson(): JSONObject = JSONObject().put("type", type).also {
    when (this) {
      is TorchSet -> it.put("enabled", enabled)
      is Vibrate -> it.put("durationMs", durationMs)
      is PairRequest -> { it.put("name", name); if (token != null) it.put("token", token) }
      else -> Unit
    }
  }
}

data class CommandRequest(val requestId: String, val command: Command)

data class CommandResponse(
  val requestId: String,
  val success: Boolean,
  val error: String? = null,
  val message: String? = null,
  val data: JSONObject? = null,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("requestId", requestId)
    .put("success", success)
    .also { o ->
      error?.let { o.put("error", it) }
      message?.let { o.put("message", it) }
      data?.let { o.put("data", it) }
    }

  companion object {
    fun ok(requestId: String, data: JSONObject? = null) = CommandResponse(requestId, true, data = data)
    fun fail(requestId: String, code: String, message: String? = null) =
      CommandResponse(requestId, false, code, message)

    fun parse(text: String): CommandResponse = try {
      val o = JSONObject(text)
      CommandResponse(
        requestId = o.getString("requestId"),
        success = o.getBoolean("success"),
        error = o.optString("error").ifEmpty { null },
        message = o.optString("message").ifEmpty { null },
        data = o.optJSONObject("data"),
      )
    } catch (e: JSONException) {
      throw ProtocolException(ErrorCode.INVALID, "Malformed response: ${e.message}")
    }
  }
}

object Protocol {
  const val VERSION = 1
  const val MAX_VIBRATE_MS = 5000L
  const val MAX_NAME_LENGTH = 64
  const val MAX_REQUEST_ID_LENGTH = 64

  fun encodeRequest(req: CommandRequest): String = JSONObject()
    .put("protocolVersion", VERSION)
    .put("requestId", req.requestId)
    .put("command", req.command.toJson())
    .toString()

  /**
   * Parses and validates an incoming request. Throws [ProtocolException] with a code the server
   * can return directly. The requestId is recovered best-effort so errors can still be correlated.
   */
  fun parseRequest(text: String): CommandRequest {
    val o = try {
      JSONObject(text)
    } catch (e: JSONException) {
      throw ProtocolException(ErrorCode.INVALID, "Request is not a JSON object")
    }
    val version = o.optInt("protocolVersion", -1)
    if (version != VERSION) {
      throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocolVersion $version")
    }
    val requestId = o.optString("requestId")
    if (requestId.isEmpty() || requestId.length > MAX_REQUEST_ID_LENGTH) {
      throw ProtocolException(ErrorCode.INVALID, "Missing or oversized requestId")
    }
    val c = o.optJSONObject("command") ?: throw ProtocolException(ErrorCode.INVALID, "Missing command")
    return CommandRequest(requestId, parseCommand(c))
  }

  fun requestIdOrUnknown(text: String): String =
    try { JSONObject(text).optString("requestId").take(MAX_REQUEST_ID_LENGTH).ifEmpty { "unknown" } } catch (e: JSONException) { "unknown" }

  fun parseCommand(c: JSONObject): Command = when (val type = c.optString("type")) {
    "GET_STATUS" -> Command.GetStatus
    "TORCH_SET" -> {
      val v = c.opt("enabled") as? Boolean
        ?: throw ProtocolException(ErrorCode.INVALID, "TORCH_SET.enabled must be a boolean")
      Command.TorchSet(v)
    }
    "PLAY_FIND_SOUND" -> Command.PlayFindSound
    "STOP_FIND_SOUND" -> Command.StopFindSound
    "VIBRATE" -> {
      val n = c.opt("durationMs") as? Number
        ?: throw ProtocolException(ErrorCode.INVALID, "VIBRATE.durationMs must be a number")
      val ms = n.toLong()
      if (ms < 1 || ms > MAX_VIBRATE_MS) {
        throw ProtocolException(ErrorCode.INVALID, "VIBRATE.durationMs must be 1..$MAX_VIBRATE_MS")
      }
      Command.Vibrate(ms)
    }
    "WAKE_SCREEN" -> Command.WakeScreen
    "PING" -> Command.Ping
    "UNPAIR" -> Command.Unpair
    "PAIR_REQUEST" -> {
      val name = c.optString("name").trim()
      if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
        throw ProtocolException(ErrorCode.INVALID, "PAIR_REQUEST.name must be 1..$MAX_NAME_LENGTH chars")
      }
      val token = if (c.has("token")) c.optString("token").ifEmpty { null } else null
      Command.PairRequest(token, name)
    }
    else -> throw ProtocolException(ErrorCode.INVALID, "Unknown command type '$type'")
  }
}
