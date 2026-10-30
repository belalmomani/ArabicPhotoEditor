package com.example.arabicphotoeditor.segmentation

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
 * Local background removal using U²-Net-P (u2netp.onnx, Apache-2.0), run on-device with ONNX Runtime.
 *
 * Pipeline: resize to 320x320 -> normalize -> inference -> min/max-normalize the saliency map ->
 * bilinear upsample to the original size -> smoothstep edge refinement -> ARGB bitmap with real alpha.
 *
 * Heavy work: call [remove] from a background dispatcher, never from the main thread.
 */
class BackgroundRemover(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val lock = Any()

    private fun session(): OrtSession = synchronized(lock) {
        session ?: run {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            env.createSession(bytes, options).also { session = it }
        }
    }

    /** Returns a new ARGB_8888 bitmap of the same size as [source], transparent where background was found. */
    fun remove(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val mask = predictMask(source)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Precompute bilinear sample positions for columns.
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val xf = FloatArray(w)
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * SIZE / w - 0.5f).coerceIn(0f, SIZE - 1f)
            val i = floor(fx).toInt()
            x0[x] = i
            x1[x] = min(i + 1, SIZE - 1)
            xf[x] = fx - i
        }

        for (y in 0 until h) {
            val fy = ((y + 0.5f) * SIZE / h - 0.5f).coerceIn(0f, SIZE - 1f)
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, SIZE - 1)
            val yf = fy - y0
            val row0 = y0 * SIZE
            val row1 = y1 * SIZE
            for (x in 0 until w) {
                val top = mask[row0 + x0[x]] * (1 - xf[x]) + mask[row0 + x1[x]] * xf[x]
                val bottom = mask[row1 + x0[x]] * (1 - xf[x]) + mask[row1 + x1[x]] * xf[x]
                val m = top * (1 - yf) + bottom * yf

                // Edge refinement: smoothstep pushes uncertain values toward 0/1 and
                // shifts the boundary slightly inward, which reduces background halos.
                val t = ((m - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
                val alpha = t * t * (3f - 2f * t)

                val p = pixels[y * w + x]
                val srcAlpha = p ushr 24
                val a = (alpha * srcAlpha + 0.5f).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Returns a SIZE*SIZE saliency mask in 0..1 (min/max-normalized). */
    private fun predictMask(source: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== source) small.recycle()

        // Same preprocessing as the reference pipeline: divide by the image max, then mean/std.
        var maxValue = 1e-6f
        for (p in px) {
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (r > maxValue) maxValue = r
            if (g > maxValue) maxValue = g
            if (b > maxValue) maxValue = b
        }

        val plane = SIZE * SIZE
        val buffer = ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = (((p shr 16) and 0xFF) / maxValue - MEAN[0]) / STD[0]
            data[plane + i] = (((p shr 8) and 0xFF) / maxValue - MEAN[1]) / STD[1]
            data[2 * plane + i] = ((p and 0xFF) / maxValue - MEAN[2]) / STD[2]
        }
        buffer.put(data)
        buffer.rewind()

        val session = session()
        val inputName = session.inputNames.first()
        val raw = FloatArray(plane)
        OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                // The first output is the fused, final prediction (already passed through sigmoid).
                val fb = (result[0] as OnnxTensor).floatBuffer
                fb.get(raw, 0, plane)
            }
        }

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in raw) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = (hi - lo).coerceAtLeast(1e-6f)
        for (i in raw.indices) raw[i] = (raw[i] - lo) / range
        return raw
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
    }

    private companion object {
        const val MODEL_ASSET = "u2netp.onnx"
        const val SIZE = 320
        const val EDGE_LOW = 0.20f
        const val EDGE_HIGH = 0.90f
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
