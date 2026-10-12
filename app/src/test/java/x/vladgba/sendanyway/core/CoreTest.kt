package x.vladgba.sendanyway.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Random
import kotlin.concurrent.thread

class CoreTest {

    private class MemorySink : ReceiveSink {
        val data = HashMap<Int, ByteArrayOutputStream>()
        val finished = HashMap<Int, Boolean>()
        override fun open(index: Int, info: ItemInfo): OutputStream = ByteArrayOutputStream().also { data[index] = it }
        override fun finish(index: Int, info: ItemInfo, ok: Boolean) { finished[index] = ok }
    }

    private class BytesItem(name: String, private val bytes: ByteArray, knownSize: Boolean = true) : OutgoingItem {
        override val info = ItemInfo(ItemInfo.KIND_FILE, name, "application/octet-stream", if (knownSize) bytes.size.toLong() else -1)
        override fun open() = bytes.inputStream()
    }

    private fun randomBytes(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }

    @Test
    fun pairingRoundTrip() {
        val (id, key) = Pairing.newSecrets()
        val p = Pairing(id, key, "Pixel 8 Привіт", 45123, listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)), true)
        val bytes = p.encode()
        assertEquals(Wire.TYPE_PAIRING, Wire.typeOf(bytes))
        val d = Pairing.decode(bytes)!!
        assertArrayEquals(id, d.sessionId)
        assertArrayEquals(key, d.key)
        assertEquals("Pixel 8 Привіт", d.deviceName)
        assertEquals(45123, d.lanPort)
        assertArrayEquals(p.lanAddresses[0], d.lanAddresses[0])
        assertTrue(d.bluetooth)
        assertTrue(bytes.size < 120)
        assertNull(d.wifiGroup)
        assertEquals(p.rfcommUuid, Pairing(id, key, "", 0, emptyList(), false).rfcommUuid)
        val h = Pairing.decode(p.withWifiGroup(Pairing.WifiGroup("DIRECT-ab-SendAnyway", "pass word 123"), p.lanAddresses).encode())!!
        assertEquals("DIRECT-ab-SendAnyway", h.wifiGroup!!.networkName)
        assertEquals("pass word 123", h.wifiGroup!!.passphrase)
        assertArrayEquals(key, h.key)
        assertNull(Pairing.decode("hello".toByteArray()))
        assertNull(Pairing.decode(bytes.copyOf(20)))
    }

    /** Two connected in-memory "sockets". */
    private fun pipePair(buffer: Int = 1 shl 20): Pair<Pair<PipedInputStream, PipedOutputStream>, Pair<PipedInputStream, PipedOutputStream>> {
        val aIn = PipedInputStream(buffer); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(buffer); val aOut = PipedOutputStream(bIn)
        return (aIn to aOut) to (bIn to bOut)
    }

    @Test
    fun fullTransferOverSecureChannel() {
        val (id, key) = Pairing.newSecrets()
        val (a, b) = pipePair()
        val items = listOf(
            TextItem("Hello, світ!"),
            BytesItem("big.bin", randomBytes(300_000)),
            BytesItem("unknown-size.bin", randomBytes(70_000, 7), knownSize = false),
            BytesItem("empty", ByteArray(0)),
        )
        var senderError: Throwable? = null
        val sender = thread {
            try {
                val ch = SecureChannel(key, id, initiator = false, input = a.first, output = a.second)
                assertEquals("receiver", Protocol.readHello(ch))
                Protocol.send(ch, "sender", items, object : TransferListener {})
            } catch (t: Throwable) { senderError = t }
        }
        val ch = SecureChannel(key, id, initiator = true, input = b.first, output = b.second)
        val offer = Protocol.requestOffer(ch, "receiver")
        assertEquals("sender", offer.peerName)
        assertEquals(4, offer.items.size)
        val sink = MemorySink()
        var lastProgress = 0L
        Protocol.receive(ch, offer, sink, object : TransferListener {
            override fun onProgress(done: Long, total: Long) { lastProgress = done }
        })
        sender.join(5000)
        senderError?.let { throw it }
        assertEquals("Hello, світ!", sink.data[0]!!.toString("UTF-8"))
        assertArrayEquals(randomBytes(300_000), sink.data[1]!!.toByteArray())
        assertArrayEquals(randomBytes(70_000, 7), sink.data[2]!!.toByteArray())
        assertEquals(0, sink.data[3]!!.size())
        assertTrue(sink.finished.values.all { it })
        assertEquals(300_000L + 70_000 + "Hello, світ!".toByteArray().size, lastProgress)
    }

    /** Bluetooth-like: SDU-sized frames over a tiny buffer (heavy back-pressure). Must not deadlock. */
    @Test(timeout = 20_000)
    fun smallFramesTinyBufferWithAcks() {
        val (id, key) = Pairing.newSecrets()
        val (a, b) = pipePair(buffer = 2048)
        val data = randomBytes(1_000_000, 11)
        val items = listOf(BytesItem("a.bin", data), TextItem("tail"))
        val senderProgress = ArrayList<Long>()
        var senderError: Throwable? = null
        val sender = thread {
            try {
                val ch = SecureChannel(key, id, initiator = false, input = a.first, output = a.second)
                Protocol.readHello(ch)
                Protocol.send(ch, "s", items, object : TransferListener {
                    override fun onProgress(done: Long, total: Long) { senderProgress += done }
                }, chunk = 512 - Protocol.DATA_OVERHEAD)
            } catch (t: Throwable) { senderError = t }
        }
        val ch = SecureChannel(key, id, initiator = true, input = b.first, output = b.second)
        val offer = Protocol.requestOffer(ch, "r")
        val sink = MemorySink()
        Protocol.receive(ch, offer, sink, object : TransferListener {})
        sender.join(5000)
        senderError?.let { throw it }
        assertArrayEquals(data, sink.data[0]!!.toByteArray())
        assertEquals("tail", sink.data[1]!!.toString("UTF-8"))
        // Sender progress comes from ACKs: many intermediate steps, monotonic, ends at the total.
        assertTrue(senderProgress.size > 20)
        assertEquals(senderProgress, senderProgress.sorted())
        assertEquals(data.size + 4L, senderProgress.last())
    }

    @Test
    fun wrongKeyIsRejected() {
        val (id, key) = Pairing.newSecrets()
        val (_, otherKey) = Pairing.newSecrets()
        val (a, b) = pipePair()
        thread {
            try {
                Protocol.requestOffer(SecureChannel(otherKey, id, true, b.first, b.second), "intruder")
            } catch (_: Exception) {}
        }
        try {
            Protocol.readHello(SecureChannel(key, id, false, a.first, a.second))
            fail("should not authenticate")
        } catch (e: SecureChannel.AuthException) {
            // expected
        }
    }

    @Test
    fun qrSlideshowRoundTrip() {
        val items = listOf(TextItem("Some text ".repeat(200)), BytesItem("photo.jpg", randomBytes(20_000, 3)))
        val bundle = QrBundle.build(items)
        val slicer = QrSlicer(bundle, 500)
        val asm = QrAssembler()

        // A frame from some other transfer first, then shuffled frames with duplicates.
        asm.offer(QrSlicer(QrBundle.build(listOf(TextItem("other"))), 100).frame(0))
        val order = (0 until slicer.count).toMutableList().also { it.shuffle(Random(5)) }
        var result = QrAssembler.Result.IGNORED
        for (i in order + order.take(3)) {
            val r = asm.offer(slicer.frame(i))
            if (r == QrAssembler.Result.COMPLETE) result = r
        }
        assertEquals(QrAssembler.Result.COMPLETE, result)
        val joined = asm.bundle()
        assertNotNull(joined)
        val sink = MemorySink()
        val infos = QrBundle.parse(joined!!, sink)
        assertEquals(2, infos.size)
        assertTrue(infos[0].isText)
        assertEquals("Some text ".repeat(200), sink.data[0]!!.toString("UTF-8"))
        assertArrayEquals(randomBytes(20_000, 3), sink.data[1]!!.toByteArray())
    }

    /** 10% of frames lost at random: repair frames must finish without waiting for full loops. */
    @Test
    fun qrFountainRecoversLostChunks() {
        val data = randomBytes(60_000, 21)
        val bundle = QrBundle.build(listOf(BytesItem("x.bin", data)))
        val slicer = QrSlicer(bundle, 500)
        val asm = QrAssembler()
        val rnd = Random(4)
        var shown = 0L
        var done = false
        while (!done && shown < slicer.count * 4L) {
            val frame = slicer.frameAt(shown++)
            if (rnd.nextDouble() < 0.10) continue // camera missed it
            done = asm.offer(frame) == QrAssembler.Result.COMPLETE
        }
        assertTrue("not complete after ${shown} frames", done)
        // Plain looping would need ~2 full passes for 10% loss; the fountain needs far fewer.
        assertTrue("took $shown frames for ${slicer.count} chunks", shown < slicer.count * 1.6)
        assertTrue(asm.recovered > 0)
        val sink = MemorySink()
        QrBundle.parse(asm.bundle()!!, sink)
        assertArrayEquals(data, sink.data[0]!!.toByteArray())
    }

    @Test
    fun qrAssemblerIgnoresGarbage() {
        val asm = QrAssembler()
        assertEquals(QrAssembler.Result.IGNORED, asm.offer("https://example.com".toByteArray()))
        assertEquals(QrAssembler.Result.IGNORED, asm.offer(byteArrayOf(0xA5.toByte(), 2, 0)))
        assertNull(asm.bundle())
    }

    @Test
    fun resumableTransferWithCrcValidation() {
        val (id, key) = Pairing.newSecrets()
        val (a, b) = pipePair()
        val data = randomBytes(100_000, 42)
        val items = listOf(BytesItem("test.bin", data))

        val prefixSize = 40_000
        val prefix = data.copyOf(prefixSize)
        val resumedSink = object : ReceiveSink {
            val bos = ByteArrayOutputStream().apply { write(prefix) }
            var finishedOk = false

            override fun offset(index: Int, info: ItemInfo): Long = prefixSize.toLong()

            override fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: java.util.zip.CRC32) {
                crc.update(prefix, 0, offset.toInt())
            }

            override fun open(index: Int, info: ItemInfo): OutputStream = bos

            override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
                finishedOk = ok
            }
        }

        var senderError: Throwable? = null
        val sender = thread {
            try {
                val ch = SecureChannel(key, id, initiator = false, input = a.first, output = a.second)
                Protocol.readHello(ch)
                Protocol.send(ch, "sender", items, object : TransferListener {})
            } catch (t: Throwable) { senderError = t }
        }

        val ch = SecureChannel(key, id, initiator = true, input = b.first, output = b.second)
        val offer = Protocol.requestOffer(ch, "receiver")
        Protocol.receive(ch, offer, resumedSink, object : TransferListener {})

        sender.join(5000)
        senderError?.let { throw it }
        assertTrue(resumedSink.finishedOk)
        assertArrayEquals(data, resumedSink.bos.toByteArray())
    }

    @Test
    fun corruptedResumeFailsCrc() {
        val (id, key) = Pairing.newSecrets()
        val (a, b) = pipePair()
        val data = randomBytes(100_000, 42)
        val items = listOf(BytesItem("test.bin", data))

        val prefixSize = 40_000
        val corruptedPrefix = randomBytes(prefixSize, 99) // different bytes!
        val badSink = object : ReceiveSink {
            val bos = ByteArrayOutputStream().apply { write(corruptedPrefix) }
            var finishedOk: Boolean? = null

            override fun offset(index: Int, info: ItemInfo): Long = prefixSize.toLong()

            override fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: java.util.zip.CRC32) {
                crc.update(corruptedPrefix, 0, offset.toInt())
            }

            override fun open(index: Int, info: ItemInfo): OutputStream = bos

            override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
                finishedOk = ok
            }
        }

        thread {
            try {
                val ch = SecureChannel(key, id, initiator = false, input = a.first, output = a.second)
                Protocol.readHello(ch)
                Protocol.send(ch, "sender", items, object : TransferListener {})
            } catch (_: Throwable) {}
        }

        val ch = SecureChannel(key, id, initiator = true, input = b.first, output = b.second)
        val offer = Protocol.requestOffer(ch, "receiver")
        try {
            Protocol.receive(ch, offer, badSink, object : TransferListener {})
            fail("should fail due to CRC32 mismatch")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CRC32 mismatch") == true)
        }
        assertEquals(false, badSink.finishedOk)
    }

    @Test
    fun testLargeQrChunk() {
        val data = ByteArray(10_000) { it.toByte() }
        val slicer = QrSlicer(data, 2900)
        assertEquals(4, slicer.count)
        val frame = slicer.frameAt(0)
        assertEquals(2900 + 16, frame.size)
        val asm = QrAssembler()
        for (i in 0 until slicer.count) {
            asm.offer(slicer.frameAt(i.toLong()))
        }
        val bundle = asm.bundle()
        assertNotNull(bundle)
        assertArrayEquals(data, bundle)
    }
}
