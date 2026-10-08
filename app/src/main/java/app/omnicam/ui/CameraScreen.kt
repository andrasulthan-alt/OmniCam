// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size as AndroidSize
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.omnicam.camera.CameraEngine
import app.omnicam.model.CamUi
import app.omnicam.model.Mode
import app.omnicam.storage.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private fun Context.has(perm: String) =
    ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraScreen(engine: CameraEngine, prefs: Prefs) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ui by engine.ui.collectAsState()
    val readout by engine.readout.collectAsState()
    val frame by engine.scopeFrame.collectAsState()
    val screenFlashActive by engine.screenFlashActive.collectAsState()
    // Continuous "screen light": front camera has no torch, so brighten the screen instead
    // while recording video with the light toggle on.
    val screenLightOn = ui.front && !ui.hasFlash && ui.torch && ui.mode.isVideo
    // Once recording actually starts, go further than just raising the backlight: turn the
    // viewfinder itself into a bright white area (real light, not just backlight) and shrink the
    // live feed to a small corner thumbnail so framing is still possible. Only while recording,
    // since before that the full preview is more useful for lining up the shot.
    val whiteLightMode = screenLightOn && ui.recording
    ScreenBrightnessBoost(active = screenFlashActive || screenLightOn)
    // Keep the screen on while recording: a long time-lapse must not be cut off by the screen timeout
    val hostView = LocalView.current
    DisposableEffect(ui.recording) {
        hostView.keepScreenOn = ui.recording
        onDispose { hostView.keepScreenOn = false }
    }
    val settings by prefs.settings.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    // Exposure bar: appears after tapping the viewfinder (like iPhone) and hides after a few seconds idle.
    var evTouch by remember { mutableStateOf(0L) }
    var showEvBar by remember { mutableStateOf(false) }
    LaunchedEffect(evTouch) {
        if (evTouch != 0L) { showEvBar = true; delay(3500); showEvBar = false }
    }
    // Brightness slide: the full exposure range spans this much finger travel
    val evDragPx = with(LocalDensity.current) { 320.dp.toPx() }
    var evCarry by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // PERFORMANCE (SurfaceView) is sharper and lower-latency than COMPATIBLE (TextureView),
            // which renders through an extra blending pass that softens the viewfinder.
            // (The implementation mode only takes effect on the next surface request, so it is set once
            // here rather than switched while recording.)
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        engine.setMic(ok)
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.update { it.copy(geotag = ok) }
    }

    LaunchedEffect(Unit) {
        engine.start()
        // Mic shows "on" by default: without the permission the video would be silent, so show it as off
        if (!ctx.has(Manifest.permission.RECORD_AUDIO)) engine.setMic(false)
    }
    DisposableEffect(owner) {
        engine.attach(owner, previewView)
        onDispose { engine.detach() }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); engine.clearMessage() }
    }

    BackHandler(enabled = showInfo) { showInfo = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ───── Viewfinder ─────
        val ratio = if (ui.mode.isVideo) 9f / 16f else 3f / 4f
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { engine.focusAt(it.x, it.y); evTouch = System.nanoTime() })
                }
                .pointerInput(Unit) {
                    // Pinch = zoom. One-finger slide up/down after a tap = brightness (like the iPhone camera)
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) { engine.zoomBy(zoom); evCarry = 0f; return@detectTransformGestures }
                        val u = engine.ui.value   // live value: several slide events arrive between frames
                        val r = u.ranges ?: return@detectTransformGestures
                        val evOk = u.mode == Mode.PHOTO || u.mode.isVideo
                        if (u.focusRing == null || !evOk || !r.evSupported || r.evMax <= r.evMin) return@detectTransformGestures
                        val pxPerStep = evDragPx / (r.evMax - r.evMin)
                        evCarry += -pan.y   // up = brighter
                        val steps = (evCarry / pxPerStep).toInt()
                        if (steps != 0) {
                            evCarry -= steps * pxPerStep
                            engine.setQuickEv((u.ev + steps).coerceIn(r.evMin, r.evMax))
                        }
                        evTouch = System.nanoTime()
                    }
                },
        ) {
            if (whiteLightMode) Box(Modifier.fillMaxSize().background(Color.White))
            val slowMoC2 = ui.mode == Mode.SLOWMO && ui.slowMo.camera2
            val slowMoSize = ui.slowMo.size
            if (slowMoC2 && slowMoSize != null) {
                SlowMoSurface(slowMoSize, engine, Modifier.fillMaxSize())
            } else {
                AndroidView(
                    factory = { previewView },
                    modifier = if (whiteLightMode) {
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp)
                            .size(112.dp).clip(RoundedCornerShape(16.dp))
                            .border(2.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    } else Modifier.fillMaxSize(),
                )
            }
            if (!whiteLightMode) {
                GridOverlay(settings.grid, Modifier.fillMaxSize())
                if (settings.level) LevelOverlay(Modifier.fillMaxSize())
            }
            if (ui.mode == Mode.PRO) ScopeOverlay(frame, Modifier.fillMaxSize())
            if (!whiteLightMode) FocusBox(ui, active = showEvBar)

            if (ui.mode == Mode.PRO && ui.scope.histogram) {
                frame?.let {
                    HistogramView(
                        it.histogram,
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 10.dp)
                            .size(width = 120.dp, height = 44.dp),
                    )
                }
            }
            if (ui.mode == Mode.PRO && readout.iso > 0) {
                Text(
                    "ISO ${readout.iso}  ${app.omnicam.model.formatShutter(readout.expNs)}",
                    color = Color.White, fontSize = 12.sp, fontFamily = Dot,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 10.dp)
                        .clip(RoundedCornerShape(6.dp)).background(PanelBg).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (ui.countdown > 0) {
                Text(
                    "${ui.countdown}", color = Color.White, fontSize = 96.sp, fontFamily = Dot,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (ui.recording) {
                // Time-lapse: real time recorded, plus the automatic speed and the clip length so far
                val lapseInfo = if (ui.mode == Mode.TIMELAPSE) {
                    "  ${ui.lapseSpeed}×  ${formatTime(ui.lapseFrames * 1000L / 30)}"
                } else ""
                Text(
                    (if (ui.paused) "⏸ " else "● ") + formatTime(ui.recordedMs) + lapseInfo,
                    color = Accent, fontSize = 16.sp, fontFamily = Dot,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 44.dp)
                        .clip(RoundedCornerShape(50)).background(PanelBg).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // ───── Bar atas ─────
        TopBar(ui, engine, settings.grid.label, onSettings = { showSettings = true }, onInfo = { showInfo = true },
            modifier = Modifier.align(Alignment.TopCenter), onWhite = whiteLightMode)

        // ───── Kartu hasil QR ─────
        ui.qr?.let { hit ->
            QrCard(hit.text, hit.format, engine, Modifier.align(Alignment.Center).padding(24.dp))
        }

        // ───── Kontrol bawah ─────
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (ui.mode) {
                    Mode.PHOTO -> ExtensionRow(ui, engine)
                    Mode.VIDEO -> VideoPanel(ui, engine) { on ->
                        if (on && !ctx.has(Manifest.permission.RECORD_AUDIO)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else engine.setMic(on)
                    }
                    Mode.SLOWMO -> SlowMoPanel(ui, engine)
                    Mode.TIMELAPSE -> TimelapsePanel(ui)
                    else -> {}
                }
            }
            if (ui.mode == Mode.PRO) ProPanel(ui, readout, engine)

            // Zoom/lens chips don't apply to the Camera2 slow-motion recorder (fixed high-speed stream)
            if (ui.mode != Mode.QR && !(ui.mode == Mode.SLOWMO && ui.slowMo.camera2)) LensRow(ui, engine)

            // Rana
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastThumb(ui.lastUri) { ui.lastUri?.let { openUri(ctx, it) } }
                ShutterButton(ui, engine)
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                        .clickable(enabled = !ui.recording) { engine.flip() },
                    contentAlignment = Alignment.Center,
                ) { Text("⟲", color = Color.White, fontSize = 24.sp) }
            }

            // Mode picker: spreads evenly when the tabs fit, and scrolls sideways (keeping the active mode
            // in view) on narrow screens or with large display/font sizes, so no mode is ever cut off.
            val modes = Mode.entries.filter {
                it != Mode.SLOWMO || ui.slowMoOk || ui.mode == Mode.SLOWMO
            }
            val modeScroll = rememberScrollState()
            LaunchedEffect(ui.mode, modeScroll.maxValue) {
                val i = modes.indexOf(ui.mode)
                if (i >= 0 && modes.size > 1 && modeScroll.maxValue > 0) {
                    modeScroll.animateScrollTo(modeScroll.maxValue * i / (modes.size - 1))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(
                Modifier.horizontalScroll(modeScroll).widthIn(min = maxWidth).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                modes.forEach { m ->
                    val on = ui.mode == m
                    Column(
                        Modifier.clickable(enabled = !ui.recording) { engine.setMode(m) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            m.label,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.45f),
                            fontFamily = Dot,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                        // Nothing-style red dot under the active mode
                        Box(
                            Modifier.padding(top = 4.dp).size(5.dp).clip(CircleShape)
                                .background(if (on) Accent else Color.Transparent)
                        )
                    }
                }
            }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))

        // Instant screen-as-flash for photo capture on cameras with no physical flash.
        if (screenFlashActive) {
            Box(Modifier.fillMaxSize().background(Color.White))
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onDismiss = { showSettings = false },
            onChange = { block -> prefs.update(block) },
            onGeotag = { on ->
                if (!on) prefs.update { it.copy(geotag = false) }
                else if (ctx.has(Manifest.permission.ACCESS_FINE_LOCATION)) prefs.update { it.copy(geotag = true) }
                else locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
            onInfo = { showSettings = false; showInfo = true },
        )
    }
    if (showInfo) InfoScreen(onBack = { showInfo = false })
}

// ───────────────────────── bagian-bagian ─────────────────────────

@Composable
private fun TopBar(
    ui: CamUi,
    engine: CameraEngine,
    gridLabel: String,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier,
    onWhite: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)
            .let { if (onWhite) it.background(PanelBg, RoundedCornerShape(20.dp)) else it }
            .padding(horizontal = 4.dp, vertical = if (onWhite) 4.dp else 0.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val photoish = ui.mode == Mode.PHOTO || ui.mode == Mode.PRO
        if (photoish) {
            if (ui.hasFlash) {
                val label = when (ui.flash) {
                    ImageCapture.FLASH_MODE_ON -> "⚡ On"
                    ImageCapture.FLASH_MODE_AUTO -> "⚡ Auto"
                    else -> "⚡ Off"
                }
                Chip(label, ui.flash != ImageCapture.FLASH_MODE_OFF, compact = true) { engine.cycleFlash() }
            } else if (ui.front) {
                // No physical flash on this camera (typical for a front camera): flash the screen instead.
                Chip(if (ui.screenFlash) "💡 Flash: On" else "💡 Flash: Off", ui.screenFlash, compact = true) {
                    engine.toggleScreenFlash()
                }
            }
            Chip(if (ui.timer == 0) "⏱ Off" else "⏱ ${ui.timer}s", ui.timer != 0, compact = true) { engine.cycleTimer() }
            Chip(if (ui.burst == 1) "Burst 1" else "Burst ${ui.burst}", ui.burst != 1, compact = true) { engine.cycleBurst() }
        } else if (ui.hasFlash) {
            Chip(if (ui.torch) "🔦 On" else "🔦 Off", ui.torch, compact = true) { engine.toggleTorch() }
        } else if (ui.front && ui.mode.isVideo) {
            // Continuous fill light for front-camera video: brightens the screen instead of a torch.
            Chip(if (ui.torch) "💡 Light: On" else "💡 Light: Off", ui.torch, compact = true) { engine.toggleTorch() }
        }
        // Grid lives in Settings; the top bar keeps only quick toggles so it fits narrow screens
        Chip("⚙", false, compact = true, onClick = onSettings)
        Chip("ⓘ", false, compact = true, onClick = onInfo)
    }
}

