package x.vladgba.sendanyway.transport

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import x.vladgba.sendanyway.core.Pairing
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi without a shared network, via Wi-Fi Direct:
 *
 * - the sender creates a P2P group and becomes its owner (always 192.168.49.1). On Android 10+
 *   the app chooses the network name and passphrase itself and pins the group to 2.4 GHz,
 *   which every phone can see; older senders get system-chosen credentials;
 * - name + passphrase go into the pairing QR;
 * - the receiver (Android 10+) joins with those credentials directly, with no scan or peer
 *   picker dialog, then it's the usual TCP protocol to the group owner;
 * - fallback: the group is also an ordinary WPA2 network "DIRECT-…", so any phone can join it
 *   from Wi-Fi settings, and [WifiWatcher] connects as soon as that network is up.
 */
object WifiDirect {
    const val GROUP_OWNER = "192.168.49.1"

    fun supported(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    /** Receiver can join from inside the app with credentials: Android 10+. */
    val canJoin: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Runtime permissions Wi-Fi Direct needs on this Android version (both sides). */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Before Android 13 many devices refuse Wi-Fi Direct while Location is off. */
    fun locationOff(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return !lm.isLocationEnabled
    }

    internal fun reason(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy (another app or group is using it)"
        else -> "Wi-Fi Direct error (is Wi-Fi on? Location on?)"
    }

    private val random = SecureRandom()
    /** Lowercase + digits without look-alikes (0/o, 1/l/i): easy to read, type and copy. */
    private const val FRIENDLY = "abcdefghjkmnpqrstuvwxyz23456789"

    internal fun randomString(n: Int) = String(CharArray(n) { FRIENDLY[random.nextInt(FRIENDLY.length)] })

    /** e.g. "k7fp-3wqa-mx9d": 12 random chars (~59 bits), fine for a short-lived local group. */
    internal fun friendlyPassphrase() = List(3) { randomString(4) }.joinToString("-")

    /** ActionListener that runs [then] whatever the outcome. */
    internal fun after(then: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = then()
        override fun onFailure(reason: Int) = then()
    }

    /**
     * Leaves/cancels everything on [ch] and closes it only once the framework confirmed —
     * closing right away drops the pending request and leaves a stale group behind
     * (after a few rounds Wi-Fi Direct then reports BUSY).
     */
    @SuppressLint("MissingPermission")
    internal fun teardown(m: WifiP2pManager, ch: WifiP2pManager.Channel, cancelConnect: Boolean) {
        val main = Handler(Looper.getMainLooper())
        var closed = false
        val close = Runnable {
            if (closed) return@Runnable
            closed = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) try { ch.close() } catch (_: Exception) {}
        }
        main.postDelayed(close, 5_000) // safety net if a callback never arrives
        try {
            val remove: () -> Unit = {
                try {
                    m.removeGroup(ch, after { main.post(close) })
                } catch (e: Exception) {
                    main.post(close)
                }
            }
            if (cancelConnect) m.cancelConnect(ch, after(remove)) else remove()
        } catch (e: Exception) {
            main.post(close)
        }
    }
}

/** Sender side: owns a Wi-Fi Direct group. Callbacks arrive on the main thread. */
@SuppressLint("MissingPermission")
class WifiDirectHost(ctx: Context) {
    private val app = ctx.applicationContext
    private val mgr: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var stopped = false

    var active = false
        private set

