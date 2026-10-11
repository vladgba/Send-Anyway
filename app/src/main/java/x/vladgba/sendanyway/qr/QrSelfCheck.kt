package x.vladgba.sendanyway.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * ZXing's encoder picks the mask pattern by penalty score, and for a few percent of payloads
 * that mask produces shapes its own detector can't find, even in a perfect image (measured:
 * ~3-15 % of slideshow frames, every one of them readable with a different mask). So every
 * code is decoded in memory before it's shown, and re-encoded with the next mask if needed.
 * Pure JVM code, no Android types, so it's unit-testable.
 */
object QrSelfCheck {
    private const val MARGIN = 1 // minimal quiet zone; the view's white padding adds the rest
    /** Checked at two scales: a mask that only works at one sampling grid is fragile on camera. */
    private val SCALES = intArrayOf(4, 5)

    fun readableMatrix(data: ByteArray, ecc: ErrorCorrectionLevel): BitMatrix {
        val text = String(data, Charsets.ISO_8859_1)
        val auto = encode(text, ecc, null)
        if (decodes(auto)) return auto
        for (mask in 0..7) {
            val m = encode(text, ecc, mask)
            if (decodes(m)) return m
        }
        return auto // nothing better; a camera may still read it, and the fountain code covers it
    }

    private fun encode(text: String, ecc: ErrorCorrectionLevel, mask: Int?): BitMatrix {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ecc
        hints[EncodeHintType.MARGIN] = MARGIN
        if (mask != null) hints[EncodeHintType.QR_MASK_PATTERN] = mask
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    private fun decodes(m: BitMatrix): Boolean = SCALES.all { decodesAt(m, it) }

    private fun decodesAt(m: BitMatrix, k: Int): Boolean {
        val w = m.width * k
        val h = m.height * k
        val lum = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (!m[x / k, y / k]) lum[y * w + x] = 0xFF.toByte()
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false))))
            true
        } catch (e: ReaderException) {
            false
        }
    }
}
