// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Built-in HDR that does not depend on vendor extensions.
 *
 * Input: 2+ JPEG frames of the same scene taken at different exposures (hand-held).
 * 1. Alignment: median threshold bitmaps (Ward 2003) on an image pyramid. Robust to exposure
 *    differences; corrects global translation from hand shake (up to ~±60 px).
 * 2. De-ghosting: each extra frame is mapped to the base frame's brightness with an intensity
 *    mapping function (cumulative histogram matching). Where the mapped frame disagrees with a
 *    well-exposed base pixel, something moved, so that frame gets zero weight there and the base
 *    frame is used instead.
 * 3. Merge: exposure fusion (Mertens et al. 2007). Per-pixel weights from contrast, saturation and
 *    well-exposedness are computed at 1/8 resolution, smoothed, and applied at full resolution.
 *    Smoothing the weights avoids seams without the memory cost of full Laplacian pyramids.
 *
 * Memory: works row by row on the source bitmaps; extra buffers are one grayscale byte per pixel
 * per frame plus small low-resolution weight maps.
 */
object HdrProcessor {

    private const val WEIGHT_SCALE = 8

    fun fuse(frames: List<Bitmap>, base: Int = 0): Bitmap {
        require(frames.size >= 2) { "HDR needs at least two frames" }
        val w = frames[0].width
        val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) { "HDR frames differ in size" }

        val grays = frames.map { toGray(it) }
        val offsets = Array(frames.size) { IntArray(2) }
        for (k in frames.indices) {
            if (k == base) continue
            val o = alignMtb(grays[base], grays[k], w, h)
            offsets[k][0] = o.first
            offsets[k][1] = o.second
        }

