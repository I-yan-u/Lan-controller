package expo.modules.lancontrol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class ProtocolTest {
  private fun request(command: String, version: Int = 1, requestId: String = "r1") =
    """{"protocolVersion":$version,"requestId":"$requestId","command":$command}"""

  private fun expectError(code: String, text: String) {
    try {
      Protocol.parseRequest(text)
      fail("Expected $code for $text")
    } catch (e: ProtocolException) {
      assertEquals(code, e.code)
    }
  }

  @Test fun roundTripsEveryCommand() {
    val commands = listOf(
      Command.GetStatus, Command.TorchSet(true), Command.TorchSet(false), Command.PlayFindSound,
      Command.StopFindSound, Command.Vibrate(500), Command.WakeScreen, Command.Ping, Command.Unpair,
      Command.PairRequest("abc", "Pixel"), Command.PairRequest(null, "Pixel"),
    )
    for (c in commands) {
      val parsed = Protocol.parseRequest(Protocol.encodeRequest(CommandRequest("id-1", c)))
      assertEquals("id-1", parsed.requestId)
      assertEquals(c, parsed.command)
    }
  }

  @Test fun rejectsMalformedRequests() {
    expectError(ErrorCode.INVALID, "not json")
    expectError(ErrorCode.INVALID, "[]")
    expectError(ErrorCode.UNSUPPORTED_VERSION, request("""{"type":"PING"}""", version = 2))
    expectError(ErrorCode.UNSUPPORTED_VERSION, """{"requestId":"r","command":{"type":"PING"}}""")
    expectError(ErrorCode.INVALID, request("""{"type":"PING"}""", requestId = ""))
    expectError(ErrorCode.INVALID, request("""{"type":"PING"}""", requestId = "x".repeat(65)))
    expectError(ErrorCode.INVALID, """{"protocolVersion":1,"requestId":"r"}""")
  }

  @Test fun rejectsUnknownAndInvalidCommands() {
    expectError(ErrorCode.INVALID, request("""{"type":"SHELL","cmd":"rm -rf /"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"TORCH_SET"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"TORCH_SET","enabled":"yes"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"VIBRATE"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"VIBRATE","durationMs":0}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"VIBRATE","durationMs":5001}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"VIBRATE","durationMs":"100"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"PAIR_REQUEST"}"""))
    expectError(ErrorCode.INVALID, request("""{"type":"PAIR_REQUEST","name":"${"n".repeat(65)}"}"""))
  }

  @Test fun recoversRequestIdForErrors() {
    assertEquals("abc", Protocol.requestIdOrUnknown("""{"requestId":"abc"}"""))
    assertEquals("unknown", Protocol.requestIdOrUnknown("garbage"))
  }

  @Test fun responseRoundTrip() {
    val r = CommandResponse("r1", false, ErrorCode.DENIED, "nope", JSONObject().put("a", 1))
    val parsed = CommandResponse.parse(r.toJson().toString())
    assertEquals("r1", parsed.requestId)
    assertFalse(parsed.success)
    assertEquals(ErrorCode.DENIED, parsed.error)
    assertEquals("nope", parsed.message)
    assertEquals(1, parsed.data!!.getInt("a"))
  }
}

class FrameCodecTest {
  /** Delivers at most one byte per read() call, like a slow socket. */
  private class TrickleStream(bytes: ByteArray) : InputStream() {
    private val inner = ByteArrayInputStream(bytes)
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 1))
  }

  @Test fun roundTripsMultipleFramesAcrossSplitReads() {
    val out = ByteArrayOutputStream()
    FrameCodec.write(out, """{"a":"héllo"}""")
    FrameCodec.write(out, "{}")
    val input = TrickleStream(out.toByteArray())
    assertEquals("""{"a":"héllo"}""", FrameCodec.read(input))
    assertEquals("{}", FrameCodec.read(input))
    assertNull(FrameCodec.read(input))
  }

  @Test fun rejectsOversizeFramesOnBothSides() {
    try {
      FrameCodec.write(ByteArrayOutputStream(), "x".repeat(FrameCodec.MAX_FRAME_BYTES + 1))
      fail()
    } catch (e: FrameTooLargeException) { /* expected */ }
    val header = byteArrayOf(0x7f, 0, 0, 0)
    try {
      FrameCodec.read(ByteArrayInputStream(header))
      fail()
    } catch (e: FrameTooLargeException) { /* expected */ }
  }

  @Test fun rejectsTruncatedFrames() {
    for (bytes in listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 5, 'a'.code.toByte()))) {
      try {
        FrameCodec.read(ByteArrayInputStream(bytes))
        fail()
      } catch (e: IOException) { /* expected */ }
    }
  }
}

