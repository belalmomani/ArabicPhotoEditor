package com.example.arabicphotoeditor.gradient

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Renderers for the content-driven layer types: mesh, gravity, path and gradient map.
 * All write non-premultiplied ARGB into `out` (size w*h) and poll [cancelled] once per row.
 */
object SourceRenderers {

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Packs OKLab + alpha per node (stride 4) so mesh and mesh resizing share one sampler. */
    fun meshLab(colors: List<Int>): FloatArray {
        val lab = FloatArray(colors.size * 4)
        for (i in colors.indices) {
            ColorMath.toOklab(colors[i], lab, i * 4)
            lab[i * 4 + 3] = (colors[i] ushr 24).toFloat()
        }
        return lab
    }

    /** Samples the mesh at (u, v) in 0..1; writes [L, a, b, alpha] to [out]. Smoothstep keeps node creases soft. */
    fun sampleMesh(mesh: MeshSpec, lab: FloatArray, u: Float, v: Float, out: FloatArray) {
        val cols = mesh.cols
        val rows = mesh.rows
        val gx = u.coerceIn(0f, 1f) * (cols - 1)
        val gy = v.coerceIn(0f, 1f) * (rows - 1)
        val x0 = min(gx.toInt(), cols - 2)
        val y0 = min(gy.toInt(), rows - 2)
        val fx = smooth(gx - x0)
        val fy = smooth(gy - y0)
        val i00 = (y0 * cols + x0) * 4
        val i10 = i00 + 4
        val i01 = i00 + cols * 4
        val i11 = i01 + 4
        for (k in 0..3) {
            val top = lab[i00 + k] + (lab[i10 + k] - lab[i00 + k]) * fx
            val bottom = lab[i01 + k] + (lab[i11 + k] - lab[i01 + k]) * fx
            out[k] = top + (bottom - top) * fy
        }
    }

