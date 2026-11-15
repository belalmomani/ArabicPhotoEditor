package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import com.example.arabicphotoeditor.retouch.BlemishInpainter

/**
 * One way of rebuilding the area under the removed object. Several engines can exist side by side and the best one
 * is chosen per region (simple area: a light local fill; complex area: a context-aware one).
 */
interface RemovalEngine {
    /** Short stable name, for logs and for choosing between engines. */
    val id: String

    /**
     * Returns a new bitmap with the pixels where [mask] is true rebuilt. [mask] is at the size of [source].
     * [progress] receives 0..1; the engine should stop early when [cancelled] returns true.
     * Heavy: call from a background dispatcher.
     */
    fun remove(source: Bitmap, mask: BooleanArray, progress: (Float) -> Unit, cancelled: () -> Boolean): Bitmap
}

/**
 * The basic engine: the existing local fill (onion-peel + diffusion). Good for small objects
 * on smooth backgrounds. Smears large or complex areas.
 */
object BasicRemovalEngine : RemovalEngine {
    override val id = "basic"

    override fun remove(source: Bitmap, mask: BooleanArray, progress: (Float) -> Unit, cancelled: () -> Boolean): Bitmap {
        progress(0f)
        val out = BlemishInpainter.inpaint(source, mask)
        progress(1f)
        return out
    }
}

/**
 * Picks the best engine for the given mask automatically:
 * - Small selection (< [BASIC_THRESHOLD] of the image): BasicRemovalEngine (fast, no artefacts on smooth areas).
 * - Medium selection on a small image: PatchMatchEngine directly (single-pass is fine).
 * - Large image (> [TILED_MEGAPIXELS] MP) with any non-trivial selection: TiledRemovalEngine to
 *   avoid OOM by splitting the fill into overlapping tiles.
 *
 * This keeps the user experience simple: they never need to choose.
 */
object AutoRemovalEngine : RemovalEngine {
    override val id = "auto"

    /** Mask-coverage fraction below which we use the basic fill (fast, good for tiny spots). */
    private const val BASIC_THRESHOLD = 0.015f   // 1.5 % of the image

    /** Images above this size in megapixels are processed via the tiling engine. */
    private const val TILED_MEGAPIXELS = 2.0

    override fun remove(source: Bitmap, mask: BooleanArray, progress: (Float) -> Unit, cancelled: () -> Boolean): Bitmap {
        val coverage = mask.count { it }.toFloat() / mask.size
        val mp = source.width.toLong() * source.height / 1_000_000.0
        val engine: RemovalEngine = when {
            coverage <= BASIC_THRESHOLD      -> BasicRemovalEngine
            mp > TILED_MEGAPIXELS            -> TiledRemovalEngine
            else                             -> PatchMatchEngine
        }
        return engine.remove(source, mask, progress, cancelled)
    }
}
