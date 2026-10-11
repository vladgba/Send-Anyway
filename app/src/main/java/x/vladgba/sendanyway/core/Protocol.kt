package x.vladgba.sendanyway.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Transfer protocol on top of [SecureChannel]. Every message is one encrypted frame whose
 * first byte is the message type.
 *
 * ```
 * receiver (connects)                 sender (shows QR, listens)
 *   HELLO  name            ──▶
 *                          ◀──        OFFER  name, items[]
 *   ACCEPT                 ──▶
 *                          ◀──        DATA bytes … ITEM_END   (per item, in order)
 *   ACK  u64 bytesSoFar    ──▶        (every ACK_EVERY bytes)
 *                          ◀──        DONE
 *   DONE                   ──▶        (ack: everything was written)
 * ```
 * Flow control: the sender never has more than [WINDOW] unacknowledged bytes in flight.
 * That keeps OS/Bluetooth buffers small, and the sender's progress reflects bytes the
 * receiver actually has, not bytes sitting in a local socket buffer.
 *
 * Either side may send ERROR(message) instead of the next expected frame.
 */
object Protocol {
    const val HELLO = 1
    const val OFFER = 2
    const val ACCEPT = 3
    const val DATA = 4
    const val ITEM_END = 5
    const val DONE = 6
    const val ERROR = 7
    const val ACK = 8

    /** Default DATA payload for fast links (TCP). Slow links pass a smaller one. */
    const val DEFAULT_CHUNK = 64 * 1024
    const val ACK_EVERY = 32 * 1024L
    const val WINDOW = 256 * 1024L

    /** Bytes a DATA frame adds on the wire besides the payload: type byte + SecureChannel overhead. */
    const val DATA_OVERHEAD = 1 + SecureChannel.OVERHEAD

    class Offer(val peerName: String, val items: List<ItemInfo>)

    class RemoteError(msg: String) : IOException(msg)

    // ---- sender side ----------------------------------------------------------------------

    /** First frame on a freshly accepted connection. Throws [SecureChannel.AuthException] for strangers. */
    fun readHello(ch: SecureChannel): String {
        val m = ch.receive()
        expect(m, HELLO)
        return body(m).readStr()
    }

    fun send(
        ch: SecureChannel,
        myName: String,
        items: List<OutgoingItem>,
        listener: TransferListener,
        chunk: Int = DEFAULT_CHUNK,
    ) {
        require(chunk in 64..SecureChannel.MAX_PLAINTEXT - 1)
        ch.send(message(OFFER) {
            writeStr(myName)
            writeShort(items.size)
            items.forEach { it.info.write(this) }
        })
        expect(ch.receive(), ACCEPT)

        val total = items.map { it.info }.knownTotal()
        var sent = 0L
        var acked = 0L
        val frame = ByteArray(chunk + 1)
        frame[0] = DATA.toByte()

        fun readAck() {
            val m = ch.receive()
            expect(m, ACK)
            acked = body(m).readLong()
            listener.onProgress(acked, maxOf(total, acked))
        }

        items.forEachIndexed { index, item ->
            item.open().use { inp ->
                while (true) {
                    val n = inp.read(frame, 1, chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    ch.send(frame, 0, n + 1)
                    sent += n
                    while (sent - acked > WINDOW) readAck()
                }
            }
            ch.send(byteArrayOf(ITEM_END.toByte()))
            listener.onItemDone(index, item.info)
        }
        ch.send(byteArrayOf(DONE.toByte()))
        // Drain remaining ACKs until the receiver confirms everything.
        while (true) {
            val m = ch.receive()
            when (type(m)) {
                ACK -> {
                    acked = body(m).readLong()
                    listener.onProgress(acked, maxOf(total, acked))
                }
                DONE -> break
                else -> expect(m, DONE)
            }
        }
        listener.onProgress(sent, maxOf(total, sent))
    }

    // ---- receiver side --------------------------------------------------------------------

    /** Says hello and waits for the offer. A successful return proves the peer knows the key. */
    fun requestOffer(ch: SecureChannel, myName: String): Offer {
        ch.send(message(HELLO) { writeStr(myName) })
        val m = ch.receive()
        expect(m, OFFER)
        val inp = body(m)
        val peer = inp.readStr()
        val count = inp.readUnsignedShort()
        return Offer(peer, List(count) { ItemInfo.read(inp) })
    }

    fun receive(ch: SecureChannel, offer: Offer, sink: ReceiveSink, listener: TransferListener) {
        ch.send(byteArrayOf(ACCEPT.toByte()))
        val total = offer.items.knownTotal()
        var done = 0L
        var lastAck = 0L
        offer.items.forEachIndexed { index, info ->
            var ok = false
            val out = sink.open(index, info)
            try {
                while (true) {
                    val m = ch.receive()
                    when (type(m)) {
                        DATA -> {
                            out.write(m, 1, m.size - 1)
                            done += m.size - 1
                            listener.onProgress(done, maxOf(total, done))
                            if (done - lastAck >= ACK_EVERY) {
                                ch.send(message(ACK) { writeLong(done) })
                                lastAck = done
                            }
                        }
                        ITEM_END -> break
                        else -> expect(m, DATA)
                    }
                }
                out.flush()
                ok = true
            } finally {
                try { out.close() } catch (_: IOException) { ok = false }
                sink.finish(index, info, ok)
            }
            listener.onItemDone(index, info)
        }
        expect(ch.receive(), DONE)
        ch.send(byteArrayOf(DONE.toByte()))
    }

    // ---- helpers --------------------------------------------------------------------------

    fun sendError(ch: SecureChannel, msg: String) {
        try { ch.send(message(ERROR) { writeStr(msg) }) } catch (_: Exception) {}
    }

    private fun type(m: ByteArray) = if (m.isEmpty()) -1 else m[0].toInt() and 0xFF

    private fun expect(m: ByteArray, wanted: Int) {
        val t = type(m)
        if (t == wanted) return
        if (t == ERROR) throw RemoteError(body(m).readStr())
        throw IOException("protocol error: expected $wanted, got $t")
    }

    private fun body(m: ByteArray) = DataInputStream(ByteArrayInputStream(m, 1, m.size - 1))

    private inline fun message(type: Int, block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.writeByte(type); it.block() }
        return bos.toByteArray()
    }
}
