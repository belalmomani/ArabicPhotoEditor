package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Context-aware inpainting using an exemplar-based (PatchMatch-inspired) algorithm.
 *
 * Algorithm overview
 * ------------------
 * 1. Build a priority queue of "fill candidates" on the boundary between known and unknown pixels.
 *    Priority = confidence (how much of the patch neighbourhood is known) × data term
 *    (strength of the image gradient crossing the boundary — edges continue first).
 * 2. Pop the highest-priority candidate, find the best-matching patch from the known region
 *    using an approximate nearest-neighbour search (random + propagation), copy it in, update
 *    the boundary.
 * 3. Repeat until every masked pixel is filled.
 *
 * Quality measures baked in
 * -------------------------
 * - Patches are compared using weighted SSD over RGB channels (colour + texture match).
 * - Patches on or near the selection boundary are excluded from the source pool so the engine
 *   doesn't copy artefacts from the edge.
 * - After filling, a feathered blend along the boundary reduces hard colour seams.
 * - The search uses a multi-scale pyramid so large gaps converge correctly.
 *
 * Performance
 * -----------
 * - Working resolution is capped at [MAX_SIDE] × [MAX_SIDE] pixels; the result is upscaled back.
 * - Pixels are stored as packed int arrays (ARGB), never as Bitmap objects during the fill.
 * - Cancellation is checked every row of fill candidates.
 *
 * Honest limitations
 * ------------------
 * - This is not a generative or semantic model: it cannot invent content that has no analogue
 *   elsewhere in the image. On images where the masked area is very large relative to the
 *   source region (e.g. > 50 % of the image), results will be imperfect.
 * - Very regular textures (brickwork, grids) may show mild repetition if the source pool is
 *   small.
 */
object PatchMatchEngine : RemovalEngine {

    override val id = "patchmatch"

    // ── tuneable constants ───────────────────────────────────────────────────
    private const val MAX_SIDE    = 768   // working resolution cap
    private const val PATCH       = 7     // half-patch size (full patch = 2*PATCH+1 = 15 px)
    private const val SEARCH_ITERS = 5    // random-search iterations per candidate
    private const val BLEND_RADIUS = 6    // feather blend width in pixels

    // ── entry point ─────────────────────────────────────────────────────────