@Composable
private fun LensRow(ui: CamUi, engine: CameraEngine) {
    val zooms = listOf(0.5f, 1f, 2f, 3f, 5f, 10f).filter { it >= ui.minZoom - 0.01f && it <= ui.maxZoom + 0.01f }
    val lenses = ui.lenses.filter { it.front == ui.front }
    if (zooms.size <= 1 && lenses.size <= 1) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        zooms.forEach { z ->
            val active = abs(ui.zoom - z) < z * 0.05f
            Chip(if (z < 1f) "%.1f×".format(z) else "${z.roundToInt()}×", active, dot = true) { engine.setZoom(z) }
        }
        if (lenses.size > 1) {
            Text("│", color = Color.Gray)
            lenses.forEach { l -> Chip(l.label, ui.lensId == l.id, dot = true) { engine.selectLens(l.id) } }
        }
    }
}

@Composable
private fun ShutterButton(ui: CamUi, engine: CameraEngine) {
    if (ui.mode == Mode.QR) {
        Text("Point at a code", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        return
    }
    val inner = when {
        ui.mode.isVideo -> Accent
        ui.busy -> Color(0xFFFFB300)
        else -> Color.White
    }
    val shape = if (ui.recording) RoundedCornerShape(14.dp) else CircleShape
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Pause/resume is not available for high-speed (slow-motion) recording
        if (ui.recording && ui.mode != Mode.TIMELAPSE && !(ui.mode == Mode.SLOWMO && ui.slowMo.camera2)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { engine.pauseResume() },
                contentAlignment = Alignment.Center,
            ) { Text(if (ui.paused) "▶" else "⏸", color = Color.White, fontSize = 18.sp) }
        }
        Box(
            Modifier
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .clip(shape)
                .background(inner)
                .clickable { engine.onShutter() },
        )
    }
}

