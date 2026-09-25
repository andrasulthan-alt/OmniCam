// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.storage

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.omnicam.model.GridType
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Settings(
    /** Gaya GrapheneOS: hapus lokasi + identitas perangkat dari EXIF JPEG */
    val stripMetadata: Boolean = true,
    /** Gaya Open Camera: tulis lokasi ke foto. Default MATI. */
    val geotag: Boolean = false,
    val shutterSound: Boolean = true,
    val volumeShutter: Boolean = true,
    val mirrorFront: Boolean = false,
    val qualityFirst: Boolean = true,
    val grid: GridType = GridType.OFF,
    /** Indikator kemiringan horizon (sensor akselerometer) */
    val level: Boolean = true,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("omnicam", Context.MODE_PRIVATE)
    val settings = MutableStateFlow(load())

    private fun load() = Settings(
        stripMetadata = sp.getBoolean("strip", true),
        geotag = sp.getBoolean("geotag", false),
        shutterSound = sp.getBoolean("sound", true),
        volumeShutter = sp.getBoolean("volume", true),
        mirrorFront = sp.getBoolean("mirror", false),
        qualityFirst = sp.getBoolean("quality", true),
        grid = runCatching { GridType.valueOf(sp.getString("grid", null) ?: "OFF") }
            .getOrDefault(GridType.OFF),
        level = sp.getBoolean("level", true),
    )

    fun update(block: (Settings) -> Settings) {
        val n = block(settings.value)
        settings.value = n
        sp.edit()
            .putBoolean("strip", n.stripMetadata)
            .putBoolean("geotag", n.geotag)
            .putBoolean("sound", n.shutterSound)
            .putBoolean("volume", n.volumeShutter)
            .putBoolean("mirror", n.mirrorFront)
            .putBoolean("quality", n.qualityFirst)
            .putString("grid", n.grid.name)
            .putBoolean("level", n.level)
            .apply()
    }
}

object MediaOutput {
    private const val DIR = "DCIM/OmniCam"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun imageOptions(
        ctx: Context,
        mime: String,
        ext: String,
        stamp: String,
        location: Location?,
        mirror: Boolean,
    ): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$stamp.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        }
        val meta = ImageCapture.Metadata().apply {
            this.location = location
            isReversedHorizontal = mirror
        }
        return ImageCapture.OutputFileOptions
            .Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(meta)
            .build()
    }

    fun videoOptions(ctx: Context, stamp: String): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$stamp")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        }
        return MediaStoreOutputOptions
            .Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /**
     * Hapus lokasi GPS dan identitas perangkat dari JPEG biasa.
     * Tidak dijalankan untuk Ultra HDR (menulis ulang EXIF berisiko merusak gain map/MPF).
     */
    fun scrub(ctx: Context, uri: Uri) {
        val tags = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
        )
        runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                tags.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun lastLocation(ctx: Context): Location? {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
    }
}
