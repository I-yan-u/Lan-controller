package expo.modules.lancontrol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FrameTooLargeException(size: Int) : IOException("Frame of $size bytes exceeds ${FrameCodec.MAX_FRAME_BYTES}")

/** Frames are a 4-byte big-endian length followed by that many bytes of UTF-8 JSON. */
object FrameCodec {
  const val MAX_FRAME_BYTES = 64 * 1024

  fun write(out: OutputStream, text: String) {
    val body = text.toByteArray(Charsets.UTF_8)
    if (body.size > MAX_FRAME_BYTES) throw FrameTooLargeException(body.size)
    val header = byteArrayOf(
      (body.size ushr 24).toByte(),
      (body.size ushr 16).toByte(),
      (body.size ushr 8).toByte(),
      body.size.toByte(),
    )
    out.write(header + body)
    out.flush()
  }

  /** Returns null on a clean EOF before any header byte; throws on truncation or oversize. */
  fun read(input: InputStream): String? {
    val din = input as? DataInputStream ?: DataInputStream(input)
    val first = din.read()
    if (first == -1) return null
    val size = try {
      (first shl 24) or (din.readUnsignedByte() shl 16) or (din.readUnsignedByte() shl 8) or din.readUnsignedByte()
    } catch (e: EOFException) {
      throw IOException("Truncated frame header", e)
    }
    if (size < 0 || size > MAX_FRAME_BYTES) throw FrameTooLargeException(size)
    val body = ByteArray(size)
    try {
      din.readFully(body)
    } catch (e: EOFException) {
      throw IOException("Truncated frame body", e)
    }
    return String(body, Charsets.UTF_8)
  }
}
