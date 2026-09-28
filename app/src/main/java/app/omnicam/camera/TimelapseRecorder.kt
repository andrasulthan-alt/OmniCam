// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.omnicam.storage.MediaOutput
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Automatic time-lapse, modelled on the iPhone: one button, no speed setting, and the finished clip
 * stays short (about 20-40 s) however long you record, at a uniform speed.
 *
 * How:
 *  - Frames come from an ImageAnalysis stream (YUV) and are sampled every [intervalMs]
 *    (starting at 0.5 s = 15x). Only the sampled frames are encoded, so the work is tiny.
 *  - Every frame is encoded as a key frame (all-intra H.264) and appended to a cache file.
 *  - When the clip would pass 40 s (1200 frames at 30 fps), every other stored frame is dropped
 *    and the interval doubles (15x -> 30x -> 60x ...). Because all frames are key frames, dropping
 *    them needs no re-encoding, and the remaining frames stay evenly spaced.
 *  - On stop, the kept frames are written to an MP4 at 30 fps.
 * If an encoder refuses to make every frame a key frame, frames are never dropped (the clip then
 * simply keeps growing, at a speed that still doubles every 40 s of output).
 */
class TimelapseRecorder(private val ctx: Context) : ImageAnalysis.Analyzer {

    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Sample(val offset: Long, val size: Int, val key: Boolean)

    @Volatile var isRecording = false
        private set

    /** Current speed factor shown in the UI (15 = 15x faster than real time). */
    @Volatile var speed = 15
        private set

    @Volatile var keptFrames = 0
        private set

    private var intervalMs = START_INTERVAL_MS
    private var lastCaptureAt = 0L
    private var codec: MediaCodec? = null
    private var outFormat: MediaFormat? = null
    private var rotation = 0
    private var cache: RandomAccessFile? = null
    private var cacheFile: File? = null
    private var kept = ArrayList<Sample>()
    private var allKeyFrames = true
    private var frameIndex = 0L
    private var failed: String? = null
    private var configBytes: ByteArray? = null   // SPS/PPS, for encoders that never report an output format
    private var encW = 0
    private var encH = 0

    // Stabilisation (global motion between sampled frames, smoothed path, moving crop window)
    private var margin = 0
    private var prevHalf: IntArray? = null
    private var prevEighth: IntArray? = null
    private var pathX = 0f; private var pathY = 0f        // accumulated scene motion (px)
    private var smoothX = 0f; private var smoothY = 0f    // low-passed path
    // Deflicker (weighted moving average of frame brightness)
    private val lumaHistory = ArrayDeque<Float>()
    private val lut = ByteArray(256)

