package x.vladgba.sendanyway.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import x.vladgba.sendanyway.core.Pairing
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UdpStreamTest {

    /** Two streams joined by a fake network that drops, delays and reorders datagrams. */
    private class Net(lossPercent: Int, seed: Long = 7, private val jitter: Boolean = true) {
        private val random = Random(seed)
        private val loss = lossPercent
        private val wire = Executors.newSingleThreadScheduledExecutor() // one receive loop, like a real socket
        lateinit var a: UdpStream
        lateinit var b: UdpStream

        private fun deliver(to: () -> UdpStream, packet: ByteArray) {
            val (drop, delay) = synchronized(random) { (random.nextInt(100) < loss) to (if (jitter) 1L + random.nextInt(15) else 20L) }
            if (drop) return
            wire.schedule({ to().onPacket(packet) }, delay, TimeUnit.MILLISECONDS)
        }

        init {
            a = UdpStream({ deliver({ b }, it) })
            b = UdpStream({ deliver({ a }, it) })
        }

        fun shutdown() = wire.shutdownNow()
    }

    private fun bytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun readAll(s: UdpStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < n) {
            val k = s.input.read(buf)
            if (k < 0) break
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    private fun transfer(lossPercent: Int, size: Int) {
        val net = Net(lossPercent)
        try {
            val ab = bytes(size, 1)
            val ba = bytes(size / 4, 2)
            // Both directions at once, written in odd-sized pieces like SecureChannel frames.
            val w1 = thread { var o = 0; while (o < ab.size) { val k = minOf(5000, ab.size - o); net.a.output.write(ab, o, k); net.a.output.flush(); o += k } }
            val w2 = thread { net.b.output.write(ba); net.b.output.flush() }
            val gotAb = readAll(net.b, ab.size)
            val gotBa = readAll(net.a, ba.size)
            w1.join(); w2.join()
            assertArrayEquals(ab, gotAb)
            assertArrayEquals(ba, gotBa)
        } finally {
            net.a.close(); net.b.close(); net.shutdown()
        }
    }

    @Test(timeout = 30_000)
    fun deliversInOrderWithoutLoss() = transfer(lossPercent = 0, size = 1_000_000)

    @Test(timeout = 60_000)
    fun survivesLossAndReordering() = transfer(lossPercent = 10, size = 500_000)

    @Test(timeout = 60_000)
    fun survivesHeavyLoss() = transfer(lossPercent = 30, size = 100_000)

    /** Steady 20 ms one-way delay (40 ms RTT), no loss: the window must open up, not crawl. */
    @Test(timeout = 30_000)
    fun throughputOnSteadyLink() {
        val net = Net(lossPercent = 0, jitter = false)
        try {
            val data = bytes(8_000_000, 4)
            val t0 = System.nanoTime()
            val w = thread { net.a.output.write(data); net.a.output.flush() }
            val got = readAll(net.b, data.size)
            w.join()
            val secs = (System.nanoTime() - t0) / 1e9
            println("UdpStream: %.1f MB/s over a 40 ms RTT link".format(data.size / secs / 1e6))
            assertArrayEquals(data, got)
            assertEquals("no loss, so nothing may be resent", 0, net.a.retransmits)
            assertTrue("window should open fully", net.a.window >= 512)
            assertTrue("too slow: %.2f MB/s".format(data.size / secs / 1e6), data.size / secs > 2e6)
        } finally {
            net.a.close(); net.b.close(); net.shutdown()
        }
    }

    @Test(timeout = 30_000)
    fun closeSendsEndOfStreamAfterData() {
        val net = Net(lossPercent = 10)
        try {
            val data = bytes(50_000, 3)
            net.a.output.write(data)
            net.a.output.flush()
            net.a.close() // queues FIN after the data and lingers until it's acknowledged
            val got = readAll(net.b, Int.MAX_VALUE)
            assertArrayEquals(data, got)
            assertEquals(-1, net.b.input.read())
        } finally {
            net.b.close(); net.shutdown()
        }
    }

    @Test
    fun roomAndTagDependOnTheKeyOnly() {
        val (_, k1) = Pairing.newSecrets()
        val (_, k2) = Pairing.newSecrets()
        val room = P2p.room(k1)
        assertTrue(room.matches(Regex("^[a-f0-9]{64}$"))) // format the rendezvous server accepts
        assertEquals(room, P2p.room(k1.copyOf()))
        assertNotEquals(room, P2p.room(k2))
        val id = UUID.randomUUID()
        assertArrayEquals(P2p.tag(k1, id), P2p.tag(k1, id))
        assertFalse(P2p.tag(k1, id).contentEquals(P2p.tag(k2, id)))
        assertFalse(P2p.tag(k1, id).contentEquals(P2p.tag(k1, UUID.randomUUID())))
    }

    @Test
    fun pairingCarriesInternetFlag() {
        val (id, key) = Pairing.newSecrets()
        val on = Pairing.decode(Pairing(id, key, "A", 1, emptyList(), bluetooth = true, internet = true).encode())!!
        assertTrue(on.internet)
        assertTrue(on.bluetooth)
        val off = Pairing.decode(Pairing(id, key, "A", 1, emptyList(), bluetooth = false).encode())!!
        assertFalse(off.internet)
        // Kept when the Wi-Fi Direct group is added to the code.
        assertTrue(on.withWifiGroup(Pairing.WifiGroup("DIRECT-x", "password"), emptyList()).internet)
    }
}
