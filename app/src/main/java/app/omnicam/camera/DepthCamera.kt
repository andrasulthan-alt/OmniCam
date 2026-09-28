// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import app.omnicam.storage.MediaOutput
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * D3D — OmniCam's take on Dazz Cam's "D3D" camera: one tap, a photo plus real depth data, turned
 * into a short looping clip in which the foreground and background move differently around the
 * subject, so the photo looks 3D.
 *
 * Dazz Cam uses the Portrait-mode depth map of dual/triple-camera iPhones. On Android the equivalent
 * without AI is a camera that exposes depth to apps (Camera2 DEPTH_OUTPUT, usually a ToF sensor,
 * e.g. Galaxy S20+/Ultra, Note10+). The mode is only offered on phones that have one.
 */

/** A back-facing depth camera: its id, depth-map size and 35mm-equivalent focal length. */
data class DepthCam(val id: String, val size: Size, val eqFocal: Float, val sensorOrientation: Int)

object DepthSupport {
    /** The phone's back depth camera, or null when there is none (then D3D is hidden). */
    fun find(cm: CameraManager): DepthCam? = runCatching {
        cm.cameraIdList.firstNotNullOfOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@firstNotNullOfOrNull null
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return@firstNotNullOfOrNull null
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) return@firstNotNullOfOrNull null
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.DEPTH16) ?: return@firstNotNullOfOrNull null
            val size = sizes.maxByOrNull { it.width * it.height } ?: return@firstNotNullOfOrNull null
            DepthCam(id, size, eqFocal(c), c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
        }
    }.getOrNull()

    fun eqFocal(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 26f
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
        return f * 43.27f / hypot(s.width, s.height)
    }
}

/** Depth map in millimetres (0 = unknown), row-major, in the depth sensor's orientation. */
class DepthMap(val w: Int, val h: Int, val mm: FloatArray)

/**
 * Grabs a few DEPTH16 frames from the depth camera and averages them. Runs after the photo, while
 * CameraX is unbound (most phones cannot stream two back cameras at once). [done] runs on the main
 * thread once the depth camera is fully closed, so CameraX can reopen straight away.
 */
class DepthGrabber(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun grab(cam: DepthCam, frames: Int = 6, done: (DepthMap?, String?) -> Unit) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val t = HandlerThread("omnicam-depth").also { it.start() }
        val h = Handler(t.looper)
        val w = cam.size.width
        val hh = cam.size.height
        val sum = FloatArray(w * hh)
        val cnt = IntArray(w * hh)
        var got = 0
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var result: DepthMap? = null
        var error: String? = null
        var finished = false
        val reader = ImageReader.newInstance(w, hh, ImageFormat.DEPTH16, 3)

        fun finish(err: String?) {
            if (finished) return
            finished = true
            error = err
            if (err == null && got > 0) {
                result = DepthMap(w, hh, FloatArray(w * hh) { if (cnt[it] > 0) sum[it] / cnt[it] else 0f })
            } else if (err == null) error = "No depth data"
            runCatching { session?.close() }
            val d = device
            if (d != null) runCatching { d.close() } else {
                runCatching { reader.close() }
                t.quitSafely()
                main.post { done(result, error) }
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (finished) return@setOnImageAvailableListener
                val plane = img.planes[0]
                val sb = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val rowShorts = plane.rowStride / 2
                for (y in 0 until hh) for (x in 0 until w) {
                    val v = sb.get(y * rowShorts + x).toInt() and 0xFFFF
                    val range = v and 0x1FFF
                    val conf = (v shr 13) and 0x7
                    // DEPTH16: confidence 1 = 0 % confident; range 0 = no measurement
                    if (range > 0 && conf != 1) { sum[y * w + x] += range.toFloat(); cnt[y * w + x]++ }
                }
                got++
                if (got >= frames) finish(null)
            } finally {
                img.close()
            }
        }, h)

        h.postDelayed({ finish(if (got > 0) null else "The depth sensor did not respond") }, 4000)

        try {
            cm.openCamera(cam.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    if (finished) { d.close(); return }
                    try {
                        val cfg = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(reader.surface)),
                            Executor { r -> h.post(r) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    runCatching {
                                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        b.addTarget(reader.surface)
                                        s.setRepeatingRequest(b.build(), null, h)
                                    }.onFailure { finish("Depth capture failed: ${it.message}") }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { finish("The depth camera refused the configuration") }
                            },
                        )
                        d.createCaptureSession(cfg)
                    } catch (e: Exception) {
                        finish("Depth capture failed: ${e.message}")
                    }
                }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); finish("Depth camera error $e") }
                override fun onClosed(d: CameraDevice) {
                    runCatching { reader.close() }
                    t.quitSafely()
                    main.post { done(result, error) }
                }
            }, h)
        } catch (e: Exception) {
            finish("Could not open the depth camera: ${e.message}")
        }
    }
}

