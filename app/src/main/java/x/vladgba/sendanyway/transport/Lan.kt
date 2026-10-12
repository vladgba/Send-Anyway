package x.vladgba.sendanyway.transport

import x.vladgba.sendanyway.core.Protocol
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** A raw, not-yet-authenticated bidirectional byte stream produced by some transport. */
class Link(
    val transport: String,
    val input: InputStream,
    val output: OutputStream,
    private val resource: Closeable,
    /** DATA payload size that suits this link (see Protocol.send). */
    val chunk: Int = Protocol.DEFAULT_CHUNK,
    /** Unacknowledged bytes in flight limit for flow control. */
    val window: Long = Protocol.WINDOW,
    /** Frequency of ACK frames from the receiver. */
    val ackEvery: Long = Protocol.ACK_EVERY,
    /** Extra info for the status line. */
    val detail: String = "",
) : Closeable {
    override fun close() {
        try { resource.close() } catch (_: IOException) {}
    }
}

const val TRANSPORT_LAN = "Wi-Fi/LAN"
const val TRANSPORT_BT = "Bluetooth"
const val TRANSPORT_WIFI_DIRECT = "Wi-Fi Direct"
const val TRANSPORT_WIFI = "Wi-Fi"

/** Adaptive parameters for fast TCP links (LAN / Wi-Fi Direct). */
const val LAN_CHUNK = 128 * 1024
const val LAN_WINDOW = 1024 * 1024L
const val LAN_ACK_EVERY = 128 * 1024L

object Lan {
    /** IPv4 addresses of every active non-loopback interface (Wi-Fi, hotspot, Ethernet, USB tethering…). */
    fun localIpv4(): List<Inet4Address> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }
}

/** Sender side: accepts TCP connections on an ephemeral port on all interfaces. */
class LanServer(private val onLink: (Link) -> Unit) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    fun start() {
        thread(name = "lan-accept", isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                s.tcpNoDelay = true
                s.soTimeout = 30_000
                onLink(Link(TRANSPORT_LAN, s.getInputStream(), s.getOutputStream(), s, chunk = LAN_CHUNK, window = LAN_WINDOW, ackEvery = LAN_ACK_EVERY))
            }
        }
    }

    fun stop() {
        try { server.close() } catch (_: IOException) {}
    }
}

/**
 * Receiver side: tries every advertised address in parallel, a few times.
 * [factory] lets the Wi-Fi watcher bind sockets to a specific network; [wrap] lets it
 * attach extra cleanup to the link.
 */
class LanClient(
    addresses: List<InetAddress>,
    private val port: Int,
    private val onLink: (Link) -> Unit,
    private val factory: SocketFactory = SocketFactory.getDefault(),
    private val transport: String = TRANSPORT_LAN,
    private val attempts: Int = 3,
    private val wrap: (Socket) -> Closeable = { it },
    /** Called once if every address failed every attempt (not called after [stop]). */
    private val onExhausted: () -> Unit = {},
) {
    private val addresses = addresses.distinct()
    private val stopped = AtomicBoolean(false)
    private val succeeded = AtomicBoolean(false)
    private val remaining = java.util.concurrent.atomic.AtomicInteger(this.addresses.size)

    fun start() {
        for (addr in addresses) {
            thread(name = "lan-connect-${addr.hostAddress}", isDaemon = true) {
                repeat(attempts) { attempt ->
                    if (stopped.get()) return@thread
                    val s = factory.createSocket()
                    try {
                        s.connect(InetSocketAddress(addr, port), 3_000)
                        s.tcpNoDelay = true
                        s.soTimeout = 30_000
                        if (stopped.get()) { s.close(); return@thread }
                        succeeded.set(true)
                        onLink(Link(transport, s.getInputStream(), s.getOutputStream(), wrap(s), chunk = LAN_CHUNK, window = LAN_WINDOW, ackEvery = LAN_ACK_EVERY))
                        return@thread
                    } catch (e: IOException) {
                        try { s.close() } catch (_: IOException) {}
                        if (attempt < attempts - 1) Thread.sleep(2_000)
                    }
                }
                if (remaining.decrementAndGet() == 0 && !succeeded.get() && !stopped.get()) onExhausted()
            }
        }
        if (addresses.isEmpty() && !stopped.get()) onExhausted()
    }

    fun stop() = stopped.set(true)
}
