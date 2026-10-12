package x.vladgba.sendanyway.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import x.vladgba.sendanyway.R
import x.vladgba.sendanyway.core.OutgoingItem
import x.vladgba.sendanyway.core.Pairing
import x.vladgba.sendanyway.core.Protocol
import x.vladgba.sendanyway.core.QrBundle
import x.vladgba.sendanyway.core.QrSlicer
import x.vladgba.sendanyway.core.SecureChannel
import x.vladgba.sendanyway.core.TextItem
import x.vladgba.sendanyway.core.TransferListener
import x.vladgba.sendanyway.core.knownTotal
import x.vladgba.sendanyway.data.Device
import x.vladgba.sendanyway.data.UriItem
import x.vladgba.sendanyway.service.TransferService
import x.vladgba.sendanyway.qr.QrRender
import x.vladgba.sendanyway.transport.Bluetooth
import x.vladgba.sendanyway.transport.Classic
import x.vladgba.sendanyway.transport.ClassicServer
import x.vladgba.sendanyway.transport.Lan
import x.vladgba.sendanyway.transport.LanServer
import x.vladgba.sendanyway.transport.Link
import x.vladgba.sendanyway.transport.WifiDirect
import x.vladgba.sendanyway.transport.WifiDirectHost
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sender: shows the pairing QR and waits for the receiver to connect over any transport.
 * The first connection that proves it knows the session key wins; everything else is closed.
 * As a fallback the user can switch to the QR slideshow, which needs no connection at all.
 */
class SendActivity : Activity() {

    private var items = ArrayList<OutgoingItem>()
    private lateinit var myName: String

    private lateinit var qrView: ImageView
    private lateinit var status: TextView
    private lateinit var channels: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnMode: Button
    private lateinit var btnRetry: Button
    private lateinit var controls: LinearLayout
    private lateinit var frameSizeLabel: TextView
    private lateinit var frameSize: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var speed: SeekBar
    private lateinit var itemsContainer: View
    private lateinit var itemsList: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var channelsCard: View
    private lateinit var channelList: LinearLayout

    private val app by lazy { applicationContext }
    private var sessionId: ByteArray? = null
    private var key: ByteArray? = null
    private var pairingQr: ByteArray? = null
    private var basePairing: Pairing? = null
    private var classic = false
    private var discoverable = false
    private var classicServer: ClassicServer? = null
    private var directHost: WifiDirectHost? = null
    private var wifiGroup: Pairing.WifiGroup? = null
    private lateinit var btnDirect: Button
    private lateinit var directView: TextView
    private var lan: LanServer? = null
    private val claimed = AtomicBoolean(false)
    @Volatile private var activeLink: Link? = null
    @Volatile private var closed = false
    @Volatile private var stalled = false
    private var meter = TransferMeter()
    private var watchdog: StallWatchdog? = null

    private var slideshow = false
    private var bundle: ByteArray? = null
    private var slicer: QrSlicer? = null
    private var frameIndex = 0L
    private val tick = object : Runnable {
        override fun run() {
            val s = slicer ?: return
            if (!slideshow) return
            try {
                QrRender.show(qrView, QrRender.encode(s.frameAt(frameIndex), ErrorCorrectionLevel.L))
            } catch (e: Exception) {
                showError(e)
                return
            }
            frameIndex++
            mainHandler.postDelayed(this, 1000L / fps())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        qrView = findViewById(R.id.qr)
        status = findViewById(R.id.status)
        channels = findViewById(R.id.channels)
        progress = findViewById(R.id.progress)
        btnMode = findViewById(R.id.btnMode)
        btnRetry = findViewById(R.id.btnRetry)
        controls = findViewById(R.id.slideshowControls)
        frameSizeLabel = findViewById(R.id.frameSizeLabel)
        frameSize = findViewById(R.id.frameSize)
        speedLabel = findViewById(R.id.speedLabel)
        speed = findViewById(R.id.speed)
        statusIcon = findViewById(R.id.statusIcon)
        channelsCard = findViewById(R.id.channelsCard)
        channelList = findViewById(R.id.channelList)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        myName = Device.name(this)
        items = ArrayList(collectItems())
        itemsContainer = findViewById(R.id.itemsContainer)
        itemsList = findViewById(R.id.itemsList)
        findViewById<Button>(R.id.btnAddMore).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK_MORE)
        }
        if (!updateSummaryAndList()) return

