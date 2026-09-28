// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.border
import app.omnicam.R
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.omnicam.camera.ScopeFrame
import app.omnicam.model.GridType
import kotlin.math.ln
import kotlin.math.roundToInt

// Nothing-inspired palette: pure black, white, one red accent.
val Accent = Color(0xFFD71921)
val PanelBg = Color(0xB3000000)

/** Dot-matrix display face (Doto, SIL OFL 1.1), subset to Latin + digits to stay small. */
val Dot = FontFamily(Font(R.font.doto))

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondaryContainer = Color(0xFF2A2A2A),
            background = Color.Black,
            surface = Color(0xFF0E0E0E),
            onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
fun Chip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    // Nothing-style pills: selected = solid white with black text, otherwise a thin outline
    val bg = if (selected) Color.White else Color(0x1FFFFFFF)
    val fg = if (selected) Color.Black else Color.White
    Text(
        text = text,
        color = if (enabled) fg else fg.copy(alpha = 0.4f),
        fontSize = if (compact) 12.sp else 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = if (dot) Dot else null,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Color.White else Color(0x40FFFFFF), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 7.dp),
    )
}

/** Slider dengan label + nilai di atasnya. Nilai 0..1 kecuali valueRange diberikan. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = if (enabled) 0.8f else 0.4f), fontSize = 12.sp)
            Text(valueText, color = if (enabled) Color.White else Color.Gray, fontSize = 13.sp, fontFamily = Dot)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.OFF) return
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.45f)
        val sw = 1.dp.toPx()
        val fr = when (type) {
            GridType.THIRDS -> listOf(1f / 3, 2f / 3)
            GridType.GRID4 -> listOf(0.25f, 0.5f, 0.75f)
            GridType.GOLDEN -> listOf(0.382f, 0.618f)
            GridType.OFF -> emptyList()
        }
        fr.forEach { f ->
            drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), sw)
            drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), sw)
        }
    }
}

/** Zebra + focus peaking dari ScopeAnalyzer, digambar skala penuh di atas preview. */
@Composable
fun ScopeOverlay(frame: ScopeFrame?, modifier: Modifier = Modifier) {
    val bmp = frame?.overlay ?: return
    Canvas(modifier) {
        drawImage(
            image = bmp,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
fun HistogramView(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(PanelBg)) {
        val max = bins.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val lm = ln(1f + max)
        val bw = size.width / bins.size
        bins.forEachIndexed { i, count ->
            val h = ln(1f + count) / lm * size.height
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * bw, size.height - h), Size(bw * 0.85f, h))
        }
    }
}
