package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Memory-safe wrapper around [PatchMatchEngine] that splits large masked regions into overlapping
 * tiles, processes each tile independently, and stitches them back with a feathered seam.
 *
 * Why tiling?
 * -----------
 * [PatchMatchEngine] scales its input down to MAX_SIDE (768 px) before filling, so a 4 K photo
 * uses ≈ 768×768×4 bytes ≈ 2.3 MB for the work buffer — that is fine.  The problem is that the
 * *full-resolution composite* step copies the fill back onto the original bitmap, which may be
 * 20–30 MB.  Holding the source, the scaled fill, and the output at the same time can exceed the
 * per-process heap on 32-bit devices or low-memory phones.
 *
 * The tiling strategy here works at the *full* resolution:
 * 1. Compute the bounding box of the mask.
 * 2. Decide a tile size that keeps each tile (with overlap) under a safe memory budget.
 * 3. For each tile: crop a sub-bitmap, build a sub-mask, run PatchMatch, blend back.
 * 4. Recycle every intermediate bitmap as soon as its region is composited.
 *
 * Overlap and blending
 * --------------------
 * Adjacent tiles share an [OVERLAP_PX] pixel border.  After filling, we apply a linear cross-fade
 * in the overlap zone so seams are invisible.  The overlap must be wider than PatchMatch's
 * MAX_SIDE-scaled patch radius (PATCH=7, scaled to full res can be ~14 px at 2× zoom → 32 px
 * overlap is comfortable).
 *
 * When NOT to tile
 * ----------------
 * If the image fits safely in memory without tiling, [PatchMatchEngine] is called directly (fewer
 * allocations, no seam risk).  The threshold is [SAFE_MEGAPIXELS].
 *
 * Honest limitations
 * ------------------
 * - Tile boundaries may be faintly visible at very high zoom on images with strong linear
 *   gradients that cross a tile border. The overlap blend mitigates but does not eliminate this.
 * - Very large masked areas (> 60 % of the image) may still stress memory on devices with < 128 MB
 *   heap because the full-resolution output bitmap must be allocated once to stitch all tiles.
 */
object TiledRemovalEngine : RemovalEngine {

    override val id = "tiled"

    // ── constants ────────────────────────────────────────────────────────────

    /** Images below this size are handed directly to PatchMatchEngine without tiling. */
    private const val SAFE_MEGAPIXELS = 2.0   // 2 MP ≈ 1600×1250

    /**
     * Target megapixels per tile (source pixels, before PatchMatch's internal downscale).
     * At 1 MP a tile is typically 1000×1000 or narrower — well within the 768 px cap.
     */
    private const val TILE_MEGAPIXELS = 1.0

    /** Overlap in pixels at full resolution between adjacent tiles. */
    private const val OVERLAP_PX = 48

    // ── entry point ──────────────────────────────────────────────────────────