        val dm = resources.displayMetrics
        // Screen gutter (16dp) + card padding (20dp) on each side.
        val side = minOf(dm.widthPixels - dp(72), (dm.heightPixels * 0.5).toInt())
        qrView.layoutParams = qrView.layoutParams.apply { width = side; height = side }

        setupSlideshowControls()
        btnMode.setOnClickListener { if (slideshow) showPairing() else startSlideshow() }
        btnRetry.setOnClickListener { recreate() }
        btnDirect = findViewById(R.id.btnWifiDirect)
        directView = findViewById(R.id.wifiDirectInfo)
        directView.setOnClickListener { wifiGroup?.let { copySecret(getString(R.string.wifi_password), it.passphrase) } }
        if (WifiDirect.supported(this)) btnDirect.setOnClickListener { onWifiDirectButton() } else btnDirect.visibility = View.GONE
        qrScreenMode(true)

        val missing = missingPermissions(Bluetooth.permissions(server = true) + notificationPermission())
        if (missing.isNotEmpty()) requestPermissions(missing, REQ_PERMS) else startSession()
    }

    private fun updateSummaryAndList(): Boolean {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_send, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        val total = items.map { it.info }.knownTotal()
        findViewById<TextView>(R.id.summary).text =
            getString(R.string.items_summary, items.size, formatSize(total))
        itemsList.removeAllViews()
        for (i in 0 until items.size) itemsList.addView(itemRow(i, items[i]))
        return true
    }

    /** One row of the "Items" card: type icon, name + size, remove button. */
    private fun itemRow(index: Int, item: OutgoingItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        row.addView(iconBadge(if (item is TextItem) R.drawable.ic_text else R.drawable.ic_file))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) }
        }
        texts.addView(TextView(this, null, 0, R.style.Text_Subtitle).apply {
            text = item.info.name
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(this, null, 0, R.style.Text_Caption).apply {
            text = formatSize(item.info.size)
        })
        row.addView(texts)
        row.addView(ImageButton(this, null, 0, R.style.IconButton).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = -dp(8) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(getColor(R.color.muted))
            contentDescription = getString(R.string.remove)
            setOnClickListener {
                items.removeAt(index)
                bundle = null
                slicer = null
                if (updateSummaryAndList()) refreshPairing()
            }
        })
        return row
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_DISCOVERABLE -> {
                discoverable = resultCode != RESULT_CANCELED
                refreshPairing()
            }
            REQ_PICK_MORE -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
                if (uris.isEmpty()) data.data?.let(uris::add)
                uris.forEach { items.add(UriItem(contentResolver, it)) }
                bundle = null
                slicer = null
                updateSummaryAndList()
                refreshPairing()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_PERMS -> startSession()
            REQ_WIFI_DIRECT ->
                if (missingPermissions(WifiDirect.permissions()).isEmpty()) startWifiDirect()
                else {
                    directView.visibility = View.VISIBLE
                    directView.setText(R.string.wifi_direct_perm_denied)
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        mainHandler.removeCallbacks(tick)
        stopListeners()
        if (claimed.get() && TransferService.isActive) {
            // A transfer is running: let it finish in the background (foreground service).
            // The stall watchdog stays armed; the Wi-Fi Direct group is removed when the app process ends.
            return
        }
        watchdog?.stop()
        activeLink?.close()
        directHost?.stop()
    }

    // ---- session ----------------------------------------------------------------------------

    private fun startSession() {
        if (basePairing != null || closed) return
        val (id, k) = Pairing.newSecrets()
        sessionId = id
        key = k
        lan = try { LanServer(::onLink).also { it.start() } } catch (e: IOException) { null }
        val base = Pairing(id, k, myName, lan?.port ?: 0, emptyList(), bluetooth = false)
        val rfcomm = ClassicServer(this, base.rfcommUuid, ::onLink)
        classic = rfcomm.start()
        if (classic) {
            classicServer = rfcomm
            // The receiver can only find us by classic discovery: ask to become visible.
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Classic.discoverableIntent(), REQ_DISCOVERABLE)
            } catch (_: Exception) {}
        }
        basePairing = Pairing(id, k, myName, lan?.port ?: 0, emptyList(), bluetooth = classic)
        refreshPairing()
    }

    /** Rebuilds the pairing QR from the current addresses / Wi-Fi Direct state. */
    private fun refreshPairing() {
        val base = basePairing ?: return
        val addrs = if (lan != null) Lan.localIpv4() else emptyList()
        pairingQr = base.withWifiGroup(wifiGroup, addrs.map { it.address }).encode()

        renderChannels(addrs.joinToString { it.hostAddress ?: "" })
        if (!slideshow && !claimed.get()) showPairing()
    }

    /** Fills the "Channels" card: one row per way the receiver can reach us, with an on/off dot. */
    private fun renderChannels(lanAddrs: String) {
        channelList.removeAllViews()
        channelList.addView(channelRow(R.drawable.ic_wifi, R.string.ch_lan,
            lanAddrs.ifEmpty { getString(R.string.ch_lan_off) }, lanAddrs.isNotEmpty()))
        if (WifiDirect.supported(this)) {
            val g = wifiGroup
            channelList.addView(channelRow(R.drawable.ic_wifi, R.string.ch_direct,
                if (g != null) getString(R.string.ch_direct_on, g.networkName) else getString(R.string.ch_direct_off), g != null))
        }
        channelList.addView(channelRow(R.drawable.ic_bluetooth, R.string.ch_bt,
            getString(when {
                !classic -> R.string.ch_bt_off
                discoverable -> R.string.ch_bt_visible
                else -> R.string.ch_bt_hidden
            }), classic))
        channelList.addView(channelRow(R.drawable.ic_slideshow, R.string.ch_qr, getString(R.string.ch_qr_desc), true))
    }

    private fun channelRow(icon: Int, title: Int, detail: String, on: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
        }
        row.addView(iconBadge(icon, 36).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (!on) showBadge(icon, R.color.card, R.color.muted)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) }
        }
        texts.addView(TextView(this, null, 0, R.style.Text_Subtitle).apply {
            setText(title)
            if (!on) setTextColor(getColor(R.color.muted))
        })
        texts.addView(TextView(this, null, 0, R.style.Text_Caption).apply { text = detail })
        row.addView(texts)
        row.addView(View(this).apply {
            background = getDrawable(R.drawable.bg_dot)
            backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.success else R.color.outline))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(12) }
        })
        return row
    }

    // ---- Wi-Fi Direct -----------------------------------------------------------------------

    private fun onWifiDirectButton() {
        val host = directHost
        if (host != null && (host.active || wifiGroup != null)) {
            host.stop()
            wifiGroup = null
            directView.visibility = View.GONE
            btnDirect.setText(R.string.start_wifi_direct)
            refreshPairing()
            return
        }
        val missing = missingPermissions(WifiDirect.permissions())
        if (missing.isNotEmpty()) requestPermissions(missing, REQ_WIFI_DIRECT) else startWifiDirect()
    }

    private fun startWifiDirect() {
        if (closed) return
        val host = directHost ?: WifiDirectHost(this).also { directHost = it }
        directView.visibility = View.VISIBLE
        directView.setText(R.string.wifi_direct_starting)
        btnDirect.isEnabled = false
        host.start(
            whenStarted = { group ->
                // The group interface gets 192.168.49.1 a moment after the group forms; wait so it's in the QR.
                thread(name = "p2p-ip", isDaemon = true) {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (System.currentTimeMillis() < deadline &&
                        Lan.localIpv4().none { it.hostAddress == WifiDirect.GROUP_OWNER }
                    ) Thread.sleep(250)
                    ui {
                        if (closed) return@ui
                        wifiGroup = group
                        btnDirect.isEnabled = true
                        btnDirect.setText(R.string.stop_wifi_direct)
                        directView.text = getString(R.string.wifi_direct_on, group.networkName, group.passphrase)
                        refreshPairing()
                    }
                }
            },
            whenFailed = { msg ->
                if (!closed) {
                    host.stop()
                    wifiGroup = null
                    btnDirect.isEnabled = true
                    btnDirect.setText(R.string.start_wifi_direct)
                    directView.text = getString(R.string.wifi_direct_failed, msg)
                    if (WifiDirect.locationOff(this)) {
                        directView.append("\n")
                        directView.append(getString(R.string.wifi_direct_location))
                    }
                    refreshPairing()
                }
            },
        )
    }

    private fun onLink(link: Link) {
        thread(name = "send-session", isDaemon = true) {
            var mine = false
            try {
                val ch = SecureChannel(key!!, sessionId!!, initiator = false, input = link.input, output = link.output)
                val peer = Protocol.readHello(ch)
                if (closed || !claimed.compareAndSet(false, true)) return@thread
                mine = true
                activeLink = link
                stopListeners()
                // Open every file now, while this screen still holds read access to them.
                items.forEach { (it as? UriItem)?.preopen() }
                ui { onConnected(peer, link) }
                val throttle = Throttle()
                Protocol.send(ch, myName, items, object : TransferListener {
                    override fun onProgress(done: Long, total: Long) {
                        meter.update(done)
                        TransferService.progress(app, done, total, app.getString(R.string.sending_progress, formatSize(done), formatSize(total), meter.speed()))
                        if (throttle.ready(done == total)) ui { showProgress(done, total) }
                    }
                }, chunk = link.chunk, window = link.window)
                TransferService.end(app, app.getString(R.string.notif_sent), app.getString(R.string.done_sent, peer))
                ui { onDone(peer) }
            } catch (e: Exception) {
                if (mine) {
                    TransferService.end(app, app.getString(R.string.notif_failed), e.message ?: e.javaClass.simpleName)
                    ui { onFailed(e) }
                }
            } finally {
                link.close()
            }
        }
    }

    private fun stopListeners() {
        lan?.stop()
        classicServer?.stop()
    }

    private fun onConnected(peer: String, link: Link) {
        if (closed) return
        TransferService.begin(this, getString(R.string.notif_sending, peer), getString(R.string.connected_to, peer, link.transport), link)
        meter = TransferMeter()
        watchdog = StallWatchdog(meter, STALL_LIMIT_MS) {
            stalled = true
            activeLink?.close()
        }.also { it.start() }
        slideshow = false
        mainHandler.removeCallbacks(tick)
        qrScreenMode(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        qrView.visibility = View.GONE
        controls.visibility = View.GONE
        btnMode.visibility = View.GONE
        btnDirect.visibility = View.GONE
        directView.visibility = View.GONE
        channelsCard.visibility = View.GONE
        itemsContainer.visibility = View.GONE
        statusIcon.visibility = View.VISIBLE
        statusIcon.showTone(Tone.WORKING)
        vibrateTick()
        val total = items.map { it.info }.knownTotal()
        if (total > 100 * 1024 * 1024L) checkBatteryOptimization()
        val via = if (link.detail.isEmpty()) link.transport else "${link.transport} (${link.detail})"
        status.text = getString(R.string.connected_to, peer, via)
        channels.text = ""
        channels.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.progress = 0
    }

    private fun showProgress(done: Long, total: Long) {
        if (closed) return
        progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
        channels.text = getString(R.string.sending_progress, formatSize(done), formatSize(total), meter.speed())
    }

    private fun onDone(peer: String) {
        watchdog?.stop()
        if (closed) return
        progress.progress = 1000
        statusIcon.showTone(Tone.SUCCESS)
        status.text = getString(R.string.done_sent, peer)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vibrateSuccess()
    }

    private fun onFailed(e: Exception) {
        watchdog?.stop()
        if (closed) return
        status.text = if (stalled) getString(R.string.stalled, (STALL_LIMIT_MS / 1000).toInt())
        else getString(R.string.failed, e.message ?: e.javaClass.simpleName)
        statusIcon.visibility = View.VISIBLE
        statusIcon.showTone(Tone.ERROR)
        btnRetry.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- QR display -------------------------------------------------------------------------

    private fun showPairing() {
        val qr = pairingQr ?: return
        slideshow = false
        mainHandler.removeCallbacks(tick)
        controls.visibility = View.GONE
        btnMode.setText(R.string.use_qr_slideshow)
        status.setText(R.string.scan_this)
        statusIcon.visibility = View.GONE
        qrView.visibility = View.VISIBLE
        qrView.post { QrRender.show(qrView, QrRender.encode(qr, ErrorCorrectionLevel.M)) }
    }

    /** The slideshow could not be built or rendered: say so in the main card. */
    private fun showError(e: Exception) {
        status.text = getString(R.string.failed, e.message ?: e.toString())
        qrView.visibility = View.GONE
        statusIcon.visibility = View.VISIBLE
        statusIcon.showTone(Tone.ERROR)
    }

    private fun startSlideshow() {
        slideshow = true
        btnMode.setText(R.string.show_pairing_qr)
        controls.visibility = View.VISIBLE
        if (bundle != null) return beginSlides()
        status.setText(R.string.preparing)
        thread(name = "qr-bundle", isDaemon = true) {
            try {
                val b = QrBundle.build(items)
                ui { if (!closed) { bundle = b; beginSlides() } }
            } catch (e: Exception) {
                ui { if (!closed) showError(e) }
            }
        }
    }

    private fun beginSlides() {
        val b = bundle ?: return
        if (!slideshow || closed) return
        val s = QrSlicer(b, chunkSize())
        slicer = s
        frameIndex = 0L
        var text = getString(R.string.slideshow_info, s.count, formatSize(b.size.toLong()))
        if (b.size > QR_WARN_BYTES) {
            val seconds = s.count.toLong() / fps()
            text += "\n" + getString(R.string.slideshow_too_big, formatSize(b.size.toLong()), formatDuration(seconds))
        }
        status.text = text
        statusIcon.visibility = View.GONE
        qrView.visibility = View.VISIBLE
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    private fun chunkSize() = 200 + frameSize.progress * 100      // 200..2900 bytes
    private fun fps() = 1 + speed.progress                         // 1..30 frames/s

    private fun setupSlideshowControls() {
        frameSize.max = 27       // up to 2900 bytes
        frameSize.progress = 4   // 600 bytes: dense enough, still easy for most cameras
        speed.max = 29           // up to 30 fps
        speed.progress = 4       // 5 fps
        frameSizeLabel.text = getString(R.string.frame_size, chunkSize())
        speedLabel.text = getString(R.string.speed, fps())
        frameSize.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                frameSizeLabel.text = getString(R.string.frame_size, chunkSize())
            }
            override fun onStopTrackingTouch(sb: SeekBar) = beginSlides()
        })
        speed.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                speedLabel.text = getString(R.string.speed, fps())
            }
        })
    }

    private open class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // ---- incoming intent --------------------------------------------------------------------

    private fun collectItems(): List<OutgoingItem> {
        val out = ArrayList<OutgoingItem>()
        try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val uri = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) out += UriItem(contentResolver, uri)
                    else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { out += TextItem(it.toString()) }
                }
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.parcelableList<Uri>(Intent.EXTRA_STREAM)?.forEach { out += UriItem(contentResolver, it) }
                else -> {
                    intent.parcelableList<Uri>(EXTRA_URIS)?.forEach { out += UriItem(contentResolver, it) }
                    intent.parcelable<Uri>(EXTRA_TREE_URI)?.let { out += x.vladgba.sendanyway.data.TreeWalker.walk(contentResolver, it) }
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { out += TextItem(it) }
                }
            }
        } catch (e: Exception) {
            // SecurityException for a revoked grant etc. – fall through with what we have
        }
        return out
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableList(key: String): List<T>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra(key)

    companion object {
        const val EXTRA_URIS = "x.vladgba.sendanyway.URIS"
        const val EXTRA_TREE_URI = "x.vladgba.sendanyway.TREE_URI"
        private const val REQ_PERMS = 1
        private const val REQ_WIFI_DIRECT = 2
        private const val REQ_DISCOVERABLE = 3
        private const val REQ_PICK_MORE = 4
        private const val QR_WARN_BYTES = 256 * 1024
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
