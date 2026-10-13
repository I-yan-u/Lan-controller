package expo.modules.lancontrol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Policy(val wire: String) {
  ALLOW("allow"), ASK("ask"), DENY("deny");

  companion object {
    fun from(wire: String): Policy = entries.firstOrNull { it.wire == wire }
      ?: throw ProtocolException(ErrorCode.INVALID, "Unknown policy '$wire'")
  }
}

/**
 * A paired device. [policies] are what *this* device grants to the peer. Both sides of a pairing
 * trust each other's certificate, but only the side that was paired *to* grants control by default.
 */
data class Peer(
  val id: String,
  val name: String,
  val policies: Map<String, Policy>,
  val pairedAt: Long,
  val lastHost: String? = null,
  val lastPort: Int? = null,
  val lastSeen: Long? = null,
  val capabilities: List<String> = emptyList(),
) {
  fun policyFor(capability: String): Policy = policies[capability] ?: Policy.DENY

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("pairedAt", pairedAt)
    .put("policies", JSONObject().also { o -> policies.forEach { (k, v) -> o.put(k, v.wire) } })
    .put("capabilities", JSONArray(capabilities))
    .also { o ->
      lastHost?.let { o.put("lastHost", it) }
      lastPort?.let { o.put("lastPort", it) }
      lastSeen?.let { o.put("lastSeen", it) }
    }

  companion object {
    /** Defaults for a peer that paired *to* this device, i.e. a controller of this device. */
    val CONTROLLER_DEFAULTS = mapOf(
      Capability.STATUS to Policy.ALLOW,
      Capability.TORCH to Policy.ALLOW,
      Capability.SOUND to Policy.ALLOW,
      Capability.VIBRATE to Policy.ALLOW,
      // Not "ask": the phone being woken has its screen off, so nobody could answer the prompt.
      Capability.WAKE to Policy.ALLOW,
    )

    /** Defaults for a peer this device paired to: it can read status, nothing more until granted. */
    val TARGET_DEFAULTS = Capability.ALL.associateWith { if (it == Capability.STATUS) Policy.ALLOW else Policy.DENY }

    fun fromJson(o: JSONObject): Peer {
      val pol = o.optJSONObject("policies") ?: JSONObject()
      val caps = o.optJSONArray("capabilities") ?: JSONArray()
      return Peer(
        id = o.getString("id"),
        name = o.getString("name"),
        policies = pol.keys().asSequence().associateWith { Policy.from(pol.getString(it)) },
        pairedAt = o.getLong("pairedAt"),
        lastHost = o.optString("lastHost").ifEmpty { null },
        lastPort = if (o.has("lastPort")) o.getInt("lastPort") else null,
        lastSeen = if (o.has("lastSeen")) o.getLong("lastSeen") else null,
        capabilities = (0 until caps.length()).map { caps.getString(it) },
      )
    }
  }
}

/** Paired devices, persisted as JSON in app-private storage. Holds only public data. */
class PeerStore(private val file: File) {
  private val peers = LinkedHashMap<String, Peer>()

  init {
    load()
  }

  @Synchronized fun all(): List<Peer> = peers.values.toList()
  @Synchronized fun get(id: String): Peer? = peers[id]
  @Synchronized fun contains(id: String): Boolean = peers.containsKey(id)

  @Synchronized
  fun put(peer: Peer) {
    peers[peer.id] = peer
    save()
  }

  @Synchronized
  fun remove(id: String): Peer? = peers.remove(id)?.also { save() }

  @Synchronized
  fun update(id: String, transform: (Peer) -> Peer): Peer? {
    val current = peers[id] ?: return null
    val next = transform(current)
    if (next != current) {
      peers[id] = next
      save()
    }
    return next
  }

  private fun load() {
    if (!file.exists()) return
    try {
      val arr = JSONArray(file.readText())
      for (i in 0 until arr.length()) {
        val p = Peer.fromJson(arr.getJSONObject(i))
        peers[p.id] = p
      }
    } catch (e: Exception) {
      // A corrupt registry must not brick the app; the user can re-pair.
      file.renameTo(File(file.parentFile, file.name + ".corrupt"))
      peers.clear()
    }
  }

  private fun save() {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(JSONArray(peers.values.map { it.toJson() }).toString())
    if (!tmp.renameTo(file)) {
      file.writeText(tmp.readText())
      tmp.delete()
    }
  }
}
