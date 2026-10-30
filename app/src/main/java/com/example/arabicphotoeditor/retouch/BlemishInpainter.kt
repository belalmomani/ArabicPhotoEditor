package com.example.arabicphotoeditor.retouch

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Small-blemish removal without an AI model.
 *
 * The painted area is filled from the surrounding pixels: first an "onion peel" pass copies a distance-weighted
 * average of the nearest known pixels inward from the border, then a few diffusion passes smooth the fill so it
 * blends with its surroundings. Good for spots, dust and small marks; it does not invent texture, so large areas
 * look smeared. Alpha is never changed.
 *
 * Heavy: call from a background dispatcher.
 */
object BlemishInpainter {

    private const val GROW_PX = 2
    private const val DIFFUSION_PASSES = 24

    /** Builds a pixel mask from a stroke given in coordinates normalized to 0..1 of the image. */
    fun buildMask(w: Int, h: Int, points: List<Pair<Float, Float>>, radiusPx: Float): BooleanArray {
        val mask = BooleanArray(w * h)
        val r = max(radiusPx, 2f)
        val step = max(r / 2f, 1f)

        fun stamp(cx: Float, cy: Float) {
            val ri = r.toInt() + 1
            val icx = cx.toInt()
            val icy = cy.toInt()
            for (dy in -ri..ri) {
                val y = icy + dy
                if (y < 0 || y >= h) continue
                for (dx in -ri..ri) {
                    val x = icx + dx
                    if (x < 0 || x >= w) continue
                    if (dx * dx + dy * dy <= r * r) mask[y * w + x] = true
                }
            }
        }

        var prevX = 0f
        var prevY = 0f
        var hasPrev = false
        for (point in points) {
            val x = point.first * w
            val y = point.second * h
            if (!hasPrev) {
                stamp(x, y)
            } else {
                val dx = x - prevX
                val dy = y - prevY
                val n = max(1, ceil(hypot(dx, dy) / step).toInt())
                for (k in 1..n) stamp(prevX + dx * k / n, prevY + dy * k / n)
            }
            prevX = x
            prevY = y
            hasPrev = true
        }
        return mask
    }

    /** Returns a new bitmap with the masked pixels filled in. [source] is not modified. */
    fun inpaint(source: Bitmap, mask: BooleanArray): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)

        val grown = grow(mask, w, h, GROW_PX)
        var count = 0
        for (m in grown) if (m) count++

        if (count > 0 && count < w * h) {
            val known = BooleanArray(w * h) { !grown[it] }
            val masked = IntArray(count)
            var k = 0
            for (i in grown.indices) if (grown[i]) masked[k++] = i

            // Onion peel: fill from the border inward.
            var todo = masked
            while (todo.isNotEmpty()) {
                val ready = IntArray(todo.size)
                val rest = IntArray(todo.size)
                var nReady = 0
                var nRest = 0
                for (i in todo) {
                    if (hasKnownNeighbor(known, i, w, h)) ready[nReady++] = i else rest[nRest++] = i
                }
                if (nReady == 0) break
                val colors = IntArray(nReady) { averageKnown(px, known, ready[it], w, h) }
                for (j in 0 until nReady) {
                    val i = ready[j]
                    px[i] = (px[i] and ALPHA_MASK) or (colors[j] and RGB_MASK)
                    known[i] = true
                }
                todo = rest.copyOf(nRest)
            }

            // Diffusion: smooth the fill into its surroundings.
            repeat(DIFFUSION_PASSES) {
                for (i in masked) {
                    val x = i % w
                    val y = i / w
                    val l = px[y * w + max(x - 1, 0)]
                    val r = px[y * w + min(x + 1, w - 1)]
                    val u = px[max(y - 1, 0) * w + x]
                    val d = px[min(y + 1, h - 1) * w + x]
                    val red = (channel(l, 16) + channel(r, 16) + channel(u, 16) + channel(d, 16)) / 4
                    val green = (channel(l, 8) + channel(r, 8) + channel(u, 8) + channel(d, 8)) / 4
                    val blue = (channel(l, 0) + channel(r, 0) + channel(u, 0) + channel(d, 0)) / 4
                    px[i] = (px[i] and ALPHA_MASK) or (red shl 16) or (green shl 8) or blue
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(source.hasAlpha())
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private const val ALPHA_MASK = -0x1000000 // 0xFF000000
    private const val RGB_MASK = 0x00FFFFFF

    private fun channel(color: Int, shift: Int) = (color shr shift) and 0xFF

    private fun hasKnownNeighbor(known: BooleanArray, i: Int, w: Int, h: Int): Boolean {
        val x = i % w
        val y = i / w
        for (dy in -1..1) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            for (dx in -1..1) {
                val xx = x + dx
                if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                if (known[yy * w + xx]) return true
            }
        }
        return false
    }

    private fun averageKnown(px: IntArray, known: BooleanArray, i: Int, w: Int, h: Int): Int {
        val x = i % w
        val y = i / w
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var sw = 0f
        for (dy in -2..2) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            for (dx in -2..2) {
                val xx = x + dx
                if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                val j = yy * w + xx
                if (!known[j]) continue
                val wt = 1f / (dx * dx + dy * dy)
                val c = px[j]
                sr += channel(c, 16) * wt
                sg += channel(c, 8) * wt
                sb += channel(c, 0) * wt
                sw += wt
            }
        }
        if (sw == 0f) return px[i]
        val r = (sr / sw + 0.5f).toInt().coerceIn(0, 255)
        val g = (sg / sw + 0.5f).toInt().coerceIn(0, 255)
        val b = (sb / sw + 0.5f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** Dilates the mask by [radius] pixels, scanning only around the painted area. */
    private fun grow(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        var x0 = w
        var x1 = -1
        var y0 = h
        var y1 = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % w
            val y = i / w
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 < 0) return mask

        val out = mask.copyOf()
        for (y in max(0, y0 - radius)..min(h - 1, y1 + radius)) {
            for (x in max(0, x0 - radius)..min(w - 1, x1 + radius)) {
                if (out[y * w + x]) continue
                var hit = false
                loop@ for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (mask[yy * w + xx]) { hit = true; break@loop }
                    }
                }
                if (hit) out[y * w + x] = true
            }
        }
        return out
    }
}