    companion object {
        const val START_INTERVAL_MS = 500L       // 2 frames per second of real time = 15x
        const val OUTPUT_FPS = 30
        const val MAX_FRAMES = 40 * OUTPUT_FPS   // 40 s; halving brings it back to 20 s
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun start() {
        executor.execute {
            resetState()
            val f = File(ctx.cacheDir, "timelapse_${System.currentTimeMillis()}.h264")
            cacheFile = f
            cache = RandomAccessFile(f, "rw")
            isRecording = true
        }
    }

    private fun resetState() {
        intervalMs = START_INTERVAL_MS
        speed = (START_INTERVAL_MS * OUTPUT_FPS / 1000).toInt()
        lastCaptureAt = 0L
        kept = ArrayList()
        keptFrames = 0
        allKeyFrames = true
        frameIndex = 0
        failed = null
        outFormat = null
        configBytes = null
        prevHalf = null; prevEighth = null
        pathX = 0f; pathY = 0f; smoothX = 0f; smoothY = 0f
        lumaHistory.clear()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!isRecording || failed != null) return
            val now = SystemClock.elapsedRealtime()
            if (lastCaptureAt != 0L && now - lastCaptureAt < intervalMs) return
            lastCaptureAt = now
            val c = codec ?: createEncoder(image) ?: return
            val (ox, oy) = stabilise(image)
            deflickerLut(currentMeanLuma)
            encodeFrame(c, image, ox, oy)
            drain(c, endOfStream = false)
            decimateIfNeeded()
        } catch (e: Exception) {
            failed = e.message ?: "encoder error"
        } finally {
            image.close()
        }
    }

    private fun createEncoder(image: ImageProxy): MediaCodec? {
        rotation = image.imageInfo.rotationDegrees
        // 6 % on each side is kept in reserve so the crop window can move to cancel hand shake
        margin = ((minOf(image.width, image.height) * 0.06f).toInt()) and 1.inv()
        var w = (image.width - 2 * margin) and 15.inv()
        var h = (image.height - 2 * margin) and 15.inv()
        val c = MediaCodec.createEncoderByType(MIME)
        // Some (older) encoders only take sizes aligned to 16: crop a few lines rather than fail
        val caps = runCatching { c.codecInfo.getCapabilitiesForType(MIME) }.getOrNull()
        val video = caps?.videoCapabilities
        if (video != null && !video.isSizeSupported(w, h)) {
            val w16 = w and 15.inv()
            val h16 = h and 15.inv()
            if (video.isSizeSupported(w16, h16)) { w = w16; h = h16 }
        }
        if (caps != null && MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in caps.colorFormats) {
            c.release()
            error("this phone's video encoder does not accept camera frames")
        }
        encW = w
        encH = h
        val bitrate = when {
            w * h >= 1920 * 1080 -> 24_000_000
            w * h >= 1280 * 720 -> 14_000_000
            else -> 8_000_000
        }
        fun format(iFrameInterval: Int) = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)   // 0 = every frame is a key frame
        }
        try {
            c.configure(format(0), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            c.reset()
            c.configure(format(1), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        c.start()
        codec = c
        return c
    }

    private fun encodeFrame(c: MediaCodec, image: ImageProxy, ox: Int, oy: Int) {
        // Ask for a key frame every time, in case the encoder ignored I-frame interval 0
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        val idx = c.dequeueInputBuffer(200_000)
        if (idx < 0) return   // encoder busy: skip this sample
        val dst = c.getInputImage(idx) ?: error("encoder has no image input")
        copyYuv(image, dst, ox, oy)
        val pts = frameIndex * 1_000_000L / OUTPUT_FPS
        frameIndex++
        c.queueInputBuffer(idx, 0, dst.width * dst.height * 3 / 2, pts, 0)
    }

    /**
     * Copies the window at (ox, oy) of a YUV_420_888 frame into the encoder's input image, whatever the
     * plane layouts are, applying the deflicker curve to luma.
     */
    private fun copyYuv(src: ImageProxy, dst: android.media.Image, ox: Int, oy: Int) {
        val w = minOf(src.width - ox, dst.width) and 1.inv()
        val h = minOf(src.height - oy, dst.height) and 1.inv()
        for (p in 0 until 3) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val sx = if (p == 0) ox else ox / 2
            val sy = if (p == 0) oy else oy / 2
            val sBuf = sp.buffer
            val dBuf = dp.buffer
            val sRow = sp.rowStride
            val dRow = dp.rowStride
            val sPix = sp.pixelStride
            val dPix = dp.pixelStride
            val row = ByteArray(pw)
            for (y in 0 until ph) {
                val sBase = (y + sy) * sRow + sx * sPix
                if (sPix == 1) { sBuf.position(sBase); sBuf.get(row, 0, pw) }
                else for (x in 0 until pw) row[x] = sBuf.get(sBase + x * sPix)
                if (p == 0) for (x in 0 until pw) row[x] = lut[row[x].toInt() and 0xFF]
                val dBase = y * dRow
                if (dPix == 1) { dBuf.position(dBase); dBuf.put(row, 0, pw) }
                else for (x in 0 until pw) dBuf.put(dBase + x * dPix, row[x])
            }
        }
    }

    // ───────────────────────── stabilisation ─────────────────────────

    private var currentMeanLuma = 128f

    /** Luma downscaled by [s] (box filter) from the Y plane. */
    private fun lumaDown(img: ImageProxy, s: Int): IntArray {
        val p = img.planes[0]
        val buf = p.buffer
        val row = p.rowStride
        val dw = img.width / s
        val dh = img.height / s
        val out = IntArray(dw * dh)
        val line = ByteArray(img.width)
        val acc = IntArray(dw)
        for (y in 0 until dh) {
            java.util.Arrays.fill(acc, 0)
            for (j in 0 until s) {
                buf.position((y * s + j) * row); buf.get(line, 0, dw * s)
                for (x in 0 until dw) {
                    var sum = 0
                    val b = x * s
                    for (i in 0 until s) sum += line[b + i].toInt() and 0xFF
                    acc[x] += sum
                }
            }
            for (x in 0 until dw) out[y * dw + x] = acc[x] / (s * s)
        }
        return out
    }

    /** Best (dx, dy) so that cur(x + dx, y + dy) matches prev(x, y) inside the given region. */
    private fun match(
        prev: IntArray, cur: IntArray, w: Int, h: Int,
        x0: Int, x1: Int, y0: Int, y1: Int, cx: Int, cy: Int, r: Int, step: Int,
    ): IntArray {
        var best = Long.MAX_VALUE
        var bx = cx; var by = cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            if (x0 + dx < 0 || y0 + dy < 0 || x1 + dx > w || y1 + dy > h) continue
            var sad = 0L
            var y = y0
            while (y < y1) {
                val a = y * w; val b = (y + dy) * w + dx
                var x = x0
                while (x < x1) { sad += kotlin.math.abs(prev[a + x] - cur[b + x]); x += step }
                y += step
            }
            if (sad < best) { best = sad; bx = dx; by = dy }
        }
        return intArrayOf(bx, by)
    }

    /**
     * Estimates the global motion since the previous sampled frame (median of 3x3 tiles at 1/8 size,
     * so moving people/cars/clouds in part of the picture do not fool it, then refined at 1/2 size),
     * low-passes the accumulated path and returns the crop origin that cancels the shake.
     */
    private fun stabilise(img: ImageProxy): Pair<Int, Int> {
        val half = lumaDown(img, 2)
        val hw = img.width / 2; val hh = img.height / 2
        val eighth = IntArray((hw / 4) * (hh / 4))
        val ew = hw / 4; val eh = hh / 4
        for (y in 0 until eh) for (x in 0 until ew) {
            var s = 0
            for (j in 0 until 4) { val r = (y * 4 + j) * hw + x * 4; for (i in 0 until 4) s += half[r + i] }
            eighth[y * ew + x] = s / 16
        }
        currentMeanLuma = eighth.average().toFloat()
        val pe = prevEighth; val ph = prevHalf
        if (pe != null && ph != null) {
            val dxs = IntArray(9); val dys = IntArray(9)
            var k = 0
            for (ty in 0 until 3) for (tx in 0 until 3) {
                val x0 = tx * ew / 3 + ew / 12; val x1 = (tx + 1) * ew / 3 - ew / 12
                val y0 = ty * eh / 3 + eh / 12; val y1 = (ty + 1) * eh / 3 - eh / 12
                val d = match(pe, eighth, ew, eh, x0, x1, y0, y1, 0, 0, 8, 1)
                dxs[k] = d[0]; dys[k] = d[1]; k++
            }
            dxs.sort(); dys.sort()
            val f = match(ph, half, hw, hh, hw / 4, hw * 3 / 4, hh / 4, hh * 3 / 4, dxs[4] * 4, dys[4] * 4, 3, 2)
            val dx = f[0] * 2f; val dy = f[1] * 2f
            // Ignore implausible jumps (scene change, lens covered)
            if (kotlin.math.abs(dx) < img.width / 4f && kotlin.math.abs(dy) < img.height / 4f) {
                pathX += dx; pathY += dy
            }
        }
        prevEighth = eighth; prevHalf = half
        smoothX += 0.15f * (pathX - smoothX)
        smoothY += 0.15f * (pathY - smoothY)
        val cx = (pathX - smoothX).coerceIn(-margin.toFloat(), margin.toFloat())
        val cy = (pathY - smoothY).coerceIn(-margin.toFloat(), margin.toFloat())
        // Even offsets keep the 4:2:0 chroma aligned
        val ox = ((margin + cx).toInt() and 1.inv()).coerceIn(0, 2 * margin)
        val oy = ((margin + cy).toInt() and 1.inv()).coerceIn(0, 2 * margin)
        return ox to oy
    }

    /**
     * Deflicker: brightness is pulled towards a weighted moving average of the last frames, so
     * auto-exposure steps do not flash, while slow changes (sunset) are kept.
     */
    private fun deflickerLut(mean: Float) {
        lumaHistory.addLast(mean)
        while (lumaHistory.size > 8) lumaHistory.removeFirst()
        var num = 0f; var den = 0f
        lumaHistory.forEachIndexed { i, v -> val wgt = (i + 1).toFloat(); num += v * wgt; den += wgt }
        val target = num / den
        val gain = if (mean > 4f) (target / mean).coerceIn(0.85f, 1.18f) else 1f
        for (i in 0 until 256) lut[i] = (i * gain).toInt().coerceIn(0, 255).toByte()
    }

    private fun drain(c: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        while (true) {
            val idx = try {
                c.dequeueOutputBuffer(info, if (endOfStream) 100_000 else 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Never wait forever for end-of-stream: some encoders drop it (about 3 s max)
                    if (!endOfStream || ++emptyPolls > 30) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = c.outputFormat
                idx >= 0 -> {
                    val buf: ByteBuffer? = c.getOutputBuffer(idx)
                    if (buf == null) { c.releaseOutputBuffer(idx, false); continue }
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (isConfig && info.size > 0) {
                        configBytes = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                    }
                    if (!isConfig && info.size > 0) {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (!key) allKeyFrames = false
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        val f = cache!!
                        val off = f.length()
                        f.seek(off); f.write(bytes)
                        kept.add(Sample(off, info.size, key))
                        keptFrames = kept.size
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Keeps the clip between ~20 and 40 s: drop every other frame and double the interval. */
    private fun decimateIfNeeded() {
        if (kept.size < MAX_FRAMES) return
        if (allKeyFrames) {
            kept = ArrayList(kept.filterIndexed { i, _ -> i % 2 == 0 })
            keptFrames = kept.size
        }
        intervalMs *= 2
        speed = (intervalMs * OUTPUT_FPS / 1000).toInt()
    }

    /** Finishes the clip. [onResult] runs on the main thread with the saved video, or null. */
    fun stop(onResult: (Uri?, String?) -> Unit) {
        executor.execute {
            if (!isRecording) { main.post { onResult(null, null) }; return@execute }
            isRecording = false
            var uri: Uri? = null
            var err = failed
            try {
                codec?.let { c ->
                    val idx = c.dequeueInputBuffer(500_000)
                    if (idx >= 0) c.queueInputBuffer(idx, 0, 0, frameIndex * 1_000_000L / OUTPUT_FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(c, endOfStream = true)
                }
                if (err == null) uri = writeMp4()
                if (uri == null && err == null) err = if (kept.isEmpty()) "Recording too short" else "Could not save the time-lapse"
            } catch (e: Exception) {
                err = e.message ?: "Could not save the time-lapse"
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
                runCatching { cache?.close() }
                cache = null
                cacheFile?.delete()
                cacheFile = null
            }
            main.post { onResult(uri, err) }
        }
    }

    /**
     * Output format for the muxer. Normally reported by the encoder; otherwise rebuilt from its
     * codec-config buffer (SPS/PPS), as older devices did.
     */
    private fun muxerFormat(): MediaFormat? {
        outFormat?.let { return it }
        val cfg = configBytes ?: return null
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < cfg.size) {
            if (cfg[i] == 0.toByte() && cfg[i + 1] == 0.toByte() && cfg[i + 2] == 0.toByte() && cfg[i + 3] == 1.toByte()) {
                starts += i; i += 4
            } else i++
        }
        if (starts.size < 2) return null
        val sps = cfg.copyOfRange(starts[0], starts[1])
        val pps = cfg.copyOfRange(starts[1], cfg.size)
        return MediaFormat.createVideoFormat(MIME, encW, encH).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
    }

    private fun writeMp4(): Uri? {
        val format = muxerFormat() ?: return null
        if (kept.isEmpty()) return null
        val (uri, fd) = MediaOutput.newPendingVideo(ctx, MediaOutput.stamp(), "LAPSE") ?: return null
        var ok = false
        try {
            val muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val track = muxer.addTrack(format)
            muxer.start()
            val f = cache!!
            val info = MediaCodec.BufferInfo()
            val buf = ByteBuffer.allocate(kept.maxOf { it.size })
            kept.forEachIndexed { i, s ->
                val bytes = ByteArray(s.size)
                f.seek(s.offset); f.readFully(bytes)
                buf.clear(); buf.put(bytes); buf.flip()
                info.set(0, s.size, i * 1_000_000L / OUTPUT_FPS, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
            }
            muxer.stop()
            muxer.release()
            ok = true
        } finally {
            runCatching { fd.close() }
            if (ok) MediaOutput.finishPendingVideo(ctx, uri) else runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
        return if (ok) uri else null
    }

    fun release() {
        executor.execute {
            runCatching { codec?.release() }
            codec = null
            runCatching { cache?.close() }
            cacheFile?.delete()
        }
        executor.shutdown()
    }
}