    fun renderMesh(mesh: MeshSpec, w: Int, h: Int, out: IntArray, cancelled: () -> Boolean) {
        val safe = if (mesh.colors.size == mesh.cols * mesh.rows && mesh.cols >= 2 && mesh.rows >= 2) mesh
        else MeshSpec()
        val lab = meshLab(safe.colors)
        val s = FloatArray(4)
        for (y in 0 until h) {
            if (cancelled()) return
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                sampleMesh(safe, lab, (x + 0.5f) / w, v, s)
                out[y * w + x] = ColorMath.fromOklabFast(s[0], s[1], s[2], (s[3] + 0.5f).toInt())
            }
        }
    }

    /** Inverse-distance weighting in OKLab: weight = mass / distance^power (distance relative to the long side). */
    fun renderGravity(spec: GravitySpec, w: Int, h: Int, out: IntArray, cancelled: () -> Boolean) {
        val pts = spec.points
        val n = pts.size
        if (n == 0) {
            out.fill(0)
            return
        }
        val lab = FloatArray(n * 4)
        val px = FloatArray(n)
        val py = FloatArray(n)
        val mass = FloatArray(n)
        for (i in 0 until n) {
            ColorMath.toOklab(pts[i].color, lab, i * 4)
            lab[i * 4 + 3] = (pts[i].color ushr 24).toFloat()
            px[i] = pts[i].x * w
            py[i] = pts[i].y * h
            mass[i] = max(pts[i].mass, 0.01f)
        }
        val ref = max(w, h).toFloat()
        val power = spec.power.coerceIn(0.5f, 8f)
        val half = power / 2f
        val squared = power == 2f

        for (y in 0 until h) {
            if (cancelled()) return
            for (x in 0 until w) {
                var sw = 0f
                var s0 = 0f
                var s1 = 0f
                var s2 = 0f
                var s3 = 0f
                for (i in 0 until n) {
                    val dx = (x + 0.5f - px[i]) / ref
                    val dy = (y + 0.5f - py[i]) / ref
                    val d2 = dx * dx + dy * dy + 1e-5f
                    val wt = if (squared) mass[i] / d2 else mass[i] / d2.pow(half)
                    sw += wt
                    s0 += wt * lab[i * 4]
                    s1 += wt * lab[i * 4 + 1]
                    s2 += wt * lab[i * 4 + 2]
                    s3 += wt * lab[i * 4 + 3]
                }
                out[y * w + x] = ColorMath.fromOklabFast(s0 / sw, s1 / sw, s2 / sw, (s3 / sw + 0.5f).toInt())
            }
        }
    }

    /** Colors follow the path's length (0 at its start, 1 at its end); pixels fade with distance from it. */
    fun renderPath(spec: PathSpec, stops: List<ColorStop>, w: Int, h: Int, out: IntArray, cancelled: () -> Boolean) {
        val pts = spec.points
        if (pts.size < 2) {
            out.fill(0)
            return
        }
        val lut = GradientRenderer.buildLut(stops)
        val last = GradientRenderer.LUT_SIZE - 1
        val segCount = pts.size - 1
        val ax = FloatArray(segCount)
        val ay = FloatArray(segCount)
        val bx = FloatArray(segCount)
        val by = FloatArray(segCount)
        val start = FloatArray(segCount)
        val len = FloatArray(segCount)
        var total = 0f
        for (i in 0 until segCount) {
            ax[i] = pts[i].x * w
            ay[i] = pts[i].y * h
            bx[i] = pts[i + 1].x * w
            by[i] = pts[i + 1].y * h
            len[i] = hypot(bx[i] - ax[i], by[i] - ay[i])
            start[i] = total
            total += len[i]
        }
        val radius = max(spec.width * max(w, h), 1f)
        val core = radius * (1f - spec.softness.coerceIn(0f, 0.98f))
        val flow = spec.flow

        for (y in 0 until h) {
            if (cancelled()) return
            val py = y + 0.5f
            for (x in 0 until w) {
                val px = x + 0.5f
                var bestD2 = Float.MAX_VALUE
                var bestT = 0f
                for (i in 0 until segCount) {
                    val ex = bx[i] - ax[i]
                    val ey = by[i] - ay[i]
                    val l2 = ex * ex + ey * ey
                    val u = if (l2 < 1e-6f) 0f else (((px - ax[i]) * ex + (py - ay[i]) * ey) / l2).coerceIn(0f, 1f)
                    val dx = px - (ax[i] + ex * u)
                    val dy = py - (ay[i] + ey * u)
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestD2) {
                        bestD2 = d2
                        bestT = if (total > 0f) (start[i] + u * len[i]) / total else 0f
                    }
                }
                var color = lut[(bestT * last + 0.5f).toInt().coerceIn(0, last)]
                if (!flow) {
                    val d = sqrt(bestD2)
                    val fade = when {
                        d <= core -> 1f
                        d >= radius -> 0f
                        else -> {
                            val u = (d - core) / (radius - core)
                            1f - u * u * (3f - 2f * u)
                        }
                    }
                    val alpha = ((color ushr 24) * fade + 0.5f).toInt()
                    color = (alpha shl 24) or (color and 0x00FFFFFF)
                }
                out[y * w + x] = color
            }
        }
    }

    /** Looks each pixel's luminance up in the color ramp. The photo is fitted to cover the canvas. */
    fun renderMap(spec: MapSpec, stops: List<ColorStop>, w: Int, h: Int, out: IntArray, cancelled: () -> Boolean) {
        val img = spec.image
        if (img == null || img.width < 1 || img.height < 1 || img.luma.size < img.width * img.height) {
            out.fill(0)
            return
        }
        val lut = GradientRenderer.buildLut(stops)
        val last = GradientRenderer.LUT_SIZE - 1
        val iw = img.width
        val ih = img.height
        val scale = max(w.toFloat() / iw, h.toFloat() / ih)
        val offX = (iw - w / scale) / 2f
        val offY = (ih - h / scale) / 2f
        val gamma = spec.gamma.coerceIn(0.2f, 5f)
        val useGamma = gamma != 1f

        for (y in 0 until h) {
            if (cancelled()) return
            val sy = ((y + 0.5f) / scale + offY - 0.5f).coerceIn(0f, ih - 1f)
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, ih - 1)
            val fy = sy - y0
            for (x in 0 until w) {
                val sx = ((x + 0.5f) / scale + offX - 0.5f).coerceIn(0f, iw - 1f)
                val x0 = floor(sx).toInt()
                val x1 = min(x0 + 1, iw - 1)
                val fx = sx - x0
                val a = img.luma[y0 * iw + x0].toInt() and 0xFF
                val b = img.luma[y0 * iw + x1].toInt() and 0xFF
                val c = img.luma[y1 * iw + x0].toInt() and 0xFF
                val d = img.luma[y1 * iw + x1].toInt() and 0xFF
                var l = ((a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy) / 255f
                if (spec.invert) l = 1f - l
                if (useGamma) l = l.pow(gamma)
                out[y * w + x] = lut[(l * last + 0.5f).toInt().coerceIn(0, last)]
            }
        }
    }
}
