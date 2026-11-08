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
import android.os.ParcelFileDescriptor
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
    /** Off = ~12 MP binned photos like stock apps; on = the sensor's largest size. */
    val fullResolution: Boolean = false,
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
        fullResolution = sp.getBoolean("full_res", false),
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
            .putBoolean("full_res", n.fullResolution)
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
            // Wajib: tanpa MIME type, MediaStore menolak insert ke koleksi video
            // dan CameraX melapor ERROR_INVALID_OUTPUT_OPTIONS (kode 5).
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        }
        return MediaStoreOutputOptions
            .Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /**
     * Removes OmniCam's own unfinished (pending) files older than an hour: left behind when the app was
     * killed during a slow-motion or time-lapse take, they would otherwise stay hidden for a week.
     */
    fun deleteStalePending(ctx: Context) {
        val cutoff = System.currentTimeMillis() / 1000 - 3600
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DATE_ADDED}<?",
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(ctx.packageName, cutoff.toString()),
            )
        }
        listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ).forEach { col ->
            runCatching {
                ctx.contentResolver.query(col, arrayOf(android.provider.BaseColumns._ID), args, null)?.use { c ->
                    while (c.moveToNext()) {
                        runCatching { ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(col, c.getLong(0)), null, null) }
                    }
                }
            }
        }
    }

    /** OmniCam's newest saved photo or video (no permission needed: apps always see their own files). */
    fun latestOwn(ctx: Context): Uri? {
        val sel = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE ?"
        val args = arrayOf(ctx.packageName, "%dng%")
        fun newest(collection: Uri): Pair<Uri, Long>? = ctx.contentResolver.query(
            collection,
            arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) null
            else android.content.ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1)
        }
        val img = runCatching { newest(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)) }.getOrNull()
        val vid = runCatching { newest(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)) }.getOrNull()
        return listOfNotNull(img, vid).maxByOrNull { it.second }?.first
    }

    /** A new, still-hidden (pending) video entry in DCIM/OmniCam plus a writable descriptor for it. */
    fun newPendingVideo(ctx: Context, stamp: String, suffix: String): Pair<Uri, ParcelFileDescriptor>? {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp}_$suffix.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val fd = runCatching { cr.openFileDescriptor(uri, "rw") }.getOrNull()
        if (fd == null) { runCatching { cr.delete(uri, null, null) }; return null }
        return uri to fd
    }

    /** Makes a finished pending video visible in the gallery. */
    fun finishPendingVideo(ctx: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { ctx.contentResolver.update(uri, values, null, null) }
    }

    /**
     * Writes an already-encoded JPEG (e.g. a merged HDR photo) to DCIM/OmniCam.
     * Only orientation, capture time and (if enabled) GPS are written; no device identifiers.
     */
    fun saveJpeg(
        ctx: Context,
        bytes: ByteArray,
        stamp: String,
        suffix: String,
        rotationDegrees: Int,
        location: Location?,
    ): Uri? {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp}_$suffix.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { it.write(bytes) } ?: error("cannot open output")
            cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val orientation = when (((rotationDegrees % 360) + 360) % 360) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                if (location != null) exif.setGpsInfo(location)
                exif.saveAttributes()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            runCatching { cr.delete(uri, null, null) }
            null
        }
    }

    /**
     * Hapus lokasi GPS dan identitas perangkat dari JPEG biasa.
     * Tidak dijalankan untuk Ultra HDR (menulis ulang EXIF berisiko merusak gain map/MPF).
     */
    fun scrub(ctx: Context, uri: Uri, keepGps: Boolean = false) {
        val gps = if (keepGps) emptyList() else listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
        val tags = gps + listOf(
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