/** Builds the D3D clip from the photo and the depth map, and saves it as an MP4. */
object D3DRenderer {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FPS = 30
    private const val FRAMES_PER_CYCLE = 40   // one gentle sway = 1.3 s
    private const val CYCLES = 3
    private const val WORK_W = 1152           // working size (sensor orientation, 4:3)
    private const val WORK_H = 864
    private const val AMPLITUDE = 0.03f       // max shift, fraction of the width

    fun build(
        ctx: Context, jpeg: ByteArray, rotation: Int, mainSensorOrientation: Int, mainEqFocal: Float,
        depth: DepthMap, depthCam: DepthCam,
    ): Uri? {
        // 1. Photo -> working size, planar YUV
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: error("could not decode the photo")
        val bmp = Bitmap.createScaledBitmap(decoded, WORK_W, WORK_H, true)
        if (bmp !== decoded) decoded.recycle()
        val W = WORK_W; val H = WORK_H
        val argb = IntArray(W * H)
        bmp.getPixels(argb, 0, W, 0, 0, W, H)
        bmp.recycle()
        val yP = ByteArray(W * H)
        val uP = ByteArray(W * H / 4)
        val vP = ByteArray(W * H / 4)
        rgbToI420(argb, W, H, yP, uP, vP)

        // 2. Depth -> disparity map at half resolution, registered to the photo
        val hw = W / 2; val hh = H / 2
        val disp = disparityMap(depth, depthCam, mainSensorOrientation, mainEqFocal, hw, hh)
        val shift = normalise(disp, hw, hh)   // -1..1, 0 = the subject in the centre

        // 3. Render and encode
        val amp = AMPLITUDE * W
        val margin = (amp * 1.15f).roundToInt() + 2
        val outW = (W - 2 * margin) and 15.inv()
        val outH = (H - 2 * margin) and 15.inv()
        val x0 = (W - outW) / 2
        val y0 = (H - outH) / 2
        return encode(ctx, rotation, outW, outH) { k, dst ->
            val phi = 2.0 * PI * k / FRAMES_PER_CYCLE
            val ox = (amp * sin(phi)).toFloat()
            val oy = (amp * 0.3 * cos(phi)).toFloat()
            renderFrame(yP, uP, vP, W, H, shift, hw, ox, oy, x0, y0, outW, outH, dst)
        }
    }

