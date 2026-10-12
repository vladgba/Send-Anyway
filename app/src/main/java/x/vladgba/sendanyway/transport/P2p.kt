package x.vladgba.sendanyway.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

const val TRANSPORT_P2P = "Internet P2P"

object P2p {
    /** Rendezvous endpoint (hole-punch.php): pairs the two phones of a session, nothing else. */
    const val SERVER = "https://zcxv.cc/fm/hole-punch.php"

    /** Public STUN servers, asked in parallel for this socket's public IPv4 endpoint. */
    val STUN_SERVERS = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun.cloudflare.com" to 3478,
    )

    /** One id per app process, so a receiver that re-scans replaces its own registration (rooms hold two peers). */
    val selfId: UUID = UUID.randomUUID()

    /**
     * The active network is meant to reach the internet. Deliberately not "VALIDATED": Android
     * validates a few seconds after every network change, and the screen may open in that gap.
     * If the server turns out to be unreachable, the peer reports ERROR and keeps retrying.
     */
    fun hasInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Rendezvous room for a session: derived from the key, so it can't be guessed and reveals nothing. */
    fun room(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest("SendAnyway/p2p-room".toByteArray() + key)
            .joinToString("") { "%02x".format(it) }

    /** Proof that the sender of a punch packet knows the session key. */
    fun tag(key: ByteArray, id: UUID): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("SendAnyway/p2p-punch".toByteArray())
        return mac.doFinal(uuidBytes(id)).copyOf(TAG_LEN)
    }

    fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    const val TAG_LEN = 16

    fun isPrivate(a: Inet4Address): Boolean {
        val b = a.address
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)
    }
}

/**
 * Internet peer-to-peer link through UDP hole punching. Used by both sides, which are symmetric.
 *
 * 1. One UDP socket. STUN tells us our public endpoint (as seen through our NAT).
 * 2. We register (public endpoint + LAN addresses) with the rendezvous server under
 *    [P2p.room] and poll it until the other phone shows up.
 * 3. Both phones fire punch packets at each other's candidates: this opens both NATs. A punch
 *    carries our id + an HMAC of it with the session key, so strangers are ignored.
 * 4. The first authenticated punch locks the peer address; [UdpStream] carries the data from then on,
 *    on the same socket (the NAT mappings we just opened). Encryption is SecureChannel's, as on LAN.
 *
 * Doesn't work through symmetric NATs (some mobile carriers): the other channels cover that.
 * [onState] reports progress for the UI (any thread).
 */