        val lw = max(1, w / WEIGHT_SCALE)
        val lh = max(1, h / WEIGHT_SCALE)
        val weights = computeWeights(frames, offsets, w, h, lw, lh, base)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = Array(frames.size) { IntArray(w) }
        val outRow = IntArray(w)
        val n = frames.size
        val wk = FloatArray(n)
        for (y in 0 until h) {
            for (k in 0 until n) {
                val sy = (y + offsets[k][1]).coerceIn(0, h - 1)
                frames[k].getPixels(rows[k], 0, w, 0, sy, w, 1)
            }
            val fy = (y + 0.5f) / WEIGHT_SCALE - 0.5f
            val y0 = fy.toInt().coerceIn(0, lh - 1)
            val y1 = min(y0 + 1, lh - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) / WEIGHT_SCALE - 0.5f
                val x0 = fx.toInt().coerceIn(0, lw - 1)
                val x1 = min(x0 + 1, lw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                var sum = 0f
                for (k in 0 until n) {
                    val m = weights[k]
                    val a = m[y0 * lw + x0] + (m[y0 * lw + x1] - m[y0 * lw + x0]) * tx
                    val b = m[y1 * lw + x0] + (m[y1 * lw + x1] - m[y1 * lw + x0]) * tx
                    val v = a + (b - a) * ty
                    wk[k] = v
                    sum += v
                }
                var r = 0f
                var g = 0f
                var bl = 0f
                val inv = if (sum > 1e-6f) 1f / sum else 0f
                for (k in 0 until n) {
                    val sx = (x + offsets[k][0]).coerceIn(0, w - 1)
                    val p = rows[k][sx]
                    val f = if (inv > 0f) wk[k] * inv else if (k == base) 1f else 0f
                    r += ((p shr 16) and 0xFF) * f
                    g += ((p shr 8) and 0xFF) * f
                    bl += (p and 0xFF) * f
                }
                outRow[x] = (0xFF shl 24) or
                    (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(bl)
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun clamp255(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    private fun toGray(b: Bitmap): ByteArray {
        val w = b.width
        val h = b.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            val o = y * w
            for (x in 0 until w) {
                val p = row[x]
                val v = (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8
                out[o + x] = v.toByte()
            }
        }
        return out
    }

    // ───────────────────────── alignment (MTB) ─────────────────────────

    private class Level(val px: ByteArray, val w: Int, val h: Int)

    private fun pyramid(g: ByteArray, w: Int, h: Int, levels: Int): List<Level> {
        val list = ArrayList<Level>(levels)
        list += Level(g, w, h)
        var cur = list[0]
        for (i in 1 until levels) {
            val nw = cur.w / 2
            val nh = cur.h / 2
            if (nw < 32 || nh < 32) break
            val np = ByteArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val i0 = (2 * y) * cur.w + 2 * x
                val s = (cur.px[i0].toInt() and 0xFF) + (cur.px[i0 + 1].toInt() and 0xFF) +
                    (cur.px[i0 + cur.w].toInt() and 0xFF) + (cur.px[i0 + cur.w + 1].toInt() and 0xFF)
                np[y * nw + x] = (s shr 2).toByte()
            }
            cur = Level(np, nw, nh)
            list += cur
        }
        return list
    }

    private fun median(px: ByteArray): Int {
        val hist = IntArray(256)
        for (b in px) hist[b.toInt() and 0xFF]++
        val half = px.size / 2
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) return i
        }
        return 128
    }

    /** Returns (dx, dy) such that frame pixel (x+dx, y+dy) matches base pixel (x, y). */
    private fun alignMtb(base: ByteArray, frame: ByteArray, w: Int, h: Int): Pair<Int, Int> {
        val pa = pyramid(base, w, h, 7)
        val pb = pyramid(frame, w, h, 7)
        val levels = min(pa.size, pb.size)
        var dx = 0
        var dy = 0
        for (l in levels - 1 downTo 0) {
            dx *= 2
            dy *= 2
            val a = pa[l]
            val b = pb[l]
            val ma = median(a.px)
            val mb = median(b.px)
            var best = Long.MAX_VALUE
            var bx = dx
            var by = dy
            for (sy in -1..1) for (sx in -1..1) {
                val cx = dx + sx
                val cy = dy + sy
                val err = mtbError(a, ma, b, mb, cx, cy)
                if (err < best) {
                    best = err
                    bx = cx
                    by = cy
                }
            }
            dx = bx
            dy = by
        }
        return dx to dy
    }

    private fun mtbError(a: Level, ma: Int, b: Level, mb: Int, dx: Int, dy: Int): Long {
        val w = a.w
        val h = a.h
        val x0 = max(0, -dx)
        val x1 = min(w, b.w - dx)
        val y0 = max(0, -dy)
        val y1 = min(h, b.h - dy)
        var err = 0L
        val step = if (w * h > 2_000_000) 2 else 1
        var y = y0
        while (y < y1) {
            val ra = y * w
            val rb = (y + dy) * b.w + dx
            var x = x0
            while (x < x1) {
                val va = a.px[ra + x].toInt() and 0xFF
                val vb = b.px[rb + x].toInt() and 0xFF
                // Exclude pixels close to the median (noise-sensitive)
                if (abs(va - ma) > 4 && abs(vb - mb) > 4) {
                    if ((va > ma) != (vb > mb)) err++
                }
                x += step
            }
            y += step
        }
        return err
    }

    // ───────────────────────── fusion weights ─────────────────────────

    private fun computeWeights(
        frames: List<Bitmap>, offsets: Array<IntArray>, w: Int, h: Int, lw: Int, lh: Int, base: Int,
    ): Array<FloatArray> {
        val n = frames.size
        val maps = Array(n) { FloatArray(lw * lh) }
        val grays = Array(n) { FloatArray(lw * lh) }
        val row = IntArray(w)
        val sigma2 = 2f * 0.2f * 0.2f
        for (k in 0 until n) {
            val gray = grays[k]
            val sat = FloatArray(lw * lh)
            val wex = FloatArray(lw * lh)
            for (ly in 0 until lh) {
                val sy = (ly * WEIGHT_SCALE + WEIGHT_SCALE / 2 + offsets[k][1]).coerceIn(0, h - 1)
                frames[k].getPixels(row, 0, w, 0, sy, w, 1)
                for (lx in 0 until lw) {
                    val sx = (lx * WEIGHT_SCALE + WEIGHT_SCALE / 2 + offsets[k][0]).coerceIn(0, w - 1)
                    val p = row[sx]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    val mu = (r + g + b) / 3f
                    val i = ly * lw + lx
                    gray[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    sat[i] = sqrt(((r - mu) * (r - mu) + (g - mu) * (g - mu) + (b - mu) * (b - mu)) / 3f)
                    wex[i] = exp(-(r - 0.5f) * (r - 0.5f) / sigma2) *
                        exp(-(g - 0.5f) * (g - 0.5f) / sigma2) *
                        exp(-(b - 0.5f) * (b - 0.5f) / sigma2)
                }
            }
            val m = maps[k]
            for (ly in 0 until lh) for (lx in 0 until lw) {
                val i = ly * lw + lx
                val c = gray[i]
                val l = if (lx > 0) gray[i - 1] else c
                val rr = if (lx < lw - 1) gray[i + 1] else c
                val u = if (ly > 0) gray[i - lw] else c
                val d = if (ly < lh - 1) gray[i + lw] else c
                val contrast = abs(l + rr + u + d - 4f * c)
                m[i] = (contrast + 1e-3f) * (sat[i] + 1e-3f) * (wex[i] + 1e-3f)
            }
        }
        removeGhosts(maps, grays, lw, lh, base)
        normalize(maps)
        val radius = max(2, min(lw, lh) / 60)
        for (m in maps) repeat(3) { boxBlur(m, lw, lh, radius) }
        normalize(maps)
        return maps
    }

    /**
     * Zeroes the weight of a non-base frame wherever its content disagrees with the base frame after
     * brightness mapping (a moving person, car, branch...). Only pixels that are well exposed in the
     * base frame can be judged; in clipped areas all frames keep their weights.
     */
    private fun removeGhosts(maps: Array<FloatArray>, grays: Array<FloatArray>, lw: Int, lh: Int, base: Int) {
        val gb = grays[base]
        val size = lw * lh
        for (k in maps.indices) {
            if (k == base) continue
            val gk = grays[k]
            val imf = intensityMapping(gk, gb)
            val ghost = BooleanArray(size)
            for (i in 0 until size) {
                val b = gb[i]
                val f = gk[i]
                if (b < 0.04f || b > 0.96f) continue          // base clipped: cannot judge
                if (f < 0.02f || f > 0.98f) continue          // frame clipped: already low weight
                val predicted = imf[(f * 255f + 0.5f).toInt().coerceIn(0, 255)]
                // Tolerance grows slightly with brightness (noise and JPEG error)
                if (abs(predicted - b) > 0.07f + 0.08f * b) ghost[i] = true
            }
            // Grow the mask so object edges are covered as well
            val grown = BooleanArray(size)
            val r = 3
            for (y in 0 until lh) for (x in 0 until lw) {
                if (!ghost[y * lw + x]) continue
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy < 0 || yy >= lh) continue
                    for (dx in -r..r) {
                        val xx = x + dx
                        if (xx in 0 until lw) grown[yy * lw + xx] = true
                    }
                }
            }
            val m = maps[k]
            for (i in 0 until size) if (grown[i]) m[i] = 0f
        }
    }

