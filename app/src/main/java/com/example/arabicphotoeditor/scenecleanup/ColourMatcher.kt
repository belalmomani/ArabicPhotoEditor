package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Corrects colour and luminance mismatches at the seam between a filled region and the surrounding
 * known pixels. Applied after [PatchMatchEngine] fills a region.
 *
 * Method
 * ------
 * 1. Sample the known pixels within a [SAMPLE_RING] pixel border just outside the mask boundary.
 * 2. Sample the filled pixels within [SAMPLE_RING] pixels just inside the mask boundary.
 * 3. Compute mean and standard deviation of R, G, B for both rings.
 * 4. Apply a linear colour transfer: shift + scale each channel of the filled pixels so their
 *    mean and std match the surrounding ring.
 * 5. Apply this correction with a feathered weight that is 1 at the mask centre and 0 at the
 *    mask boundary, so the correction blends smoothly out.
 *
 * This is a purely statistical operation: it adjusts tone and colour cast without touching texture.
 * It makes no semantic assumption about the scene.
 */
object ColourMatcher {

    private const val SAMPLE_RING = 8   // pixels to sample around the boundary
    private const val MIN_SAMPLES = 10  // skip correction if too few samples

    /**
     * Returns a new bitmap with the colour/luminance of the [mask]-covered region corrected to
     * match its surroundings. Pixels outside [mask] are unchanged.
     * [mask] must be the same size as [bitmap].
     */
    fun correct(bitmap: Bitmap, mask: BooleanArray): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        // Build a distance-to-boundary map for filled pixels (approximated by BFS from boundary).
        val dist = buildDistMap(mask, w, h)

        // Sample the outer ring (known pixels just outside the mask).
        val outerR = FloatArray(w * h); val outerG = FloatArray(w * h); val outerB = FloatArray(w * h)
        var on = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (mask[i]) continue
            // Is this pixel adjacent to the mask?
            var nearMask = false
            outer@ for (dy in -SAMPLE_RING..SAMPLE_RING) for (dx in -SAMPLE_RING..SAMPLE_RING) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (mask[ny * w + nx]) { nearMask = true; break@outer }
            }
            if (!nearMask) continue
            outerR[on] = r(px[i]).toFloat()
            outerG[on] = g(px[i]).toFloat()
            outerB[on] = b(px[i]).toFloat()
            on++
        }

        // Sample the inner ring (filled pixels near the boundary, dist ≤ SAMPLE_RING).
        val innerR = FloatArray(w * h); val innerG = FloatArray(w * h); val innerB = FloatArray(w * h)
        var inn = 0
        for (i in mask.indices) {
            if (!mask[i] || dist[i] > SAMPLE_RING) continue
            innerR[inn] = r(px[i]).toFloat()
            innerG[inn] = g(px[i]).toFloat()
            innerB[inn] = b(px[i]).toFloat()
            inn++
        }

        if (on < MIN_SAMPLES || inn < MIN_SAMPLES) return bitmap  // not enough data, skip

        // Compute mean + std for both rings.
        val (omR, osR) = meanStd(outerR, on); val (omG, osG) = meanStd(outerG, on); val (omB, osB) = meanStd(outerB, on)
        val (imR, isR) = meanStd(innerR, inn); val (imG, isG) = meanStd(innerG, inn); val (imB, isB) = meanStd(innerB, inn)

        // Build the correction: scale = outer_std / inner_std, shift = outer_mean - scale * inner_mean.
        val scR = if (isR > 1f) osR / isR else 1f; val shR = omR - scR * imR
        val scG = if (isG > 1f) osG / isG else 1f; val shG = omG - scG * imG
        val scB = if (isB > 1f) osB / isB else 1f; val shB = omB - scB * imB

        // Find the max distance in the mask (for feathering weight).
        var maxDist = 1
        for (i in mask.indices) if (mask[i] && dist[i] > maxDist) maxDist = dist[i]

        // Apply with feathered weight.
        val out = px.copyOf()
        for (i in mask.indices) {
            if (!mask[i]) continue
            val d = dist[i]
            // Weight: 0 at boundary (d=0), 1 at centre (d=maxDist). Smooth ramp.
            val t = (d.toFloat() / maxDist).coerceIn(0f, 1f)
            val w0 = t * t * (3f - 2f * t)   // smoothstep

            val pr = r(px[i]); val pg = g(px[i]); val pb = b(px[i])
            val cr = (scR * pr + shR).coerceIn(0f, 255f).toInt()
            val cg = (scG * pg + shG).coerceIn(0f, 255f).toInt()
            val cb = (scB * pb + shB).coerceIn(0f, 255f).toInt()

            val nr = (pr + w0 * (cr - pr)).toInt().coerceIn(0, 255)
            val ng = (pg + w0 * (cg - pg)).toInt().coerceIn(0, 255)
            val nb = (pb + w0 * (cb - pb)).toInt().coerceIn(0, 255)
            val alpha = (px[i] ushr 24) and 0xFF
            out[i] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ── BFS distance map ─────────────────────────────────────────────────────

    /** For each masked pixel: approximate distance (in pixels) to the nearest unmasked pixel. */
    private fun buildDistMap(mask: BooleanArray, w: Int, h: Int): IntArray {
        val dist = IntArray(mask.size) { if (mask[it]) Int.MAX_VALUE else 0 }
        val queue = ArrayDeque<Int>()
        // Seed: masked pixels adjacent to unmasked pixels get distance 1.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!mask[i]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (!mask[ny * w + nx]) { dist[i] = 1; queue.addLast(i); break }
            }
        }
        // BFS propagation.
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w; val y = idx / w
            val d = dist[idx]
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val ni = ny * w + nx
                if (mask[ni] && dist[ni] > d + 1) {
                    dist[ni] = d + 1
                    queue.addLast(ni)
                }
            }
        }
        return dist
    }

    // ── statistics ───────────────────────────────────────────────────────────

    private fun meanStd(data: FloatArray, n: Int): Pair<Float, Float> {
        var sum = 0.0
        for (i in 0 until n) sum += data[i]
        val mean = (sum / n).toFloat()
        var variance = 0.0
        for (i in 0 until n) { val d = data[i] - mean; variance += d * d }
        return mean to sqrt((variance / n).toFloat()).coerceAtLeast(0f)
    }

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8)  and 0xFF
    private fun b(p: Int) =  p         and 0xFF
}
