package com.example.arabicphotoeditor.gradient

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU gradient renderer. Pure Kotlin. Colors are interpolated in OKLab through a 1024-entry lookup table, so the
 * per-pixel cost is only the geometry (a few float operations). Layers are composited with the W3C blend formulas.
 *
 * [cancelled] is polled once per row so a stale preview render can stop early.
 */
object GradientRenderer {

    const val LUT_SIZE = 1024
    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    /** Builds the color ramp for [stops] (sorted by position; values outside the first/last stop are clamped). */
    fun buildLut(stops: List<ColorStop>): IntArray {
        val lut = IntArray(LUT_SIZE)
        val s = stops.sortedBy { it.position }
        if (s.isEmpty()) return lut
        if (s.size == 1) {
            lut.fill(s[0].color)
            return lut
        }
        val lab = FloatArray(s.size * 3)
        for (i in s.indices) ColorMath.toOklab(s[i].color, lab, i * 3)

        var seg = 0
        for (i in 0 until LUT_SIZE) {
            val t = i / (LUT_SIZE - 1f)
            lut[i] = when {
                t <= s.first().position -> s.first().color
                t >= s.last().position -> s.last().color
                else -> {
                    while (seg < s.size - 2 && t > s[seg + 1].position) seg++
                    val p0 = s[seg].position
                    val p1 = s[seg + 1].position
                    val u = if (p1 - p0 < 1e-6f) 1f else ((t - p0) / (p1 - p0)).coerceIn(0f, 1f)
                    val a0 = (s[seg].color ushr 24).toFloat()
                    val a1 = (s[seg + 1].color ushr 24).toFloat()
                    ColorMath.fromOklab(
                        lab[seg * 3] + (lab[seg * 3 + 3] - lab[seg * 3]) * u,
                        lab[seg * 3 + 1] + (lab[seg * 3 + 4] - lab[seg * 3 + 1]) * u,
                        lab[seg * 3 + 2] + (lab[seg * 3 + 5] - lab[seg * 3 + 2]) * u,
                        (a0 + (a1 - a0) * u + 0.5f).toInt(),
                    )
                }
            }
        }
        return lut
    }

    private fun applySpread(v: Float, spread: Spread): Float = when (spread) {
        Spread.Clamp -> v.coerceIn(0f, 1f)
        Spread.Repeat -> v - floor(v)
        Spread.Mirror -> {
            val m = v - 2f * floor(v / 2f)
            if (m > 1f) 2f - m else m
        }
    }

