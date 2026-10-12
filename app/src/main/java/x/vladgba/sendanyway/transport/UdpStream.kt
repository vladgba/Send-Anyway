package x.vladgba.sendanyway.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.TreeMap
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Reliable, ordered byte stream over unreliable datagrams: the data path of the hole-punched
 * internet P2P link. Everything above it (SecureChannel, Protocol) is unchanged, as with TCP.
 *
 * Selective-repeat ARQ:
 * - Each datagram carries one segment `MAGIC(2) | type(1) | seq u32 | payload ≤ [MSS]`.
 * - The receiver answers every segment with `ACK | next-expected u32 | SACK u64`, where SACK bit i
 *   means segment `next + 1 + i` arrived out of order. Out-of-order segments are buffered.
 * - The sender retransmits a segment when its timer (RFC 6298 style RTO, backed off per retry)
 *   expires, or early once 3 later segments were SACKed and it's been out > 1.25 RTT (fast retransmit).
 * - Congestion control is TCP-like AIMD on a window counted in segments: slow start, then +1
 *   per round trip; halved once per loss window.
 * - Both sides send a keepalive every [KEEPALIVE_MS] (keeps NAT mappings open while the app layer
 *   is idle); [DEAD_MS] of silence fails the stream.
 *
 * [sendRaw] puts one datagram on the wire; [onPacket] takes every datagram from the peer.
 * [onFinished] runs once when the stream is done with the socket (after [close]'s linger, or on failure).
 */
internal class UdpStream(
    private val sendRaw: (ByteArray) -> Unit,
    private val onFinished: () -> Unit = {},
) : Closeable {

    private class Seg(val seq: Int, val type: Int, val data: ByteArray) {
        var sentAt = 0L
        var tx = 0
        var fastRetx = false
    }

    private val lock = Object()

    // ---- send side (guarded by lock)
    private val inFlight = TreeMap<Int, Seg>()
    private var nextSeq = 0
    private var cwnd = INITIAL_CWND
    private var ssthresh = MAX_CWND
    private var recoverUntil = -1
    private var srtt = 0.0
    private var rttvar = 0.0
    private var rto = INITIAL_RTO_MS

    // ---- receive side (guarded by lock)
    private var expected = 0
    private val outOfOrder = HashMap<Int, Seg>()
    private val ready = ArrayDeque<ByteArray>()
    private var readyPos = 0
    private var eof = false

    // ---- lifecycle (guarded by lock)
    private var failure: IOException? = null
    private var closing = false
    private var finished = false

    /** Diagnostics (tests): segments sent more than once, and the congestion window now. */
    @Volatile var retransmits = 0
        private set
    @Volatile var fastRetransmits = 0
        private set
    val window: Int get() = synchronized(lock) { cwnd.toInt() }

    @Volatile private var lastHeard = now()
    @Volatile private var lastSent = now()

    init {
        thread(name = "udp-stream-timer", isDaemon = true) { timerLoop() }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(lock) {
                while (true) {
                    val head = ready.firstOrNull()
                    if (head != null) {
                        val n = min(len, head.size - readyPos)
                        System.arraycopy(head, readyPos, b, off, n)
                        readyPos += n
                        if (readyPos == head.size) { ready.removeFirst(); readyPos = 0 }
                        return n
                    }
                    failure?.let { throw IOException(it.message, it) }
                    if (eof) return -1
                    if (closing) throw IOException("P2P stream closed")
                    lock.wait(250)
                }
            }
        }

        override fun available(): Int = synchronized(lock) { ready.sumOf { it.size } - readyPos }
    }

    val output: OutputStream = object : OutputStream() {
        // Only the single writer thread (SecureChannel.send is synchronized) touches the buffer.
        private val buf = ByteArray(MSS)
        private var n = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val k = min(left, MSS - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; left -= k
                if (n == MSS) emit()
            }
        }

        override fun flush() {
            if (n > 0) emit()
        }

        override fun close() = flush()

        private fun emit() {
            enqueue(TYPE_DATA, buf.copyOf(n))
            n = 0
        }
    }

    /** Blocks while the congestion window is full. */
    private fun enqueue(type: Int, data: ByteArray) = synchronized(lock) {
        while (inFlight.size >= cwnd.toInt()) {
            failure?.let { throw IOException(it.message, it) }
            if (closing) throw IOException("P2P stream closed")
            lock.wait(100)
        }
        failure?.let { throw IOException(it.message, it) }
        if (closing) throw IOException("P2P stream closed")
        val s = Seg(nextSeq++, type, data)
        inFlight[s.seq] = s
        transmit(s)
    }

    // ---- wire ---------------------------------------------------------------------------------

    private fun transmit(s: Seg) {
        if (s.tx > 0) retransmits++
        s.tx++
        s.sentAt = now()
        val p = ByteBuffer.allocate(HEADER + s.data.size)
        p.put(MAGIC0).put(MAGIC1).put(s.type.toByte()).putInt(s.seq).put(s.data)
        send(p.array())
    }

    private fun send(packet: ByteArray) {
        lastSent = now()
        try { sendRaw(packet) } catch (_: Exception) { /* lost like any datagram; the timers retry */ }
    }

    private fun sendAck() {
        var sack = 0L
        for (i in 0 until 64) if (outOfOrder.containsKey(expected + 1 + i)) sack = sack or (1L shl i)
        send(ByteBuffer.allocate(HEADER + 8).put(MAGIC0).put(MAGIC1).put(TYPE_ACK.toByte())
            .putInt(expected).putLong(sack).array())
    }

    /** Every datagram from the peer (already known to come from an authenticated address). */
    fun onPacket(p: ByteArray, len: Int = p.size) {
        if (len < HEADER || p[0] != MAGIC0 || p[1] != MAGIC1) return
        lastHeard = now()
        val type = p[2].toInt() and 0xFF
        val seq = ByteBuffer.wrap(p, 3, 4).int
        synchronized(lock) {
            if (finished) return
            when (type) {
                TYPE_DATA, TYPE_FIN -> onSegment(seq, type, p.copyOfRange(HEADER, len))
                TYPE_ACK -> if (len >= HEADER + 8) onAck(seq, ByteBuffer.wrap(p, HEADER, 8).long)
                TYPE_KEEPALIVE -> Unit
            }
        }
    }

    private fun onSegment(seq: Int, type: Int, data: ByteArray) {
        if (seq >= expected && seq < expected + RECV_WINDOW && !outOfOrder.containsKey(seq)) {
            outOfOrder[seq] = Seg(seq, type, data)
            var delivered = false
            while (true) {
                val s = outOfOrder.remove(expected) ?: break
                expected++
                if (s.type == TYPE_FIN) eof = true else if (s.data.isNotEmpty()) ready.addLast(s.data)
                delivered = true
            }
            if (delivered) lock.notifyAll()
        }
        sendAck() // also re-acks duplicates whose ACK was lost
    }

    private fun onAck(next: Int, sack: Long) {
        val now = now()
        var acked = 0
        while (inFlight.isNotEmpty() && inFlight.firstKey() < next) {
            sample(inFlight.pollFirstEntry()!!.value, now); acked++
        }
        if (sack != 0L) {
            for (i in 0 until 64) {
                if (sack and (1L shl i) != 0L) inFlight.remove(next + 1 + i)?.let { sample(it, now); acked++ }
            }
            // Fast retransmit: a segment is presumed lost once 3+ later segments were delivered AND
            // it has been out for over 1.25 RTT (RACK-style: tolerates a quarter RTT of reordering).
            val highest = next + 1 + (63 - java.lang.Long.numberOfLeadingZeros(sack))
            val reorderMs = if (srtt > 0) srtt * 1.25 else rto.toDouble()
            for (s in inFlight.values) {
                if (s.seq > highest - 3) break
                if (!s.fastRetx && now - s.sentAt >= reorderMs) {
                    s.fastRetx = true
                    fastRetransmits++
                    onLoss(s.seq)
                    transmit(s)
                }
            }
        }
        repeat(acked) { cwnd = min(MAX_CWND, if (cwnd < ssthresh) cwnd + 1 else cwnd + 1 / cwnd) }
        if (acked > 0) lock.notifyAll()
    }

    /** RTT sample (Karn: only from segments sent once) → RTO. */
    private fun sample(s: Seg, now: Long) {
        if (s.tx != 1) return
        val r = (now - s.sentAt).toDouble()
        if (srtt == 0.0) { srtt = r; rttvar = r / 2 } else {
            rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - r)
            srtt = 0.875 * srtt + 0.125 * r
        }
        rto = (srtt + max(4 * rttvar, 20.0)).toLong().coerceIn(MIN_RTO_MS, MAX_RTO_MS)
    }

    /** Multiplicative decrease, at most once per window of data in flight. */
    private fun onLoss(seq: Int) {
        if (seq <= recoverUntil) return
        ssthresh = max(MIN_CWND, cwnd / 2)
        cwnd = ssthresh
        recoverUntil = nextSeq - 1
    }

    private fun timerLoop() {
        while (true) {
            Thread.sleep(TICK_MS)
            synchronized(lock) {
                if (finished) return
                val now = now()
                for (s in inFlight.values) {
                    val timeout = min(MAX_RTO_MS, rto shl min(s.tx - 1, 4))
                    if (now - s.sentAt >= timeout) {
                        onLoss(s.seq)
                        transmit(s)
                    }
                }
                if (now - lastSent >= KEEPALIVE_MS) {
                    send(byteArrayOf(MAGIC0, MAGIC1, TYPE_KEEPALIVE.toByte(), 0, 0, 0, 0))
                }
                if (now - lastHeard >= DEAD_MS) fail(IOException("P2P peer stopped responding"))
            }
        }
    }

    private fun fail(e: IOException) {
        if (failure == null) failure = e
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        lock.notifyAll()
        try { onFinished() } catch (_: Exception) {}
    }

    /**
     * Graceful close: pending reads/writes fail now, a FIN tells the peer we're done, and the
     * socket lingers (≤ [LINGER_MS]) until everything we sent is acknowledged. Without the linger
     * the final protocol frame (DONE) could be lost with nobody left to retransmit it.
     */
    override fun close() {
        synchronized(lock) {
            if (closing || finished) return
            closing = true
            val fin = Seg(nextSeq++, TYPE_FIN, ByteArray(0))
            inFlight[fin.seq] = fin
            transmit(fin)
            lock.notifyAll()
        }
        thread(name = "udp-stream-linger", isDaemon = true) {
            val deadline = now() + LINGER_MS
            synchronized(lock) {
                while (!finished && inFlight.isNotEmpty() && now() < deadline) lock.wait(50)
                finish()
            }
        }
    }

    companion object {
        const val MAGIC0: Byte = 0x53 // 'S'
        const val MAGIC1: Byte = 0x41 // 'A'
        const val TYPE_DATA = 0x10
        const val TYPE_FIN = 0x11
        const val TYPE_ACK = 0x12
        const val TYPE_KEEPALIVE = 0x13
        const val HEADER = 7

        /** Payload per datagram: header + MSS stays well under the 1280-byte IPv6 minimum MTU. */
        const val MSS = 1180
        private const val INITIAL_CWND = 16.0
        private const val MIN_CWND = 4.0
        private const val MAX_CWND = 1024.0
        private const val RECV_WINDOW = 4096
        private const val INITIAL_RTO_MS = 600L
        private const val MIN_RTO_MS = 120L
        private const val MAX_RTO_MS = 4_000L
        private const val TICK_MS = 15L
        private const val KEEPALIVE_MS = 4_000L
        const val DEAD_MS = 20_000L
        private const val LINGER_MS = 3_000L

        private fun now() = System.nanoTime() / 1_000_000
    }
}
