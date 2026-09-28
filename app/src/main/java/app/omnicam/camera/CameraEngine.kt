// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.camera

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.omnicam.model.CamUi
import app.omnicam.model.FocusRing
import app.omnicam.model.LensOption
import app.omnicam.model.ManualRanges
import app.omnicam.model.Mode
import app.omnicam.model.PhotoFormat
import app.omnicam.model.QrHit
import app.omnicam.model.Readout
import app.omnicam.model.ScopeSettings
import app.omnicam.model.SlowMoUi
import app.omnicam.storage.MediaOutput
import app.omnicam.storage.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Satu-satunya pemilik sesi kamera.
 *
 * Arsitektur: CameraX (stabil, kompatibel banyak perangkat) + Camera2Interop untuk kontrol
 * manual (ISO/rana/fokus/WB). Binding dibuat per-mode seperti GrapheneOS Camera:
 *  FOTO/PRO : Preview + ImageCapture (+ ImageAnalysis di PRO)
 *  VIDEO    : Preview + VideoCapture
 *  QR       : Preview + ImageAnalysis
 */
@OptIn(ExperimentalCamera2Interop::class)
/** See the comment at the stream-sharing decision in tryBind(). */
private const val SHARE_4K_STREAM = false

class CameraEngine(private val app: Application, private val prefs: Prefs) {

    val ui = MutableStateFlow(CamUi())
    val readout = MutableStateFlow(Readout())
    val scopeFrame = MutableStateFlow<ScopeFrame?>(null)
    /** True for the brief moment the screen should show full white as a substitute flash. */
    val screenFlashActive = MutableStateFlow(false)

    private val mainExec = ContextCompat.getMainExecutor(app)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Camera2 slow-motion fallback (CameraX lacks high-speed profiles on some ROMs)
    private val hs by lazy { HighSpeedRecorder(app) }
    private var hsCaps: HighSpeedCaps? = null
    private var hsSurface: android.view.Surface? = null
    private var hsSurfaceSize: Size? = null
    private var hsTicker: kotlinx.coroutines.Job? = null
    private var hsWatchdog: kotlinx.coroutines.Job? = null

    // Automatic (iPhone-style) time-lapse
    private val lapse by lazy { TimelapseRecorder(app) }
    private var lapseTicker: kotlinx.coroutines.Job? = null
    private var lifecycleObserver: androidx.lifecycle.LifecycleEventObserver? = null

