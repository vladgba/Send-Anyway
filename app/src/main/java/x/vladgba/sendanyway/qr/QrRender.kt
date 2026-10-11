package x.vladgba.sendanyway.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrRender {
    private val writer = QRCodeWriter()

    /**
     * Encodes raw bytes as a QR code (byte mode). ZXing's default byte charset is ISO-8859-1,
     * which maps every byte 0..255 to exactly one char, so no ECI header is needed.
     * Returns a 1-pixel-per-module bitmap; [show] scales it up without smoothing.
     */
    fun encode(data: ByteArray, ecc: ErrorCorrectionLevel): Bitmap {
        val m = QrSelfCheck.readableMatrix(data, ecc)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h) { if (m[it % w, it / w]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun show(view: ImageView, bmp: Bitmap) {
        val side = maxOf(view.width, view.height, 600)
        val k = maxOf(1, side / bmp.width)
        val scaled = Bitmap.createScaledBitmap(bmp, bmp.width * k, bmp.height * k, false)
        view.setImageDrawable(BitmapDrawable(view.resources, scaled).apply {
            isFilterBitmap = false
            setAntiAlias(false)
        })
    }
}
