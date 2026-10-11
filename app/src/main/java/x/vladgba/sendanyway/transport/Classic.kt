package x.vladgba.sendanyway.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Classic Bluetooth (RFCOMM) transport. Works on every Android version and is usually much
 * faster than BLE, but the receiver has to *find* the sender first: apps can't read their own
 * MAC address, so the sender becomes discoverable (system prompt) and the receiver runs a
 * classic discovery scan, trying nearby phones strongest-signal first. Only the sender listens
 * on this session's service UUID, so connecting to any other phone fails fast at the SDP lookup.
 *
 * Sockets are "insecure" (no pairing dialog on most phones); the data is already encrypted by
 * SecureChannel with the key from the QR code.
 */
object Classic {
    const val DISCOVERABLE_SECONDS = 300

    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)

    /** RFCOMM is a plain stream; 16 KB frames keep progress smooth with little overhead. */
    internal fun link(socket: BluetoothSocket): Link =
        Link(TRANSPORT_RFCOMM, socket.inputStream, socket.outputStream, socket, chunk = 16 * 1024)
}

/** Sender side: listens on the session's RFCOMM service UUID. */
@SuppressLint("MissingPermission")
class ClassicServer(private val ctx: Context, private val uuid: UUID, private val onLink: (Link) -> Unit) {
    private var server: BluetoothServerSocket? = null

    /** Returns false if Bluetooth is off or not permitted. */
    fun start(): Boolean {
        if (!Ble.hasPermissions(ctx, server = true)) return false
        val adapter = Ble.adapter(ctx) ?: return false
        return try {
            val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("Send Anyway", uuid)
            server = ss
            thread(name = "rfcomm-accept", isDaemon = true) {
                while (true) {
                    val s = try { ss.accept() } catch (e: IOException) { break }
                    onLink(Classic.link(s))
                }
            }
            true
        } catch (e: Exception) { // IOException, SecurityException
            false
        }
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }
}

/** Receiver side: classic discovery, then tries phones by signal strength until one has our service. */
@SuppressLint("MissingPermission")
class ClassicClient(private val ctx: Context, private val uuid: UUID, private val onLink: (Link) -> Unit) {
    private var adapter: BluetoothAdapter? = null
    private val stopped = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val tried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val found = HashMap<String, Pair<BluetoothDevice, Int>>() // address -> (device, rssi)
    private var rounds = 0
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (stopped.get()) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.device() ?: return
                    if (device.address in tried || !looksLikePhone(device)) return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    synchronized(found) { found[device.address] = device to rssi }
                    // A phone right next to us: don't wait for the whole ~12 s scan.
                    if (rssi > NEAR_RSSI) tryNext()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (!connecting.get()) tryNext()
            }
        }
    }

    fun start(): Boolean {
        if (!Ble.hasPermissions(ctx, server = false)) return false
        val a = Ble.adapter(ctx) ?: return false
        adapter = a
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            registered = true
            // Phones paired earlier are tried too (last), so a non-discoverable sender can still be found.
            try {
                a.bondedDevices.orEmpty().filter(::looksLikePhone).forEach { d ->
                    synchronized(found) { found.getOrPut(d.address) { d to BONDED_RSSI } }
                }
            } catch (_: Exception) {}
            discover()
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        if (registered) try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    private fun discover(): Boolean {
        if (stopped.get() || rounds >= MAX_ROUNDS) return false
        rounds++
        return try { adapter?.startDiscovery() == true } catch (e: Exception) { false }
    }

    /** Connect to the strongest untried candidate; if none, scan again. */
    private fun tryNext() {
        if (stopped.get() || !connecting.compareAndSet(false, true)) return
        val next = synchronized(found) {
            found.values.filter { it.first.address !in tried }.maxByOrNull { it.second }?.first
        }
        if (next == null) {
            connecting.set(false)
            discover()
            return
        }
        tried += next.address
        // Discovery slows down / breaks connection attempts.
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        thread(name = "rfcomm-connect", isDaemon = true) {
            var socket: BluetoothSocket? = null
            try {
                socket = next.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()
                if (stopped.get()) { socket.close(); return@thread }
                onLink(Classic.link(socket))
                stop()
            } catch (e: Exception) {
                try { socket?.close() } catch (_: IOException) {}
                connecting.set(false)
                tryNext()
            }
        }
    }

    private fun looksLikePhone(d: BluetoothDevice): Boolean {
        val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: Exception) { null } ?: return true
        return major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.COMPUTER || // many tablets report this
            major == BluetoothClass.Device.Major.MISC ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED
    }

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val NEAR_RSSI = -65
        const val BONDED_RSSI = -1000
        const val MAX_ROUNDS = 4
    }
}
