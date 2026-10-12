package x.vladgba.sendanyway.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Last-resort transport: the whole transfer is packed into one compressed "bundle", cut into
 * chunks, and each chunk is shown as a QR code in a looping slideshow. One-way, no pairing needed.
 *
 * Bundle (DEFLATE-compressed): `u16 count`, then per item `ItemInfo` (exact size) + raw bytes.
 */
object QrBundle {
    /** Reads every item fully into memory — QR mode is only practical for small payloads anyway. */
    fun build(items: List<OutgoingItem>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bos, Deflater(Deflater.BEST_COMPRESSION))).use { out ->
            out.writeShort(items.size)
            for (item in items) {
                val data = item.open().use { it.readBytes() }
                item.info.withSize(data.size.toLong()).write(out)
                out.write(data)
            }
        }
        return bos.toByteArray()
    }

    fun parse(bundle: ByteArray, sink: ReceiveSink): List<ItemInfo> {
        val result = ArrayList<ItemInfo>()
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bundle))).use { inp ->
            val count = inp.readUnsignedShort()
            val buf = ByteArray(32 * 1024)
            for (i in 0 until count) {
                val info = ItemInfo.read(inp)
                var ok = false
                val out = sink.open(i, info)
                try {
                    var left = info.size
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("bundle truncated")
                        out.write(buf, 0, n)
                        left -= n
                    }
                    ok = true
                } finally {
                    try { out.close() } catch (_: IOException) { ok = false }
                    sink.finish(i, info, ok)
                }
                result += info
            }
        }
        return result
    }
}

/**
 * Cuts a bundle into self-describing QR frames, with a systematic fountain code on top.
 *
 * Frame header (16 bytes): `u8 MAGIC, u8 TYPE_QR_DATA, u32 crc32(bundle), u32 bundleLength,
 * u16 chunkSize, u32 seq`, then exactly one chunk of payload.
 *
 * - `seq < K` (K = number of chunks): plain source chunk `seq` (the last one may be shorter).
 * - `seq >= K`: **repair** chunk = XOR of a pseudo-random subset of source chunks
 *   (padded to chunkSize), the subset derived from (seq, crc) by [repairSet] on both sides.
 *
 * Schedule ([seqAt]): one full pass of source chunks, then source and repair alternate. A
 * missing chunk no longer costs a whole loop: any repair frame that covers it plus already
 * known chunks rebuilds it ("peeling" in [QrAssembler]).
 */
class QrSlicer(private val bundle: ByteArray, val chunkSize: Int) {
    val crc: Int = CRC32().apply { update(bundle) }.value.toInt()
    val count: Int = maxOf(1, (bundle.size + chunkSize - 1) / chunkSize)

    init {
        require(chunkSize in 16..MAX_CHUNK) { "chunk size out of range" }
        require(count <= 0xFFFF) { "bundle too large for QR transfer" }
    }

    /** Frame to show as the n-th one of the slideshow. */
    fun frameAt(n: Long): ByteArray = frame(seqAt(n, count))

    fun frame(seq: Int): ByteArray {
        val payload = if (seq < count) {
            val off = seq * chunkSize
            bundle.copyOfRange(off, minOf(bundle.size, off + chunkSize))
        } else {
            val out = ByteArray(chunkSize)
            for (i in repairSet(seq, count, crc)) {
                val off = i * chunkSize
                val len = minOf(chunkSize, bundle.size - off)
                for (j in 0 until len) out[j] = (out[j].toInt() xor bundle[off + j].toInt()).toByte()
            }
            out
        }
        return ByteBuffer.allocate(HEADER + payload.size)
            .put(Wire.MAGIC.toByte()).put(Wire.TYPE_QR_DATA.toByte())
            .putInt(crc).putInt(bundle.size)
            .putShort(chunkSize.toShort()).putInt(seq)
            .put(payload)
            .array()
    }

    companion object {
        const val HEADER = 16
        const val MAX_CHUNK = 2900
        private val DEGREES = intArrayOf(2, 3, 4, 6, 8, 12, 16, 32)

        /** First pass: all sources. Then alternate source (cycling) and a fresh repair frame. */
        fun seqAt(n: Long, k: Int): Int {
            if (n < k) return n.toInt()
            val m = n - k
            return if (m % 2 == 0L) ((m / 2) % k).toInt()
            else (k + ((m / 2) % (Int.MAX_VALUE - k))).toInt()
        }

        /** Source chunks XOR-ed into repair frame [seq]. Deterministic, identical on both sides. */
        fun repairSet(seq: Int, k: Int, crc: Int): IntArray {
            var x = (seq.toLong() shl 32) xor (crc.toLong() and 0xFFFFFFFFL) xor -0x61c8864680b583ebL
            fun next(): Long {
                x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
                return x ushr 1
            }
            val d = minOf(k, DEGREES[(next() % DEGREES.size).toInt()])
            val set = LinkedHashSet<Int>()
            while (set.size < d) set += (next() % k).toInt()
            return set.toIntArray()
        }
    }
}

/**
 * Collects source and repair frames in any order, with duplicates; restarts automatically when a
 * new transfer appears. Missing chunks are rebuilt from repair frames by peeling: a repair frame
 * with exactly one unknown chunk left reveals it, which may unlock other stored repair frames.
 */
