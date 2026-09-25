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
import androidx.compose.foundation.layout.Box
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
    val settings by prefs.settings.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        engine.setMic(ok)
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.update { it.copy(geotag = ok) }
    }

    LaunchedEffect(Unit) { engine.start() }
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
                .pointerInput(Unit) { detectTapGestures(onTap = { engine.focusAt(it.x, it.y) }) }
                .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) engine.zoomBy(zoom) } },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            GridOverlay(settings.grid, Modifier.fillMaxSize())
            if (settings.level) LevelOverlay(Modifier.fillMaxSize())
            if (ui.mode == Mode.PRO) ScopeOverlay(frame, Modifier.fillMaxSize())
            FocusRingView(ui, engine)

            if (ui.mode == Mode.PRO && ui.scope.histogram) {
                frame?.let {
                    HistogramView(
                        it.histogram,
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).size(width = 120.dp, height = 44.dp),
                    )
                }
            }
            if (ui.mode == Mode.PRO && readout.iso > 0) {
                Text(
                    "ISO ${readout.iso}  ${app.omnicam.model.formatShutter(readout.expNs)}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .clip(RoundedCornerShape(6.dp)).background(PanelBg).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (ui.countdown > 0) {
                Text(
                    "${ui.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (ui.recording) {
                Text(
                    (if (ui.paused) "⏸ " else "● ") + formatTime(ui.recordedMs),
                    color = Color(0xFFFF5252), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 44.dp)
                        .clip(RoundedCornerShape(50)).background(PanelBg).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // ───── Bar atas ─────
        TopBar(ui, engine, settings.grid.label, onSettings = { showSettings = true }, onInfo = { showInfo = true },
            modifier = Modifier.align(Alignment.TopCenter))

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
                    else -> {}
                }
            }
            if (ui.mode == Mode.PRO) ProPanel(ui, readout, engine)

            if (ui.mode != Mode.QR) LensRow(ui, engine)

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

            // Pemilih mode
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Mode.entries.filter { it != Mode.SLOWMO || ui.slowMoOk || ui.mode == Mode.SLOWMO }.forEach { m ->
                    Text(
                        m.label,
                        color = if (ui.mode == m) Accent else Color.White.copy(alpha = 0.7f),
                        fontWeight = if (ui.mode == m) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(enabled = !ui.recording) { engine.setMode(m) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
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
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
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
                Chip(label, ui.flash != ImageCapture.FLASH_MODE_OFF) { engine.cycleFlash() }
            }
            Chip(if (ui.timer == 0) "⏱ Off" else "⏱ ${ui.timer}s", ui.timer != 0) { engine.cycleTimer() }
            Chip(if (ui.burst == 1) "Burst 1" else "Burst ${ui.burst}", ui.burst != 1) { engine.cycleBurst() }
        } else if (ui.hasFlash) {
            Chip(if (ui.torch) "🔦 On" else "🔦 Off", ui.torch) { engine.toggleTorch() }
        }
        Chip("▦ $gridLabel", false, onClick = onSettings)
        Chip("⚙", false, onClick = onSettings)
        Chip("ⓘ", false, onClick = onInfo)
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
            Chip(if (z < 1f) "%.1f×".format(z) else "${z.roundToInt()}×", active) { engine.setZoom(z) }
        }
        if (lenses.size > 1) {
            Text("│", color = Color.Gray)
            lenses.forEach { l -> Chip(l.label, ui.lensId == l.id) { engine.selectLens(l.id) } }
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
        ui.mode.isVideo -> Color(0xFFFF3B30)
        ui.busy -> Color(0xFFFFB300)
        else -> Color.White
    }
    val shape = if (ui.recording) RoundedCornerShape(14.dp) else CircleShape
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ui.recording) {
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

@Composable
private fun FocusRingView(ui: CamUi, engine: CameraEngine) {
    val ring = ui.focusRing ?: return
    LaunchedEffect(ring.stamp) { delay(900); engine.clearFocusRing() }
    val half = with(LocalDensity.current) { 36.dp.roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(ring.x.roundToInt() - half, ring.y.roundToInt() - half) }
            .size(72.dp)
            .border(2.dp, Accent, CircleShape),
    )
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
