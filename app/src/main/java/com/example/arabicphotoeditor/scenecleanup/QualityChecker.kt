package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analyses the filled region for visible artefacts and returns a [QualityReport].
 *
 * Checks performed
 * ----------------
 * 1. **Colour seam** – mean colour difference between pixels just inside vs just outside the mask boundary.
 *    A large difference means the fill doesn't blend with its surroundings.
 * 2. **Blotch** – local colour variance inside the filled region is compared to the surrounding area.
 *    Unusually high variance inside suggests smearing or patchwork artefacts.
 * 3. **Repetition** – scans for large patches inside the region that are very similar to other patches
 *    inside the same region. High self-similarity beyond a threshold hints at copy-paste patterns.
 * 4. **Edge residue** – checks for abrupt gradient spikes at the mask boundary that indicate the
 *    original object's silhouette was not fully removed.
 *
 * None of these checks claim semantic understanding. They are purely statistical signal detectors.
 * A passing score does not guarantee a perfect result; a failing one reliably identifies problems.
 */
object QualityChecker {

    data class QualityReport(
        val colourSeamScore : Float,   // 0 = perfect, 1 = severe seam
        val blotchScore     : Float,   // 0 = clean,   1 = blotchy
        val repetitionScore : Float,   // 0 = natural,  1 = obvious copy-paste
        val edgeResidueScore: Float,   // 0 = clean,   1 = residue visible
    ) {
        /** Overall quality: 0 = good, 1 = bad. */
        val overall: Float get() =
            (colourSeamScore * 0.35f + blotchScore * 0.30f +
             repetitionScore * 0.20f + edgeResidueScore * 0.15f).coerceIn(0f, 1f)

        /** True when the result is good enough to show without a warning. */
        val acceptable: Boolean get() = overall < ACCEPT_THRESHOLD

        companion object { const val ACCEPT_THRESHOLD = 0.35f }
    }

    /**
     * Analyses [filled] bitmap against [originalMask] (true = was filled).
     * [filled] and [originalMask] must have the same dimensions.
     */
    fun check(filled: Bitmap, originalMask: BooleanArray): QualityReport {
        val w = filled.width; val h = filled.height
        val px = IntArray(w * h)
        filled.getPixels(px, 0, w, 0, 0, w, h)

        return QualityReport(
            colourSeamScore  = colourSeam(px, originalMask, w, h),
            blotchScore      = blotchScore(px, originalMask, w, h),
            repetitionScore  = repetitionScore(px, originalMask, w, h),
            edgeResidueScore = edgeResidueScore(px, originalMask, w, h),
        )
    }

    // ── 1. Colour seam ───────────────────────────────────────────────────────