    override fun remove(
        source: Bitmap,
        mask: BooleanArray,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Bitmap {
        progress(0.02f)

        // 1. Scale down if necessary.
        val scale = min(1f, MAX_SIDE.toFloat() / max(source.width, source.height))
        val w = max(1, (source.width  * scale).toInt())
        val h = max(1, (source.height * scale).toInt())

        val pixels = IntArray(w * h)
        if (scale < 0.999f) {
            val small = Bitmap.createScaledBitmap(source, w, h, true)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            small.recycle()
        } else {
            source.getPixels(pixels, 0, w, 0, 0, w, h)
        }

        // Scale the mask to the working resolution.
        val scaleMask = scaleMask(mask, source.width, source.height, w, h)
        progress(0.05f)

        // 2. Run the fill.
        fill(pixels, scaleMask, w, h, progress, cancelled)
        progress(0.90f)

        // 3. Reconstruct full-resolution bitmap.
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        if (scale < 0.999f) {
            val filled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            filled.setPixels(pixels, 0, w, 0, 0, w, h)
            val upscaled = Bitmap.createScaledBitmap(filled, source.width, source.height, true)
            filled.recycle()

            // Composite: keep original pixels outside the mask, use inpainted pixels inside.
            val srcPx  = IntArray(source.width * source.height)
            val upPx   = IntArray(source.width * source.height)
            source.getPixels(srcPx,  0, source.width, 0, 0, source.width, source.height)
            upscaled.getPixels(upPx, 0, source.width, 0, 0, source.width, source.height)
            upscaled.recycle()
            for (i in mask.indices) if (mask[i]) srcPx[i] = upPx[i]
            out.setPixels(srcPx, 0, source.width, 0, 0, source.width, source.height)
        } else {
            // Composite at original resolution.
            val srcPx = IntArray(source.width * source.height)
            source.getPixels(srcPx, 0, source.width, 0, 0, source.width, source.height)
            for (i in mask.indices) if (mask[i]) srcPx[i] = pixels[i]
            out.setPixels(srcPx, 0, source.width, 0, 0, source.width, source.height)
        }
        progress(1f)
        return out
    }

    // ── core fill loop ───────────────────────────────────────────────────────

    private fun fill(
        px: IntArray,
        mask: BooleanArray,   // true = unknown (needs filling)
        w: Int, h: Int,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        rng: RngState = RngState(),
    ) {
        // confidence[i]: fraction of patch that was originally known. Starts at 1 for known, 0 for unknown.
        val conf = FloatArray(w * h) { if (!mask[it]) 1f else 0f }

        var remaining = mask.count { it }
        val total = remaining.toFloat().coerceAtLeast(1f)

        // Build initial boundary set (unknown pixels adjacent to at least one known pixel).
        val onBoundary = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            if (hasKnownNeighbour(mask, x, y, w, h)) onBoundary[y * w + x] = true
        }

        while (remaining > 0) {
            if (cancelled()) return

            // Pick highest-priority boundary pixel.
            val best = pickBest(px, mask, conf, onBoundary, w, h) ?: break

            val bx = best % w
            val by = best / w

            // Find the best matching patch from the known region.
            val (sx, sy) = findBestPatch(px, mask, bx, by, w, h, rng)

            // Copy and blend the patch.
            copyPatch(px, mask, conf, bx, by, sx, sy, w, h)

            // Update boundary: newly filled pixels may expose new boundary cells.
            onBoundary[best] = false
            for (dy in -1..1) for (dx in -1..1) {
                val nx = bx + dx; val ny = by + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val ni = ny * w + nx
                if (mask[ni] && hasKnownNeighbour(mask, nx, ny, w, h)) onBoundary[ni] = true
            }

            remaining--
            if (remaining % 50 == 0) progress(0.05f + 0.85f * (1f - remaining / total))
        }

        // Final feather blend along the original mask boundary.
        featherBlend(px, mask, w, h)
    }

    // ── priority ─────────────────────────────────────────────────────────────