class P2pPeer(
    private val key: ByteArray,
    private val onLink: (Link) -> Unit,
    private val onState: (State, String) -> Unit = { _, _ -> },
    /** Our identity at the rendezvous server; tests run two peers in one process. */
    private val selfId: UUID = P2p.selfId,
) {
    enum class State { STARTING, WAITING, CONNECTING, CONNECTED, BUSY, ERROR }

    private val socket = DatagramSocket()
    private val room = P2p.room(key)
    private val myTag = P2p.tag(key, selfId)
    private val random = SecureRandom()

    @Volatile private var stopped = false
    @Volatile private var candidates: List<InetSocketAddress> = emptyList()
    @Volatile private var peerId: UUID? = null
    @Volatile private var stream: UdpStream? = null
    private val authed: MutableSet<InetSocketAddress> = Collections.synchronizedSet(HashSet())
    private var punching = false
    private var state: State? = null

    // STUN transactions in flight → reply
    private val stunLock = Object()
    private val stunPending = HashSet<String>()
    private var stunResult: InetSocketAddress? = null

    fun start(): P2pPeer {
        thread(name = "p2p-recv", isDaemon = true) { receiveLoop() }
        thread(name = "p2p-discovery", isDaemon = true) { discoveryLoop() }
        return this
    }

    /** Stops discovery. A link already handed out owns the socket and closes it itself. */
    @Synchronized
    fun stop() {
        stopped = true
        if (stream == null) socket.close()
    }

    private fun state(s: State, detail: String = "") {
        if (state == s && s != State.ERROR) return
        state = s
        if (!stopped || s == State.CONNECTED) onState(s, detail)
    }

    // ---- discovery ----------------------------------------------------------------------------

    private fun discoveryLoop() {
        state(State.STARTING)
        var publicEp: InetSocketAddress? = null
        var lastStun = 0L
        while (!stopped && stream == null) {
            val now = System.currentTimeMillis()
            // Refresh every 15 s: keeps our NAT mapping alive and notices a changed one.
            if (publicEp == null || now - lastStun > 15_000) {
                stun()?.let { publicEp = it }
                lastStun = now
            }
            if (stopped || stream != null) break
            val delay = try {
                val r = register(publicEp)
                when {
                    r == null -> { state(State.BUSY); 5_000L }
                    r.optString("type") == "peer" -> { onPeer(r); 2_000L }
                    else -> { state(State.WAITING); 2_000L }
                }
            } catch (e: Exception) {
                state(State.ERROR, e.message ?: e.javaClass.simpleName)
                5_000L
            }
            sleep(delay)
        }
    }

    /** POST to the rendezvous server. Returns its JSON reply, or null when the room is full (409). */
    private fun register(public: InetSocketAddress?): JSONObject? {
        val local = Lan.localIpv4().filter { P2p.isPrivate(it) }.take(8).map { it.hostAddress }
        val body = JSONObject()
            .put("type", "register")
            .put("room", room)
            .put("id", selfId.toString())
            .put("localPort", socket.localPort)
            .put("public", public?.let { JSONObject().put("ip", it.address.hostAddress).put("port", it.port) } ?: JSONObject.NULL)
            .put("local", JSONArray(local))
            .toString().toByteArray()
        val c = URL(P2p.SERVER).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code == 409) return null
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun onPeer(r: JSONObject) {
        val id = runCatching { UUID.fromString(r.getString("id")) }.getOrNull() ?: return
        val list = ArrayList<InetSocketAddress>()
        r.optJSONObject("public")?.let { pub ->
            runCatching { InetSocketAddress(InetAddress.getByName(pub.getString("ip")), pub.getInt("port")) }
                .getOrNull()?.let(list::add)
        }
        val localPort = r.optInt("localPort", 0)
        r.optJSONArray("local")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (localPort in 1..65535) {
                    runCatching { InetSocketAddress(InetAddress.getByName(arr.getString(i)), localPort) }
                        .getOrNull()?.let(list::add)
                }
            }
        }
        peerId = id
        candidates = list.distinct()
        state(State.CONNECTING)
        startPunching()
    }

    @Synchronized
    private fun startPunching() {
        if (punching) return
        punching = true
        thread(name = "p2p-punch", isDaemon = true) {
            val packet = punchPacket(TYPE_PUNCH)
            val started = System.currentTimeMillis()
            while (!stopped && stream == null) {
                for (c in candidates) sendTo(packet, c)
                // Fast at first so both NATs open within the same moment, then gentler.
                sleep(if (System.currentTimeMillis() - started < 10_000) 200 else 1_000)
            }
        }
    }

    // ---- STUN (RFC 5389 binding request) -------------------------------------------------------

    /** Our public endpoint per STUN, or null (no reply within 2 s). Internal for tests. */
    internal fun stun(): InetSocketAddress? {
        synchronized(stunLock) { stunResult = null; stunPending.clear() }
        for ((host, port) in P2p.STUN_SERVERS) {
            val addr = runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull() ?: continue
            val txn = ByteArray(12).also(random::nextBytes)
            synchronized(stunLock) { stunPending += txn.toHex() }
            val req = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(STUN_COOKIE).put(txn).array()
            sendTo(req, InetSocketAddress(addr, port))
        }
        synchronized(stunLock) {
            val deadline = System.currentTimeMillis() + 2_000
            while (stunResult == null && System.currentTimeMillis() < deadline) {
                stunLock.wait(maxOf(1, deadline - System.currentTimeMillis()))
            }
            return stunResult
        }
    }

    private fun onStun(p: ByteArray, len: Int) {
        val b = ByteBuffer.wrap(p, 0, len)
        if (b.short.toInt() != 0x0101) return // binding success response
        val bodyLen = b.short.toInt() and 0xFFFF
        if (b.int != STUN_COOKIE) return
        val txn = ByteArray(12).also { b.get(it) }.toHex()
        synchronized(stunLock) { if (!stunPending.remove(txn)) return }
        val end = minOf(len, 20 + bodyLen)
        var found: InetSocketAddress? = null
        while (b.position() + 4 <= end) {
            val type = b.short.toInt() and 0xFFFF
            val alen = b.short.toInt() and 0xFFFF
            val start = b.position()
            if (start + alen > end) break
            if ((type == 0x0020 || type == 0x0001) && alen >= 8 && p[start + 1].toInt() == 1) { // IPv4
                var port = ((p[start + 2].toInt() and 0xFF) shl 8) or (p[start + 3].toInt() and 0xFF)
                val ip = p.copyOfRange(start + 4, start + 8)
                if (type == 0x0020) { // XOR-MAPPED-ADDRESS
                    port = port xor (STUN_COOKIE ushr 16)
                    for (i in 0..3) ip[i] = (ip[i].toInt() xor (STUN_COOKIE ushr (24 - 8 * i))).toByte()
                }
                val a = InetSocketAddress(InetAddress.getByAddress(ip), port)
                if (type == 0x0020 || found == null) found = a
            }
            b.position(start + ((alen + 3) and 3.inv()))
        }
        if (found != null) synchronized(stunLock) {
            if (stunResult == null) stunResult = found
            stunLock.notifyAll()
        }
    }

    // ---- packets --------------------------------------------------------------------------------

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            val dp = DatagramPacket(buf, buf.size)
            try { socket.receive(dp) } catch (_: IOException) { break }
            val len = dp.length
            val src = dp.socketAddress as? InetSocketAddress ?: continue
            try {
                when {
                    len >= 20 && buf[0].toInt() == 0x01 && buf[1].toInt() == 0x01 -> onStun(buf, len)
                    len >= 3 && buf[0] == UdpStream.MAGIC0 && buf[1] == UdpStream.MAGIC1 -> {
                        val type = buf[2].toInt() and 0xFF
                        if (type == TYPE_PUNCH || type == TYPE_PUNCH_ACK) onPunch(type, buf, len, src)
                        else if (src in authed) stream?.onPacket(buf.copyOf(len), len)
                    }
                }
            } catch (_: Exception) {
                // malformed datagram: ignore
            }
        }
    }

    private fun onPunch(type: Int, p: ByteArray, len: Int, src: InetSocketAddress) {
        if (len < 3 + 16 + P2p.TAG_LEN) return
        val b = ByteBuffer.wrap(p, 3, 16)
        val id = UUID(b.long, b.long)
        if (id == selfId) return
        val tag = p.copyOfRange(19, 19 + P2p.TAG_LEN)
        if (!MessageDigest.isEqual(tag, P2p.tag(key, id))) return
        authed += src
        if (type == TYPE_PUNCH) sendTo(punchPacket(TYPE_PUNCH_ACK), src)
        lock(src)
    }

    @Synchronized
    private fun lock(peer: InetSocketAddress) {
        if (stream != null || stopped || socket.isClosed) return
        val s = UdpStream(
            sendRaw = { data -> socket.send(DatagramPacket(data, data.size, peer)) },
            onFinished = { socket.close() },
        )
        stream = s
        state(State.CONNECTED, peer.address.hostAddress ?: "")
        onLink(Link(TRANSPORT_P2P, s.input, s.output, s, chunk = P2P_CHUNK, window = P2P_WINDOW, ackEvery = P2P_ACK_EVERY,
            detail = "${peer.address.hostAddress}:${peer.port}"))
    }

    private fun punchPacket(type: Int): ByteArray =
        ByteBuffer.allocate(3 + 16 + P2p.TAG_LEN)
            .put(UdpStream.MAGIC0).put(UdpStream.MAGIC1).put(type.toByte())
            .put(P2p.uuidBytes(selfId)).put(myTag).array()

    private fun sendTo(data: ByteArray, to: InetSocketAddress) {
        try { socket.send(DatagramPacket(data, data.size, to)) } catch (_: Exception) {}
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TYPE_PUNCH = 0x01
        const val TYPE_PUNCH_ACK = 0x02
        const val STUN_COOKIE = 0x2112A442
        const val P2P_CHUNK = 32 * 1024
        const val P2P_WINDOW = 1024 * 1024L
        const val P2P_ACK_EVERY = 64 * 1024L
    }
}
