package x.vladgba.sendanyway.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import x.vladgba.sendanyway.R
import x.vladgba.sendanyway.core.Pairing
import x.vladgba.sendanyway.core.Protocol
import x.vladgba.sendanyway.core.QrAssembler
import x.vladgba.sendanyway.core.QrBundle
import x.vladgba.sendanyway.core.SecureChannel
import x.vladgba.sendanyway.core.TransferListener
import x.vladgba.sendanyway.core.Wire
import x.vladgba.sendanyway.data.Device
import x.vladgba.sendanyway.data.DownloadSink
import x.vladgba.sendanyway.qr.QrScanner
import x.vladgba.sendanyway.service.TransferService
import x.vladgba.sendanyway.transport.Bluetooth
import x.vladgba.sendanyway.transport.ClassicClient
import x.vladgba.sendanyway.transport.LanClient
import x.vladgba.sendanyway.transport.Link
import x.vladgba.sendanyway.transport.WifiDirect
import x.vladgba.sendanyway.transport.WifiDirectClient
import x.vladgba.sendanyway.transport.WifiWatcher
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Receiver: keeps the camera running. A pairing code starts direct connection attempts
 * (LAN + Bluetooth in parallel); slideshow frames are collected at the same time, so the
 * sender can switch to the QR fallback without the receiver doing anything.
 */
class ReceiveActivity : Activity() {

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var myName: String
    private val app by lazy { applicationContext }

    private var scanner: QrScanner? = null
    private var cameraAllowed = false
    private var pairing: Pairing? = null
    private var lanClient: LanClient? = null
    private var classicClient: ClassicClient? = null
    private var directClient: WifiDirectClient? = null
    private var wifiWatcher: WifiWatcher? = null
    private lateinit var btnWifi: Button
    private lateinit var qrMap: QrMapView
    private lateinit var btnCopyPassword: Button
    private var manualPassword: String? = null
    private var lastPairingBytes: ByteArray? = null
    private val claimed = AtomicBoolean(false)
    @Volatile private var activeLink: Link? = null
    @Volatile private var closed = false
    @Volatile private var stalled = false
    private var transferring = false
    private var meter = TransferMeter()
    private var watchdog: StallWatchdog? = null

    private val assembler = QrAssembler()
    private var qrParsing = false
    private var lastQrDone: Int? = null
    private var lastHint = 0L

