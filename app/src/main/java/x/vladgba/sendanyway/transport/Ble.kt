package x.vladgba.sendanyway.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.annotation.TargetApi
import x.vladgba.sendanyway.core.Protocol
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Bluetooth transport: BLE advertising for discovery + an L2CAP connection-oriented channel
 * for the data (Android 10+). This avoids classic discovery, pairing dialogs and the need to
 * know the other phone's MAC address (which apps can't read since Android 6).
 *
 * The sender advertises manufacturer data `"SA" + bleTag(4) + psm(u16)`, where bleTag comes
 * from the pairing QR. The receiver scans for exactly that tag and connects to the PSM.
 */
object Ble {
    /** 0xFFFF = "reserved for testing" company id; fine for a prototype, replace before release. */
    const val MANUFACTURER_ID = 0xFFFF
    private val MARKER = byteArrayOf('S'.code.toByte(), 'A'.code.toByte())

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun prefix(tag: ByteArray) = MARKER + tag

    /** Runtime permissions for both BLE and classic Bluetooth (sender = server, receiver = client). */
    fun permissions(server: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (server) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        // Before Android 12, BLE scans and classic discovery need Location.
        else ->
            if (server) emptyArray()
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasPermissions(ctx: Context, server: Boolean) = permissions(server).all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }

    /**
     * Wraps a connected L2CAP socket. Each encrypted frame is sized to exactly one L2CAP SDU
     * (maxTransmitPacketSize), so the stack never has to split our writes, and progress moves
     * in small steps. A GATT connection is opened alongside only to ask for a fast connection
     * interval and the 2M PHY; it is closed together with the link.
     */
    @TargetApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun link(ctx: Context, socket: BluetoothSocket): Link {
        val packet = try { socket.maxTransmitPacketSize } catch (e: Exception) { 0 }
        val chunk = when {
            packet >= 128 -> minOf(packet, 16 * 1024) - Protocol.DATA_OVERHEAD
            else -> 4 * 1024
        }
        val gatt = boost(ctx, socket.remoteDevice)
        val resource = Closeable {
            try { socket.close() } catch (_: IOException) {}
            try { gatt?.close() } catch (_: Exception) {}
        }
        val rx = try { socket.maxReceivePacketSize } catch (e: Exception) { 0 }
        // Each direction has its own packet size: out = what this phone sends, in = what it receives.
        val detail = if (packet > 0) "packets: out $packet B, in $rx B" else ""
        return Link(TRANSPORT_BT, socket.inputStream, socket.outputStream, resource, chunk, detail)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun boost(ctx: Context, device: BluetoothDevice): BluetoothGatt? = try {
        device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState != BluetoothProfile.STATE_CONNECTED) return
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                } catch (_: Exception) {}
            }
        }, BluetoothDevice.TRANSPORT_LE)
    } catch (e: Exception) {
        null
    }
}

@TargetApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
class BleServer(private val ctx: Context, private val tag: ByteArray, private val onLink: (Link) -> Unit) {
    private var server: BluetoothServerSocket? = null
    private var adapter: BluetoothAdapter? = null
    private val advCallback = object : AdvertiseCallback() {}

    /** Returns false if Bluetooth is off, unsupported or not permitted. */
    fun start(): Boolean {
        if (!Ble.hasPermissions(ctx, server = true)) return false
        val a = Ble.adapter(ctx) ?: return false
        val advertiser = a.bluetoothLeAdvertiser ?: return false
        return try {
            val ss = a.listenUsingInsecureL2capChannel()
            server = ss
            adapter = a
            val psm = ss.psm
            val payload = Ble.prefix(tag) + byteArrayOf((psm shr 8).toByte(), psm.toByte())
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(Ble.MANUFACTURER_ID, payload)
                .build()
            advertiser.startAdvertising(settings, data, advCallback)
            thread(name = "ble-accept", isDaemon = true) {
                while (true) {
                    val s = try { ss.accept() } catch (e: IOException) { break }
                    onLink(Ble.link(ctx, s))
                }
            }
            true
        } catch (e: Exception) { // IOException, SecurityException
            stop()
            false
        }
    }

    fun stop() {
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: IOException) {}
        server = null
    }
}

@TargetApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
class BleClient(private val ctx: Context, private val tag: ByteArray, private val onLink: (Link) -> Unit) {
    private val connecting = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val attempts = AtomicInteger(0)
    private var adapter: BluetoothAdapter? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stopped.get() || !connecting.compareAndSet(false, true)) return
            val data = result.scanRecord?.getManufacturerSpecificData(Ble.MANUFACTURER_ID)
            if (data == null || data.size < 8) { connecting.set(false); return }
            val psm = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            stopScan()
            thread(name = "ble-connect", isDaemon = true) {
                try {
                    val s = result.device.createInsecureL2capChannel(psm)
                    s.connect()
                    if (stopped.get()) { s.close(); return@thread }
                    onLink(Ble.link(ctx, s))
                } catch (e: Exception) {
                    connecting.set(false)
                    if (!stopped.get() && attempts.incrementAndGet() < 3) startScan()
                }
            }
        }
    }

    fun start(): Boolean {
        if (!Ble.hasPermissions(ctx, server = false)) return false
        adapter = Ble.adapter(ctx) ?: return false
        return startScan()
    }

    private fun startScan(): Boolean = try {
        val scanner = adapter?.bluetoothLeScanner ?: throw IOException("no scanner")
        val prefix = Ble.prefix(tag)
        val filter = ScanFilter.Builder()
            .setManufacturerData(Ble.MANUFACTURER_ID, prefix, ByteArray(prefix.size) { 0xFF.toByte() })
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        true
    } catch (e: Exception) {
        false
    }

    private fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
    }

    fun stop() {
        stopped.set(true)
        stopScan()
    }
}