class SecurityTest {
  private val a = "a".repeat(64)
  private val b = "b".repeat(64)

  @Test fun sasIsSymmetricDeterministicAndSixDigits() {
    val s = Security.sas(a, b)
    assertEquals(s, Security.sas(b, a))
    assertEquals(s, Security.sas(a.uppercase(), b))
    assertTrue(s.matches(Regex("\\d{6}")))
  }

  @Test fun sasDiffersWhenAPeerIsSubstituted() {
    // A MITM presents its own certificate to each side, so the two screens disagree.
    val mitm = "c".repeat(64)
    assertTrue(Security.sas(a, mitm) != Security.sas(b, mitm))
  }

  @Test fun tokensAreRandomHex() {
    val t1 = Security.randomToken()
    assertTrue(t1.matches(Regex("[0-9a-f]{32}")))
    assertTrue(t1 != Security.randomToken())
    assertTrue(Security.constantTimeEquals(t1, t1))
    assertFalse(Security.constantTimeEquals(t1, Security.randomToken()))
  }

  @Test fun rateLimiterAllowsBurstThenRefills() {
    var now = 0L
    val limiter = RateLimiter(capacity = 3.0, refillPerSecond = 1.0, now = { now })
    repeat(3) { assertTrue(limiter.tryAcquire()) }
    assertFalse(limiter.tryAcquire())
    now += 1_000_000_000L
    assertTrue(limiter.tryAcquire())
    assertFalse(limiter.tryAcquire())
  }
}

class StoreTest {
  private val dir = Files.createTempDirectory("lancontrol").toFile()

  @Test fun peerStoreRoundTripsAndUpdates() {
    val file = dir.resolve("peers.json")
    val store = PeerStore(file)
    val peer = Peer("f".repeat(64), "Pixel", Peer.CONTROLLER_DEFAULTS, 1L, "10.0.0.2", 4000, 2L, listOf(Capability.TORCH))
    store.put(peer)
    store.update(peer.id) { it.copy(policies = it.policies + (Capability.TORCH to Policy.DENY)) }

    val reloaded = PeerStore(file).get(peer.id)!!
    assertEquals("Pixel", reloaded.name)
    assertEquals(Policy.DENY, reloaded.policyFor(Capability.TORCH))
    assertEquals(Policy.ALLOW, reloaded.policyFor(Capability.WAKE))
    assertEquals("10.0.0.2", reloaded.lastHost)
    assertEquals(4000, reloaded.lastPort)
    assertEquals(listOf(Capability.TORCH), reloaded.capabilities)

    store.remove(peer.id)
    assertNull(PeerStore(file).get(peer.id))
  }

  @Test fun unknownCapabilityDefaultsToDeny() {
    val p = Peer("x", "n", emptyMap(), 0)
    assertEquals(Policy.DENY, p.policyFor(Capability.WAKE))
    assertEquals(Policy.ALLOW, Peer.TARGET_DEFAULTS[Capability.STATUS])
    assertEquals(Policy.DENY, Peer.TARGET_DEFAULTS[Capability.TORCH])
  }

  @Test fun corruptPeerFileIsSetAside() {
    val file = dir.resolve("bad.json").apply { writeText("{not json") }
    assertTrue(PeerStore(file).all().isEmpty())
    assertTrue(dir.resolve("bad.json.corrupt").exists())
  }

  @Test fun historyIsABoundedNewestFirstRingBuffer() {
    val file = dir.resolve("history.json")
    val h = CommandHistory(file, capacity = 3)
    for (i in 1..5) h.record(HistoryEntry(i.toLong(), "out", "p", "Pixel", "PING", true))
    assertEquals(listOf(5L, 4L, 3L), h.list().map { it.timestamp })
    assertEquals(listOf(5L, 4L, 3L), CommandHistory(file, capacity = 3).list().map { it.timestamp })
  }
}