    /** Renders one gradient into [out] (size w*h, non-premultiplied ARGB). */
    fun renderLayer(spec: GradientSpec, w: Int, h: Int, out: IntArray, cancelled: () -> Boolean = { false }) {
        when (spec.type) {
            GradientType.Mesh -> return SourceRenderers.renderMesh(spec.mesh, w, h, out, cancelled)
            GradientType.Gravity -> return SourceRenderers.renderGravity(spec.gravity, w, h, out, cancelled)
            GradientType.Path -> return SourceRenderers.renderPath(spec.path, spec.stops, w, h, out, cancelled)
            GradientType.Map -> return SourceRenderers.renderMap(spec.map, spec.stops, w, h, out, cancelled)
            else -> Unit
        }
        val lut = buildLut(spec.stops)
        val last = LUT_SIZE - 1
        val cx = spec.centerX * w
        val cy = spec.centerY * h
        val ang = Math.toRadians(spec.angle.toDouble()).toFloat()
        val cosA = cos(ang)
        val sinA = sin(ang)
        val scale = max(spec.scale, 0.01f)
        val farX = max(spec.centerX, 1f - spec.centerX) * w
        val farY = max(spec.centerY, 1f - spec.centerY) * h
        val radius = max(scale * hypot(farX, farY), 1e-3f)
        val linearLength = max(scale * (abs(w * cosA) + abs(h * sinA)), 1e-3f)
        val diamondLength = max(scale * 0.5f * (w + h), 1e-3f)
        val spread = spec.spread
        val turns = spec.turns
        val type = spec.type

        for (y in 0 until h) {
            if (cancelled()) return
            val dy = y + 0.5f - cy
            val row = y * w
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val t: Float = when (type) {
                    GradientType.Linear ->
                        applySpread((dx * cosA + dy * sinA) / linearLength + 0.5f, spread)

                    GradientType.Radial ->
                        applySpread(sqrt(dx * dx + dy * dy) / radius, spread)

                    GradientType.Conic -> {
                        var v = (atan2(dy, dx) - ang) / TWO_PI
                        v -= floor(v)
                        if (spread == Spread.Mirror) 1f - abs(2f * v - 1f) else v
                    }

                    GradientType.Spiral -> {
                        val v = (atan2(dy, dx) - ang) / TWO_PI + turns * sqrt(dx * dx + dy * dy) / radius
                        if (spread == Spread.Mirror) {
                            // atan2 jumps by exactly 1 at its branch cut; mirroring has period 2, so double the
                            // frequency to make that jump a whole period and keep the spiral seamless.
                            applySpread(2f * v, Spread.Mirror)
                        } else {
                            applySpread(v, Spread.Repeat)
                        }
                    }

                    GradientType.Diamond -> {
                        val xr = dx * cosA + dy * sinA
                        val yr = -dx * sinA + dy * cosA
                        applySpread((abs(xr) + abs(yr)) / diamondLength, spread)
                    }

                    // Handled by the early dispatch above; listed so the `when` stays exhaustive.
                    GradientType.Mesh, GradientType.Gravity, GradientType.Path, GradientType.Map -> 0f
                }
                out[row + x] = lut[(t * last + 0.5f).toInt().coerceIn(0, last)]
            }
        }
    }

    /** Renders all visible layers (bottom to top) over the project background. */
    fun render(project: GradientProject, w: Int, h: Int, cancelled: () -> Boolean = { false }): IntArray {
        val dst = IntArray(w * h)
        if (project.background != 0) dst.fill(project.background)
        val tmp = IntArray(w * h)
        for (layer in project.layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            renderLayer(layer.spec, w, h, tmp, cancelled)
            if (cancelled()) return dst
            composite(dst, tmp, layer.opacity.coerceIn(0f, 1f), layer.blend)
        }
        if (cancelled()) return dst
        Effects.applyLighting(dst, w, h, project.lighting, cancelled)
        Effects.applyTexture(dst, w, h, project.texture, cancelled)
        return dst
    }

    /** W3C "source over" with a separable blend mode, in place on [dst]. */
    fun composite(dst: IntArray, src: IntArray, opacity: Float, mode: BlendMode) {
        for (i in dst.indices) {
            val s = src[i]
            val sa = (s ushr 24) / 255f * opacity
            if (sa <= 0f) continue
            val d = dst[i]
            val da = (d ushr 24) / 255f

            if (mode == BlendMode.Normal && da == 0f) {
                dst[i] = ((sa * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (s and 0x00FFFFFF)
                continue
            }

            val ar = sa + da * (1f - sa)
            var packed = 0
            var shift = 16
            while (shift >= 0) {
                val cs = ((s shr shift) and 0xFF) / 255f
                val cb = ((d shr shift) and 0xFF) / 255f
                val blended = blendChannel(mode, cb, cs)
                val csPrime = (1f - da) * cs + da * blended
                val c = (sa * csPrime + (1f - sa) * da * cb) / ar
                packed = packed or ((c * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
                shift -= 8
            }
            dst[i] = ((ar * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or packed
        }
    }

    fun blendChannel(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.Normal -> cs
        BlendMode.Multiply -> cb * cs
        BlendMode.Screen -> cb + cs - cb * cs
        BlendMode.Overlay -> if (cb <= 0.5f) 2f * cb * cs else 1f - 2f * (1f - cb) * (1f - cs)
        BlendMode.SoftLight -> {
            if (cs <= 0.5f) {
                cb - (1f - 2f * cs) * cb * (1f - cb)
            } else {
                val dd = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
                cb + (2f * cs - 1f) * (dd - cb)
            }
        }
        BlendMode.Add -> kotlin.math.min(1f, cb + cs)
        BlendMode.Difference -> abs(cb - cs)
    }
}
