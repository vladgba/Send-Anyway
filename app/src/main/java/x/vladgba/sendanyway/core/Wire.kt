package x.vladgba.sendanyway.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Constants shared by everything that goes "on the wire" (QR codes and stream transports).
 *
 * Every QR code produced by the app starts with [MAGIC] followed by a type byte, so the
 * scanner can tell a pairing code from a slideshow frame and ignore foreign QR codes.
 * MAGIC is deliberately not a digit / alphanumeric character, which forces QR byte mode.
 */
object Wire {
    const val MAGIC: Int = 0xA5
    const val TYPE_PAIRING: Int = 0x01
    const val TYPE_QR_DATA: Int = 0x02
    const val VERSION: Int = 2  // 2: ACK flow control

    /** Returns the QR payload type, or -1 if this is not one of our codes. */
    fun typeOf(bytes: ByteArray): Int =
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == MAGIC) bytes[1].toInt() and 0xFF else -1
}

internal fun utf8Limited(s: String, maxBytes: Int): ByteArray {
    var str = s
    var b = str.toByteArray(Charsets.UTF_8)
    while (b.size > maxBytes && str.isNotEmpty()) {
        str = str.dropLast(maxOf(1, (b.size - maxBytes) / 4))
        b = str.toByteArray(Charsets.UTF_8)
    }
    return b
}

internal fun DataOutputStream.writeStr(s: String, maxBytes: Int = 1024) {
    val b = utf8Limited(s, maxBytes)
    writeShort(b.size)
    write(b)
}

internal fun DataInputStream.readStr(): String {
    val n = readUnsignedShort()
    val b = ByteArray(n)
    readFully(b)
    return String(b, Charsets.UTF_8)
}