class QrAssembler {
    enum class Result { IGNORED, DUPLICATE, NEW_CHUNK, COMPLETE }

    var crc = 0; private set
    var count = 0; private set
    /** Chunks known so far, received directly or recovered. */
    var received = 0; private set
    var recovered = 0; private set
    /** Source index of the last frame seen, or -1 if it was a repair frame. */
    var lastIndex = -1; private set
    /** Chunks covered by the last repair frame (for the UI), empty for source frames. */
    var lastRepair: IntArray = IntArray(0); private set

    private var length = 0
    private var chunkSize = 0
    private var chunks: Array<ByteArray?> = emptyArray()
    private var state = ByteArray(0)
    private val repairs = ArrayList<Repair>()

    private class Repair(val unknown: MutableSet<Int>, val data: ByteArray)

    val active: Boolean get() = count > 0

    /** 0 = missing, 1 = received, 2 = recovered from repair frames. */
    fun stateOf(i: Int): Int = state[i].toInt()

    fun offer(frame: ByteArray): Result {
        if (Wire.typeOf(frame) != Wire.TYPE_QR_DATA || frame.size < QrSlicer.HEADER) return Result.IGNORED
        val bb = ByteBuffer.wrap(frame)
        bb.position(2)
        val fCrc = bb.int
        val fLen = bb.int
        val fChunk = bb.short.toInt() and 0xFFFF
        val seq = bb.int
        if (fLen < 0 || fLen > MAX_BUNDLE || fChunk == 0 || seq < 0) return Result.IGNORED
        val k = maxOf(1, (fLen + fChunk - 1) / fChunk)

        if (!active || fCrc != crc || fLen != length || fChunk != chunkSize) {
            reset()
            crc = fCrc; length = fLen; chunkSize = fChunk; count = k
            chunks = arrayOfNulls(k)
            state = ByteArray(k)
        }
        val payload = frame.copyOfRange(QrSlicer.HEADER, frame.size)
        val before = received

        if (seq < k) {
            lastIndex = seq
            lastRepair = IntArray(0)
            if (payload.size != minOf(chunkSize, length - seq * chunkSize)) return Result.IGNORED
            if (chunks[seq] != null) return Result.DUPLICATE
            learn(seq, payload.copyOf(chunkSize), 1)
        } else {
            if (payload.size != chunkSize) return Result.IGNORED
            val set = QrSlicer.repairSet(seq, k, crc)
            lastIndex = -1
            lastRepair = set
            val unknown = HashSet<Int>()
            for (i in set) {
                val c = chunks[i]
                if (c == null) unknown += i else xorInto(payload, c)
            }
            when {
                unknown.isEmpty() -> return Result.DUPLICATE
                unknown.size == 1 -> learn(unknown.first(), payload, 2)
                repairs.size < MAX_REPAIRS -> repairs += Repair(unknown, payload)
            }
        }
        return when {
            received == count -> Result.COMPLETE
            received > before -> Result.NEW_CHUNK
            else -> Result.DUPLICATE
        }
    }

    /** Stores a chunk and peels every stored repair frame it unlocks. */
    private fun learn(index: Int, data: ByteArray, how: Int) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue += index to data
        var first = true
        while (queue.isNotEmpty()) {
            val (i, d) = queue.removeFirst()
            if (chunks[i] != null) continue
            chunks[i] = d
            state[i] = (if (first) how else 2).toByte()
            if (state[i].toInt() == 2) recovered++
            received++
            first = false
            val it = repairs.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (!r.unknown.remove(i)) continue
                xorInto(r.data, d)
                if (r.unknown.size == 1) {
                    queue += r.unknown.first() to r.data
                    it.remove()
                } else if (r.unknown.isEmpty()) {
                    it.remove()
                }
            }
        }
    }

    private fun xorInto(target: ByteArray, src: ByteArray) {
        for (j in target.indices) target[j] = (target[j].toInt() xor src[j].toInt()).toByte()
    }

    /** Joined bundle if complete and the checksum matches, otherwise null (and the state is reset). */
    fun bundle(): ByteArray? {
        if (!active || received != count) return null
        val out = ByteArray(length)
        chunks.forEachIndexed { i, c ->
            val off = i * chunkSize
            c!!.copyInto(out, off, 0, minOf(chunkSize, length - off))
        }
        val ok = CRC32().apply { update(out) }.value.toInt() == crc
        if (!ok) reset()
        return if (ok) out else null
    }

    fun reset() {
        crc = 0; count = 0; received = 0; recovered = 0; length = 0; chunkSize = 0
        lastIndex = -1; lastRepair = IntArray(0)
        chunks = emptyArray(); state = ByteArray(0)
        repairs.clear()
    }

    companion object {
        const val MAX_BUNDLE = 64 * 1024 * 1024
        private const val MAX_REPAIRS = 20_000

        /** Transfer id (bundle CRC) of a slideshow frame, or null if it isn't one. */
        fun crcOf(frame: ByteArray): Int? =
            if (Wire.typeOf(frame) == Wire.TYPE_QR_DATA && frame.size >= QrSlicer.HEADER) ByteBuffer.wrap(frame, 2, 4).int else null
    }
}
