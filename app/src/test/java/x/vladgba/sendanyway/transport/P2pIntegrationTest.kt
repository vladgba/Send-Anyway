package x.vladgba.sendanyway.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import x.vladgba.sendanyway.core.Pairing
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Two peers in one process, through the real rendezvous server and public STUN servers.
 * Needs the internet, so it only runs when asked: `P2P_IT=1 ./gradlew testDebugUnitTest`.
 */
class P2pIntegrationTest {

    @Test(timeout = 30_000)
    fun stunReportsPublicEndpoint() {
        assumeTrue("set P2P_IT=1 to run", System.getenv("P2P_IT") == "1")
        val peer = P2pPeer(ByteArray(32), {}, selfId = UUID.randomUUID())
        val started = peer.start()
        val ep = started.stun()
        started.stop()
        assertNotNull("no STUN reply", ep)
        val a = ep!!.address as java.net.Inet4Address
        println("public endpoint: $ep")
        assertFalse("STUN returned a private address", P2p.isPrivate(a))
        assertTrue(ep.port in 1..65535)
    }

    @Test(timeout = 60_000)
    fun twoPeersFindEachOtherAndExchangeData() {
        assumeTrue("set P2P_IT=1 to run", System.getenv("P2P_IT") == "1")
        val (_, key) = Pairing.newSecrets()
        val linkA = ArrayBlockingQueue<Link>(1)
        val linkB = ArrayBlockingQueue<Link>(1)
        val a = P2pPeer(key, { linkA.offer(it) }, { s, d -> println("A: $s $d") }, UUID.randomUUID()).start()
        val b = P2pPeer(key, { linkB.offer(it) }, { s, d -> println("B: $s $d") }, UUID.randomUUID()).start()
        val la = linkA.poll(40, TimeUnit.SECONDS)
        val lb = linkB.poll(10, TimeUnit.SECONDS)
        a.stop(); b.stop()
        assertNotNull("A never connected", la)
        assertNotNull("B never connected", lb)
        println("A ↔ ${la!!.detail}, B ↔ ${lb!!.detail}")

        val data = ByteArray(300_000) { (it * 31).toByte() }
        val w = thread { la.output.write(data); la.output.flush() }
        val got = ByteArray(data.size)
        var n = 0
        while (n < data.size) { val k = lb.input.read(got, n, data.size - n); if (k < 0) break; n += k }
        w.join()
        assertArrayEquals(data, got)
        la.close(); lb.close()
    }
}