    /** Maps frame brightness to base brightness by matching cumulative histograms (256 bins). */
    private fun intensityMapping(frame: FloatArray, base: FloatArray): FloatArray {
        val hf = IntArray(256)
        val hb = IntArray(256)
        for (v in frame) hf[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]++
        for (v in base) hb[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]++
        val total = frame.size.toFloat()
        val cb = FloatArray(256)
        var acc = 0
        for (i in 0 until 256) { acc += hb[i]; cb[i] = acc / total }
        val out = FloatArray(256)
        acc = 0
        var j = 0
        for (i in 0 until 256) {
            acc += hf[i]
            // Use the middle of this bin's cumulative range so flat regions map sensibly
            val target = (acc - hf[i] / 2f) / total
            while (j < 255 && cb[j] < target) j++
            out[i] = j / 255f
        }
        return out
    }

    private fun normalize(maps: Array<FloatArray>) {
        val size = maps[0].size
        for (i in 0 until size) {
            var s = 0f
            for (m in maps) s += m[i]
            if (s > 1e-12f) for (m in maps) m[i] /= s
            else for (m in maps) m[i] = 1f / maps.size
        }
    }

    private fun boxBlur(m: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(m.size)
        val win = 2 * r + 1
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += m[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / win
                acc += m[y * w + (x + r + 1).coerceIn(0, w - 1)] - m[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                m[y * w + x] = acc / win
                acc += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
    }
}