/**
 * iPhone-style focus box: a square at the tapped spot with a sun on a short line beside it. Sliding a finger
 * up or down anywhere on the viewfinder moves the sun and changes the brightness. The box stays (dimmed when
 * idle) until the phone is turned to a new scene or the hold ends.
 */
@Composable
private fun FocusBox(ui: CamUi, active: Boolean) {
    val ring = ui.focusRing ?: return
    val r = ui.ranges
    val evOk = (ui.mode == Mode.PHOTO || ui.mode.isVideo) && r != null && r.evSupported && r.evMax > r.evMin
    val density = LocalDensity.current
    val box = 72.dp
    val line = 120.dp
    val alpha = if (active) 1f else 0.5f
    val half = with(density) { (box / 2).roundToPx() }
    Box(Modifier.offset { IntOffset(ring.x.roundToInt() - half, ring.y.roundToInt() - half) }) {
        Box(Modifier.size(box).border(1.5.dp, Color.White.copy(alpha = alpha), RoundedCornerShape(4.dp)))
        if (evOk && r != null) {
            val frac = (r.evMax - ui.ev).toFloat() / (r.evMax - r.evMin)   // 0 = brightest (top)
            val lineTop = (box - line) / 2
            // thin line beside the box, only while adjusting (the sun alone when idle)
            if (active) {
                Box(
                    Modifier.offset(x = box + 13.dp, y = lineTop).width(1.dp).height(line)
                        .background(Color.White.copy(alpha = 0.7f))
                )
            }
            Text(
                "☀", color = Color.White.copy(alpha = alpha), fontSize = 18.sp,
                modifier = Modifier.offset(x = box + 4.dp, y = lineTop + line * frac - 12.dp),
            )
            if (ui.ev != 0) {
                val v = ui.ev * r.evStep
                Text(
                    (if (v > 0) "+" else "") + "%.1f".format(java.util.Locale.US, v),
                    color = Color.White.copy(alpha = alpha), fontSize = 12.sp, fontFamily = Dot,
                    modifier = Modifier.offset(x = box + 26.dp, y = lineTop + line * frac - 9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(PanelBg).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun LastThumb(uri: Uri?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.loadThumbnail(uri, AndroidSize(160, 160), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF))
            .clickable(enabled = uri != null, onClick = onClick),
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun QrCard(text: String, format: String, engine: CameraEngine, modifier: Modifier) {
    val ctx = LocalContext.current
    val isUrl = text.startsWith("http://", true) || text.startsWith("https://", true)
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEE12181F)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(format, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        // Isi kode = data tak tepercaya: tampilkan apa adanya, jangan buka otomatis.
        Text(text, color = Color.White, fontSize = 15.sp, maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Copy") {
                ctx.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("QR", text))
            }
            if (isUrl) Chip("Open link") {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Chip("Close") { engine.clearQr() }
        }
    }
}

private fun openUri(ctx: Context, uri: Uri) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
