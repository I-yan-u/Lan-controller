package expo.modules.lancontrol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
  val timestamp: Long,
  /** "in" for commands this device received, "out" for commands it sent, "event" for local events. */
  val direction: String,
  val peerId: String?,
  val peerName: String?,
  val command: String,
  val success: Boolean,
  val error: String? = null,
  val detail: String? = null,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("timestamp", timestamp)
    .put("direction", direction)
    .put("command", command)
    .put("success", success)
    .also { o ->
      peerId?.let { o.put("peerId", it) }
      peerName?.let { o.put("peerName", it) }
      error?.let { o.put("error", it) }
      detail?.let { o.put("detail", it) }
    }

  companion object {
    fun fromJson(o: JSONObject) = HistoryEntry(
      timestamp = o.getLong("timestamp"),
      direction = o.getString("direction"),
      peerId = o.optString("peerId").ifEmpty { null },
      peerName = o.optString("peerName").ifEmpty { null },
      command = o.getString("command"),
      success = o.getBoolean("success"),
      error = o.optString("error").ifEmpty { null },
      detail = o.optString("detail").ifEmpty { null },
    )
  }
}

/** Ring buffer of recent commands, persisted so received commands are logged even without the UI. */
class CommandHistory(private val file: File, private val capacity: Int = 500) {
  private val entries = ArrayDeque<HistoryEntry>()

  init {
    if (file.exists()) {
      try {
        val arr = JSONArray(file.readText())
        for (i in 0 until arr.length()) entries.addLast(HistoryEntry.fromJson(arr.getJSONObject(i)))
      } catch (e: Exception) {
        file.delete()
      }
    }
  }

  @Synchronized
  fun record(entry: HistoryEntry) {
    entries.addLast(entry)
    while (entries.size > capacity) entries.removeFirst()
    file.writeText(JSONArray(entries.map { it.toJson() }).toString())
  }

  /** Newest first. */
  @Synchronized fun list(): List<HistoryEntry> = entries.reversed()

  @Synchronized
  fun clear() {
    entries.clear()
    file.delete()
  }
}
