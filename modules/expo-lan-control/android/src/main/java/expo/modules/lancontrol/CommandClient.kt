package expo.modules.lancontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** A single mutual-TLS connection to a peer, pinned to [expectedFingerprint]. */
class PeerConnection(val socket: SSLSocket, val host: String, val port: Int) {
  val input = DataInputStream(BufferedInputStream(socket.inputStream))
  val output = BufferedOutputStream(socket.outputStream)
  val lock = Mutex()
  @Volatile var lastUsed = System.currentTimeMillis()

  val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

  /** Sends one request and waits for the response with the same requestId. Caller holds [lock]. */
  fun exchange(request: CommandRequest, timeoutMs: Int): CommandResponse {
    socket.soTimeout = timeoutMs
    FrameCodec.write(output, Protocol.encodeRequest(request))
    while (true) {
      val text = FrameCodec.read(input) ?: throw EOFException("Peer closed the connection")
      val response = CommandResponse.parse(text)
      if (response.requestId == request.requestId) {
        lastUsed = System.currentTimeMillis()
        return response
      }
    }
  }

  fun close() = runCatching { socket.close() }
}

/** Pooled client connections to paired peers (one persistent connection per peer). */
class CommandClient(private val core: LanControlCore) {
  companion object {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000

    fun newRequestId(): String = UUID.randomUUID().toString()

    /** Translates network failures into protocol error codes the UI can act on. */
    fun errorResponse(requestId: String, e: Throwable): CommandResponse = when (e) {
      is ProtocolException -> CommandResponse.fail(requestId, e.code, e.message)
      is SSLHandshakeException -> CommandResponse.fail(
        requestId, ErrorCode.HANDSHAKE_FAILED,
        "The other phone rejected this device. It may have forgotten the pairing; pair again.",
      )
      is SocketTimeoutException -> CommandResponse.fail(requestId, ErrorCode.TIMEOUT, "No response in time")
      is ConnectException, is NoRouteToHostException -> CommandResponse.fail(
        requestId, ErrorCode.UNREACHABLE, "Could not connect (${e.message})",
      )
      is SSLException -> CommandResponse.fail(requestId, ErrorCode.HANDSHAKE_FAILED, "Secure connection failed: ${e.message}")
      is IOException -> CommandResponse.fail(requestId, ErrorCode.UNREACHABLE, e.message ?: "Network error")
      else -> CommandResponse.fail(requestId, ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName)
    }
  }

  private val connections = ConcurrentHashMap<String, PeerConnection>()
  private val connectLocks = ConcurrentHashMap<String, Mutex>()

  /** Opens a fresh connection that only accepts a server presenting [expectedFingerprint]. */
  fun open(host: String, port: Int, expectedFingerprint: String): PeerConnection {
    val ctx = Tls.context(core.identity, PinnedTrustManager { it == expectedFingerprint })
    val socket = ctx.socketFactory.createSocket() as SSLSocket
    try {
      Tls.configure(socket)
      socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
      socket.soTimeout = HANDSHAKE_TIMEOUT_MS
      socket.startHandshake()
    } catch (e: Exception) {
      runCatching { socket.close() }
      throw e
    }
    return PeerConnection(socket, host, port)
  }

  suspend fun send(peerId: String, command: Command, timeoutMs: Int): CommandResponse = withContext(Dispatchers.IO) {
    val request = CommandRequest(newRequestId(), command)
    var attempt = 0
    while (true) {
      val pooled = connections[peerId]?.takeIf { it.isOpen }
      val conn = try {
        pooled ?: connect(peerId)
      } catch (e: Exception) {
        return@withContext errorResponse(request.requestId, e)
      } ?: return@withContext CommandResponse.fail(
        request.requestId, ErrorCode.UNREACHABLE, "No known address for this device yet",
      )
      try {
        return@withContext conn.lock.withLock { conn.exchange(request, timeoutMs) }
      } catch (e: SocketTimeoutException) {
        drop(peerId, conn)
        return@withContext errorResponse(request.requestId, e)
      } catch (e: IOException) {
        drop(peerId, conn)
        // A pooled connection may have been closed by the peer's idle timeout; retry once fresh.
        if (pooled == null || attempt++ > 0) return@withContext errorResponse(request.requestId, e)
      } catch (e: ProtocolException) {
        drop(peerId, conn)
        return@withContext errorResponse(request.requestId, e)
      }
    }
    @Suppress("UNREACHABLE_CODE")
    throw IllegalStateException()
  }

  private suspend fun connect(peerId: String): PeerConnection? =
    connectLocks.getOrPut(peerId) { Mutex() }.withLock {
      connections[peerId]?.takeIf { it.isOpen }?.let { return@withLock it }
      val (host, port) = core.addressOf(peerId) ?: return@withLock null
      open(host, port, peerId).also { connections[peerId] = it }
    }

  private fun drop(peerId: String, conn: PeerConnection) {
    conn.close()
    connections.remove(peerId, conn)
  }

  /** Called when discovery reports a new address for a peer. */
  fun invalidate(peerId: String, host: String, port: Int) {
    val c = connections[peerId] ?: return
    if (c.host != host || c.port != port) {
      c.close()
      connections.remove(peerId, c)
    }
  }

  fun close(peerId: String) {
    connections.remove(peerId)?.close()
  }

  fun closeIdle(maxIdleMs: Long) {
    val now = System.currentTimeMillis()
    connections.entries.toList().forEach { (id, c) ->
      if (now - c.lastUsed > maxIdleMs && !c.lock.isLocked) {
        c.close()
        connections.remove(id, c)
      }
    }
  }

  fun closeAll() {
    connections.values.forEach { it.close() }
    connections.clear()
  }
}
