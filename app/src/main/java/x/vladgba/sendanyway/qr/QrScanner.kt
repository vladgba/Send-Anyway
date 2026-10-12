package x.vladgba.sendanyway.qr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal camera2 QR scanner: preview into a [TextureView], luminance frames from an
 * [ImageReader] go to ZXing on a separate thread. Frames are dropped while a decode is running.
 * [onCode] gets the raw bytes of the QR byte segments on the main thread.
 *
 * Steadier image for the slideshow:
 * - continuous AF finds the code, then focus is **locked** as soon as a code decodes, so the
 *   lens stops "breathing" between frames; it unlocks again after [UNLOCK_AFTER_MS] without
 *   a decode, or when [refocus] is called (tap on preview);
 * - optical image stabilisation on, if the lens has it;
 * - a fixed high AE frame rate, which caps exposure time and reduces motion blur from hand shake.
 *
 * Expects the host activity to be locked to portrait.
 */
class QrScanner(
    private val ctx: Context,
    private val view: TextureView,
    private val onCode: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onFocusLock: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var decThread: HandlerThread? = null
    private var decHandler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var size = Size(1280, 720)
    private var swapped = true
    private var luma = ByteArray(0)
    private val busy = AtomicBoolean(false)
    @Volatile private var running = false

    // Camera capabilities, read in open()
    private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
    private var ois = false
    private var hasTorch = false
    @Volatile private var torchOn = false
    private var fpsRange: Range<Int>? = null
    private var targets: List<Surface> = emptyList()

    @Volatile private var focusLocked = false
    @Volatile private var lastDecodeAt = 0L
    private val autoUnlock = object : Runnable {
        override fun run() {
            if (!running) return
            if (focusLocked && SystemClock.uptimeMillis() - lastDecodeAt > UNLOCK_AFTER_MS) setFocusLock(false)
            camHandler?.postDelayed(this, 500)
        }
    }

    private val qr = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1")

    fun start() {
        if (running) return
        running = true
        camThread = HandlerThread("camera").also { it.start(); camHandler = Handler(it.looper) }
        decThread = HandlerThread("qr-decode").also { it.start(); decHandler = Handler(it.looper) }
        if (view.isAvailable) open()
        else view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (running) open() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun stop() {
        if (!running) return
        running = false
        focusLocked = false
        torchOn = false
        val h = camHandler
        h?.removeCallbacks(autoUnlock)
        val ct = camThread
        val dt = decThread
        val cleanup = Runnable {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            previewSurface?.release()
            session = null; device = null; reader = null; previewSurface = null
            ct?.quitSafely()
            dt?.quitSafely()
        }
        if (h != null) h.post(cleanup) else cleanup.run()
        camHandler = null; camThread = null; decHandler = null; decThread = null
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        try {
            val ids = mgr.cameraIdList
            val id = ids.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.firstOrNull() ?: return onError("no camera")
            val chars = mgr.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError("no camera config")
            size = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888))
            swapped = (chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            afMode = when {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF // fixed-focus camera
            }
            ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            hasTorch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            // Highest fixed-ish range up to 30 fps, e.g. [30,30]: short exposures, less blur.
            fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper <= 30 }
                ?.maxWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
            luma = ByteArray(size.width * size.height)
            applyTransform()
            val handler = camHandler ?: return
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener(::onImage, handler)
            }
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!running) { d.close(); return }
                    device = d
                    createSession(d)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    main.post { this@QrScanner.onError("camera error $error") }
                }
            }, handler)
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List) is fine down to API 24
    private fun createSession(d: CameraDevice) {
        val st = view.surfaceTexture ?: return
        val r = reader ?: return
        st.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(st).also { previewSurface = it }
        val targets = listOf(preview, r.surface)
        this.targets = targets
        try {
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) { s.close(); return }
                    session = s
                    try {
                        s.setRepeatingRequest(request(d).build(), null, camHandler)
                        camHandler?.postDelayed(autoUnlock, 500)
                    } catch (e: Exception) {
                        main.post { onError(e.message ?: e.toString()) }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { onError("camera configuration failed") }
                }
            }, camHandler)
        } catch (e: Exception) {
            main.post { onError(e.message ?: e.toString()) }
        }
    }

    private fun request(d: CameraDevice): CaptureRequest.Builder =
        d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            targets.forEach(::addTarget)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode)
            if (ois) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            if (hasTorch && torchOn) {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

    fun hasFlash(): Boolean = hasTorch

    val isTorchOn: Boolean get() = torchOn

    fun toggleTorch(callback: (Boolean) -> Unit) {
        if (!hasTorch || !running) return
        torchOn = !torchOn
        camHandler?.post {
            val s = session
            val d = device
            if (s != null && d != null) {
                try {
                    s.setRepeatingRequest(request(d).build(), null, camHandler)
                } catch (_: Exception) {}
            }
            main.post { callback(torchOn) }
        }
    }

    /** Tap-to-refocus: unlock and let continuous AF search again; it re-locks on the next decode. */
    fun refocus() {
        camHandler?.post { setFocusLock(false, force = true) }
    }

    /**
     * In continuous AF modes, AF_TRIGGER_START locks the lens where it is (after finishing a scan
     * in progress) and the repeating request keeps it there; AF_TRIGGER_CANCEL resumes scanning.
     * Must run on the camera thread.
     */
    private fun setFocusLock(lock: Boolean, force: Boolean = false) {
        if (!force && lock == focusLocked) return
        if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) return
        val d = device ?: return
        val s = session ?: return
        try {
            val trigger = request(d).apply {
                set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    if (lock) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL,
                )
            }
            s.capture(trigger.build(), null, camHandler)
            focusLocked = lock
            main.post { if (running) onFocusLock(lock) }
        } catch (e: Exception) {
            // session closing; ignore
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (!running || !busy.compareAndSet(false, true)) return
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val stride = plane.rowStride
            val buf = plane.buffer
            if (luma.size != w * h) luma = ByteArray(w * h)
            val data = luma
            if (stride == w) buf.get(data, 0, w * h)
            else for (y in 0 until h) {
                buf.position(y * stride)
                buf.get(data, y * w, w)
            }
            val posted = decHandler?.post { decode(data, w, h); busy.set(false) } ?: false
            if (!posted) busy.set(false)
        } catch (e: Exception) {
            busy.set(false)
        } finally {
            img.close()
        }
    }

    private fun decode(data: ByteArray, w: Int, h: Int) {
        val src = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val result = try {
            qr.decode(BinaryBitmap(HybridBinarizer(src)), hints)
        } catch (e: ReaderException) {
            null
        } finally {
            qr.reset()
        } ?: return

        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<*>
        val bytes = if (!segments.isNullOrEmpty()) {
            val bos = ByteArrayOutputStream()
            segments.forEach { (it as? ByteArray)?.let(bos::write) }
            bos.toByteArray()
        } else {
            result.text.toByteArray(Charsets.ISO_8859_1)
        }
        lastDecodeAt = SystemClock.uptimeMillis()
        if (!focusLocked) camHandler?.post { setFocusLock(true) }
        main.post { if (running) onCode(bytes) }
    }

    /** Center-crops the preview so it isn't stretched. */
    private fun applyTransform() {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val bw = (if (swapped) size.height else size.width).toFloat()
        val bh = (if (swapped) size.width else size.height).toFloat()
        val vr = vw / vh
        val br = bw / bh
        val m = Matrix()
        if (vr > br) m.setScale(1f, vr / br, vw / 2, vh / 2) else m.setScale(br / vr, 1f, vw / 2, vh / 2)
        view.setTransform(m)
    }

    private companion object {
        const val UNLOCK_AFTER_MS = 2_500L
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        val fits = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return fits.filter { it.width * 9 == it.height * 16 }.maxByOrNull { it.width * it.height }
            ?: fits.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }
}