    private val connectTimeout = Runnable {
        if (!claimed.get() && pairing != null && !assembler.active) status.setText(R.string.connect_failed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        results = findViewById(R.id.results)
        qrMap = findViewById(R.id.qrMap)
        btnWifi = findViewById(R.id.btnWifiSettings)
        btnWifi.setOnClickListener { openWifiSettings() }
        btnCopyPassword = findViewById(R.id.btnCopyPassword)
        btnCopyPassword.setOnClickListener { manualPassword?.let { copySecret(getString(R.string.wifi_password), it) } }
        myName = Device.name(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wanted = (arrayOf(Manifest.permission.CAMERA) + Bluetooth.permissions(server = false) +
            (if (WifiDirect.supported(this)) WifiDirect.permissions() else emptyArray()) +
            notificationPermission()).distinct().toTypedArray()
        val missing = missingPermissions(wanted)
        if (missing.isEmpty()) onCameraAllowed() else requestPermissions(missing, REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onCameraAllowed()
        } else {
            status.setText(R.string.camera_needed)
        }
    }

    private fun onCameraAllowed() {
        cameraAllowed = true
        val focusHint = findViewById<TextView>(R.id.focusHint)
        scanner = QrScanner(
            this, preview, ::onCode,
            onError = { msg -> status.text = getString(R.string.failed, msg) },
            onFocusLock = { locked -> focusHint.setText(if (locked) R.string.focus_locked else R.string.tap_to_focus) },
        )
        preview.setOnClickListener { scanner?.refocus() }
        if (!transferring) scanner?.start()
    }

    override fun onResume() {
        super.onResume()
        if (cameraAllowed && !transferring) scanner?.start()
    }

    override fun onPause() {
        super.onPause()
        scanner?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        mainHandler.removeCallbacks(connectTimeout)
        scanner?.stop()
        stopClients() // a Wi-Fi Direct group carrying the active link stays up until that link closes
        if (transferring && TransferService.isActive) return // keep going in the background
        watchdog?.stop()
        activeLink?.close()
    }

    // ---- scanned codes ----------------------------------------------------------------------

    private fun onCode(bytes: ByteArray) {
        if (closed || transferring) return
        when (Wire.typeOf(bytes)) {
            Wire.TYPE_PAIRING -> {
                // Same session but different bytes = sender started/stopped Wi-Fi Direct: reconnect.
                if (bytes.contentEquals(lastPairingBytes)) return
                val p = Pairing.decode(bytes) ?: return
                lastPairingBytes = bytes
                if (claimed.get()) return
                connect(p)
            }
            Wire.TYPE_QR_DATA -> onFrame(bytes)
            else -> {
                val now = SystemClock.uptimeMillis()
                if (pairing == null && !assembler.active && now - lastHint > 3000) {
                    lastHint = now
                    status.setText(R.string.not_ours)
                }
            }
        }
    }

    private fun connect(p: Pairing) {
        stopClients()
        pairing = p
        btnWifi.visibility = View.GONE
        btnCopyPassword.visibility = View.GONE
        manualPassword = null
        status.text = getString(R.string.connecting_to, p.deviceName)
        val group = p.wifiGroup
        val addrs = p.lanAddresses.mapNotNull { runCatching { InetAddress.getByAddress(it) }.getOrNull() }
        if (p.lanPort > 0 && addrs.isNotEmpty()) {
            lanClient = LanClient(addrs, p.lanPort, { onLink(p, it) }).also { it.start() }
        }

        var timeout = CONNECT_TIMEOUT_MS
        if (group != null) {
            timeout = WIFI_DIRECT_TIMEOUT_MS
            // Always watch for Wi-Fi networks: covers joining "DIRECT-…" by hand on any Android version.
            wifiWatcher = WifiWatcher(this, p) { onLink(p, it) }.also { it.start() }
            if (WifiDirect.canJoin && missingPermissions(WifiDirect.permissions()).isEmpty()) {
                // No dialog involved, so start right away, in parallel with the shared-LAN attempt.
                // Bluetooth only kicks in if Wi-Fi Direct fails (it's much slower).
                startWifiDirectJoin(p)
            } else {
                showManualJoin(group)
                startBluetooth(p)
            }
        } else {
            startBluetooth(p)
        }
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.postDelayed(connectTimeout, timeout)
    }

    private fun startWifiDirectJoin(p: Pairing) {
        val group = p.wifiGroup ?: return
        status.text = getString(R.string.joining_wifi_direct, group.networkName)
        directClient = WifiDirectClient(this, p, { onLink(p, it) }, onAttempt = { n, max ->
            if (!closed && !claimed.get() && pairing === p) {
                status.text = if (n == 1) getString(R.string.joining_wifi_direct, group.networkName)
                else getString(R.string.joining_wifi_direct_retry, group.networkName, n, max)
            }
        }, onGiveUp = { reason ->
            if (!closed && !claimed.get() && pairing === p) {
                status.text = getString(R.string.wifi_direct_join_failed, reason)
                if (WifiDirect.locationOff(this)) status.append("\n" + getString(R.string.location_off))
                showManualJoin(group, keepStatus = true)
                startBluetooth(p)
            }
        }).also { it.start() }
    }

    /** Fallback: the user joins "DIRECT-…" in Wi-Fi settings, the Wi-Fi watcher connects. */
    private fun showManualJoin(g: Pairing.WifiGroup, keepStatus: Boolean = false) {
        val text = getString(R.string.join_manually, g.networkName, g.passphrase)
        status.text = if (keepStatus) "${status.text}\n$text" else text
        manualPassword = g.passphrase
        btnWifi.visibility = View.VISIBLE
        btnCopyPassword.visibility = View.VISIBLE
    }

    private fun openWifiSettings() {
        // The password is what people need to type there: put it on the clipboard first.
        manualPassword?.let { copySecret(getString(R.string.wifi_password), it) }
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else null
        try {
            startActivity(panel ?: Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun startBluetooth(p: Pairing) {
        if (!p.bluetooth || classicClient != null) return
        val c = ClassicClient(this, p.rfcommUuid) { onLink(p, it) }
        if (c.start()) classicClient = c
    }

    private fun stopClients() {
        lanClient?.stop(); lanClient = null
        classicClient?.stop(); classicClient = null
        directClient?.stop(); directClient = null
        wifiWatcher?.stop(); wifiWatcher = null
    }

    // ---- direct transfer --------------------------------------------------------------------

    private fun onLink(p: Pairing, link: Link) {
        thread(name = "receive-session", isDaemon = true) {
            var mine = false
            try {
                val ch = SecureChannel(p.key, p.sessionId, initiator = true, input = link.input, output = link.output)
                val offer = Protocol.requestOffer(ch, myName)
                if (closed || !claimed.compareAndSet(false, true)) return@thread
                mine = true
                activeLink = link
                ui {
                    stopClients()
                    onConnected(offer.items.size, offer.peerName, link)
                }
                val throttle = Throttle()
                Protocol.receive(ch, offer, sink(), object : TransferListener {
                    override fun onProgress(done: Long, total: Long) {
                        meter.update(done)
                        TransferService.progress(app, done, total, app.getString(R.string.receiving_progress, formatSize(done), formatSize(total), meter.speed()))
                        if (throttle.ready(done == total)) ui { showProgress(done, total) }
                    }
                })
                TransferService.end(app, app.getString(R.string.notif_received), app.getString(R.string.notif_received_text, offer.items.size, offer.peerName))
                ui { onFinished(null) }
            } catch (e: Exception) {
                if (mine) {
                    TransferService.end(app, app.getString(R.string.notif_failed), e.message ?: e.javaClass.simpleName)
                    ui { onFinished(e) }
                }
            } finally {
                link.close()
            }
        }
    }

    private fun onConnected(count: Int, peer: String, link: Link) {
        if (closed) return
        TransferService.begin(this, getString(R.string.notif_receiving, peer), getString(R.string.receiving_from, count, peer, link.transport), link)
        transferring = true
        stalled = false
        btnWifi.visibility = View.GONE
        btnCopyPassword.visibility = View.GONE
        meter = TransferMeter()
        watchdog = StallWatchdog(meter, STALL_LIMIT_MS) {
            stalled = true
            activeLink?.close()
        }.also { it.start() }
        val transport = if (link.detail.isEmpty()) link.transport else "${link.transport} (${link.detail})"
        mainHandler.removeCallbacks(connectTimeout)
        scanner?.stop()
        status.text = getString(R.string.receiving_from, count, peer, transport)
        progress.visibility = View.VISIBLE
        progress.progress = 0
    }

    private fun showProgress(done: Long, total: Long) {
        if (closed) return
        progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
        status.text = getString(R.string.receiving_progress, formatSize(done), formatSize(total), meter.speed())
    }

    /** Back to scanning, ready for the next transfer. */
    private fun onFinished(error: Exception?) {
        watchdog?.stop()
        if (closed) return
        status.text = when {
            error == null -> getString(R.string.done_received)
            stalled -> getString(R.string.stalled, (STALL_LIMIT_MS / 1000).toInt())
            else -> getString(R.string.failed, error.message ?: error.javaClass.simpleName)
        }
        if (error == null) progress.progress = 1000
        transferring = false
        activeLink = null
        pairing = null
        lastPairingBytes = null
        claimed.set(false)
        if (cameraAllowed) scanner?.start()
    }

    // ---- QR slideshow -----------------------------------------------------------------------

    private fun onFrame(frame: ByteArray) {
        if (qrParsing) return
        val crc = QrAssembler.crcOf(frame)
        if (crc != null && crc == lastQrDone) return
        val r = assembler.offer(frame)
        if (r == QrAssembler.Result.IGNORED) return
        mainHandler.removeCallbacks(connectTimeout)
        progress.visibility = View.VISIBLE
        progress.progress = assembler.received * 1000 / assembler.count
        status.text = getString(R.string.qr_frames, assembler.received, assembler.count, assembler.recovered)
        qrMap.visibility = View.VISIBLE
        qrMap.show(assembler)
        if (r != QrAssembler.Result.COMPLETE) return
        qrMap.visibility = View.GONE

        val bundle = assembler.bundle()
        if (bundle == null) {
            status.setText(R.string.qr_corrupt)
            return
        }
        lastQrDone = assembler.crc
        assembler.reset()
        qrParsing = true
        val sink = sink()
        thread(name = "qr-parse", isDaemon = true) {
            val error = try { QrBundle.parse(bundle, sink); null } catch (e: Exception) { e }
            ui {
                qrParsing = false
                if (!closed) status.text = if (error == null) getString(R.string.done_received)
                else getString(R.string.failed, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    // ---- results ----------------------------------------------------------------------------

    private fun sink() = DownloadSink(
        this,
        onText = { text -> ui { addText(text) } },
        onFile = { name, location -> ui { addFile(name, location) } },
    )

    private fun addFile(name: String, location: String) {
        if (closed) return
        results.addView(TextView(this).apply {
            text = getString(R.string.saved_to, name, location)
            setTextColor(getColor(R.color.fg))
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }, 0)
    }

    private fun addText(text: String) {
        if (closed) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.card))
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.fg))
            textSize = 15f
            setTextIsSelectable(true)
        })
        row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.received_text), text))
                Toast.makeText(this@ReceiveActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        })
        results.addView(row, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PERMS = 1
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WIFI_DIRECT_TIMEOUT_MS = 45_000L
    }
}
