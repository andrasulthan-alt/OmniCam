// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Sun = Color(0xFFFFCC00)

/**
 * iPhone-style brightness control: a vertical bar with a sun thumb. Drag up = brighter, down = darker.
 * Double-tap resets to 0. Maps onto exposure compensation steps reported by the camera
 * (e.g. -12..+12 steps of 1/6 EV = -2..+2 EV).
 */
@Composable
fun ExposureBar(
    ev: Int,
    evMin: Int,
    evMax: Int,
    evStep: Float,
    onChange: (Int) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackHeight = 200.dp
    val density = LocalDensity.current
    val trackPx = with(density) { trackHeight.toPx() }
    val steps = (evMax - evMin).coerceAtLeast(1)
    val pxPerStep = trackPx / steps
    val currentEv by rememberUpdatedState(ev)
    var carry by remember { mutableFloatStateOf(0f) }

    Column(
        modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val value = ev * evStep
        Text(
            if (ev == 0) "0" else (if (value > 0) "+" else "") + "%.1f".format(java.util.Locale.US, value),
            color = Sun, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PanelBg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Box(
            Modifier
                .width(44.dp)
                .height(trackHeight)
                .pointerInput(evMin, evMax, pxPerStep) {
                    detectVerticalDragGestures(
                        onDragStart = { carry = 0f; onInteract() },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            onInteract()
                            carry += -dy // up = brighter
                            val delta = (carry / pxPerStep).toInt()
                            if (delta != 0) {
                                carry -= delta * pxPerStep
                                onChange((currentEv + delta).coerceIn(evMin, evMax))
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onInteract(); onChange(0) }, onTap = { onInteract() })
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            // track
            Box(Modifier.width(2.dp).height(trackHeight).background(Sun.copy(alpha = 0.55f)))
            // zero mark
            val zeroFrac = (evMax - 0).toFloat() / steps
            Box(
                Modifier.offset(y = trackHeight * zeroFrac - 1.dp).width(14.dp).height(2.dp)
                    .background(Color.White.copy(alpha = 0.8f))
            )
            // sun thumb
            val frac = (evMax - ev).toFloat() / steps
            Box(
                Modifier.offset(y = trackHeight * frac - 14.dp).size(28.dp).clip(CircleShape)
                    .background(PanelBg),
                contentAlignment = Alignment.Center,
            ) { Text("☀", color = Sun, fontSize = 16.sp) }
        }
    }
}