    private fun colourSeam(px: IntArray, mask: BooleanArray, w: Int, h: Int): Float {
        var innerR = 0f; var innerG = 0f; var innerB = 0f; var innerN = 0
        var outerR = 0f; var outerG = 0f; var outerB = 0f; var outerN = 0
        val RING = 4
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            if (mask[i]) {
                // Inner: filled pixel near the boundary
                if (nearBoundary(mask, x, y, w, h, RING)) {
                    innerR += r(p); innerG += g(p); innerB += b(p); innerN++
                }
            } else {
                // Outer: known pixel near the boundary
                if (nearBoundary(mask, x, y, w, h, RING)) {
                    outerR += r(p); outerG += g(p); outerB += b(p); outerN++
                }
            }
        }
        if (innerN == 0 || outerN == 0) return 0f
        val dr = abs(innerR / innerN - outerR / outerN)
        val dg = abs(innerG / innerN - outerG / outerN)
        val db = abs(innerB / innerN - outerB / outerN)
        return ((dr + dg + db) / 3f / 255f * 3f).coerceIn(0f, 1f)   // scale: 20/255 ≈ 0.24
    }

    // ── 2. Blotch ────────────────────────────────────────────────────────────

    private fun blotchScore(px: IntArray, mask: BooleanArray, w: Int, h: Int): Float {
        // Sample at most MAX_SAMPLE pixels from each region to stay memory-safe on large images.
        val MAX_SAMPLE = 8_000

        fun varianceSampled(indices: List<Int>): Float {
            val step = if (indices.size <= MAX_SAMPLE) 1 else indices.size / MAX_SAMPLE
            var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            var i = 0; while (i < indices.size) {
                val p = px[indices[i]]; sr += r(p); sg += g(p); sb += b(p); n++
                i += step
            }
            if (n < 2) return 0f
            val mr = sr / n; val mg = sg / n; val mb = sb / n
            var v = 0f; i = 0
            while (i < indices.size) {
                val p = px[indices[i]]
                val dr = r(p) - mr; val dg = g(p) - mg; val db = b(p) - mb
                v += dr * dr + dg * dg + db * db
                i += step
            }
            return v / n
        }

        val inside  = (0 until mask.size).filter {  mask[it] }
        val outside = (0 until mask.size).filter { !mask[it] }
        if (inside.isEmpty() || outside.isEmpty()) return 0f
        val vi = varianceSampled(inside)
        val vo = varianceSampled(outside).coerceAtLeast(1f)
        return ((vi / vo - 1f) / 3f).coerceIn(0f, 1f)   // ratio > 4 → score 1
    }

    // ── 3. Repetition ────────────────────────────────────────────────────────

    /**
     * Samples [SAMPLE_N] random patch pairs from inside the mask; counts how many have
     * SSD below [REP_THRESHOLD]. A high ratio indicates copy-paste repetition.
     */
    private fun repetitionScore(px: IntArray, mask: BooleanArray, w: Int, h: Int): Float {
        val SAMPLE_N    = 60
        val PATCH_HALF  = 4
        val REP_THRESH  = 800f    // mean SSD per pixel-channel over 9x9 patch

        val insideList = (0 until mask.size).filter { mask[it] }
        if (insideList.size < SAMPLE_N * 2) return 0f

        var matches = 0
        var seed = 0xABCDEF12L
        fun rng(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0x7FFF).toInt()
        }

        repeat(SAMPLE_N) {
            val ai = insideList[rng() % insideList.size]
            val bi = insideList[rng() % insideList.size]
            if (ai == bi) return@repeat
            val ax = ai % w; val ay = ai / w
            val bx = bi % w; val by = bi / w
            var ssd = 0f; var n = 0
            for (dy in -PATCH_HALF..PATCH_HALF) for (dx in -PATCH_HALF..PATCH_HALF) {
                val ax2 = (ax + dx).coerceIn(0, w-1); val ay2 = (ay + dy).coerceIn(0, h-1)
                val bx2 = (bx + dx).coerceIn(0, w-1); val by2 = (by + dy).coerceIn(0, h-1)
                val pa = px[ay2 * w + ax2]; val pb = px[by2 * w + bx2]
                val dr = r(pa)-r(pb); val dg = g(pa)-g(pb); val db = b(pa)-b(pb)
                ssd += dr*dr + dg*dg + db*db; n++
            }
            if (n > 0 && ssd / n < REP_THRESH) matches++
        }
        return (matches.toFloat() / SAMPLE_N / 0.3f).coerceIn(0f, 1f)   // 30% match rate → score 1
    }

    // ── 4. Edge residue ───────────────────────────────────────────────────────

    private fun edgeResidueScore(px: IntArray, mask: BooleanArray, w: Int, h: Int): Float {
        // Sample gradient magnitude just inside the mask boundary.
        var sum = 0f; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!mask[i]) continue
            if (!nearBoundary(mask, x, y, w, h, 2)) continue
            val l = px[y * w + (x - 1)]; val r2 = px[y * w + (x + 1)]
            val t = px[(y - 1) * w + x]; val b2 = px[(y + 1) * w + x]
            val gx = (r(r2) - r(l)).toFloat(); val gy = (r(b2) - r(t)).toFloat()
            sum += sqrt(gx * gx + gy * gy); n++
        }
        if (n == 0) return 0f
        return (sum / n / 255f * 2f).coerceIn(0f, 1f)   // gradient of 128/255 → score 1
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun nearBoundary(mask: BooleanArray, x: Int, y: Int, w: Int, h: Int, r: Int): Boolean {
        val inside = mask[y * w + x]
        for (dy in -r..r) for (dx in -r..r) {
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            if (mask[ny * w + nx] != inside) return true
        }
        return false
    }

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8)  and 0xFF
    private fun b(p: Int) =  p         and 0xFF
}
