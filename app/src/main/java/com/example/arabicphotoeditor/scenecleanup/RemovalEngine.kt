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
 * The first engine: the existing local fill (onion-peel + diffusion). It copies nothing from elsewhere in the
 * photo, so it is good for small objects on smooth or softly textured backgrounds and it smears large areas.
 * It makes no semantic guess about what was behind the object.
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
