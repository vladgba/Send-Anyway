package x.vladgba.sendanyway.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import x.vladgba.sendanyway.qr.QrSelfCheck
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/** Same encode/decode settings as QrRender / QrScanner, checked for exact binary round-trip. */
class QrCodecTest {

    private fun roundTrip(data: ByteArray, ecc: ErrorCorrectionLevel): ByteArray {
        val m = QrSelfCheck.readableMatrix(data, ecc) // exactly what the app shows
        val k = 4 // pixels per module, like a camera image of the screen
        val w = m.width * k
        val lum = ByteArray(w * w) { i -> if (m[(i % w) / k, (i / w) / k]) 0 else 255.toByte() }
        val src = PlanarYUVLuminanceSource(lum, w, w, 0, 0, w, w, false)
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1"))
        val segs = r.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<*>
        val bos = ByteArrayOutputStream()
        segs.forEach { bos.write(it as ByteArray) }
        return bos.toByteArray()
    }

    @Test
    fun pairingCodeRoundTrip() {
        val (id, key) = Pairing.newSecrets()
        val p = Pairing(id, key, "Phone", 40000, listOf(byteArrayOf(10, 0, 0, 7)), true).encode()
        assertArrayEquals(p, roundTrip(p, ErrorCorrectionLevel.M))
    }

    @Test
    fun slideshowFramesRoundTrip() {
        val bundle = ByteArray(5000).also { Random(9).nextBytes(it) }
        for (chunk in listOf(200, 600, 1200, 2000)) {
            val s = QrSlicer(bundle, chunk)
            // Source and repair frames. Without the mask self-check ZXing misses 3-15 % of these
            // even in a perfect image; with it, all of them must decode.
            val seqs = (0 until s.count) + (s.count until s.count + 20)
            var unreadable = 0
            val bad = ArrayList<String>()
            for (seq in seqs) {
                val f = s.frame(seq)
                val back = try { roundTrip(f, ErrorCorrectionLevel.L) } catch (e: com.google.zxing.ReaderException) { unreadable++; bad += "$seq"; continue }
                assertArrayEquals("chunk=$chunk seq=$seq", f, back) // when decoded, always bit-exact
            }
            assertTrue("chunk=$chunk: $unreadable of ${seqs.size} unreadable (K=${s.count}, seqs $bad)", unreadable == 0)
        }
    }
}
