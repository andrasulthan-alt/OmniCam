// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.omnicam.camera.CameraInspector
import app.omnicam.camera.CameraReport
import app.omnicam.model.GridType
import app.omnicam.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onDismiss: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onGeotag: (Boolean) -> Unit,
    onInfo: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Text("Grid", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GridType.entries.forEach { g ->
                    Chip(g.label, settings.grid == g) { onChange { it.copy(grid = g) } }
                }
            }

            SwitchRow(
                "Strip private metadata",
                "Remove GPS location and device/lens identifiers from JPEG EXIF (does not apply to Ultra HDR & DNG).",
                settings.stripMetadata,
            ) { on -> onChange { it.copy(stripMetadata = on) } }
            SwitchRow(
                "Save location (geotag)",
                "Adds your last known location to photos. Off by default.",
                settings.geotag,
                onGeotag,
            )
            SwitchRow("Horizon level indicator", "Tilt line from the accelerometer; turns green when level.", settings.level) { on -> onChange { it.copy(level = on) } }
            SwitchRow("Shutter sound", "Sound when taking photos or recording.", settings.shutterSound) { on -> onChange { it.copy(shutterSound = on) } }
            SwitchRow("Volume keys = shutter", "Press a volume key to take a photo or record.", settings.volumeShutter) { on -> onChange { it.copy(volumeShutter = on) } }
            SwitchRow("Mirror front camera photos", "Selfies are saved flipped, like the preview.", settings.mirrorFront) { on -> onChange { it.copy(mirrorFront = on) } }
            SwitchRow("Prioritize quality", "Off = prioritize speed (minimum latency).", settings.qualityFirst) { on -> onChange { it.copy(qualityFirst = on) } }

            Chip("ⓘ  Camera info & device capabilities", onClick = onInfo)
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun InfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reports by remember { mutableStateOf<List<CameraReport>?>(null) }
    LaunchedEffect(Unit) { reports = withContext(Dispatchers.IO) { CameraInspector.inspect(ctx) } }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("← Back", onClick = onBack)
            Text("Camera info", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            reports?.let { rl ->
                Box(Modifier.weight(1f))
                Chip("Copy report") {
                    val head = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} " +
                        "(API ${android.os.Build.VERSION.SDK_INT}) | ${android.os.Build.DISPLAY}"
                    val body = rl.joinToString("\n\n") { r ->
                        r.title + "\n" + r.lines.joinToString("\n") { (k, v) -> "$k: $v" }
                    }
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("OmniCam camera report", head + "\n\n" + body))
                }
            }
        }
        val list = reports
        if (list == null) {
            Text("Loading…", color = Color.Gray, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { rep ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF12181F)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(rep.title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        rep.lines.forEach { (k, v) ->
                            Column {
                                Text(k, color = Color.Gray, fontSize = 11.sp)
                                Text(v, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
