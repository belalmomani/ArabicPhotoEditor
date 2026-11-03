package com.example.arabicphotoeditor.gradient

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Whole-image effects applied after the layers are composited: lighting first, then texture. */
object Effects {

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    // ------------------------------------------------------------ lighting

    /**
     * Each light adds a soft radial glow (strongest at its center, fading to nothing at [Light.radius]).
     * The glow is screen-blended over the image, so it also lights up transparent areas. The vignette darkens
     * the edges of what is already drawn and never touches transparency.
     */
    fun applyLighting(px: IntArray, w: Int, h: Int, spec: LightingSpec, cancelled: () -> Boolean = { false }) {
        val lights = spec.lights
        if (lights.isNotEmpty()) {
            val n = lights.size
            val ref = max(w, h).toFloat()
            val lx = FloatArray(n) { lights[it].x * w }
            val ly = FloatArray(n) { lights[it].y * h }
            val lr = FloatArray(n) { max(lights[it].radius * ref, 1f) }
            val k = FloatArray(n) { lights[it].intensity.coerceIn(0f, 2f) }
            val cr = FloatArray(n) { ((lights[it].color shr 16) and 0xFF) / 255f }
            val cg = FloatArray(n) { ((lights[it].color shr 8) and 0xFF) / 255f }
            val cb = FloatArray(n) { (lights[it].color and 0xFF) / 255f }

            val glow = IntArray(w * h)
            for (y in 0 until h) {
                if (cancelled()) return
                for (x in 0 until w) {
                    var gr = 0f
                    var gg = 0f
                    var gb = 0f
                    for (i in 0 until n) {
                        val d = hypot(x + 0.5f - lx[i], y + 0.5f - ly[i]) / lr[i]
                        if (d >= 1f) continue
                        val f = (1f - d) * (1f - d) * k[i]
                        gr += f * cr[i]
                        gg += f * cg[i]
                        gb += f * cb[i]
                    }
                    val peak = max(gr, max(gg, gb))
                    if (peak <= 0f) continue
                    val alpha = peak.coerceAtMost(1f)
                    glow[y * w + x] = ((alpha * 255f + 0.5f).toInt() shl 24) or
                        ((gr / peak * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((gg / peak * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (gb / peak * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
            }
            GradientRenderer.composite(px, glow, 1f, BlendMode.Screen)
        }

        val vignette = spec.vignette.coerceIn(0f, 1f)
        if (vignette > 0f) {
            for (y in 0 until h) {
                if (cancelled()) return
                val ny = (y + 0.5f) / h - 0.5f
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    if ((c ushr 24) == 0) continue
                    val nx = (x + 0.5f) / w - 0.5f
                    val r = hypot(nx, ny) / 0.7071f
                    val t = ((r - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    val f = 1f - vignette * smooth(t)
                    px[y * w + x] = (c and (0xFF shl 24)) or
                        (((c shr 16 and 0xFF) * f + 0.5f).toInt() shl 16) or
                        (((c shr 8 and 0xFF) * f + 0.5f).toInt() shl 8) or
                        ((c and 0xFF) * f + 0.5f).toInt()
                }
            }
        }
    }

    // ------------------------------------------------------------ texture

    private fun hash(x: Int, y: Int, seed: Int, salt: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177 + salt * 362437
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /** Uniform value in [0, 1). */
    private fun rand(x: Int, y: Int, seed: Int, salt: Int) = (hash(x, y, seed, salt) and 0xFFFFFF) / 16777216f

    private fun valueNoise(fx: Float, fy: Float, seed: Int, salt: Int): Float {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = smooth(fx - ix)
        val ty = smooth(fy - iy)
        val a = rand(ix, iy, seed, salt)
        val b = rand(ix + 1, iy, seed, salt)
        val c = rand(ix, iy + 1, seed, salt)
        val d = rand(ix + 1, iy + 1, seed, salt)
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }

    /** Three octaves, result in about 0..1. */
    private fun fbm(fx: Float, fy: Float, seed: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var f = 1f
        for (o in 0 until 3) {
            sum += amp * valueNoise(fx * f, fy * f, seed, 100 + o)
            amp *= 0.5f
            f *= 2f
        }
        return sum / 0.875f
    }

    /**
     * Grain and noise are sized relative to a 1080 px canvas, so the preview and a large export look alike.
     * Only pixels that have some alpha are touched, and alpha is never changed.
     */
    fun applyTexture(px: IntArray, w: Int, h: Int, spec: TextureSpec, cancelled: () -> Boolean = { false }) {
        if (!spec.isActive) return
        val ref = max(w, h).toFloat()
        val cell = max(1, (spec.grainSize * ref / 1080f).roundToInt())
        val noiseCell = max(2f, spec.noiseScale * ref)
        val grain = spec.grain.coerceIn(0f, 1f) * 0.5f * 255f
        val noise = spec.noise.coerceIn(0f, 1f) * 0.35f * 255f
        val seed = spec.seed

        for (y in 0 until h) {
            if (cancelled()) return
            for (x in 0 until w) {
                val c = px[y * w + x]
                if ((c ushr 24) == 0) continue
                var dr = 0f
                var dg = 0f
                var db = 0f
                if (grain > 0f) {
                    val gx = x / cell
                    val gy = y / cell
                    if (spec.monochrome) {
                        val n = (rand(gx, gy, seed, 0) * 2f - 1f) * grain
                        dr = n; dg = n; db = n
                    } else {
                        dr = (rand(gx, gy, seed, 1) * 2f - 1f) * grain
                        dg = (rand(gx, gy, seed, 2) * 2f - 1f) * grain
                        db = (rand(gx, gy, seed, 3) * 2f - 1f) * grain
                    }
                }
                if (noise > 0f) {
                    val n = (fbm(x / noiseCell, y / noiseCell, seed) * 2f - 1f) * noise
                    dr += n; dg += n; db += n
                }
                if (spec.dither) {
                    // Triangular noise of about +-1 level breaks up the steps of 8-bit gradients.
                    val t = (rand(x, y, seed, 7) + rand(x, y, seed, 8) - 1f) * 0.9f
                    dr += t; dg += t; db += t
                }
                val r = (((c shr 16) and 0xFF) + dr + 0.5f).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + dg + 0.5f).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) + db + 0.5f).toInt().coerceIn(0, 255)
                px[y * w + x] = (c and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