    override fun remove(
        source: Bitmap,
        mask: BooleanArray,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Bitmap {
        val w = source.width
        val h = source.height
        val mp = w.toLong() * h / 1_000_000.0

        // If the image is small enough, skip tiling entirely.
        if (mp <= SAFE_MEGAPIXELS) {
            return PatchMatchEngine.remove(source, mask, progress, cancelled)
        }

        // ── 1. Bounding box of the mask ──────────────────────────────────────
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
        }
        if (x0 > x1 || y0 > y1) return source   // empty mask — nothing to do

        // ── 2. Tile layout ───────────────────────────────────────────────────
        val maskW = (x1 - x0 + 1).coerceAtLeast(1)
        val maskH = (y1 - y0 + 1).coerceAtLeast(1)
        val tileSide = tileSideFor(TILE_MEGAPIXELS)
        val cols = ceil(maskW.toDouble() / tileSide).toInt().coerceAtLeast(1)
        val rows = ceil(maskH.toDouble() / tileSide).toInt().coerceAtLeast(1)
        val totalTiles = cols * rows

        // Start with a mutable copy of the source; each tile writes its result back in-place.
        val output = source.copy(Bitmap.Config.ARGB_8888, true)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (cancelled()) {
                    output.recycle()
                    return source
                }

                val tileIdx = row * cols + col
                progress(tileIdx.toFloat() / totalTiles)

                // ── 3. Tile coordinates (with overlap, clamped to image bounds) ──
                val tileBaseX = x0 + col * tileSide
                val tileBaseY = y0 + row * tileSide
                val tx0 = max(0, tileBaseX - OVERLAP_PX)
                val ty0 = max(0, tileBaseY - OVERLAP_PX)
                val tx1 = min(w - 1, tileBaseX + tileSide - 1 + OVERLAP_PX)
                val ty1 = min(h - 1, tileBaseY + tileSide - 1 + OVERLAP_PX)
                val tw = tx1 - tx0 + 1
                val th = ty1 - ty0 + 1

                // ── 4. Build sub-mask (only pixels inside this tile's box) ──────
                val subMask = BooleanArray(tw * th)
                var hasMask = false
                for (sy in 0 until th) {
                    for (sx in 0 until tw) {
                        val gx = tx0 + sx; val gy = ty0 + sy
                        if (gx in 0 until w && gy in 0 until h && mask[gy * w + gx]) {
                            subMask[sy * tw + sx] = true
                            hasMask = true
                        }
                    }
                }
                if (!hasMask) continue   // this tile has no masked pixels

                // ── 5. Crop the tile from current output (includes earlier tiles' fills) ──
                val subSource = Bitmap.createBitmap(output, tx0, ty0, tw, th)

                // ── 6. Fill the tile ─────────────────────────────────────────────
                val subFilled = PatchMatchEngine.remove(
                    subSource, subMask,
                    progress = { p -> progress((tileIdx + p) / totalTiles) },
                    cancelled = cancelled,
                )
                subSource.recycle()   // free the crop immediately

                if (cancelled()) {
                    subFilled.recycle()
                    return output   // return whatever was stitched so far
                }

                // ── 7. Composite back with overlap feathering ────────────────────
                blendTileInto(output, subFilled, tx0, ty0,
                    tileBaseX, tileBaseY,
                    min(x0 + col * tileSide + tileSide - 1, x1),
                    min(y0 + row * tileSide + tileSide - 1, y1))
                subFilled.recycle()

                // ── 8. Suggest GC after each tile (non-blocking hint) ────────────
                if (tileIdx % 4 == 3) Runtime.getRuntime().gc()
            }
        }

        progress(1f)
        return output
    }

    // ── tile size helper ─────────────────────────────────────────────────────

    /**
     * Returns a tile side length in pixels such that a tile of that side has approximately
     * [targetMp] megapixels, capped at the available heap to avoid OOM.
     */
    private fun tileSideFor(targetMp: Double): Int {
        val heapMb = Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0)
        // Each pixel needs ~12 bytes in the worst case (source crop + fill + composite blend).
        val safeMp = (heapMb / 12.0).coerceIn(0.5, 4.0)
        val mp = min(targetMp, safeMp)
        return kotlin.math.sqrt(mp * 1_000_000.0).toInt().coerceIn(256, 2048)
    }

    // ── overlap blend ─────────────────────────────────────────────────────────

    /**
     * Writes [tile] back into [dest] at ([tileOffX], [tileOffY]).
     *
     * - Pixels inside the *core* zone ([coreX0]..[coreX1] × [coreY0]..[coreY1]) are copied as-is.
     * - Pixels in the *overlap* fringe are blended between the existing [dest] pixel and the tile
     *   pixel using a smooth weight that is 0 at the outer edge and 1 at the core boundary.
     *
     * This ensures tiles blend seamlessly without hard seams.
     */
    private fun blendTileInto(
        dest: Bitmap, tile: Bitmap,
        tileOffX: Int, tileOffY: Int,
        coreX0: Int, coreY0: Int, coreX1: Int, coreY1: Int,
    ) {
        val tw = tile.width; val th = tile.height
        val dw = dest.width

        // Read the destination pixels in the tile region.
        val destPx  = IntArray(tw * th)
        dest.getPixels(destPx, 0, tw, tileOffX, tileOffY, tw, th)

        val tilePx  = IntArray(tw * th)
        tile.getPixels(tilePx, 0, tw, 0, 0, tw, th)

        val outPx = IntArray(tw * th)

        for (ty in 0 until th) {
            for (tx in 0 until tw) {
                val gx = tileOffX + tx; val gy = tileOffY + ty
                val idx = ty * tw + tx

                // Weight: how much of the tile pixel to use vs the destination pixel.
                val wx = overlapWeight(gx, coreX0, coreX1, tileOffX, tileOffX + tw - 1)
                val wy = overlapWeight(gy, coreY0, coreY1, tileOffY, tileOffY + th - 1)
                val w = (wx * wy).coerceIn(0f, 1f)

                val dp = destPx[idx]; val tp = tilePx[idx]
                val r = lerp(r(dp), r(tp), w)
                val g = lerp(g(dp), g(tp), w)
                val b = lerp(b(dp), b(tp), w)
                val a = (dp ushr 24) and 0xFF
                outPx[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        dest.setPixels(outPx, 0, tw, tileOffX, tileOffY, tw, th)
    }

    /**
     * Returns a blend weight (0 → keep dest, 1 → use tile) for a coordinate [v] given the core
     * range [[core0]..[core1]] and the tile extent [[tileMin]..[tileMax]].
     * Inside the core the weight is 1; in the overlap fringe it ramps from 0 to 1 via smoothstep.
     */
    private fun overlapWeight(v: Int, core0: Int, core1: Int, tileMin: Int, tileMax: Int): Float {
        return when {
            v < core0 -> {
                val fringeW = (core0 - tileMin).coerceAtLeast(1)
                val t = (v - tileMin).toFloat() / fringeW
                smoothstep(t)
            }
            v > core1 -> {
                val fringeW = (tileMax - core1).coerceAtLeast(1)
                val t = (tileMax - v).toFloat() / fringeW
                smoothstep(t)
            }
            else -> 1f
        }
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun lerp(a: Int, b: Int, t: Float) = (a + t * (b - a)).toInt().coerceIn(0, 255)

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8)  and 0xFF
    private fun b(p: Int) =  p         and 0xFF
}