    /**
     * Per-device quirks learned at runtime. A camera whose high-speed mode delivers no frames is
     * remembered so the SLO-MO tab is hidden next time; the flag is keyed by app version so every
     * update retries once.
     */
    private val quirks by lazy { app.getSharedPreferences("omnicam_quirks", android.content.Context.MODE_PRIVATE) }
    private val appVersion by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
    }
    private fun slowMoBrokenKey(cameraId: String) = "slowmo_broken_${cameraId}_v$appVersion"
    private fun slowMoCompatKey(cameraId: String) = "slowmo_compat_${cameraId}_v$appVersion"
    private val sound by lazy {
        MediaActionSound().also {
            it.load(MediaActionSound.SHUTTER_CLICK)
            it.load(MediaActionSound.START_VIDEO_RECORDING)
            it.load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    private var started = false
    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var captureJob: Job? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var rotation = Surface.ROTATION_0
    private var activeFormat = PhotoFormat.JPEG
    private val extCache = ConcurrentHashMap<String, Boolean>()

    private val scopeAnalyzer = ScopeAnalyzer { scopeFrame.value = it }
    private var lastQrText: String? = null
    private var lastQrTime = 0L
    private val qrAnalyzer = QrAnalyzer { text, fmt -> onQr(text, fmt) }

    private val orientation = object : OrientationEventListener(app) {
        override fun onOrientationChanged(o: Int) {
            if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
            val r = when (o) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (r != rotation) {
                rotation = r
                imageCapture?.targetRotation = r
                videoCapture?.targetRotation = r
            }
        }
    }

    /** Membaca ISO/rana/fokus otomatis dari setiap ±250ms untuk HUD dan nilai awal mode manual. */
    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 250) return
            last = now
            readout.value = Readout(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                focusDiopter = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                aperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f,
            )
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────

    fun start() {
        if (started) return
        started = true
        val f = ProcessCameraProvider.getInstance(app)
        f.addListener({
            val p = try {
                f.get()
            } catch (e: Exception) {
                toast("Camera unavailable: ${e.message}")
                return@addListener
            }
            provider = p
            val ef = ExtensionsManager.getInstanceAsync(app, p)
            ef.addListener({
                extMgr = try { ef.get() } catch (_: Exception) { null }
                ui.update { it.copy(ready = true, lenses = buildLenses(p)) }
                rebind()
            }, mainExec)
        }, mainExec)
    }

    fun attach(o: LifecycleOwner, v: PreviewView) {
        owner = o
        previewView = v
        orientation.enable()
        // A time-lapse must be finished properly when the app goes to the background
        lifecycleObserver?.let { runCatching { o.lifecycle.removeObserver(it) } }
        lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && lapse.isRecording) stopTimelapse()
        }.also { o.lifecycle.addObserver(it) }
        if (ui.value.ready) rebind()
    }

    fun detach() {
        orientation.disable()
    }

    fun release() {
        orientation.disable()
        recording?.stop()
        scope.cancel()
        io.shutdown()
        analysisExec.shutdown()
        runCatching { lapse.release() }
        runCatching { provider?.unbindAll() }
        runCatching { sound.release() }
    }

    // ───────────────────────── enumerasi ─────────────────────────

    private fun buildLenses(p: ProcessCameraProvider): List<LensOption> =
        p.availableCameraInfos.mapNotNull { info ->
            runCatching {
                val c = Camera2CameraInfo.from(info)
                val caps = c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                // Depth-only auxiliary sensors (ToF, etc.) lack BACKWARD_COMPATIBLE and cannot do normal
                // photo/video; some devices expose them as extra camera IDs, so they must be filtered out
                // here rather than assumed to already be excluded.
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return@mapNotNull null
                val facing = c.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                val fl = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (fl != null && sensor != null) {
                    fl * 43.27f / hypot(sensor.width, sensor.height)
                } else 0f
                LensOption(c.cameraId, facing == CameraMetadata.LENS_FACING_FRONT, eq)
            }.getOrNull()
        }

    private fun baseSelector(s: CamUi): CameraSelector {
        val id = s.lensId
        return if (id != null) {
            CameraSelector.Builder()
                .addCameraFilter(CameraFilter { infos ->
                    infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId == id }.getOrDefault(false) }
                })
                .build()
        } else if (s.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun selectorKey(s: CamUi) = "${s.front}|${s.lensId}"

    // ───────────────────────── ekstensi (HDR/Malam/Potret) ─────────────────────────

    /**
     * Beberapa vendor mengiklankan ekstensi lalu crash saat di-bind. getCameraInfo() melakukan
     * inisialisasi vendor yang sama dengan bindToLifecycle(), jadi dipakai sebagai probe aman.
     */
    private fun probeExtensions() {
        val p = provider ?: return
        val em = extMgr ?: return
        val s = ui.value
        val base = baseSelector(s)
        val key = selectorKey(s)
        io.execute {
            val modes = listOf(
                ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT,
                ExtensionMode.BOKEH, ExtensionMode.FACE_RETOUCH,
            )
            val usable = modes.filter { m ->
                val ck = "$key:$m"
                val cached = extCache[ck]
                val verdict: Boolean? = cached ?: probeOne(p, em, base, m)
                if (cached == null && verdict != null) extCache[ck] = verdict
                verdict == true
            }
            mainHandler.post {
                if (selectorKey(ui.value) == key) {
                    ui.update {
                        it.copy(
                            extensions = usable,
                            extension = if (it.extension in usable) it.extension else ExtensionMode.NONE,
                        )
                    }
                }
            }
        }
    }

    /** true/false = verdict tetap (boleh di-cache); null = gagal sementara. */
    private fun probeOne(p: ProcessCameraProvider, em: ExtensionsManager, base: CameraSelector, mode: Int): Boolean? =
        try {
            if (!em.isExtensionAvailable(base, mode)) {
                false
            } else {
                p.getCameraInfo(em.getExtensionEnabledCameraSelector(base, mode))
                true
            }
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: Exception) {
            null
        }

    // ───────────────────────── binding ─────────────────────────

    fun rebind() {
        if (recording != null || lapse.isRecording) return
        // Leaving (or re-configuring) Camera2 slow motion: CameraX may only open the camera once
        // OmniCam's own high-speed session has fully released it. Opening earlier leaves some phones
        // (e.g. Huawei) with a black viewfinder.
        if (hs.isActive) {
            closeHighSpeed()
            hs.whenClosed { rebind() }
            return
        }
        val p = provider ?: return
        val o = owner ?: return
        val v = previewView ?: return
        if (tryBind(p, o, v, withAnalysis = true) || tryBind(p, o, v, withAnalysis = false)) return
        // Some devices reject stabilized configurations; retry once without stabilization.
        if (ui.value.video.stab) {
            ui.update { it.copy(video = it.video.copy(stab = false)) }
            if (tryBind(p, o, v, withAnalysis = false)) toast("Stabilization is not supported with these settings")
        }
    }

    private fun tryBind(p: ProcessCameraProvider, o: LifecycleOwner, v: PreviewView, withAnalysis: Boolean): Boolean {
        val s = ui.value
        try {
            val base = baseSelector(s)
            var selector = base
            var extActive = false
            val em = extMgr
            if (s.mode == Mode.PHOTO && s.extension != ExtensionMode.NONE && em != null &&
                extCache["${selectorKey(s)}:${s.extension}"] == true
            ) {
                selector = em.getExtensionEnabledCameraSelector(base, s.extension)
                extActive = true
            }
            val info = p.getCameraInfo(selector)

            // Any previous Camera2 slow-motion session must release the camera before CameraX binds again
            closeHighSpeed()
            // Slow motion: OmniCam's own Camera2 high-speed recorder is preferred on every device. It closes
            // the camera cleanly when leaving SLO-MO. CameraX's HighSpeedVideoSessionConfig is only a
            // fallback: on a Huawei P50 Pro, binding it left the viewfinder black in every other mode
            // afterwards (and SLO-MO then reported "not supported") until the app was restarted.
            val c2Id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
            val slowMoBroken = c2Id != null && quirks.getBoolean(slowMoBrokenKey(c2Id), false)
            val c2Caps = if (c2Id != null && !extActive && !slowMoBroken) {
                HighSpeedRecorder.query(app.getSystemService(android.hardware.camera2.CameraManager::class.java), c2Id)
            } else null
            // A camera already known to fail high speed is not retried through CameraX either
            val cameraXSlowMo = c2Caps == null && !slowMoBroken &&
                runCatching { Recorder.getHighSpeedVideoCapabilities(info) != null }.getOrDefault(false)
            val slowMoOk = c2Caps != null || cameraXSlowMo
            if (s.mode == Mode.SLOWMO) {
                if (c2Caps != null) { enterCamera2SlowMo(p, c2Caps); return true }
                if (cameraXSlowMo && bindSlowMo(p, o, v, selector, info)) return true
                toast("Slow motion is not supported by this camera")
                ui.update { it.copy(mode = Mode.VIDEO, slowMoOk = false) }
                return tryBind(p, o, v, withAnalysis)
            }

            val ratio = if (s.mode == Mode.VIDEO || s.mode == Mode.TIMELAPSE) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            // Video stabilization (off by default). Preferred: preview stabilization, which stabilizes the
            // viewfinder and the recording with the same crop. Fallback: video-only EIS.
            val videoCaps = if (s.mode == Mode.VIDEO) Recorder.getVideoCapabilities(info) else null
            val previewStabOk = s.mode == Mode.VIDEO &&
                runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false)
            val videoStabOk = videoCaps?.isStabilizationSupported == true
            val usePreviewStab = s.video.stab && previewStabOk
            val useVideoStab = s.video.stab && !previewStabOk && videoStabOk

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            if (!extActive) Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCb)
            if (usePreviewStab) previewBuilder.setPreviewStabilizationEnabled(true)

            val preview = previewBuilder.build().also { it.setSurfaceProvider(v.surfaceProvider) }

            val cases = mutableListOf<UseCase>(preview)
            imageCapture = null
            videoCapture = null
            var newFormats = ui.value.formats
            var newFormat = s.format
            var newVideo = s.video

            when (s.mode) {
                Mode.PHOTO, Mode.PRO -> {
                    val supported = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
                    val allowed = PhotoFormat.entries.filter { f ->
                        f.outputFormat in supported && (s.mode == Mode.PRO || !f.hasRaw)
                    }.ifEmpty { listOf(PhotoFormat.JPEG) }
                    newFormats = allowed
                    newFormat = if (s.format in allowed) s.format else PhotoFormat.JPEG
                    activeFormat = newFormat

                    val highRes = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(
                            if (prefs.settings.value.qualityFirst) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                            else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        )
                        .setOutputFormat(newFormat.outputFormat)
                        .setResolutionSelector(highRes)
                        .setFlashMode(s.flash)
                        .setTargetRotation(rotation)
                        .build()
                    imageCapture = ic
                    cases += ic
                    if (s.mode == Mode.PRO && withAnalysis && !extActive) {
                        cases += buildAnalysis(scopeAnalyzer, Size(640, 480))
                    }
                }

                Mode.VIDEO -> {
                    val caps = videoCaps ?: Recorder.getVideoCapabilities(info)
                    val hdrOk = DynamicRange.HLG_10_BIT in caps.supportedDynamicRanges
                    val range = if (s.video.hdr && hdrOk) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                    val qualities = caps.getSupportedQualities(range)
                    val q: Quality? = s.video.quality?.takeIf { it in qualities } ?: qualities.firstOrNull()
                    val fps60 = info.supportedFrameRateRanges.any { it.upper >= 60 }
                    val fps = if (s.video.fps == 60 && fps60) 60 else 30
                    val recorder = Recorder.Builder().apply {
                        if (q != null) setQualitySelector(
                            QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
                        )
                        targetBitrate(q, fps, range != DynamicRange.SDR)?.let { setTargetVideoEncodingBitRate(it) }
                    }.build()
                    val vc = VideoCapture.Builder(recorder)
                        .setDynamicRange(range)
                        .setVideoStabilizationEnabled(useVideoStab)
                        .setTargetFrameRate(Range(fps, fps))
                        .setTargetRotation(rotation)
                        .build()
                    videoCapture = vc
                    cases += vc
                    newVideo = s.video.copy(
                        qualities = qualities, quality = q, fps = fps, fps60Ok = fps60,
                        hdrOk = hdrOk, hdr = s.video.hdr && hdrOk, stabOk = previewStabOk || videoStabOk,
                    )
                }

                Mode.QR -> cases += buildAnalysis(qrAnalyzer, Size(1280, 960))
                Mode.SLOWMO -> {} // bound separately in bindSlowMo()
                Mode.TIMELAPSE -> {
                    // Time-lapse samples frames from a 16:9 YUV stream (up to 1080p); see TimelapseRecorder
                    cases += ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also { it.setAnalyzer(lapse.executor, lapse) }
                }
            }

            p.unbindAll()
            // 4K video: let Preview and VideoCapture share ONE camera stream (via a pass-through
            // effect targeting both). The viewfinder then shows the same clean frames that go to the
            // encoder, instead of a second full-size stream that some camera drivers (e.g. custom ROMs)
            // corrupt with torn/smeared rows while recording.
            // First attempt only; if the device rejects the shared stream, the retry binds normally.
            // Disabled: measured on a Galaxy S9+ (Exynos 9810), routing 4K through the GPU (both CameraX's
            // OverlayEffect and OmniCam's minimal PassThroughEffect) drops the recording to ~24 fps, while the
            // plain two-stream path records a steady 30 fps. A smooth recording matters more than a clean
            // viewfinder, so 4K uses the plain path. PassThroughEffect is kept for future per-device use.
            val shareStream = SHARE_4K_STREAM && withAnalysis && s.mode == Mode.VIDEO &&
                newVideo.quality == Quality.UHD && videoCapture != null
            val cam = if (shareStream) {
                val group = UseCaseGroup.Builder().apply {
                    cases.forEach { addUseCase(it) }
                    addEffect(passThroughEffect())
                }.build()
                p.bindToLifecycle(o, selector, group)
            } else {
                p.bindToLifecycle(o, selector, *cases.toTypedArray())
            }
            camera = cam

            observeZoom(cam)
            val ranges = readRanges(cam)
            ui.update {
                it.copy(
                    formats = newFormats, format = newFormat, video = newVideo,
                    ranges = ranges, hasFlash = cam.cameraInfo.hasFlashUnit(),
                    qr = if (s.mode == Mode.QR) it.qr else null,
                    slowMoOk = slowMoOk,
                    slowMo = it.slowMo.copy(camera2 = false),
                )
            }
            scopeAnalyzer.histogram = s.scope.histogram
            scopeAnalyzer.zebra = s.scope.zebra
            scopeAnalyzer.peaking = s.scope.peaking
            // Senter menyala terus hanya di VIDEO/QR; di FOTO/PRO memakai flash mode
            // enableTorch on a camera with no flash unit (e.g. most front cameras) fails silently; guard it.
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(s.torch && (s.mode.isVideo || s.mode == Mode.QR))
            }
            applyManual()
            probeExtensions()
            return true
        } catch (e: IllegalArgumentException) {
            if (withAnalysis && s.mode == Mode.PRO) {
                toast("This format + overlay combination is not supported; histogram/zebra turned off.")
            } else toast("Camera configuration not supported: ${e.message}")
            return false
        } catch (e: Exception) {
            toast("Failed to open camera: ${e.message}")
            return true // jangan coba ulang
        }
    }

    private var effect: PassThroughEffect? = null

    /** A minimal GL copy whose only job is to make Preview + VideoCapture share one camera stream. */
    private fun passThroughEffect(): PassThroughEffect {
        effect?.let { return it }
        val proc = PassThroughProcessor { err -> toast("Preview pipeline error: ${err.message}") }
        return PassThroughEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, proc) { err ->
            toast("Preview pipeline error: ${err.message}")
        }.also { effect = it }
    }

    /**
     * Video bitrate targets at or slightly above flagship stock camera apps (measured on the Galaxy S20
     * stock app: ~14 Mbps at 1080p30, ~21 at 1080p60, ~38 at 4K30, ~69 at 4K60). Without this, CameraX
     * falls back to the device's encoder profile, which on some phones and custom ROMs is lower.
     * More bits = fewer compression artifacts (blocky shadows, smeared fine detail), larger files.
     */
    private fun targetBitrate(q: Quality?, fps: Int, tenBit: Boolean): Int? {
        val base = when (q) {
            Quality.UHD -> 48_000_000
            Quality.FHD -> 18_000_000
            Quality.HD -> 10_000_000
            Quality.SD -> 5_000_000
            else -> return null
        }
        var b = base.toLong()
        if (fps >= 60) b = b * 3 / 2
        if (tenBit) b = b * 5 / 4
        return b.coerceAtMost(100_000_000L).toInt()
    }

    // ───────────────────────── Camera2 slow motion ─────────────────────────

    private fun qualityFor(s: Size): Quality = when {
        s.height >= 1080 || s.width >= 1920 -> Quality.FHD
        s.height >= 720 || s.width >= 1280 -> Quality.HD
        else -> Quality.SD
    }

    /** Switches from CameraX to OmniCam's own Camera2 high-speed recorder. The UI then shows a SurfaceView. */
    private fun enterCamera2SlowMo(p: ProcessCameraProvider, caps: HighSpeedCaps) {
        p.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        hsCaps = caps
        val s = ui.value
        val byQuality = LinkedHashMap<Quality, Size>()
        caps.sizes.forEach { sz -> byQuality.putIfAbsent(qualityFor(sz), sz) }
        // Default to 720p like Google's official Camera2 slow-motion sample: it is the high-speed size
        // most devices support at their top frame rate. The user can still pick 1080p.
        val q = s.slowMo.quality?.takeIf { it in byQuality }
            ?: byQuality.keys.firstOrNull { it == Quality.HD } ?: byQuality.keys.first()
        val size = byQuality.getValue(q)
        val rates = caps.ratesBySize[size].orEmpty()
        val fps = s.slowMo.fps.takeIf { it in rates } ?: rates.last()
        ui.update {
            it.copy(
                slowMo = SlowMoUi(byQuality.keys.toList(), q, rates, fps, camera2 = true, size = size),
                slowMoOk = true, ranges = null, hasFlash = false, qr = null,
            )
        }
        scopeFrame.value = null
        openHighSpeedIfReady()
    }

    /** Called by the UI when the slow-motion SurfaceView (sized exactly to the stream) is ready or gone. */
    fun onSlowMoSurface(surface: android.view.Surface?, width: Int = 0, height: Int = 0) {
        hsSurface = surface
        hsSurfaceSize = if (surface != null) Size(width, height) else null
        if (surface == null) closeHighSpeed() else openHighSpeedIfReady()
    }

    private fun openHighSpeedIfReady() {
        val s = ui.value
        val caps = hsCaps ?: return
        val surface = hsSurface ?: return
        val size = s.slowMo.size ?: return
        if (s.mode != Mode.SLOWMO || !s.slowMo.camera2) return
        // High-speed sessions require the preview surface to match the stream size exactly
        if (hsSurfaceSize != size) return
        hs.whenClosed {
            // Re-check: the mode, surface or size may have changed while the previous session closed
            val now = ui.value
            val surf = hsSurface ?: return@whenClosed
            if (now.mode != Mode.SLOWMO || !now.slowMo.camera2 || now.slowMo.size != size || hsSurfaceSize != size) {
                return@whenClosed
            }
            hs.compatRecorder = quirks.getBoolean(slowMoCompatKey(caps.cameraId), false)
            hs.open(caps.cameraId, size, now.slowMo.fps, surf,
                onReady = { scope.launch { startHighSpeedWatchdog(caps.cameraId) } },
                onError = { msg ->
                    // Never leave the user on a black screen: fall back to normal video
                    scope.launch {
                        toast("$msg. Switched to video.")
                        ui.update { it.copy(mode = Mode.VIDEO, slowMo = it.slowMo.copy(camera2 = false)) }
                        rebind()
                    }
                },
            )
        }
    }

    /**
     * Some drivers accept a high-speed session but never deliver frames (frozen viewfinder, recordings
     * fail). If nothing arrives within 2.5 s, try the next lower frame rate; if even the lowest fails,
     * remember the quirk, hide SLO-MO and return to normal video.
     */
    private fun startHighSpeedWatchdog(cameraId: String) {
        hsWatchdog?.cancel()
        hsWatchdog = scope.launch {
            // Frames must keep arriving: a driver that delivers one frame and then stalls shows a
            // frozen viewfinder just like one that delivers none.
            delay(1500)
            val before = hs.framesSeen
            delay(1500)
            val s = ui.value
            val progressed = hs.framesSeen - before
            if (s.mode != Mode.SLOWMO || !s.slowMo.camera2 || !hs.isActive || progressed >= 15) return@launch
            val lower = s.slowMo.rates.filter { it < s.slowMo.fps }.maxOrNull()
            if (lower != null) {
                toast("${s.slowMo.fps} fps is not working on this phone, trying $lower fps")
                ui.update { it.copy(slowMo = it.slowMo.copy(fps = lower)) }
                rebind()
            } else {
                quirks.edit().putBoolean(slowMoBrokenKey(cameraId), true).apply()
                toast("Slow motion is not supported by this phone's camera driver. Switched to video.")
                ui.update { it.copy(mode = Mode.VIDEO, slowMoOk = false, slowMo = it.slowMo.copy(camera2 = false)) }
                rebind()
            }
        }
    }

    private fun closeHighSpeed() {
        hsWatchdog?.cancel()
        hsWatchdog = null
        hsTicker?.cancel()
        hsTicker = null
        if (hs.isRecording) ui.update { it.copy(recording = false, recordedMs = 0) }
        hs.close()   // finalizes a running take

    }

    private fun toggleHighSpeedRecording() {
        if (hs.isRecording) {
            hsTicker?.cancel()
            hsTicker = null
            if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            ui.update { it.copy(busy = true) }
            hs.stopRecording { uri ->
                ui.update { it.copy(recording = false, busy = false, recordedMs = 0, lastUri = uri ?: it.lastUri) }
                val camId = hsCaps?.cameraId
                if (uri == null && hs.framesSeen > 0 && !hs.compatRecorder && camId != null) {
                    // Frames arrive but the encoder rejected the retimed file: switch to the settings of
                    // Google's official sample and let the user try again.
                    hs.compatRecorder = true
                    quirks.edit().putBoolean(slowMoCompatKey(camId), true).apply()
                    toast("Recording failed. Switched to compatibility mode, please record again.")
                } else if (uri == null) {
                    toast("Slow-motion recording failed")
                }
            }
            return
        }
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        hs.startRecording { ok ->
            ui.update { it.copy(busy = false) }
            if (!ok) {
                toast("Could not start slow-motion recording")
                return@startRecording
            }
            if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            val t0 = SystemClock.elapsedRealtime()
            ui.update { it.copy(recording = true, paused = false, recordedMs = 0) }
            hsTicker = scope.launch {
                while (true) {
                    ui.update { it.copy(recordedMs = SystemClock.elapsedRealtime() - t0) }
                    delay(250)
                }
            }
        }
    }

    /**
     * High-speed session (e.g. 120/240 fps on the Galaxy S9 main camera). With slow motion enabled,
     * CameraX encodes the file at 30 fps, so it plays back 4x/8x slower. No audio in this mode.
     */
    private fun bindSlowMo(
        p: ProcessCameraProvider, o: LifecycleOwner, v: PreviewView, selector: CameraSelector,
        info: androidx.camera.core.CameraInfo,
    ): Boolean {
        val s = ui.value
        val hs = Recorder.getHighSpeedVideoCapabilities(info) ?: return false
        val qualities = hs.getSupportedQualities(DynamicRange.SDR)
        val q: Quality? = s.slowMo.quality?.takeIf { it in qualities } ?: qualities.firstOrNull()
        val recorder = Recorder.Builder().apply {
            if (q != null) setQualitySelector(QualitySelector.from(q))
        }.build()
        val vc = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(v.surfaceProvider) }

        val supported = info.getSupportedFrameRateRanges(HighSpeedVideoSessionConfig(vc, preview))
        val rates = supported.map { it.upper }.filter { it >= 120 }.distinct().sorted()
        val fps = s.slowMo.fps.takeIf { it in rates } ?: rates.lastOrNull() ?: return false
        val range = supported.filter { it.upper == fps }.maxByOrNull { it.lower } ?: Range(fps, fps)
        val config = HighSpeedVideoSessionConfig(vc, preview, range, true)

        imageCapture = null
        videoCapture = vc
        p.unbindAll()
        val cam = p.bindToLifecycle(o, selector, config)
        camera = cam
        observeZoom(cam)
        ui.update {
            it.copy(
                slowMo = SlowMoUi(qualities = qualities, quality = q, rates = rates, fps = fps),
                slowMoOk = true, ranges = readRanges(cam), hasFlash = cam.cameraInfo.hasFlashUnit(), qr = null,
            )
        }
        scopeFrame.value = null
        cam.cameraControl.enableTorch(s.torch)
        return true
    }

    private fun buildAnalysis(analyzer: ImageAnalysis.Analyzer, size: Size): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExec, analyzer) }

    private fun observeZoom(cam: Camera) {
        val old = zoomObserver
        if (old != null) zoomLive?.removeObserver(old)
        val obs = Observer<ZoomState> { z ->
            ui.update { it.copy(zoom = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
        }
        zoomObserver = obs
        zoomLive = cam.cameraInfo.zoomState.also { it.observeForever(obs) }
    }

    private fun readRanges(cam: Camera): ManualRanges {
        val c = Camera2CameraInfo.from(cam.cameraInfo)
        val iso = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val caps = c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val awb = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        val ois = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val apertures = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.toList()?.distinct()?.sorted() ?: emptyList()
        val es = cam.cameraInfo.exposureState
        return ManualRanges(
            manualSensor = iso != null && exp != null &&
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            isoMin = iso?.lower ?: 100,
            isoMax = iso?.upper ?: 100,
            expMinNs = exp?.lower ?: 1_000_000L,
            // Batasi 1 dtk agar preview tetap lancar
            expMaxNs = min(exp?.upper ?: 1_000_000_000L, 1_000_000_000L),
            minFocusDiopter = minFocus,
            evMin = es.exposureCompensationRange.lower,
            evMax = es.exposureCompensationRange.upper,
            evStep = es.exposureCompensationStep.toFloat(),
            evSupported = es.isExposureCompensationSupported,
            awbModes = awb.toList(),
            apertures = apertures,
            ois = ois,
        )
    }

    // ───────────────────────── kontrol manual ─────────────────────────

    private fun applyManual() {
        val cam = camera ?: return
        val s = ui.value
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val r = s.ranges
        if (s.mode == Mode.SLOWMO) return
        if (s.mode != Mode.PRO || r == null) {
            if (r?.ois == true) {
                // Keep optical stabilization explicitly on (photo and video)
                c2.setCaptureRequestOptions(
                    CaptureRequestOptions.Builder().setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                    ).build()
                )
            } else c2.clearCaptureRequestOptions()
            val q = if (r != null && r.evSupported) s.ev.coerceIn(r.evMin, r.evMax) else 0
            cam.cameraControl.setExposureCompensationIndex(q)
            return
        }
        val m = s.manual
        val b = CaptureRequestOptions.Builder()
        if (r.ois) {
            b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (m.exposureManual && r.manualSensor) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(r.isoMin, r.isoMax))
            b.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs.coerceIn(r.expMinNs, r.expMaxNs)
            )
            // Aperture is only honoured with AE off; in auto exposure the camera picks it itself.
            if (r.variableAperture && m.aperture in r.apertures) {
                b.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, m.aperture)
            }
        }
        if (m.focusManual && r.manualFocus) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopter.coerceIn(0f, r.minFocusDiopter)
            )
        }
        if (m.awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        }
        c2.setCaptureRequestOptions(b.build())
        cam.cameraControl.setExposureCompensationIndex(if (m.exposureManual) 0 else m.evIndex)
    }

    fun setExposureManual(on: Boolean) {
        val r = ui.value.ranges ?: return
        val ro = readout.value
        ui.update {
            val seedIso = if (ro.iso > 0) ro.iso else it.manual.iso
            val seedExp = if (ro.expNs > 0) ro.expNs else it.manual.exposureNs
            val seedAp = when {
                !r.variableAperture -> 0f
                it.manual.aperture in r.apertures -> it.manual.aperture
                else -> r.apertures.minByOrNull { a -> kotlin.math.abs(a - ro.aperture) } ?: r.apertures.first()
            }
            it.copy(
                manual = it.manual.copy(
                    exposureManual = on,
                    iso = seedIso.coerceIn(r.isoMin, r.isoMax),
                    exposureNs = seedExp.coerceIn(r.expMinNs, r.expMaxNs),
                    aperture = seedAp,
                )
            )
        }
        applyManual()
    }

    fun setIso(iso: Int) { ui.update { it.copy(manual = it.manual.copy(iso = iso)) }; applyManual() }
    fun setExposureNs(ns: Long) { ui.update { it.copy(manual = it.manual.copy(exposureNs = ns)) }; applyManual() }
    fun setEv(index: Int) { ui.update { it.copy(manual = it.manual.copy(evIndex = index)) }; applyManual() }

    /** iPhone-style brightness for PHOTO/VIDEO/SLO-MO: exposure compensation on top of auto exposure. */
    fun setQuickEv(index: Int) {
        val r = ui.value.ranges ?: return
        if (!r.evSupported) return
        val i = index.coerceIn(r.evMin, r.evMax)
        if (i == ui.value.ev) return
        ui.update { it.copy(ev = i) }
        camera?.cameraControl?.setExposureCompensationIndex(i)
    }
    fun setAwb(mode: Int) { ui.update { it.copy(manual = it.manual.copy(awbMode = mode)) }; applyManual() }
    fun setAperture(f: Float) { ui.update { it.copy(manual = it.manual.copy(aperture = f)) }; applyManual() }

    fun setFocusManual(on: Boolean) {
        val ro = readout.value
        ui.update {
            it.copy(manual = it.manual.copy(focusManual = on, focusDiopter = if (on) ro.focusDiopter else it.manual.focusDiopter))
        }
        applyManual()
    }

    fun setFocusDiopter(d: Float) { ui.update { it.copy(manual = it.manual.copy(focusDiopter = d)) }; applyManual() }

    fun setScope(sc: ScopeSettings) {
        ui.update { it.copy(scope = sc) }
        scopeAnalyzer.histogram = sc.histogram
        scopeAnalyzer.zebra = sc.zebra
        scopeAnalyzer.peaking = sc.peaking
        if (!sc.histogram && !sc.zebra && !sc.peaking) scopeFrame.value = null
    }

    // ───────────────────────── kontrol umum ─────────────────────────

    fun setMode(m: Mode) {
        if (recording != null || hs.isRecording || lapse.isRecording || m == ui.value.mode) return
        ui.update { it.copy(mode = m, torch = false, qr = null, manual = if (m == Mode.PRO) it.manual else it.manual.copy(exposureManual = false, focusManual = false, awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO, evIndex = 0)) }
        scopeFrame.value = null
        rebind()
    }

    fun flip() {
        if (recording != null || hs.isRecording || lapse.isRecording) return
        ui.update { it.copy(front = !it.front, lensId = null, ev = 0) }
        rebind()
    }

    fun selectLens(id: String) {
        if (recording != null) return
        ui.update { it.copy(lensId = id, front = it.lenses.firstOrNull { l -> l.id == id }?.front ?: it.front) }
        rebind()
    }

    fun setFormat(f: PhotoFormat) { ui.update { it.copy(format = f) }; rebind() }
    fun setExtension(mode: Int) { ui.update { it.copy(extension = mode) }; rebind() }

    fun setVideoQuality(q: Quality) { ui.update { it.copy(video = it.video.copy(quality = q)) }; rebind() }
    fun setVideoFps(fps: Int) { ui.update { it.copy(video = it.video.copy(fps = fps)) }; rebind() }
    fun setVideoHdr(on: Boolean) { ui.update { it.copy(video = it.video.copy(hdr = on)) }; rebind() }
    fun setVideoStab(on: Boolean) { ui.update { it.copy(video = it.video.copy(stab = on)) }; rebind() }
    fun setSlowMoQuality(q: Quality) { ui.update { it.copy(slowMo = it.slowMo.copy(quality = q)) }; rebind() }
    fun setSlowMoFps(fps: Int) { ui.update { it.copy(slowMo = it.slowMo.copy(fps = fps)) }; rebind() }
    fun setMic(on: Boolean) { ui.update { it.copy(video = it.video.copy(mic = on)) } }

    fun cycleFlash() {
        val next = when (ui.value.flash) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        ui.update { it.copy(flash = next) }
        imageCapture?.flashMode = next
    }

    fun toggleTorch() {
        val on = !ui.value.torch
        ui.update { it.copy(torch = on) }
        if (camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(on)
    }

    /** Cameras with no physical flash (typically front cameras) get a screen-as-flash toggle instead. */
    fun toggleScreenFlash() { ui.update { it.copy(screenFlash = !it.screenFlash) } }

    fun cycleTimer() { ui.update { it.copy(timer = when (it.timer) { 0 -> 3; 3 -> 10; else -> 0 }) } }
    fun cycleBurst() { ui.update { it.copy(burst = when (it.burst) { 1 -> 3; 3 -> 5; 5 -> 10; else -> 1 }) } }

    fun setZoom(ratio: Float) {
        val z = ui.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(z.minZoom, z.maxZoom))
    }

    fun zoomBy(scale: Float) = setZoom(ui.value.zoom * scale)

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val v = previewView ?: return
        if (ui.value.manual.focusManual && ui.value.mode == Mode.PRO) return
        val point = v.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        )
        ui.update { it.copy(focusRing = FocusRing(x, y, System.nanoTime())) }
    }

    fun clearFocusRing() { ui.update { it.copy(focusRing = null) } }
    fun clearMessage() { ui.update { it.copy(message = null) } }
    fun clearQr() { ui.update { it.copy(qr = null) }; lastQrText = null }
    private fun toast(msg: String) { ui.update { it.copy(message = msg) } }

    private fun onQr(text: String, format: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastQrText && now - lastQrTime < 3000) return
        lastQrText = text
        lastQrTime = now
        ui.update { it.copy(qr = QrHit(text, format)) }
    }

    // ───────────────────────── pengambilan gambar/video ─────────────────────────

    private fun toggleTimelapse() {
        if (lapse.isRecording) { stopTimelapse(); return }
        if (ui.value.busy) return
        if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        lapse.start()
        lockForTimelapse(true)
        val t0 = SystemClock.elapsedRealtime()
        ui.update { it.copy(recording = true, paused = false, recordedMs = 0, lapseSpeed = lapse.speed, lapseFrames = 0) }
        lapseTicker = scope.launch {
            while (true) {
                ui.update {
                    it.copy(recordedMs = SystemClock.elapsedRealtime() - t0, lapseSpeed = lapse.speed, lapseFrames = lapse.keptFrames)
                }
                delay(500)
            }
        }
    }

    private fun currentCameraId(): String? =
        runCatching { camera?.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId } }.getOrNull()

    /**
     * During a time-lapse, focus and white balance are locked: refocusing and colour shifts are the
     * main causes of "pumping" in phone time-lapses. Exposure stays automatic (light changes over
     * long recordings) and is smoothed by the recorder's deflicker instead.
     */
    private fun lockForTimelapse(lock: Boolean) {
        val cam = camera ?: return
        runCatching {
            if (lock) {
                val centre = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
                )
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true).build()
                )
            } else {
                cam.cameraControl.cancelFocusAndMetering()
                applyManual()   // restores the normal request options (clears the AWB lock)
            }
        }
    }

    private fun stopTimelapse() {
        lockForTimelapse(false)
        lapseTicker?.cancel()
        lapseTicker = null
        if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        ui.update { it.copy(busy = true) }
        lapse.stop { uri, err ->
            ui.update { it.copy(recording = false, busy = false, recordedMs = 0, lastUri = uri ?: it.lastUri) }
            if (uri != null) toast("Time-lapse saved") else if (err != null) toast("Time-lapse: $err")
        }
    }

    fun onShutter() {
        when (ui.value.mode) {
            Mode.PHOTO, Mode.PRO -> capturePhoto()
            Mode.VIDEO, Mode.SLOWMO -> toggleRecording()
            Mode.TIMELAPSE -> toggleTimelapse()
            Mode.QR -> {}
        }
    }

    private fun capturePhoto() {
        val ic = imageCapture ?: return
        if (captureJob?.isActive == true) {
            // Ketuk lagi saat hitung mundur = batal
            captureJob?.cancel()
            ui.update { it.copy(countdown = 0, busy = false) }
            return
        }
        captureJob = scope.launch {
            val s0 = ui.value
            // The front camera on most phones has no physical flash; light the subject with the
            // screen instead. Only offered/used when the bound camera truly lacks a flash unit.
            val useScreenFlash = s0.screenFlash && !s0.hasFlash
            ui.update { it.copy(busy = true) }
            try {
                for (t in s0.timer downTo 1) {
                    ui.update { it.copy(countdown = t) }
                    delay(1000)
                }
                ui.update { it.copy(countdown = 0) }
                if (useScreenFlash) {
                    screenFlashActive.value = true
                    delay(250) // let the screen reach full brightness and auto-exposure adjust
                }
                repeat(s0.burst) {
                    if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                    takeOne(ic, activeFormat)
                }
            } finally {
                screenFlashActive.value = false
                ui.update { it.copy(busy = false, countdown = 0) }
            }
        }
    }

    private suspend fun takeOne(ic: ImageCapture, format: PhotoFormat) = suspendCancellableCoroutine<Unit> { cont ->
        val st = prefs.settings.value
        val stamp = MediaOutput.stamp()
        val loc = if (st.geotag) MediaOutput.lastLocation(app) else null
        val mirror = st.mirrorFront && ui.value.front
        var remaining = if (format == PhotoFormat.RAW_JPEG) 2 else 1

        val cb = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val uri = results.savedUri
                if (uri != null) {
                    val mime = app.contentResolver.getType(uri).orEmpty()
                    if (st.stripMetadata && mime == "image/jpeg" && format != PhotoFormat.ULTRA_HDR) {
                        MediaOutput.scrub(app, uri)
                    }
                    if (!mime.contains("dng") || format == PhotoFormat.RAW) {
                        ui.update { it.copy(lastUri = uri) }
                    }
                }
                if (--remaining <= 0 && cont.isActive) cont.resume(Unit)
            }

            override fun onError(exception: ImageCaptureException) {
                toast("Failed to save photo: ${exception.message}")
                if (cont.isActive) cont.resume(Unit)
            }
        }

        try {
            when (format) {
                PhotoFormat.RAW_JPEG -> ic.takePicture(
                    MediaOutput.imageOptions(app, "image/x-adobe-dng", "dng", stamp, loc, false),
                    MediaOutput.imageOptions(app, "image/jpeg", "jpg", stamp, loc, mirror),
                    io, cb,
                )
                PhotoFormat.RAW -> ic.takePicture(
                    MediaOutput.imageOptions(app, "image/x-adobe-dng", "dng", stamp, loc, false), io, cb,
                )
                else -> ic.takePicture(
                    MediaOutput.imageOptions(app, "image/jpeg", "jpg", stamp, loc, mirror), io, cb,
                )
            }
        } catch (e: Exception) {
            toast("Failed to take photo: ${e.message}")
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun toggleRecording() {
        if (ui.value.mode == Mode.SLOWMO && ui.value.slowMo.camera2) { toggleHighSpeedRecording(); return }
        val cur = recording
        if (cur != null) {
            cur.stop()
            return
        }
        val vc = videoCapture ?: return
        val s = ui.value
        var pending = vc.output.prepareRecording(app, MediaOutput.videoOptions(app, MediaOutput.stamp()))
        val micGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // High-speed sessions record without audio (a slowed-down soundtrack would be unusable)
        if (s.video.mic && micGranted && s.mode != Mode.SLOWMO) pending = pending.withAudioEnabled()
        if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)

        recording = pending.start(mainExec) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> ui.update { it.copy(recording = true, paused = false) }
                is VideoRecordEvent.Status ->
                    ui.update { it.copy(recordedMs = ev.recordingStats.recordedDurationNanos / 1_000_000) }
                is VideoRecordEvent.Pause -> ui.update { it.copy(paused = true) }
                is VideoRecordEvent.Resume -> ui.update { it.copy(paused = false) }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (prefs.settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    ui.update { it.copy(recording = false, paused = false, recordedMs = 0) }
                    if (ev.hasError()) {
                        val reason = when (ev.error) {
                            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "storage full"
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "camera stopped"
                            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "could not create the video file"
                            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "encoder failed; try a lower resolution"
                            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "recording too short"
                            else -> "error"
                        }
                        toast("Recording stopped: $reason (code ${ev.error})")
                        // Rekaman gagal tetap bisa menyisakan file kosong di galeri
                        runCatching { ev.outputResults.outputUri.takeIf { it != android.net.Uri.EMPTY }
                            ?.let { app.contentResolver.delete(it, null, null) } }
                    }
                    else ui.update { it.copy(lastUri = ev.outputResults.outputUri) }
                }
                else -> {}
            }
        }
    }

    fun pauseResume() {
        val r = recording ?: return
        if (ui.value.paused) r.resume() else r.pause()
    }
}
