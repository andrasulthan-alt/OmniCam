// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.ui

import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.extensions.ExtensionMode
import app.omnicam.camera.CameraEngine
import app.omnicam.model.CamUi
import app.omnicam.model.Readout
import app.omnicam.model.awbLabel
import app.omnicam.model.extensionLabel
import app.omnicam.model.formatAperture
import app.omnicam.model.formatShutter
import app.omnicam.model.qualityLabel
import kotlin.math.roundToInt

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** PHOTO mode row: built-in HDR, vendor extensions (if any), Ultra HDR. */
@Composable
fun ExtensionRow(ui: CamUi, engine: CameraEngine) {
    val r = ui.ranges
    val builtInHdr = r != null && r.evSupported && r.evMax > r.evMin
    if (!builtInHdr && ui.extensions.isEmpty() && ui.formats.size <= 1) return
    ChipRow {
        Chip("Std", ui.extension == ExtensionMode.NONE && !ui.hdr, enabled = !ui.busy) {
            engine.setHdr(false)
            if (ui.extension != ExtensionMode.NONE) engine.setExtension(ExtensionMode.NONE)
        }
        if (builtInHdr) {
            Chip("HDR", ui.hdr, enabled = !ui.busy) { engine.setHdr(!ui.hdr) }
        }
        ui.extensions.forEach { m ->
            // Vendor HDR is labelled separately so it is not confused with the built-in HDR
            val label = if (m == ExtensionMode.HDR) "HDR (vendor)" else extensionLabel(m)
            Chip(label, ui.extension == m, enabled = !ui.busy) { engine.setExtension(m) }
        }
        ui.formats.filter { it.name == "ULTRA_HDR" }.forEach { f ->
            Chip(f.label, ui.format == f, enabled = !ui.hdr) {
                engine.setFormat(if (ui.format == f) app.omnicam.model.PhotoFormat.JPEG else f)
            }
        }
    }
}

@Composable
fun ProPanel(ui: CamUi, readout: Readout, engine: CameraEngine) {
    val r = ui.ranges
    val m = ui.manual
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Format berkas
        ChipRow {
            ui.formats.forEach { f -> Chip(f.label, ui.format == f) { engine.setFormat(f) } }
        }
        // Alat bantu
        ChipRow {
            Chip("Histogram", ui.scope.histogram) { engine.setScope(ui.scope.copy(histogram = !ui.scope.histogram)) }
            Chip("Zebra", ui.scope.zebra) { engine.setScope(ui.scope.copy(zebra = !ui.scope.zebra)) }
            Chip("Focus peaking", ui.scope.peaking) { engine.setScope(ui.scope.copy(peaking = !ui.scope.peaking)) }
        }

        if (r == null) return@Column

        // Eksposur
        ChipRow {
            Chip("Auto exposure", !m.exposureManual) { engine.setExposureManual(false) }
            Chip("Manual exposure", m.exposureManual, enabled = r.manualSensor) { engine.setExposureManual(true) }
        }
        if (m.exposureManual && r.manualSensor) {
            LabeledSlider("ISO", "${m.iso}", r.isoFrac(m.iso)) { engine.setIso(r.isoAt(it)) }
            LabeledSlider("Shutter", formatShutter(m.exposureNs), r.expFrac(m.exposureNs)) { engine.setExposureNs(r.expAt(it)) }
            if (r.variableAperture) {
                ChipRow {
                    Text("Aperture", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    r.apertures.forEach { a -> Chip(formatAperture(a), m.aperture == a) { engine.setAperture(a) } }
                }
            }
        } else if (r.evSupported && r.evMax > r.evMin) {
            LabeledSlider(
                label = "EV compensation",
                valueText = "%+.1f".format(m.evIndex * r.evStep),
                value = m.evIndex.toFloat(),
                valueRange = r.evMin.toFloat()..r.evMax.toFloat(),
                steps = (r.evMax - r.evMin - 1).coerceAtLeast(0),
            ) { engine.setEv(it.roundToInt()) }
        }
        if (!r.manualSensor) {
            Text("This camera does not expose manual sensor controls (MANUAL_SENSOR).", color = Color.Gray, fontSize = 11.sp)
        }

        // Fokus
        ChipRow {
            Chip("Autofocus", !m.focusManual) { engine.setFocusManual(false) }
            Chip("Manual focus", m.focusManual, enabled = r.manualFocus) { engine.setFocusManual(true) }
        }
        if (m.focusManual && r.manualFocus) {
            val d = m.focusDiopter
            LabeledSlider(
                label = "Focus distance",
                valueText = if (d < 0.05f) "∞" else "${(100f / d).roundToInt()} cm",
                value = d / r.minFocusDiopter,
            ) { engine.setFocusDiopter(it * r.minFocusDiopter) }
        }

        // White balance
        val awbs = r.awbModes.filter { it != CaptureRequest.CONTROL_AWB_MODE_OFF }
        if (awbs.size > 1) {
            ChipRow {
                awbs.forEach { a -> Chip(awbLabel(a), m.awbMode == a) { engine.setAwb(a) } }
            }
        }
        if (!m.exposureManual && readout.iso > 0) {
            val ap = if (r.variableAperture && readout.aperture > 0f) " · ${formatAperture(readout.aperture)}" else ""
            Text(
                "Auto: ISO ${readout.iso} · ${formatShutter(readout.expNs)}$ap",
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun VideoPanel(ui: CamUi, engine: CameraEngine, onMicToggle: (Boolean) -> Unit) {
    val v = ui.video
    ChipRow {
        v.qualities.forEach { q -> Chip(qualityLabel(q), v.quality == q, enabled = !ui.recording) { engine.setVideoQuality(q) } }
    }
    ChipRow {
        Chip("30 fps", v.fps == 30, enabled = !ui.recording) { engine.setVideoFps(30) }
        Chip("60 fps", v.fps == 60, enabled = v.fps60Ok && !ui.recording) { engine.setVideoFps(60) }
        Chip("HDR10 HLG", v.hdr, enabled = v.hdrOk && !ui.recording) { engine.setVideoHdr(!v.hdr) }
        Chip("Stabilize", v.stab && v.stabOk, enabled = v.stabOk && !ui.recording) { engine.setVideoStab(!v.stab) }
        Chip(if (v.mic) "Mic on" else "Mic off", v.mic, enabled = !ui.recording) { onMicToggle(!v.mic) }
    }
}

/** SLO-MO mode: resolution and capture frame rate. Playback is 30 fps, so 240 fps = 8x slower. */
@Composable
fun SlowMoPanel(ui: CamUi, engine: CameraEngine) {
    val sm = ui.slowMo
    if (sm.qualities.size > 1) {
        ChipRow {
            sm.qualities.forEach { q -> Chip(qualityLabel(q), sm.quality == q, enabled = !ui.recording) { engine.setSlowMoQuality(q) } }
        }
    }
    if (sm.rates.isNotEmpty()) {
        ChipRow {
            sm.rates.forEach { r -> Chip("$r fps", sm.fps == r, enabled = !ui.recording) { engine.setSlowMoFps(r) } }
        }
    }
    if (sm.fps > 0) {
        Text(
            "Plays back ${sm.fps / 30}× slower · no audio",
            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
        )
    }
}