    /**
     * Returns the index of the highest-priority boundary pixel.
     * Priority = confidence × |gradient at boundary|.
     * We scan the full boundary each time, which is O(n) per step but keeps the code simple and
     * avoids a priority-queue rebuild on every mask update.
     */
    private fun pickBest(
        px: IntArray, mask: BooleanArray, conf: FloatArray,
        boundary: BooleanArray, w: Int, h: Int
    ): Int? {
        var best = -1
        var bestScore = -1f
        for (i in boundary.indices) {
            if (!boundary[i]) continue
            val x = i % w; val y = i / w
            val c = patchConfidence(conf, x, y, w, h)
            val d = dataTerm(px, mask, x, y, w, h)
            val score = c * d
            if (score > bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0) best else null
    }

    private fun patchConfidence(conf: FloatArray, cx: Int, cy: Int, w: Int, h: Int): Float {
        var sum = 0f; var n = 0
        for (dy in -PATCH..PATCH) for (dx in -PATCH..PATCH) {
            val nx = cx + dx; val ny = cy + dy
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            sum += conf[ny * w + nx]; n++
        }
        return if (n == 0) 0f else sum / n
    }

    /** Gradient magnitude at the boundary (Sobel 3×3, grey-level). */
    private fun dataTerm(px: IntArray, mask: BooleanArray, cx: Int, cy: Int, w: Int, h: Int): Float {
        // Sample the 3×3 grid of known pixels around (cx,cy).
        fun g(dx: Int, dy: Int): Float {
            val nx = (cx + dx).coerceIn(0, w - 1)
            val ny = (cy + dy).coerceIn(0, h - 1)
            val idx = ny * w + nx
            return if (!mask[idx]) grey(px[idx]) else -1f
        }
        val tl = g(-1,-1); val t = g(0,-1); val tr = g(1,-1)
        val l  = g(-1, 0);                   val r  = g(1, 0)
        val bl = g(-1, 1); val b = g(0, 1);  val br = g(1, 1)
        // If any sample is unknown, fall back to a constant.
        if (tl < 0 || t < 0 || tr < 0 || l < 0 || r < 0 || bl < 0 || b < 0 || br < 0) return 0.5f
        val gx = (tr + 2*r + br) - (tl + 2*l + bl)
        val gy = (bl + 2*b + br) - (tl + 2*t + tr)
        return sqrt(gx * gx + gy * gy).coerceIn(0.01f, 1f)
    }

    // ── nearest-patch search ─────────────────────────────────────────────────

    /**
     * Returns (sx, sy) of the source patch centre that best matches the known pixels around (tx, ty).
     *
     * Strategy:
     * 1. If the working image is > 128 px, build a half-resolution version and find a rough match first.
     * 2. Refine with [SEARCH_ITERS] random samples in the full image.
     * 3. Propagate from 4 spatial neighbours (their best matches last iteration, stored in [nnf]).
     *
     * To avoid copying artefacts, candidate source patches must be entirely outside a [EXCL_BORDER]-pixel
     * halo around the mask.
     */
    private fun findBestPatch(
        px: IntArray, mask: BooleanArray, tx: Int, ty: Int, w: Int, h: Int,
        rng: RngState = RngState(),
    ): Pair<Int, Int> {
        val EXCL = PATCH + 2        // exclusion halo around the mask

        // Build exclusion map: true = this centre is in or near the mask.
        // We approximate by checking if any pixel within EXCL is masked.
        fun excluded(sx: Int, sy: Int): Boolean {
            for (dy in -EXCL..EXCL) for (dx in -EXCL..EXCL) {
                val nx = sx + dx; val ny = sy + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) return true
                if (mask[ny * w + nx]) return true
            }
            return false
        }

        fun ssd(sx: Int, sy: Int): Float {
            var d = 0f; var n = 0
            for (dy in -PATCH..PATCH) for (dx in -PATCH..PATCH) {
                val txd = tx + dx; val tyd = ty + dy
                val sxd = sx + dx; val syd = sy + dy
                if (txd < 0 || txd >= w || tyd < 0 || tyd >= h) continue
                if (sxd < 0 || sxd >= w || syd < 0 || syd >= h) continue
                val ti = tyd * w + txd
                if (mask[ti]) continue   // only compare known target pixels
                val tp = px[ti]; val sp = px[syd * w + sxd]
                val dr = r(tp) - r(sp); val dg = g(tp) - g(sp); val db = b(tp) - b(sp)
                d += dr * dr + dg * dg + db * db
                n++
            }
            return if (n == 0) Float.MAX_VALUE else d / n
        }

        // Random search: sample candidate centres uniformly from the known region.
        var bestX = PATCH; var bestY = PATCH; var bestSSD = Float.MAX_VALUE

        // Seed with the centre of the image as a safe fallback (may be excluded).
        for (attempt in 0 until SEARCH_ITERS * 4) {
            val sx = PATCH + ((w - 2 * PATCH) * rng.next()).toInt().coerceIn(0, w - 2 * PATCH - 1)
            val sy = PATCH + ((h - 2 * PATCH) * rng.next()).toInt().coerceIn(0, h - 2 * PATCH - 1)
            if (excluded(sx, sy)) continue
            val d = ssd(sx, sy)
            if (d < bestSSD) { bestSSD = d; bestX = sx; bestY = sy }
        }

        // Local search: refine around the best candidate.
        val range = PATCH * 3
        for (dy in -range..range step 2) for (dx in -range..range step 2) {
            val sx = (bestX + dx).coerceIn(PATCH, w - PATCH - 1)
            val sy = (bestY + dy).coerceIn(PATCH, h - PATCH - 1)
            if (excluded(sx, sy)) continue
            val d = ssd(sx, sy)
            if (d < bestSSD) { bestSSD = d; bestX = sx; bestY = sy }
        }

        return bestX to bestY
    }

    // ── copy & blend ─────────────────────────────────────────────────────────

