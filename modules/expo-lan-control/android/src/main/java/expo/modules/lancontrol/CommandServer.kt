package expo.modules.lancontrol

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Mutual-TLS command server on a random port. The TLS handshake itself rejects any client whose
 * certificate is not pinned, except while a pairing session is open (then the only thing an
 * unpaired client can do is send PAIR_REQUEST; see [CommandExecutor]).
 */
class CommandServer(private val core: LanControlCore) {
  companion object {
    private const val TAG = "LanCommandServer"
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /** Controllers ping every 10 s; a connection silent for this long is dropped. */
    private const val IDLE_TIMEOUT_MS = 45_000
    private const val MAX_CONNECTIONS = 16
  }

  private var serverSocket: SSLServerSocket? = null
  private var acceptJob: Job? = null
  private val activeConnections = AtomicInteger()
  private val sockets = Collections.synchronizedSet(HashSet<SSLSocket>())

  val port: Int? get() = serverSocket?.takeIf { !it.isClosed }?.localPort

  @Synchronized
  fun start(): Int {
    serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
    val trust = PinnedTrustManager { fp -> core.peers.contains(fp) || core.pairing.isOpen() }
    val ss = Tls.context(core.identity, trust).serverSocketFactory.createServerSocket(0) as SSLServerSocket
    Tls.configure(ss)
    serverSocket = ss
    acceptJob = core.scope.launch { acceptLoop(ss) }
    return ss.localPort
  }

  @Synchronized
  fun stop() {
    serverSocket?.let { runCatching { it.close() } }
    serverSocket = null
    acceptJob?.cancel()
    acceptJob = null
    synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
  }

  private fun acceptLoop(ss: SSLServerSocket) {
    while (!ss.isClosed) {
      val socket = try {
        ss.accept() as SSLSocket
      } catch (e: IOException) {
        if (ss.isClosed) break else continue
      }
      if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
        activeConnections.decrementAndGet()
        runCatching { socket.close() }
        continue
      }
      sockets.add(socket)
      core.scope.launch {
        try {
          handle(socket)
        } catch (e: Exception) {
          Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
          sockets.remove(socket)
          activeConnections.decrementAndGet()
          runCatching { socket.close() }
        }
      }
    }
  }

  private suspend fun handle(socket: SSLSocket) {
    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
    try {
      socket.startHandshake()
    } catch (e: IOException) {
      return // Untrusted certificate or protocol error: nothing to answer.
    }
    val fingerprint = Tls.peerFingerprint(socket)
    val remoteHost = socket.inetAddress?.hostAddress
    val limiter = RateLimiter()
    val input = DataInputStream(BufferedInputStream(socket.inputStream))
    val output = BufferedOutputStream(socket.outputStream)
    socket.soTimeout = IDLE_TIMEOUT_MS

    while (true) {
      val text = try {
        FrameCodec.read(input) ?: return
      } catch (e: FrameTooLargeException) {
        FrameCodec.write(output, CommandResponse.fail("unknown", ErrorCode.INVALID, e.message).toJson().toString())
        return
      }
      val response = core.executor.handle(fingerprint, text, limiter, remoteHost)
      FrameCodec.write(output, response.toJson().toString())
      // An unpaired client gets exactly one exchange per connection (its pairing attempt).
      if (!core.peers.contains(fingerprint)) return
    }
  }
}
