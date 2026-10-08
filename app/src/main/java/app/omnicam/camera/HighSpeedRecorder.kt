// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import app.omnicam.storage.MediaOutput
import java.util.concurrent.Executor

/** What the camera's constrained high-speed mode offers: for each size, the fixed frame rates (>= 120). */
data class HighSpeedCaps(val cameraId: String, val sizes: List<Size>, val ratesBySize: Map<Size, List<Int>>)

/**
 * Slow-motion recorder built directly on Camera2's constrained high-speed session, following the
 * structure of Google's official Camera2 slow-motion sample:
 *  - while previewing, the session contains ONLY the viewfinder surface;
 *  - to record, a MediaRecorder is prepared and a new session [viewfinder, recorder] is created;
 *  - on stop, that session is closed and the preview-only session is restarted.
 * Keeping an idle recorder surface in the session stalled the viewfinder on some drivers (Huawei).
 *
 * Frames are captured at [fps] (120/240) and, by default, written for 30 fps playback, so the video
 * plays back 4x/8x slower. Video only, no audio.
 */
class HighSpeedRecorder(private val ctx: Context) {

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    // State of the current (latest) open request. Older requests are recognised by their generation.
    private var generation = 0
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var pendingUri: Uri? = null
    private var pendingFd: ParcelFileDescriptor? = null
    private var previewSurface: Surface? = null
    private var size: Size? = null
    private var fps = 0
    private var orientationHint = 90
    private var onErrorCb: (String) -> Unit = {}

    /** Cameras opened (or opening) and not yet fully closed. CameraX must wait until this is 0. */
    private val openCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val waiters = ArrayList<() -> Unit>()

    @Volatile var isRecording = false
        private set

    /** Frames delivered by the current session; the engine uses it to detect a stalled driver. */
    @Volatile var framesSeen = 0L
        private set

    /**
     * Compatibility recorder settings, exactly as in Google's official Camera2 slow-motion sample:
     * the file is written at the capture rate (e.g. 240 fps) instead of being retimed to 30 fps.
     */
    @Volatile var compatRecorder = false

