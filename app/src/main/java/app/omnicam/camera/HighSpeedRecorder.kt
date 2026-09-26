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
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import app.omnicam.storage.MediaOutput
import java.util.concurrent.Executor

/** What the camera's constrained high-speed mode offers: for each size, the fixed frame rates (>= 120). */
data class HighSpeedCaps(val cameraId: String, val sizes: List<Size>, val ratesBySize: Map<Size, List<Int>>)

/**
 * Slow-motion recorder built directly on Camera2's constrained high-speed session.
 *
 * Used when CameraX refuses high-speed recording even though the camera supports it, which happens
 * on custom ROMs that lack the high-speed video profiles CameraX relies on (e.g. Galaxy S9 on Pixel
 * Experience). Frames are captured at [fps] (120/240) and written at 30 fps playback, so the video
 * plays back 4x/8x slower. Video only, no audio.
 */
class HighSpeedRecorder(private val ctx: Context) {

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var pendingUri: Uri? = null
    private var pendingFd: ParcelFileDescriptor? = null
    private var previewSurface: Surface? = null
    private var size: Size? = null
    private var fps = 0
    private var cameraId = ""
    private var orientationHint = 90

    @Volatile var isRecording = false
        private set

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

    /** Opens the camera and starts the high-speed preview on [preview] (a Surface sized exactly [size]). */
    @SuppressLint("MissingPermission")
    fun open(
        cameraId: String, size: Size, fps: Int, preview: Surface,
        onReady: () -> Unit, onError: (String) -> Unit,
    ) {
        close()
        this.cameraId = cameraId
        this.size = size
        this.fps = fps
        previewSurface = preview
        orientationHint = runCatching {
            cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 90
        val t = HandlerThread("omnicam-slowmo").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        try {
            recorderSurface = MediaCodec.createPersistentInputSurface()
            prepareRecorder()
        } catch (e: Exception) {
            onError("Could not prepare the slow-motion recorder: ${e.message}")
            return
        }
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession(d, onReady, onError)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    onError("Camera error $error")
                }
            }, h)
        } catch (e: Exception) {
            onError("Could not open the camera: ${e.message}")
        }
    }

    private fun createSession(d: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        val prev = previewSurface ?: return
        val rec = recorderSurface ?: return
        val exec = Executor { r -> handler?.post(r) }
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            listOf(OutputConfiguration(prev), OutputConfiguration(rec)),
            exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val hs = s as? CameraConstrainedHighSpeedCaptureSession
                    if (hs == null) { onError("High-speed session not available"); return }
                    session = hs
                    try {
                        repeat(recording = false)
                        onReady()
                    } catch (e: Exception) {
                        onError("Slow-motion preview failed: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("This camera refused the slow-motion configuration")
                }
            },
        )
        d.createCaptureSession(cfg)
    }

    /** Repeating high-speed burst: preview only, or preview + recorder while recording. */
    private fun repeat(recording: Boolean) {
        val d = device ?: return
        val s = session ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(previewSurface!!)
        if (recording) b.addTarget(recorderSurface!!)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, handler)
    }

    /** A fresh MediaRecorder writing to a new pending file in DCIM/OmniCam, fed by the persistent surface. */
    private fun prepareRecorder() {
        val sz = size ?: error("no size")
        val (uri, fd) = MediaOutput.newPendingVideo(ctx, MediaOutput.stamp(), "SLOMO")
            ?: error("could not create the video file")
        pendingUri = uri
        pendingFd = fd
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(fd.fileDescriptor)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(sz.width, sz.height)
        // Captured at `fps`, stored for 30 fps playback: the encoder stretches timestamps = slow motion
        r.setCaptureRate(fps.toDouble())
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(bitrateFor(sz))
        r.setOrientationHint(orientationHint)
        r.setInputSurface(recorderSurface!!)
        r.prepare()
        recorder = r
    }

    private fun bitrateFor(s: Size): Int = when {
        s.height >= 1080 || s.width >= 1920 -> 30_000_000
        s.height >= 720 || s.width >= 1280 -> 16_000_000
        else -> 8_000_000
    }

    fun startRecording(): Boolean {
        val r = recorder ?: return false
        return try {
            repeat(recording = true)
            r.start()
            isRecording = true
            true
        } catch (e: Exception) {
            isRecording = false
            runCatching { repeat(recording = false) }
            false
        }
    }

    /** Stops, finalizes the file and prepares the next one. Returns the saved video, or null on failure. */
    fun stopRecording(): Uri? {
        val r = recorder ?: return null
        val uri = pendingUri
        var ok = true
        runCatching { repeat(recording = false) }
        try { r.stop() } catch (_: Exception) { ok = false }
        isRecording = false
        r.release()
        recorder = null
        runCatching { pendingFd?.close() }
        pendingFd = null
        pendingUri = null
        if (uri != null) {
            if (ok) MediaOutput.finishPendingVideo(ctx, uri) else runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
        // Ready for the next take (the persistent surface stays attached to the session)
        runCatching { prepareRecorder() }
        return if (ok) uri else null
    }

    fun close() {
        if (isRecording) runCatching { stopRecording() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { recorder?.release() }
        recorder = null
        // Unused pending file from the last prepare(): remove it from the gallery
        pendingUri?.let { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
        pendingUri = null
        runCatching { pendingFd?.close() }
        pendingFd = null
        runCatching { recorderSurface?.release() }
        recorderSurface = null
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
        isRecording = false
    }
}
