// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.hypot
import kotlin.math.roundToInt

data class CameraReport(val id: String, val title: String, val lines: List<Pair<String, String>>)

/** Setara "CameraX Info": tampilkan apa yang sebenarnya didukung tiap kamera di perangkat ini. */
object CameraInspector {

    private val capNames = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT", 9 to "HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA",
        14 to "SYSTEM_CAMERA", 15 to "OFFLINE_PROCESSING", 16 to "ULTRA_HIGH_RES_SENSOR",
        17 to "REMOSAIC_REPROCESSING", 18 to "10BIT_HDR", 19 to "STREAM_USE_CASE",
        20 to "COLOR_SPACE_PROFILES",
    )

    fun inspect(ctx: Context): List<CameraReport> {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return emptyList()
        return cm.cameraIdList.mapNotNull { id -> runCatching { report(cm, id) }.getOrNull() }
    }

    private fun report(cm: CameraManager, id: String): CameraReport {
        val c = cm.getCameraCharacteristics(id)
        val rows = mutableListOf<Pair<String, String>>()
        fun add(k: String, v: String?) { if (!v.isNullOrBlank()) rows += k to v }

        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "Depan"
            CameraMetadata.LENS_FACING_BACK -> "Belakang"
            CameraMetadata.LENS_FACING_EXTERNAL -> "Eksternal"
            else -> "?"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        add("Hadap", facing)
        add("Level hardware", level)

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        add("Kapabilitas", caps.joinToString(", ") { capNames[it] ?: "#$it" })

        val phys = c.physicalCameraIds
        if (phys.isNotEmpty()) add("Kamera fisik (di balik kamera logis)", phys.joinToString(", "))

        val fls = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        add("Focal length", fls?.joinToString(", ") { "%.2f mm".format(it) })
        if (fls != null && sensor != null && fls.isNotEmpty()) {
            val eq = fls[0] * 43.27f / hypot(sensor.width, sensor.height)
            add("Ekuivalen 35mm", "${eq.roundToInt()} mm")
        }
        add("Ukuran sensor", sensor?.let { "%.2f × %.2f mm".format(it.width, it.height) })
        add("Piksel sensor", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { "${it.width} × ${it.height}" })
        add("Aperture", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(", ") { "f/%.1f".format(it) })
        add("Rentang ISO", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { "${it.lower} – ${it.upper}" })
        add("Rentang rana", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            "${it.lower / 1000} µs – ${"%.1f".format(it.upper / 1e9)} s"
        })
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        add("Fokus manual", if (minFocus != null && minFocus > 0f) "Ya (terdekat ≈ ${(100f / minFocus).roundToInt()} cm)" else "Tidak (fixed/AF saja)")
        add("Zoom ratio", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { "%.1f× – %.1f×".format(it.lower, it.upper) })
        add("Flash", if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "Ada" else "Tidak ada")
        add("OIS", if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) "Ya" else "Tidak")
        val vs = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        add("Stabilisasi video", buildString {
            if (vs.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) append("EIS ")
            if (Build.VERSION.SDK_INT >= 33 && vs.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) append("Preview ")
        }.trim().ifEmpty { "Tidak ada" })

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun maxSize(fmt: Int): String? = map?.getOutputSizes(fmt)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?.let { "${it.width}×${it.height} (%.1f MP)".format(it.width * it.height / 1e6) }
        add("JPEG maksimum", maxSize(ImageFormat.JPEG))
        add("RAW_SENSOR maksimum", maxSize(ImageFormat.RAW_SENSOR))
        val hs = map?.highSpeedVideoFpsRanges
        if (hs != null && hs.isNotEmpty()) add("Video high-speed s/d", "${hs.maxOf { it.upper }} fps")

        if (Build.VERSION.SDK_INT >= 31) {
            val ext = runCatching { cm.getCameraExtensionCharacteristics(id).supportedExtensions }.getOrNull()
            if (!ext.isNullOrEmpty()) {
                add("Ekstensi vendor", ext.joinToString(", ") {
                    when (it) {
                        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "Auto"
                        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "Malam"
                        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "Retouch"
                        else -> "#$it"
                    }
                })
            }
        }
        val title = "Kamera $id — $facing" + if (phys.isNotEmpty()) " (logis)" else ""
        return CameraReport(id, title, rows)
    }
}