    private val frameCounter = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult,
        ) { framesSeen++ }
    }

    val isActive: Boolean get() = openCount.get() > 0

    companion object {
        /** Camera2 high-speed capabilities of [cameraId], or null if the camera has none. */
        fun query(cm: CameraManager, cameraId: String): HighSpeedCaps? = runCatching {
            val c = cm.getCameraCharacteristics(cameraId)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) return null
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val rates = LinkedHashMap<Size, List<Int>>()
            map.highSpeedVideoSizes.sortedByDescending { it.width.toLong() * it.height }.forEach { s ->
                val r = map.getHighSpeedVideoFpsRangesFor(s)
                    .filter { it.lower == it.upper && it.upper >= 120 }.map { it.upper }.distinct().sorted()
                if (r.isNotEmpty()) rates[s] = r
            }
            if (rates.isEmpty()) null else HighSpeedCaps(cameraId, rates.keys.toList(), rates)
        }.getOrNull()
    }

    /** Runs [block] on the main thread once every camera this recorder opened is fully closed. */
    fun whenClosed(block: () -> Unit) {
        synchronized(waiters) {
            if (openCount.get() == 0) { mainHandler.post(block); return }
            waiters += block
        }
    }

    private fun deviceFullyClosed() {
        val run: List<() -> Unit>
        synchronized(waiters) {
            if (openCount.decrementAndGet() > 0) return
            run = waiters.toList()
            waiters.clear()
        }
        run.forEach { mainHandler.post(it) }
    }

    private fun error(msg: String) { mainHandler.post { onErrorCb(msg) } }

    /** Opens the camera and starts the high-speed preview on [preview] (a Surface sized exactly [size]). */
    @SuppressLint("MissingPermission")
    fun open(
        cameraId: String, size: Size, fps: Int, preview: Surface,
        onReady: () -> Unit, onError: (String) -> Unit,
    ) {
        close()
        framesSeen = 0
        val gen = ++generation
        this.size = size
        this.fps = fps
        onErrorCb = onError
        previewSurface = preview
        orientationHint = runCatching {
            cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 90
        val t = HandlerThread("omnicam-slowmo").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        openCount.incrementAndGet()
        var counted = true
        fun releaseCount() { if (counted) { counted = false; deviceFullyClosed() } }
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }   // closed/reopened while opening
                    device = d
                    createSession(gen, listOf(preview)) { ok ->
                        if (ok) { repeat(recording = false); mainHandler.post(onReady) }
                        else error("This camera refused the slow-motion configuration")
                    }
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) device = null
                    // Some Samsung firmwares drop a high-speed camera without any error: report it
                    if (gen == generation) error("The camera was disconnected")
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) device = null
                    if (gen == generation) error("Camera error $error")
                }
                override fun onClosed(d: CameraDevice) {
                    t.quitSafely()
                    releaseCount()
                }
            }, handler)
        } catch (e: Exception) {
            t.quitSafely()
            releaseCount()
            error("Could not open the camera: ${e.message}")
        }
    }

    /**
     * Creates a constrained high-speed session for [surfaces] (new API first, then the older API that
     * some vendor drivers need). [done] runs on the camera thread with the result.
     */
    private fun createSession(gen: Int, surfaces: List<Surface>, done: (Boolean) -> Unit) {
        val d = device ?: return done(false)
        val h = handler ?: return done(false)
        var finished = false
        fun finish(ok: Boolean) { if (!finished) { finished = true; done(ok) } }

        fun callback(onFailed: () -> Unit) = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) { runCatching { s.close() }; return }
                val hs = s as? CameraConstrainedHighSpeedCaptureSession
                if (hs == null) { runCatching { s.close() }; onFailed(); return }
                session = hs
                finish(true)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { if (gen == generation) onFailed() }
        }

        fun legacy() {
            try {
                @Suppress("DEPRECATION")
                d.createConstrainedHighSpeedCaptureSession(surfaces, callback { finish(false) }, h)
            } catch (e: Exception) {
                finish(false)
            }
        }

        try {
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                surfaces.map { OutputConfiguration(it) },
                Executor { r -> h.post(r) },
                callback { legacy() },
            )
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            legacy()
        }
    }

    /** Repeating high-speed burst: viewfinder only, or viewfinder + recorder while recording. */
    private fun repeat(recording: Boolean) {
        val d = device ?: return
        val s = session ?: return
        val prev = previewSurface ?: return
        runCatching {
            val b = d.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(prev)
            if (recording) recorderSurface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), frameCounter, handler)
        }.onFailure { error("Slow-motion preview failed: ${it.message}") }
    }

    /** A MediaRecorder writing to a new pending file in DCIM/OmniCam. */
    private fun prepareRecorder(): Boolean {
        val sz = size ?: return false
        val (uri, fd) = MediaOutput.newPendingVideo(ctx, MediaOutput.stamp(), "SLOMO") ?: return false
        pendingUri = uri
        pendingFd = fd
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncodingBitRate(bitrateFor(sz))
            if (compatRecorder) {
                r.setVideoFrameRate(fps)
                r.setCaptureRate(fps.toDouble())
            } else {
                // Captured at `fps`, stored for 30 fps playback: the encoder stretches timestamps = slow motion
                r.setCaptureRate(fps.toDouble())
                r.setVideoFrameRate(30)
            }
            r.setVideoSize(sz.width, sz.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setOrientationHint(orientationHint)
            r.prepare()
            recorder = r
            recorderSurface = r.surface
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            discardPending()
            false
        }
    }

    private fun bitrateFor(s: Size): Int = when {
        s.height >= 1080 || s.width >= 1920 -> 30_000_000
        s.height >= 720 || s.width >= 1280 -> 16_000_000
        else -> 8_000_000
    }

    private fun discardPending() {
        pendingUri?.let { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
        pendingUri = null
        runCatching { pendingFd?.close() }
        pendingFd = null
    }

    /**
     * Starts recording (asynchronously: a new session is configured first). [onResult] gets null on
     * success, otherwise the step that failed: "camera", "encoder", "session" or "start".
     */
    fun startRecording(onResult: (String?) -> Unit) {
        val h = handler ?: return onResult("camera")
        val gen = generation
        // Everything that touches the recorder holds the same lock as close(): MediaRecorder is not
        // thread-safe, and close() can run on the main thread while a start is in progress here.
        val posted = h.post { synchronized(this@HighSpeedRecorder) {
            val prev = previewSurface
            if (gen != generation || device == null || prev == null) {
                mainHandler.post { onResult("camera") }
                return@post
            }
            if (!prepareRecorder()) {
                mainHandler.post { onResult("encoder") }   // the preview session is untouched
                return@post
            }
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            val rec = recorderSurface ?: run {
                releaseRecorder(discard = true)
                mainHandler.post { onResult("camera") }
                return@post
            }
            createSession(gen, listOf(prev, rec)) { ok -> synchronized(this@HighSpeedRecorder) {
                if (gen != generation) {
                    // close() ran meanwhile: it already released the camera; drop the half-made take
                    releaseRecorder(discard = true)
                    mainHandler.post { onResult("camera") }
                    return@createSession
                }
                if (!ok) {
                    releaseRecorder(discard = true)
                    restartPreview(gen)
                    mainHandler.post { onResult("session") }
                    return@createSession
                }
                repeat(recording = true)
                val started = runCatching { recorder?.start() }.isSuccess
                if (started) isRecording = true
                else { releaseRecorder(discard = true); restartPreview(gen) }
                mainHandler.post { onResult(if (started) null else "start") }
            } }
        } }
        if (!posted) onResult("camera")   // camera thread already stopped (camera error)
    }

    /** Stops and finalizes the file, then restarts the preview. [onResult] gets the saved video or null. */
    fun stopRecording(onResult: (Uri?) -> Unit) {
        val h = handler ?: return onResult(finishTake())
        val gen = generation
        val posted = h.post {
            val uri = finishTake()
            restartPreview(gen)
            mainHandler.post { onResult(uri) }
        }
        if (!posted) onResult(finishTake())   // camera thread already stopped: finalize here
    }

    /** Stops the recorder and closes the recording session. Returns the saved video, or null. */
    @Synchronized private fun finishTake(): Uri? {
        val r = recorder ?: return null
        val uri = pendingUri
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }   // throws if no frame was recorded
        isRecording = false
        runCatching { r.release() }
        recorder = null
        recorderSurface = null
        runCatching { pendingFd?.close() }
        pendingFd = null
        pendingUri = null
        if (uri != null) {
            if (ok) MediaOutput.finishPendingVideo(ctx, uri) else runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
        return if (ok) uri else null
    }

    private fun releaseRecorder(discard: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        recorderSurface = null
        if (discard) discardPending()
        isRecording = false
    }

    private fun restartPreview(gen: Int) {
        val prev = previewSurface ?: return
        if (gen != generation || device == null) return
        createSession(gen, listOf(prev)) { ok ->
            if (ok) repeat(recording = false) else error("This camera refused the slow-motion configuration")
        }
    }

    /** Releases the camera. Use [whenClosed] to know when CameraX may open it again. */
    @Synchronized fun close() {
        generation++   // callbacks of the previous open request are now ignored
        if (isRecording) runCatching { finishTake() }
        runCatching { session?.close() }
        session = null
        val d = device
        device = null
        releaseRecorder(discard = true)
        previewSurface = null
        thread = null
        handler = null
        // The device's onClosed() quits its thread and lowers openCount. A device still opening is
        // closed in onOpened() because its generation is now stale.
        if (d != null) runCatching { d.close() }
    }
}
