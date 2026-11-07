package com.example.arabicphotoeditor.scenecleanup

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The user's selection of what to remove, as an 8-bit mask (0 = untouched, 255 = selected). It lives at a working
 * resolution of at most [MAX_SIDE] pixels on the long side, which keeps undo snapshots and the on-screen overlay
 * cheap; [toFullResolution] scales it up to the real photo when the removal runs. Pure Kotlin.
 */
class SelectionMask(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {

    init {
        require(width > 0 && height > 0 && data.size == width * height)
    }

    fun copy() = SelectionMask(width, height, data.copyOf())

    fun isEmpty(): Boolean {
        for (b in data) if (b.toInt() != 0) return false
        return true
    }

    /** Fraction of the image that is selected, 0..1. */
    fun coverage(): Float {
        var n = 0
        for (b in data) if (b.toInt() != 0) n++
        return n.toFloat() / data.size
    }

    fun clear() = data.fill(0)

    /** Selects ([add] = true) or deselects a round dab. Coordinates and [radius] are in mask pixels. */
    fun stamp(cx: Float, cy: Float, radius: Float, add: Boolean) {
        val r = max(radius, 0.75f)
        val value: Byte = if (add) 255.toByte() else 0
        val y0 = max(0, (cy - r).toInt())
        val y1 = min(height - 1, ceil(cy + r).toInt())
        val x0 = max(0, (cx - r).toInt())
        val x1 = min(width - 1, ceil(cx + r).toInt())
        val r2 = r * r
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy <= r2) data[y * width + x] = value
            }
        }
    }

    /** A continuous brush stroke through [points] (mask pixels); gaps between samples are filled. */
    fun stroke(points: List<Pair<Float, Float>>, radius: Float, add: Boolean) {
        if (points.isEmpty()) return
        val step = max(radius / 3f, 0.5f)
        var prev = points[0]
        stamp(prev.first, prev.second, radius, add)
        for (i in 1 until points.size) {
            val p = points[i]
            val dist = hypot(p.first - prev.first, p.second - prev.second)
            val n = max(1, ceil(dist / step).toInt())
            for (k in 1..n) {
                stamp(prev.first + (p.first - prev.first) * k / n, prev.second + (p.second - prev.second) * k / n, radius, add)
            }
            prev = p
        }
    }

    /** Adds every pixel of [region] (same size) to the selection, or removes it. */
    fun apply(region: BooleanArray, add: Boolean) {
        require(region.size == data.size)
        val value: Byte = if (add) 255.toByte() else 0
        for (i in region.indices) if (region[i]) data[i] = value
    }

    /**
     * The selection at the photo's resolution as a boolean mask, with smooth edges (bilinear sampling of the
     * working mask, thresholded at one half) and optionally grown by [growPx] so the removed object's edge
     * pixels, shadows and halos are not left behind.
     */
    fun toFullResolution(fullW: Int, fullH: Int, growPx: Int = 0): BooleanArray {
        val out = BooleanArray(fullW * fullH)
        val sx = width.toFloat() / fullW
        val sy = height.toFloat() / fullH
        for (y in 0 until fullH) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, height - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, height - 1)
            val ty = fy - y0
            for (x in 0 until fullW) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, width - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, width - 1)
                val tx = fx - x0
                val a = (data[y0 * width + x0].toInt() and 0xFF)
                val b = (data[y0 * width + x1].toInt() and 0xFF)
                val c = (data[y1 * width + x0].toInt() and 0xFF)
                val d = (data[y1 * width + x1].toInt() and 0xFF)
                val v = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
                out[y * fullW + x] = v >= 127.5f
            }
        }
        return if (growPx > 0) grow(out, fullW, fullH, growPx) else out
    }

    companion object {
        const val MAX_SIDE = 1024

        /** A new empty mask for a photo of the given size, scaled to at most [MAX_SIDE]. */
        fun forPhoto(photoW: Int, photoH: Int): SelectionMask {
            val scale = min(1f, MAX_SIDE.toFloat() / max(photoW, photoH))
            return SelectionMask(max(1, (photoW * scale).toInt()), max(1, (photoH * scale).toInt()))
        }

        /** Separable box dilation by [radius] pixels (a square structuring element). */
        fun grow(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
            val horizontal = BooleanArray(mask.size)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (!mask[row + x]) continue
                    for (k in max(0, x - radius)..min(w - 1, x + radius)) horizontal[row + k] = true
                }
            }
            val out = BooleanArray(mask.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (!horizontal[y * w + x]) continue
                    for (k in max(0, y - radius)..min(h - 1, y + radius)) out[k * w + x] = true
                }
            }
            return out
        }
    }
}
