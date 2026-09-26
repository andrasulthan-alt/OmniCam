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
        if (recording != null) return
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

            val slowMoOk = runCatching { Recorder.getHighSpeedVideoCapabilities(info) != null }.getOrDefault(false)
            if (s.mode == Mode.SLOWMO) {
                if (slowMoOk && bindSlowMo(p, o, v, selector, info)) return true
                toast("Slow motion is not supported by this camera")
                ui.update { it.copy(mode = Mode.VIDEO, slowMoOk = false) }
                return tryBind(p, o, v, withAnalysis)
            }

            val ratio = if (s.mode == Mode.VIDEO) {
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
            }

            p.unbindAll()
            val cam = p.bindToLifecycle(o, selector, *cases.toTypedArray())
            camera = cam

            observeZoom(cam)
            val ranges = readRanges(cam)
            ui.update {
                it.copy(
                    formats = newFormats, format = newFormat, video = newVideo,
                    ranges = ranges, hasFlash = cam.cameraInfo.hasFlashUnit(),
                    qr = if (s.mode == Mode.QR) it.qr else null,
                    slowMoOk = slowMoOk,
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
            cam.cameraControl.setExposureCompensationIndex(0)
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
        if (recording != null || m == ui.value.mode) return
        ui.update { it.copy(mode = m, torch = false, qr = null, manual = if (m == Mode.PRO) it.manual else it.manual.copy(exposureManual = false, focusManual = false, awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO, evIndex = 0)) }
        scopeFrame.value = null
        rebind()
    }

    fun flip() {
        if (recording != null) return
        ui.update { it.copy(front = !it.front, lensId = null) }
        rebind()
    }

    fun selectLens(id: String) {
        if (recording != null) return
        ui.update { it.copy(lensId = id, front = it.lenses.firstOrNull { l -> l.id == id }?.front ?: it.front) }
        rebind()
    }

    fun setFormat(f: PhotoFormat) { ui.update { it.copy(format = f) }; rebind() }
    fun setExtension(mode: Int) { ui.update { it.copy(extension = mode, hdr = false) }; rebind() }

    /** Built-in HDR uses plain JPEG frames and no vendor extension. */
    fun setHdr(on: Boolean) {
        val s = ui.value
        val needRebind = on && (s.extension != ExtensionMode.NONE || s.format != PhotoFormat.JPEG)
        ui.update {
            if (on) it.copy(hdr = true, extension = ExtensionMode.NONE, format = PhotoFormat.JPEG)
            else it.copy(hdr = false)
        }
        if (needRebind) rebind()
    }

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

    fun onShutter() {
        when (ui.value.mode) {
            Mode.PHOTO, Mode.PRO -> capturePhoto()
            Mode.VIDEO, Mode.SLOWMO -> toggleRecording()
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
                if (s0.mode == Mode.PHOTO && s0.hdr && s0.extension == ExtensionMode.NONE) {
                    captureHdr(ic)
                    return@launch
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

    // ───────────────────────── built-in HDR ─────────────────────────

    /**
     * Three JPEG frames at 0, -2 and +2 EV (clamped to the camera's range), aligned and merged by
     * [HdrProcessor]. Needs exposure compensation support; no vendor extension is involved.
     */
    private suspend fun captureHdr(ic: ImageCapture) {
        val cam = camera ?: return
        val r = ui.value.ranges
        val st = prefs.settings.value
        if (r == null || !r.evSupported || r.evStep <= 0f || r.evMax <= r.evMin) {
            toast("HDR needs exposure compensation, which this camera does not support")
            return
        }
        val steps = (2f / r.evStep).roundToInt().coerceAtLeast(1)
        val indices = listOf(0, max(r.evMin, -steps), min(r.evMax, steps)).distinct()
        if (indices.size < 2) {
            toast("HDR is not available with this camera's exposure range")
            return
        }
        val loc = if (st.geotag) MediaOutput.lastLocation(app) else null
        val frames = ArrayList<Bitmap>(indices.size)
        var rotationDeg = 0
        toast("HDR: hold still")
        try {
            for ((n, idx) in indices.withIndex()) {
                setEvAndWait(cam, idx)
                if (n == 0 && st.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                val shot = captureBitmap(ic)
                if (shot == null) {
                    toast("HDR capture failed")
                    frames.forEach { it.recycle() }
                    return
                }
                frames += shot.first
                rotationDeg = shot.second
            }
        } finally {
            runCatching { cam.cameraControl.setExposureCompensationIndex(0) }
        }
        toast("Processing HDR…")
        val uri = withContext(Dispatchers.Default) {
            try {
                val fused = HdrProcessor.fuse(frames, 0)
                frames.forEach { it.recycle() }
                val bytes = ByteArrayOutputStream().use { os ->
                    fused.compress(Bitmap.CompressFormat.JPEG, 95, os)
                    os.toByteArray()
                }
                fused.recycle()
                MediaOutput.saveJpeg(app, bytes, MediaOutput.stamp(), "HDR", rotationDeg, loc)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
        }
        if (uri != null) {
            ui.update { it.copy(lastUri = uri) }
            toast("HDR photo saved")
        } else {
            toast("HDR processing failed")
        }
    }

    private suspend fun setEvAndWait(cam: Camera, index: Int) {
        val f = cam.cameraControl.setExposureCompensationIndex(index)
        suspendCancellableCoroutine<Unit> { c -> f.addListener({ if (c.isActive) c.resume(Unit) }, mainExec) }
        delay(350) // let auto exposure settle on the new target
    }

    private suspend fun captureBitmap(ic: ImageCapture): Pair<Bitmap, Int>? =
        suspendCancellableCoroutine { cont ->
            ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val res: Pair<Bitmap, Int>? = try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) Pair(bmp, image.imageInfo.rotationDegrees) else null
                    } catch (_: Throwable) {
                        null
                    } finally {
                        image.close()
                    }
                    if (cont.isActive) cont.resume(res)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }

    private fun toggleRecording() {
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
