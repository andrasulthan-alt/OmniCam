// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Indikator level horizon. Aplikasi terkunci potret, jadi cukup akselerometer:
 * roll = atan2(x, y). Garis diputar sebesar roll (Compose: positif = searah jarum jam),
 * sehingga tetap sejajar horizon nyata. Disembunyikan saat ponsel hampir datar atau miring > 30°.
 */
@Composable
fun LevelOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    var valid by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smooth = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]
                val flat = hypot(x, y) < 3f            // ponsel menghadap atas/bawah
                val deg = Math.toDegrees(atan2(x, y).toDouble()).toFloat()
                valid = !flat && abs(deg) <= 30f
                smooth += (deg - smooth) * 0.2f          // low-pass
                if (abs(smooth - roll) > 0.15f) roll = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }

    if (!valid) return
    Canvas(modifier) {
        val level = abs(roll) < 1f
        val color = if (level) Color(0xFF4CD964) else Color(0xFFFFCC00)
        val cx = size.width / 2f; val cy = size.height / 2f
        val half = size.width * 0.14f
        val gap = 14.dp.toPx()
        val sw = 2.dp.toPx()
        rotate(roll, Offset(cx, cy)) {
            drawLine(color, Offset(cx - gap - half, cy), Offset(cx - gap, cy), sw)
            drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + half, cy), sw)
        }
        // referensi tetap (sejajar layar)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(cx - gap, cy), Offset(cx + gap, cy), 1.dp.toPx())
    }
}
