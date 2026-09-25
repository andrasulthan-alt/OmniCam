// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.abs
import kotlin.math.min

class ScopeFrame(val histogram: IntArray, val overlay: ImageBitmap?)

/**
 * Alat bantu pro: histogram luma, zebra (highlight terbakar), focus peaking.
 * Bekerja pada plane Y yang di-downsample 4×, lalu overlay diputar ke orientasi layar.
 */
class ScopeAnalyzer(private val onFrame: (ScopeFrame) -> Unit) : ImageAnalysis.Analyzer {

    @Volatile var histogram = true
    @Volatile var zebra = false
    @Volatile var peaking = false

    private var lastEmit = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (!histogram && !zebra && !peaking) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmit < 60) return
            lastEmit = now

            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val w = image.width
            val h = image.height
            val cols = w / STEP
            val rows = h / STEP
            val rot = image.imageInfo.rotationDegrees
            val swap = rot == 90 || rot == 270
            val outW = if (swap) rows else cols
            val outH = if (swap) cols else rows

            val hist = IntArray(BINS)
            val wantOverlay = zebra || peaking
            val pixels = if (wantOverlay) IntArray(outW * outH) else null
            val doZebra = zebra
            val doPeak = peaking

            for (cy in 0 until rows) {
                val y = cy * STEP
                for (cx in 0 until cols) {
                    val x = cx * STEP
                    val v = buf.get(y * rs + x * ps).toInt() and 0xFF
                    hist[v * BINS / 256]++
                    if (pixels == null) continue

                    var color = 0
                    if (doZebra && v >= ZEBRA_LEVEL && ((cx + cy) and 3) < 2) color = ZEBRA_COLOR
                    if (doPeak && color == 0) {
                        val vx = buf.get(y * rs + min(x + 2, w - 1) * ps).toInt() and 0xFF
                        val vy = buf.get(min(y + 2, h - 1) * rs + x * ps).toInt() and 0xFF
                        if (abs(v - vx) + abs(v - vy) > PEAK_THRESHOLD) color = PEAK_COLOR
                    }
                    if (color != 0) {
                        val dx: Int
                        val dy: Int
                        when (rot) {
                            90 -> { dx = rows - 1 - cy; dy = cx }
                            180 -> { dx = cols - 1 - cx; dy = rows - 1 - cy }
                            270 -> { dx = cy; dy = cols - 1 - cx }
                            else -> { dx = cx; dy = cy }
                        }
                        pixels[dy * outW + dx] = color
                    }
                }
            }

            val overlay = pixels?.let {
                Bitmap.createBitmap(it, outW, outH, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            onFrame(ScopeFrame(hist, overlay))
        } finally {
            image.close()
        }
    }

    companion object {
        const val BINS = 64
        private const val STEP = 4
        private const val ZEBRA_LEVEL = 245
        private const val PEAK_THRESHOLD = 40
        private const val ZEBRA_COLOR = 0xCCFF3B30.toInt()
        private const val PEAK_COLOR = 0xDD00E676.toInt()
    }
}

/** Pemindai QR/barcode berbasis ZXing (Apache-2.0, tanpa Google Play Services). */
class QrAnalyzer(private val onResult: (text: String, format: String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
                    BarcodeFormat.PDF_417, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                ),
            )
        )
    }
    private var data = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rs = plane.rowStride
            if (data.size != w * h) data = ByteArray(w * h)
            for (row in 0 until h) {
                buf.position(row * rs)
                buf.get(data, row * w, w)
            }
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            onResult(result.text, result.barcodeFormat.name)
        } catch (_: NotFoundException) {
            // tidak ada kode pada frame ini
        } catch (_: Exception) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}