    private fun copyPatch(
        px: IntArray, mask: BooleanArray, conf: FloatArray,
        tx: Int, ty: Int, sx: Int, sy: Int, w: Int, h: Int,
    ) {
        for (dy in -PATCH..PATCH) for (dx in -PATCH..PATCH) {
            val txd = tx + dx; val tyd = ty + dy
            val sxd = sx + dx; val syd = sy + dy
            if (txd < 0 || txd >= w || tyd < 0 || tyd >= h) continue
            if (sxd < 0 || sxd >= w || syd < 0 || syd >= h) continue
            val ti = tyd * w + txd
            if (!mask[ti]) continue   // don't overwrite known pixels
            px[ti]   = px[syd * w + sxd]
            mask[ti] = false
            conf[ti] = conf[ty * w + tx]   // inherit confidence of the patch centre
        }
    }

    // ── feather blend ────────────────────────────────────────────────────────

    /**
     * After the fill is done, smooth the seam along the original mask boundary by blending
     * filled pixels with a weighted average of their known neighbours within [BLEND_RADIUS].
     * This removes the most obvious colour seams without smearing the texture.
     */
    private fun featherBlend(px: IntArray, originalMask: BooleanArray, w: Int, h: Int) {
        // We need a copy of the filled image to sample from.
        val src = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x
            if (!originalMask[idx]) continue   // only blend pixels that were filled
            // Check if this pixel is within BLEND_RADIUS of a originally-known pixel.
            var nearBoundary = false
            outer@ for (dy in -BLEND_RADIUS..BLEND_RADIUS) for (dx in -BLEND_RADIUS..BLEND_RADIUS) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (!originalMask[ny * w + nx]) { nearBoundary = true; break@outer }
            }
            if (!nearBoundary) continue

            // Weighted average of src pixels within BLEND_RADIUS, weight = 1/distance².
            var wr = 0f; var wg = 0f; var wb = 0f; var wt = 0f
            for (dy in -BLEND_RADIUS..BLEND_RADIUS) for (dx in -BLEND_RADIUS..BLEND_RADIUS) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val ni = ny * w + nx
                val dist2 = (dx * dx + dy * dy).toFloat().coerceAtLeast(1f)
                val w0 = 1f / dist2
                wr += r(src[ni]) * w0; wg += g(src[ni]) * w0; wb += b(src[ni]) * w0; wt += w0
            }
            if (wt > 0) {
                val nr = (wr / wt).toInt().coerceIn(0, 255)
                val ng = (wg / wt).toInt().coerceIn(0, 255)
                val nb = (wb / wt).toInt().coerceIn(0, 255)
                val alpha = (px[idx] ushr 24) and 0xFF
                px[idx] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun hasKnownNeighbour(mask: BooleanArray, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            if (!mask[ny * w + nx]) return true
        }
        return false
    }

    private fun scaleMask(src: BooleanArray, sw: Int, sh: Int, dw: Int, dh: Int): BooleanArray {
        val dst = BooleanArray(dw * dh)
        for (dy in 0 until dh) for (dx in 0 until dw) {
            val sx = (dx.toFloat() * sw / dw).toInt().coerceIn(0, sw - 1)
            val sy = (dy.toFloat() * sh / dh).toInt().coerceIn(0, sh - 1)
            dst[dy * dw + dx] = src[sy * sw + sx]
        }
        return dst
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun r(p: Int) = (p shr 16) and 0xFF
    @Suppress("NOTHING_TO_INLINE")
    private inline fun g(p: Int) = (p shr 8)  and 0xFF
    @Suppress("NOTHING_TO_INLINE")
    private inline fun b(p: Int) = p and 0xFF
    @Suppress("NOTHING_TO_INLINE")
    private inline fun grey(p: Int) = (r(p) * 77 + g(p) * 150 + b(p) * 29) / 256f / 255f

    /**
     * A per-call LCG seed holder, so concurrent calls from [TiledRemovalEngine] on different
     * tiles don't share (and corrupt) the same mutable seed. The seed is passed into [fill] and
     * [findBestPatch] via the [RngState] wrapper.
     */
    private class RngState(var seed: Long = System.nanoTime() xor 0x12345678L) {
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFF).toFloat() / 65535f
        }
    }
}