    fun start(whenStarted: (Pairing.WifiGroup) -> Unit, whenFailed: (String) -> Unit) {
        stopped = false
        val m = mgr ?: return whenFailed(WifiDirect.reason(WifiP2pManager.P2P_UNSUPPORTED))
        try {
            val ch = m.initialize(app, Looper.getMainLooper(), null) ?: return whenFailed(WifiDirect.reason(-1))
            channel = ch
            // A leftover group (ours or another app's) makes createGroup fail with BUSY: remove it first.
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = create(m, ch, whenStarted, whenFailed)
                override fun onFailure(reason: Int) = create(m, ch, whenStarted, whenFailed)
            })
        } catch (e: Exception) { // SecurityException without the permission
            whenFailed(e.message ?: e.toString())
        }
    }

    private fun create(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        whenStarted: (Pairing.WifiGroup) -> Unit,
        whenFailed: (String) -> Unit,
    ) {
        if (stopped) return
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitGroup(m, ch, whenStarted, whenFailed, 30)
            override fun onFailure(reason: Int) = whenFailed(WifiDirect.reason(reason))
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = WifiP2pConfig.Builder()
                    // Must look like "DIRECT-xy…"; max 32 bytes.
                    .setNetworkName("DIRECT-${WifiDirect.randomString(2)}-SendAnyway")
                    .setPassphrase(WifiDirect.friendlyPassphrase())
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
                m.createGroup(ch, config, listener)
            } else {
                m.createGroup(ch, listener)
            }
        } catch (e: Exception) {
            whenFailed(e.message ?: e.toString())
        }
    }

    /** The group's details appear shortly after createGroup succeeds. */
    private fun awaitGroup(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        whenStarted: (Pairing.WifiGroup) -> Unit,
        whenFailed: (String) -> Unit,
        triesLeft: Int,
    ) {
        if (stopped) return
        try {
            m.requestGroupInfo(ch) { g ->
                if (stopped) return@requestGroupInfo
                val name = g?.networkName
                if (g != null && g.isGroupOwner && !name.isNullOrEmpty() && !g.passphrase.isNullOrEmpty()) {
                    active = true
                    whenStarted(Pairing.WifiGroup(name, g.passphrase))
                } else if (triesLeft > 0) {
                    main.postDelayed({ awaitGroup(m, ch, whenStarted, whenFailed, triesLeft - 1) }, 300)
                } else {
                    whenFailed("Wi-Fi Direct group did not start")
                }
            }
        } catch (e: Exception) {
            whenFailed(e.message ?: e.toString())
        }
    }

    fun stop() {
        stopped = true
        active = false
        val ch = channel ?: return
        channel = null
        mgr?.let { WifiDirect.teardown(it, ch, cancelConnect = false) }
    }
}

/**
 * Receiver side (Android 10+): joins the sender's group with the credentials from the QR,
 * waits until the group is formed, then connects TCP to the group owner.
 *
 * Every attempt starts from a clean slate (cancel pending connect + leave any group) and is
 * retried up to [MAX_ATTEMPTS] times with growing pauses: when the framework refuses, when the
 * group doesn't form in time, or when it forms but TCP can't reach the owner. Only then
 * [onGiveUp] is called (main thread). [onAttempt] reports "attempt n of MAX" for the UI.
 * The group is left when the last link from it closes (after [stop]).
 */
@TargetApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
class WifiDirectClient(
    ctx: Context,
    private val pairing: Pairing,
    private val onLink: (Link) -> Unit,
    private val onGiveUp: (String) -> Unit,
    private val onAttempt: (attempt: Int, max: Int) -> Unit = { _, _ -> },
) {
    private val app = ctx.applicationContext
    private val mgr: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val linked = AtomicBoolean(false)
    private val openLinks = AtomicInteger(0)
    private var lan: LanClient? = null
    private var attempt = 0
    /** Incremented per attempt so callbacks from an abandoned attempt are ignored. */
    private var generation = 0

    fun start() = main.post { nextAttempt(null) }

    private fun nextAttempt(lastError: String?) {
        if (stopped.get() || linked.get()) return
        if (attempt >= MAX_ATTEMPTS) return giveUp(lastError ?: "couldn't join ${pairing.wifiGroup?.networkName}")
        attempt++
        val gen = ++generation
        onAttempt(attempt, MAX_ATTEMPTS)
        val group = pairing.wifiGroup ?: return giveUp("no Wi-Fi Direct group in the code")
        val m = mgr ?: return giveUp(WifiDirect.reason(WifiP2pManager.P2P_UNSUPPORTED))
        try {
            val ch = channel ?: (m.initialize(app, Looper.getMainLooper(), null) ?: return giveUp(WifiDirect.reason(-1)))
                .also { channel = it }
            val config = WifiP2pConfig.Builder()
                .setNetworkName(group.networkName)
                .setPassphrase(group.passphrase)
                .build()
            // Clean slate: a pending connect or a stale group from an earlier screen makes connect fail.
            m.cancelConnect(ch, WifiDirect.after {
                m.removeGroup(ch, WifiDirect.after {
                    if (gen != generation || stopped.get()) return@after
                    m.connect(ch, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = awaitConnection(m, ch, gen, 30)
                        override fun onFailure(reason: Int) = retry(gen, WifiDirect.reason(reason))
                    })
                })
            })
        } catch (e: Exception) {
            retry(gen, e.message ?: e.toString())
        }
    }

    private fun retry(gen: Int, reason: String) {
        if (gen != generation || stopped.get() || linked.get()) return
        lan?.stop(); lan = null
        main.postDelayed({ nextAttempt(reason) }, 1_500L * attempt)
    }

    private fun awaitConnection(m: WifiP2pManager, ch: WifiP2pManager.Channel, gen: Int, triesLeft: Int) {
        if (gen != generation || stopped.get()) return
        try {
            m.requestConnectionInfo(ch) { info ->
                if (gen != generation || stopped.get()) return@requestConnectionInfo
                val owner = info?.groupOwnerAddress
                when {
                    info != null && info.groupFormed && !info.isGroupOwner && owner != null -> connectTcp(owner, gen)
                    triesLeft > 0 -> main.postDelayed({ awaitConnection(m, ch, gen, triesLeft - 1) }, 500)
                    else -> retry(gen, "group ${pairing.wifiGroup?.networkName} did not form")
                }
            }
        } catch (e: Exception) {
            retry(gen, e.message ?: e.toString())
        }
    }

    private fun connectTcp(owner: InetAddress, gen: Int) {
        val addrs = (listOf(owner) + InetAddress.getByName(WifiDirect.GROUP_OWNER) +
            pairing.lanAddresses.mapNotNull { runCatching { InetAddress.getByAddress(it) }.getOrNull() }).distinct()
        lan = LanClient(
            addrs, pairing.lanPort, onLink,
            transport = TRANSPORT_WIFI_DIRECT,
            attempts = 4,
            wrap = { socket ->
                linked.set(true)
                openLinks.incrementAndGet()
                Closeable {
                    try { socket.close() } catch (_: IOException) {}
                    if (openLinks.decrementAndGet() == 0 && stopped.get()) release()
                }
            },
            onExhausted = { main.post { retry(gen, "joined, but the sender didn't answer") } },
        ).also { it.start() }
    }

    private fun giveUp(reason: String) {
        if (!stopped.get()) main.post { if (!stopped.get()) this@WifiDirectClient.onGiveUp(reason) }
    }

    /** Stops trying. The group is kept while a handed-out link is still open. */
    fun stop() {
        stopped.set(true)
        generation++
        lan?.stop()
        if (openLinks.get() == 0) release()
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        val ch = channel ?: return
        channel = null
        mgr?.let { WifiDirect.teardown(it, ch, cancelConnect = true) }
    }

    private companion object { const val MAX_ATTEMPTS = 4 }
}

/**
 * Fallback for any Android version: waits for *any* Wi-Fi network to come up (e.g. the user
 * joined "DIRECT-…" by hand in Settings) and tries the sender over it. Sockets are bound to
 * that network, because a Wi-Fi without internet is usually not the phone's default network.
 */
class WifiWatcher(ctx: Context, private val pairing: Pairing, private val onLink: (Link) -> Unit) {
    private val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val stopped = AtomicBoolean(false)
    private val clients = java.util.Collections.synchronizedList(ArrayList<LanClient>())
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (stopped.get()) return
            Thread.sleep(300) // link properties may lag a moment behind onAvailable
            val client = LanClient(
                candidates(cm.getLinkProperties(network)), pairing.lanPort, onLink,
                factory = network.socketFactory,
                transport = TRANSPORT_WIFI,
                attempts = 5,
            )
            clients += client
            client.start()
        }
    }

    fun start() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (_: Exception) {}
    }

    /** Unregistering only stops watching; it never disconnects a network the user joined. */
    fun stop() {
        stopped.set(true)
        synchronized(clients) { clients.forEach { it.stop() } }
        if (registered) try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        registered = false
    }

    /** Group owner, sender's IPs from the QR, the network's gateway / DHCP server, and x.y.z.1. */
    private fun candidates(lp: LinkProperties?): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        out += InetAddress.getByName(WifiDirect.GROUP_OWNER)
        pairing.lanAddresses.mapNotNullTo(out) { runCatching { InetAddress.getByAddress(it) }.getOrNull() }
        lp?.routes?.mapNotNullTo(out) { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp?.dhcpServerAddress?.let(out::add)
        lp?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.forEach { a ->
            val b = a.address.copyOf().also { it[3] = 1 }
            if (!b.contentEquals(a.address)) out += InetAddress.getByAddress(b)
        }
        return out.distinct()
    }
}
