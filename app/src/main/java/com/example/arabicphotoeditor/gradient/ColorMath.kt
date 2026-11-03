package com.example.arabicphotoeditor.gradient

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Color helpers. Pure Kotlin (no Android classes) so the math stays testable on a plain JVM. */
object ColorMath {

    private val srgbToLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** Writes OKLab (L, a, b) of the color's RGB into [out] at [offset]. Alpha is ignored. */
    fun toOklab(argb: Int, out: FloatArray, offset: Int) {
        val r = srgbToLinear[(argb shr 16) and 0xFF]
        val g = srgbToLinear[(argb shr 8) and 0xFF]
        val b = srgbToLinear[argb and 0xFF]
        val l = Math.cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
        val m = Math.cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
        val s = Math.cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
        out[offset] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[offset + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[offset + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    fun fromOklab(L: Float, a: Float, b: Float, alpha: Int): Int {
        val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        val ri = (linearToSrgb(r) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (linearToSrgb(g) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (linearToSrgb(bl) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private val srgbEncodeLut = FloatArray(4097) { i -> linearToSrgb(i / 4096f) }

    private fun encodeFast(x: Float): Int {
        val c = x.coerceIn(0f, 1f) * 4096f
        val i = c.toInt().coerceAtMost(4095)
        val f = c - i
        return ((srgbEncodeLut[i] * (1f - f) + srgbEncodeLut[i + 1] * f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Same result as [fromOklab] (within one level) but with a table instead of `pow`, for per-pixel use. */
    fun fromOklabFast(L: Float, a: Float, b: Float, alpha: Int): Int {
        val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return (alpha.coerceIn(0, 255) shl 24) or (encodeFast(r) shl 16) or (encodeFast(g) shl 8) or encodeFast(bl)
    }

    /** [h] in degrees, [s] and [v] in 0..1. */
    fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (alpha.coerceIn(0, 255) shl 24) or
            ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Returns [hue 0..360, saturation 0..1, value 0..1]. */
    fun argbToHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }
}
