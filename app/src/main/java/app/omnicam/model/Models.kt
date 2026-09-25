// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.model

import android.hardware.camera2.CaptureRequest
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.video.Quality
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Mode(val label: String) {
    PHOTO("FOTO"), PRO("PRO"), VIDEO("VIDEO"), QR("QR")
}

enum class PhotoFormat(val label: String, val outputFormat: Int) {
    JPEG("JPEG", ImageCapture.OUTPUT_FORMAT_JPEG),
    ULTRA_HDR("Ultra HDR", ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR),
    RAW("RAW (DNG)", ImageCapture.OUTPUT_FORMAT_RAW),
    RAW_JPEG("RAW+JPEG", ImageCapture.OUTPUT_FORMAT_RAW_JPEG);

    val hasRaw: Boolean get() = this == RAW || this == RAW_JPEG
}

enum class GridType(val label: String) {
    OFF("Mati"), THIRDS("3×3"), GRID4("4×4"), GOLDEN("Golden")
}

fun extensionLabel(mode: Int): String = when (mode) {
    ExtensionMode.AUTO -> "Auto"
    ExtensionMode.HDR -> "HDR"
    ExtensionMode.NIGHT -> "Malam"
    ExtensionMode.BOKEH -> "Potret"
    ExtensionMode.FACE_RETOUCH -> "Retouch"
    else -> "Mati"
}

fun qualityLabel(q: Quality): String = when (q) {
    Quality.UHD -> "4K"
    Quality.FHD -> "1080p"
    Quality.HD -> "720p"
    Quality.SD -> "480p"
    else -> q.toString()
}

fun awbLabel(mode: Int): String = when (mode) {
    CaptureRequest.CONTROL_AWB_MODE_AUTO -> "Auto"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Lampu pijar"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Neon"
    CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Neon hangat"
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Siang"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Mendung"
    CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "Senja"
    CaptureRequest.CONTROL_AWB_MODE_SHADE -> "Bayangan"
    else -> "WB $mode"
}

/** Format kecepatan rana: 1/250 atau 0.5s / 2s */
fun formatShutter(ns: Long): String {
    if (ns <= 0) return "—"
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.5) {
        if (sec >= 10) "${sec.roundToInt()}s" else "%.1fs".format(sec)
    } else {
        "1/${(1.0 / sec).roundToInt()}"
    }
}

data class LensOption(
    val id: String,
    val front: Boolean,
    /** Ekuivalen 35mm dalam mm; 0 jika tidak diketahui */
    val eqMm: Float,
) {
    val label: String
        get() = if (eqMm > 0f) "${eqMm.roundToInt()}mm" else "Kam $id"
}

data class ManualState(
    val exposureManual: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 16_000_000L,
    val focusManual: Boolean = false,
    /** 0 = tak hingga, minFocusDiopter = terdekat */
    val focusDiopter: Float = 0f,
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val evIndex: Int = 0,
)

data class ManualRanges(
    val manualSensor: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val expMinNs: Long,
    val expMaxNs: Long,
    val minFocusDiopter: Float,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val evSupported: Boolean,
    val awbModes: List<Int>,
) {
    val manualFocus: Boolean get() = minFocusDiopter > 0f

    fun isoAt(f: Float): Int =
        (isoMin * (isoMax.toFloat() / isoMin).pow(f)).roundToInt().coerceIn(isoMin, isoMax)

    fun isoFrac(iso: Int): Float =
        if (isoMax <= isoMin) 0f
        else (ln(iso.toFloat() / isoMin) / ln(isoMax.toFloat() / isoMin)).coerceIn(0f, 1f)

    fun expAt(f: Float): Long =
        (expMinNs * (expMaxNs.toDouble() / expMinNs).pow(f.toDouble())).toLong()
            .coerceIn(expMinNs, expMaxNs)

    fun expFrac(ns: Long): Float =
        if (expMaxNs <= expMinNs) 0f
        else (ln(ns.toDouble() / expMinNs) / ln(expMaxNs.toDouble() / expMinNs))
            .toFloat().coerceIn(0f, 1f)
}

data class ScopeSettings(
    val histogram: Boolean = true,
    val zebra: Boolean = false,
    val peaking: Boolean = false,
)

data class VideoUi(
    val qualities: List<Quality> = emptyList(),
    val quality: Quality? = null,
    val fps: Int = 30,
    val fps60Ok: Boolean = false,
    val hdr: Boolean = false,
    val hdrOk: Boolean = false,
    val stab: Boolean = true,
    val stabOk: Boolean = false,
    val mic: Boolean = true,
)

data class FocusRing(val x: Float, val y: Float, val stamp: Long)
data class QrHit(val text: String, val format: String)

/** Nilai hidup dari sensor (ISO, rana, fokus) — dipisah agar tidak merekomposisi seluruh UI. */
data class Readout(val iso: Int = 0, val expNs: Long = 0L, val focusDiopter: Float = 0f)

data class CamUi(
    val ready: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val front: Boolean = false,
    val lensId: String? = null,
    val lenses: List<LensOption> = emptyList(),

    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,

    val hasFlash: Boolean = false,
    val flash: Int = ImageCapture.FLASH_MODE_OFF,
    val torch: Boolean = false,
    val timer: Int = 0,
    val burst: Int = 1,
    val countdown: Int = 0,
    val busy: Boolean = false,

    val format: PhotoFormat = PhotoFormat.JPEG,
    val formats: List<PhotoFormat> = listOf(PhotoFormat.JPEG),
    val extension: Int = ExtensionMode.NONE,
    val extensions: List<Int> = emptyList(),

    val manual: ManualState = ManualState(),
    val ranges: ManualRanges? = null,
    val scope: ScopeSettings = ScopeSettings(),

    val video: VideoUi = VideoUi(),
    val recording: Boolean = false,
    val paused: Boolean = false,
    val recordedMs: Long = 0L,

    val lastUri: Uri? = null,
    val qr: QrHit? = null,
    val focusRing: FocusRing? = null,
    val message: String? = null,
)
