package com.example.arabicphotoeditor.scenecleanup

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/**
 * Uses the existing U²-Net-P model (u2netp.onnx, already in assets) to produce a saliency map,
 * then segments it into discrete foreground regions the user can tap to select.
 *
 * This is NOT a semantic object detector: it finds visually salient regions (things that stand out
 * from the background) using a real trained neural network. The result is honest:
 * - Each region is a connected set of pixels the model considers foreground.
 * - The user taps a region to add it to the selection mask; nothing is auto-removed.
 * - On uniform or low-contrast images the detector may find few or no regions.
 *
 * Call [detect] from a background dispatcher; it blocks.
 */
class SaliencyDetector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val lock = Any()

    /**
     * Runs the saliency model and returns a list of detected regions.
     * Each [DetectedRegion] carries a low-resolution binary mask (at [MASK_SIZE]x[MASK_SIZE])
     * and the bounding box in image coordinates (0..1 normalized).
     * Returns an empty list if inference fails or no regions are found.
     */
    fun detect(photo: Bitmap): List<DetectedRegion> {
        return try {
            val saliency = runModel(photo)
            findRegions(saliency, photo.width, photo.height)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun runModel(photo: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(photo, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== photo) small.recycle()

        // Normalize: same preprocessing as BackgroundRemover (divide by max, then mean/std).
        var maxVal = 1e-6f
        for (p in px) {
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (r > maxVal) maxVal = r
            if (g > maxVal) maxVal = g
            if (b > maxVal) maxVal = b
        }

        val plane = SIZE * SIZE
        val buf = ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i]            = (((p shr 16) and 0xFF) / maxVal - MEAN[0]) / STD[0]
            data[plane + i]    = (((p shr 8)  and 0xFF) / maxVal - MEAN[1]) / STD[1]
            data[2 * plane + i] = ((p and 0xFF)         / maxVal - MEAN[2]) / STD[2]
        }
        buf.put(data); buf.rewind()

        val sess = session()
        val inputName = sess.inputNames.first()
        val raw = FloatArray(plane)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { inp ->
            sess.run(mapOf(inputName to inp)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                fb.get(raw, 0, plane)
            }
        }

        // Min-max normalize to 0..1.
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).coerceAtLeast(1e-6f)
        for (i in raw.indices) raw[i] = (raw[i] - lo) / range
        return raw
    }

    /**
     * Segments the saliency map into connected regions at [MASK_SIZE] resolution.
     * Returns up to [MAX_REGIONS] regions sorted by area (largest first).
     */
    private fun findRegions(saliency: FloatArray, photoW: Int, photoH: Int): List<DetectedRegion> {
        // Downsample saliency to MASK_SIZE for fast connected-components.
        val scale = SIZE.toFloat() / MASK_SIZE
        val binary = BooleanArray(MASK_SIZE * MASK_SIZE)
        for (my in 0 until MASK_SIZE) {
            for (mx in 0 until MASK_SIZE) {
                val sx = floor(mx * scale).toInt().coerceIn(0, SIZE - 1)
                val sy = floor(my * scale).toInt().coerceIn(0, SIZE - 1)
                binary[my * MASK_SIZE + mx] = saliency[sy * SIZE + sx] >= THRESHOLD
            }
        }

        // Flood-fill connected components (4-connected).
        val labels = IntArray(MASK_SIZE * MASK_SIZE) { -1 }
        val regionPixels = ArrayList<ArrayList<Int>>()
        for (start in binary.indices) {
            if (!binary[start] || labels[start] >= 0) continue
            val id = regionPixels.size
            val pixels = ArrayList<Int>()
            regionPixels.add(pixels)
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            labels[start] = id
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                pixels.add(idx)
                val x = idx % MASK_SIZE; val y = idx / MASK_SIZE
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx < 0 || nx >= MASK_SIZE || ny < 0 || ny >= MASK_SIZE) continue
                    val ni = ny * MASK_SIZE + nx
                    if (binary[ni] && labels[ni] < 0) { labels[ni] = id; stack.addLast(ni) }
                }
            }
        }

        // Build DetectedRegion for each component; skip tiny noise.
        val minPixels = (MASK_SIZE * MASK_SIZE * MIN_AREA_FRAC).toInt().coerceAtLeast(4)
        return regionPixels
            .filter { it.size >= minPixels }
            .sortedByDescending { it.size }
            .take(MAX_REGIONS)
            .map { pixels ->
                val mask = BooleanArray(MASK_SIZE * MASK_SIZE)
                var x0 = MASK_SIZE; var y0 = MASK_SIZE; var x1 = 0; var y1 = 0
                for (idx in pixels) {
                    mask[idx] = true
                    val x = idx % MASK_SIZE; val y = idx / MASK_SIZE
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                val normalCx = (x0 + x1 + 1) / 2f / MASK_SIZE
                val normalCy = (y0 + y1 + 1) / 2f / MASK_SIZE
                DetectedRegion(
                    maskSize  = MASK_SIZE,
                    maskData  = mask,
                    centerX   = normalCx,
                    centerY   = normalCy,
                    left      = x0.toFloat() / MASK_SIZE,
                    top       = y0.toFloat() / MASK_SIZE,
                    right     = (x1 + 1).toFloat() / MASK_SIZE,
                    bottom    = (y1 + 1).toFloat() / MASK_SIZE,
                    areaFrac  = pixels.size.toFloat() / (MASK_SIZE * MASK_SIZE)
                )
            }
    }

    private fun session(): OrtSession = synchronized(lock) {
        session ?: run {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            env.createSession(bytes, opts).also { session = it }
        }
    }

    fun close() = synchronized(lock) { session?.close(); session = null }

    companion object {
        const val MODEL_ASSET = "u2netp.onnx"
        const val SIZE       = 320      // model input resolution
        const val MASK_SIZE  = 64       // connected-components grid (fast + enough detail)
        const val THRESHOLD  = 0.45f    // saliency threshold → foreground
        const val MIN_AREA_FRAC = 0.003f// ignore regions < 0.3% of image
        const val MAX_REGIONS = 20
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * One salient region returned by [SaliencyDetector.detect].
 * [maskData] is a [maskSize]×[maskSize] boolean grid; true = this region.
 * All bounding-box coordinates are normalized 0..1 over the original photo.
 */
data class DetectedRegion(
    val maskSize : Int,
    val maskData : BooleanArray,
    val centerX  : Float,     // normalized centre of bounding box
    val centerY  : Float,
    val left     : Float,
    val top      : Float,
    val right    : Float,
    val bottom   : Float,
    val areaFrac : Float      // fraction of the MASK_SIZE grid this region covers
) {
    /** True if the normalized point (nx, ny) falls inside this region's mask. */
    fun contains(nx: Float, ny: Float): Boolean {
        val mx = (nx * maskSize).toInt().coerceIn(0, maskSize - 1)
        val my = (ny * maskSize).toInt().coerceIn(0, maskSize - 1)
        return maskData[my * maskSize + mx]
    }

    /**
     * Converts this region's mask into a [SelectionMask] at the cleanup working resolution,
     * scaled to [selW]×[selH] via bilinear sampling.
     */
    fun toSelectionMask(selW: Int, selH: Int): SelectionMask {
        val sel = SelectionMask(selW, selH)
        val scaleX = maskSize.toFloat() / selW
        val scaleY = maskSize.toFloat() / selH
        for (sy in 0 until selH) {
            for (sx in 0 until selW) {
                val mx = floor(sx * scaleX).toInt().coerceIn(0, maskSize - 1)
                val my = floor(sy * scaleY).toInt().coerceIn(0, maskSize - 1)
                if (maskData[my * maskSize + mx])
                    sel.data[sy * selW + sx] = SelectionMask.SELECTED
            }
        }
        return sel
    }

    // BooleanArray doesn't have structural equals; override for data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedRegion) return false
        return maskSize == other.maskSize && maskData.contentEquals(other.maskData) &&
               centerX == other.centerX && centerY == other.centerY
    }
    override fun hashCode(): Int = maskData.contentHashCode() * 31 + maskSize
}
