package expo.modules.lancontrol

import java.security.MessageDigest
import java.security.SecureRandom

/** Small, platform-independent security helpers (kept free of Android APIs for unit tests). */
object Security {
  private val random = SecureRandom()

  fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** 128-bit random token, hex encoded. */
  fun randomToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

  /**
   * Short authentication string shown on both phones during pairing. Order-independent, so both
   * sides derive the same 6 digits from (their own fingerprint, the peer fingerprint they observed).
   * A man-in-the-middle presents a different certificate to each side, so the codes would differ.
   */
  fun sas(fingerprintA: String, fingerprintB: String): String {
    val (lo, hi) = listOf(fingerprintA.lowercase(), fingerprintB.lowercase()).sorted()
    val digest = MessageDigest.getInstance("SHA-256").digest("lancontrol-sas-v1|$lo|$hi".toByteArray())
    val n = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
      ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    return "%06d".format(n % 1_000_000)
  }

  /** Constant-time comparison for pairing tokens. */
  fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** Token bucket used per peer by the command server. [now] is injectable for tests. */
class RateLimiter(
  private val capacity: Double = 10.0,
  private val refillPerSecond: Double = 5.0,
  private val now: () -> Long = System::nanoTime,
) {
  private var tokens = capacity
  private var last = now()

  @Synchronized
  fun tryAcquire(): Boolean {
    val t = now()
    tokens = minOf(capacity, tokens + (t - last) / 1e9 * refillPerSecond)
    last = t
    if (tokens < 1.0) return false
    tokens -= 1.0
    return true
  }
}
