package x.vladgba.sendanyway.qr

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.honeywell.aidc.AidcManager
import com.honeywell.aidc.BarcodeFailureEvent
import com.honeywell.aidc.BarcodeReadEvent
import com.honeywell.aidc.BarcodeReader

/**
 * Direct hardware integration for Honeywell enterprise mobile computers (EDA51, CT40, CT60, etc.)
 * using the Honeywell AIDC DataCollection SDK.
 *
 * When claimed, this bypasses OS wedge/intent settings and directs hardware laser/imager decodes
 * directly to [onCode] with raw binary bytes. Supports automatic continuous scan mode during
 * multi-frame slideshow transfers.
 */
class HoneywellScanner(
    context: Context,
    private val onCode: (ByteArray) -> Unit,
    private val onReady: (Boolean) -> Unit = {},
) : BarcodeReader.BarcodeListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: AidcManager? = null
    private var reader: BarcodeReader? = null
    private var isResumed = false
    private var isDestroyed = false

    @Volatile var isContinuous = false
        private set

    private val continuousTimeoutRunnable = Runnable {
        setContinuous(false)
    }

    init {
        try {
            // Pass Activity context directly to AidcManager for proper lifecycle service binding
            AidcManager.create(context) { aidcManager ->
                if (isDestroyed) {
                    aidcManager.close()
                    return@create
                }
                manager = aidcManager
                try {
                    reader = aidcManager.createBarcodeReader().apply {
                        configure()
                        addBarcodeListener(this@HoneywellScanner)
                        if (isResumed) claim()
                    }
                    mainHandler.post { onReady(true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Honeywell barcode reader: ${e.message}", e)
                    mainHandler.post { onReady(false) }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Honeywell AIDC service not available: ${e.message}")
            mainHandler.post { onReady(false) }
        }
    }

    private fun BarcodeReader.configure() {
        runCatching { setProperty(BarcodeReader.PROPERTY_TRIGGER_CONTROL_MODE, BarcodeReader.TRIGGER_CONTROL_MODE_AUTO_CONTROL) }
        runCatching { setProperty(BarcodeReader.PROPERTY_QR_CODE_ENABLED, true) }
        runCatching { setProperty(BarcodeReader.PROPERTY_DATA_PROCESSOR_CHARSET, "ISO-8859-1") }
    }

    /**
     * Toggles continuous scan mode for multi-frame slideshow reception.
     * When enabled, the scanner engine automatically re-triggers decoding after each frame or timeout,
     * so the user does not need to hold or repeatedly pump the physical trigger button.
     */
    fun setContinuous(enabled: Boolean) {
        if (isContinuous == enabled) return
        isContinuous = enabled
        mainHandler.removeCallbacks(continuousTimeoutRunnable)
        if (enabled) {
            runCatching { reader?.setProperty("TriggerMode", "continuous") }
            triggerContinuous()
            mainHandler.postDelayed(continuousTimeoutRunnable, CONTINUOUS_TIMEOUT_MS)
        } else {
            runCatching { reader?.softwareTrigger(false) }
            runCatching { reader?.aim(false) }
            runCatching { reader?.light(false) }
            runCatching { reader?.setProperty("TriggerMode", "autoControl") }
        }
    }

    private fun triggerContinuous() {
        if (!isContinuous || !isResumed || isDestroyed) return
        mainHandler.post {
            if (!isContinuous || !isResumed || isDestroyed) return@post
            try {
                reader?.softwareTrigger(false)
                reader?.softwareTrigger(true)
                runCatching { reader?.aim(true) }
            } catch (e: Exception) {
                Log.w(TAG, "continuous trigger failed: ${e.message}")
            }
        }
    }

    fun onResume() {
        isResumed = true
        try {
            reader?.claim()
            if (isContinuous) triggerContinuous()
        } catch (e: Exception) {
            Log.w(TAG, "claim failed: ${e.message}")
        }
    }

    fun onPause() {
        isResumed = false
        setContinuous(false)
        try {
            reader?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed: ${e.message}")
        }
    }

    fun onDestroy() {
        isDestroyed = true
        setContinuous(false)
        try {
            reader?.apply {
                removeBarcodeListener(this@HoneywellScanner)
                close()
            }
        } catch (_: Exception) {}
        reader = null
        try {
            manager?.close()
        } catch (_: Exception) {}
        manager = null
    }

    override fun onBarcodeEvent(event: BarcodeReadEvent) {
        val data = event.barcodeData ?: return
        // ZXing encodes binary QR in ISO-8859-1, mapping bytes 0..255 1:1 to chars.
        val bytes = if (data.all { it.code <= 0xFF }) data.toByteArray(Charsets.ISO_8859_1) else data.toByteArray(Charsets.UTF_8)
        mainHandler.post { onCode(bytes) }
        if (isContinuous) {
            mainHandler.removeCallbacks(continuousTimeoutRunnable)
            mainHandler.postDelayed(continuousTimeoutRunnable, CONTINUOUS_TIMEOUT_MS)
            mainHandler.postDelayed({ triggerContinuous() }, 40)
        }
    }

    override fun onFailureEvent(event: BarcodeFailureEvent) {
        if (isContinuous) {
            mainHandler.postDelayed({ triggerContinuous() }, 40)
        }
    }

    companion object {
        private const val TAG = "HoneywellScanner"
        private const val CONTINUOUS_TIMEOUT_MS = 20_000L // 20s inactivity timeout to protect battery/hardware

        fun isHoneywell(): Boolean {
            val m = Build.MANUFACTURER.lowercase()
            val b = Build.BRAND.lowercase()
            return m.contains("honeywell") || b.contains("honeywell") || m.contains("intermec") || b.contains("intermec")
        }
    }
}
