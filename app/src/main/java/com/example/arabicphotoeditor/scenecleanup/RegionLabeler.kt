package com.example.arabicphotoeditor.scenecleanup

import kotlin.math.max

/**
 * Splits a boolean mask into labelled connected regions (8-connected flood fill).
 *
 * Returns a list of [LabelledRegion], sorted by area descending so the caller can remove the
 * most prominent objects first (better source pixels available for later fills).
 */
object RegionLabeler {

    data class LabelledRegion(
        /** Indices into the flat [w]×[h] pixel array that belong to this region. */
        val indices: IntArray,
        /** Bounding box in pixel coords. */
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val w: Int, val h: Int,
    ) {
        val area: Int get() = indices.size
        /** Fraction of the total image area covered by this region. */
        fun areaFrac(): Float = area.toFloat() / (w * h)

        /** Returns a full-image boolean mask with only this region's pixels set. */
        fun toBooleanArray(): BooleanArray {
            val out = BooleanArray(w * h)
            for (i in indices) out[i] = true
            return out
        }
    }

    /**
     * Labels all connected regions in [mask] (true = selected pixel) on a [w]×[h] grid.
     * Regions smaller than [minArea] pixels are merged into the nearest region or dropped.
     */
    fun label(mask: BooleanArray, w: Int, h: Int, minArea: Int = 4): List<LabelledRegion> {
        val labels = IntArray(w * h) { -1 }
        val regions = ArrayList<ArrayList<Int>>()

        for (start in mask.indices) {
            if (!mask[start] || labels[start] >= 0) continue
            val id = regions.size
            val pixels = ArrayList<Int>()
            regions.add(pixels)

            // 8-connected iterative flood fill.
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            labels[start] = id
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                pixels.add(idx)
                val x = idx % w; val y = idx / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && labels[ni] < 0) { labels[ni] = id; stack.addLast(ni) }
                }
            }
        }

        return regions
            .filter { it.size >= minArea }
            .map { pixels ->
                var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
                for (idx in pixels) {
                    val x = idx % w; val y = idx / w
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                LabelledRegion(pixels.toIntArray(), x0, y0, x1, y1, w, h)
            }
            .sortedByDescending { it.area }
    }
}