    private fun rgbToI420(px: IntArray, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        for (j in 0 until h) for (i in 0 until w) {
            val c = px[j * w + i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            y[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (w / 2) + i / 2
                u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }
    }

    /** Fills holes, smooths, and resamples the depth map onto the photo grid as disparity (1/Z). */
    private fun disparityMap(
        d: DepthMap, cam: DepthCam, mainOrientation: Int, mainEq: Float, ow: Int, oh: Int,
    ): FloatArray {
        // Rotate the depth map into the main camera's sensor orientation if the sensors differ
        var dw = d.w; var dh = d.h
        var z = d.mm
        val rot = ((cam.sensorOrientation - mainOrientation) % 360 + 360) % 360
        if (rot != 0) {
            val (r, rw, rh) = rotate(z, dw, dh, rot)
            z = r; dw = rw; dh = rh
        }
        val inv = FloatArray(dw * dh) { if (z[it] > 1f) 1000f / z[it] else 0f }
        fillHoles(inv, dw, dh)
        repeat(2) { boxBlur(inv, dw, dh, 3) }

        // Map photo pixels into the depth image using the two fields of view (35mm-equivalent focal
        // lengths). The small offset between the two lenses is ignored; the maps are smooth anyway.
        val scale = cam.eqFocal / mainEq
        val out = FloatArray(ow * oh)
        for (j in 0 until oh) {
            val v = ((j + 0.5f) / oh - 0.5f) * scale
            val sy = ((v + 0.5f) * dh - 0.5f).coerceIn(0f, dh - 1f)
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, dh - 1); val fy = sy - y0
            for (i in 0 until ow) {
                val u = ((i + 0.5f) / ow - 0.5f) * scale
                val sx = ((u + 0.5f) * dw - 0.5f).coerceIn(0f, dw - 1f)
                val x0 = sx.toInt(); val x1 = minOf(x0 + 1, dw - 1); val fx = sx - x0
                val a = inv[y0 * dw + x0] * (1 - fx) + inv[y0 * dw + x1] * fx
                val b = inv[y1 * dw + x0] * (1 - fx) + inv[y1 * dw + x1] * fx
                out[j * ow + i] = a * (1 - fy) + b * fy
            }
        }
        return out
    }

    private fun rotate(src: FloatArray, w: Int, h: Int, deg: Int): Triple<FloatArray, Int, Int> = when (deg) {
        90 -> Triple(FloatArray(w * h) { k -> val x = k % h; val y = k / h; src[(h - 1 - x) * w + y] }, h, w)
        180 -> Triple(FloatArray(w * h) { k -> src[w * h - 1 - k] }, w, h)
        270 -> Triple(FloatArray(w * h) { k -> val x = k % h; val y = k / h; src[x * w + (w - 1 - y)] }, h, w)
        else -> Triple(src, w, h)
    }

    /** Replaces unknown values (0) by the average of known neighbours, growing inwards. */
    private fun fillHoles(a: FloatArray, w: Int, h: Int) {
        if (a.none { it > 0f }) { a.fill(1f); return }
        repeat(64) {
            var holes = 0
            val copy = a.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (copy[y * w + x] > 0f) continue
                var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) { val v = copy[yy * w + xx]; if (v > 0f) { s += v; n++ } }
                }
                if (n > 0) a[y * w + x] = s / n else holes++
            }
            if (holes == 0) return
        }
        val mean = a.filter { it > 0f }.average().toFloat()
        for (i in a.indices) if (a[i] <= 0f) a[i] = mean
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dx in -r..r) { val xx = x + dx; if (xx in 0 until w) { s += a[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -r..r) { val yy = y + dy; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            a[y * w + x] = s / n
        }
    }

    /** Scales disparity to -1..1 around the subject in the centre (which then stays still). */
    private fun normalise(disp: FloatArray, w: Int, h: Int): FloatArray {
        val centre = ArrayList<Float>()
        for (y in h * 7 / 20 until h * 13 / 20) for (x in w * 7 / 20 until w * 13 / 20) centre += disp[y * w + x]
        centre.sort()
        val focus = centre[centre.size / 2]
        val sorted = disp.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02).toInt()]
        val hi = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val span = maxOf(hi - lo, 1e-3f)
        return FloatArray(disp.size) { ((disp[it] - focus) / span * 2f).coerceIn(-1f, 1f) }
    }

    /** One output frame: every pixel is fetched from a position shifted in proportion to its depth. */
    private fun renderFrame(
        y: ByteArray, u: ByteArray, v: ByteArray, W: Int, H: Int, shift: FloatArray, sw: Int,
        ox: Float, oy: Float, x0: Int, y0: Int, outW: Int, outH: Int, dst: android.media.Image,
    ) {
        val yPlane = dst.planes[0]
        val yBuf = yPlane.buffer; val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val row = ByteArray(outW)
        for (j in 0 until outH) {
            val py = j + y0
            for (i in 0 until outW) {
                val px = i + x0
                val s = shift[(py shr 1) * sw + (px shr 1)]
                val sx = (px - ox * s).roundToInt().coerceIn(0, W - 1)
                val sy = (py - oy * s).roundToInt().coerceIn(0, H - 1)
                row[i] = y[sy * W + sx]
            }
            if (yPix == 1) { yBuf.position(j * yRow); yBuf.put(row, 0, outW) }
            else for (i in 0 until outW) yBuf.put(j * yRow + i * yPix, row[i])
        }
        val cw = outW / 2; val ch = outH / 2
        val uPlane = dst.planes[1]; val vPlane = dst.planes[2]
        val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride; val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride; val vPix = vPlane.pixelStride
        val hw = W / 2; val hh = H / 2
        for (j in 0 until ch) {
            val py = j + y0 / 2
            for (i in 0 until cw) {
                val px = i + x0 / 2
                val s = shift[py * sw + px]
                val sx = (px - ox * 0.5f * s).roundToInt().coerceIn(0, hw - 1)
                val sy = (py - oy * 0.5f * s).roundToInt().coerceIn(0, hh - 1)
                uBuf.put(j * uRow + i * uPix, u[sy * hw + sx])
                vBuf.put(j * vRow + i * vPix, v[sy * hw + sx])
            }
        }
    }

    private fun encode(
        ctx: Context, rotation: Int, w: Int, h: Int, draw: (Int, android.media.Image) -> Unit,
    ): Uri? {
        val total = FRAMES_PER_CYCLE * CYCLES
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        val (uri, fd) = MediaOutput.newPendingVideo(ctx, MediaOutput.stamp(), "D3D") ?: run { c.release(); return null }
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            val mx = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint(rotation)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var next = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val pts = next * 1_000_000L / FPS
                        if (next >= total) {
                            c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val img = c.getInputImage(idx) ?: error("encoder has no image input")
                            draw(next % FRAMES_PER_CYCLE, img)
                            c.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
                            next++
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone && ++idle > 300) outputDone = true
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mx.addTrack(c.outputFormat); mx.start() }
                    o >= 0 -> {
                        idle = 0
                        val buf = c.getOutputBuffer(o)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && !isConfig && info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            mx.writeSampleData(track, buf, info)
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (track >= 0) { mx.stop(); ok = true }
        } finally {
            runCatching { muxer?.release() }
            runCatching { c.stop() }
            runCatching { c.release() }
            runCatching { fd.close() }
            if (ok) MediaOutput.finishPendingVideo(ctx, uri) else runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
        return if (ok) uri else null
    }
}
