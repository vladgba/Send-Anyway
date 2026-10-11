package x.vladgba.sendanyway.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted, authenticated message framing over any byte stream (TCP socket, Bluetooth socket…).
 *
 * Frame: `u32 length` + AES-256-GCM ciphertext (incl. 16-byte tag).
 * The 12-byte IV is never sent: it is `direction (u32) || counter (u64)`, kept in sync on both
 * sides, so frames can't be reordered, replayed within a connection or reflected back.
 * The session id is bound in as additional authenticated data.
 *
 * A peer that does not know the key fails on the very first frame, which is how the
 * receiving side authenticates incoming connections.
 */
class SecureChannel(
    key: ByteArray,
    private val sessionId: ByteArray,
    initiator: Boolean,
    input: InputStream,
    output: OutputStream,
) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val sendDir = if (initiator) 1 else 2
    private val recvDir = if (initiator) 2 else 1
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val encCipher = Cipher.getInstance(TRANSFORMATION)
    private val decCipher = Cipher.getInstance(TRANSFORMATION)
    private val inp = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))

    @Synchronized
    fun send(plain: ByteArray, off: Int = 0, len: Int = plain.size) {
        require(len <= MAX_PLAINTEXT) { "frame too large: $len" }
        encCipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv(sendDir, sendCounter++)))
        encCipher.updateAAD(sessionId)
        val ct = encCipher.doFinal(plain, off, len)
        out.writeInt(ct.size)
        out.write(ct)
        out.flush()
    }

    fun receive(): ByteArray {
        val len = inp.readInt()
        if (len < TAG_BITS / 8 || len > MAX_PLAINTEXT + TAG_BITS / 8) throw IOException("bad frame length $len")
        val ct = ByteArray(len)
        inp.readFully(ct)
        decCipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv(recvDir, recvCounter++)))
        decCipher.updateAAD(sessionId)
        return try {
            decCipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw AuthException()
        }
    }

    private fun iv(dir: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()

    class AuthException : IOException("authentication failed (wrong session key)")

    companion object {
        const val MAX_PLAINTEXT = 256 * 1024
        /** Bytes added per frame on the wire: u32 length + 16-byte GCM tag. */
        const val OVERHEAD = 4 + 16
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
