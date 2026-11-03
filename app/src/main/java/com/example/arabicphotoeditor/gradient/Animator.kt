package com.example.arabicphotoeditor.gradient

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a project plus its [AnimationSpec] into a still project for any moment of the loop. [t] is the position in
 * the loop, 0 <= t < 1 (t = 1 equals t = 0 for a seamless loop). Pure Kotlin; rendering stays in [GradientRenderer].
 */
object Animator {

    private const val TAU = (2.0 * PI).toFloat()

    fun frame(project: GradientProject, t: Float): GradientProject {
        val anim = project.animation
        if (!anim.isActive) return project
        val tt = t - Math.floor(t.toDouble()).toFloat()

        var layers = project.layers
        var selected = project.selectedIndex
        val target = project.morphTarget
        if (anim.morph && target != null && target.isNotEmpty()) {
            val m = 0.5f - 0.5f * cos(TAU * tt)
            layers = morphLayers(layers, target, m)
            selected = selected.coerceIn(0, layers.lastIndex)
        }
        layers = layers.map { it.copy(spec = motion(it.spec, anim, tt)) }

        val hueDeg = 360f * anim.hueCycles * tt
        val lights = project.lighting.lights.mapIndexed { i, l ->
            val phase = i / project.lighting.lights.size.toFloat()
            var x = l.x
            var y = l.y
            if (anim.orbit > 0f) {
                x = (x + anim.orbit * cos(TAU * (tt + phase))).coerceIn(0f, 1f)
                y = (y + anim.orbit * sin(TAU * (tt + phase))).coerceIn(0f, 1f)
            }
            l.copy(x = x, y = y, color = if (hueDeg != 0f) ColorLab.rotateHue(l.color, hueDeg) else l.color)
        }
        val background = if (hueDeg != 0f && project.background != 0) ColorLab.rotateHue(project.background, hueDeg) else project.background
        return project.copy(
            layers = layers,
            selectedIndex = selected,
            background = background,
            lighting = project.lighting.copy(lights = lights),
        )
    }

    /** Renders the frame at loop position [t]. */
    fun render(project: GradientProject, t: Float, w: Int, h: Int, cancelled: () -> Boolean = { false }): IntArray =
        GradientRenderer.render(frame(project, t), w, h, cancelled)

    // ------------------------------------------------------------------ motions

    private fun motion(spec: GradientSpec, anim: AnimationSpec, t: Float): GradientSpec {
        var s = spec
        if (anim.rotate != 0) s = s.copy(angle = s.angle + 360f * anim.rotate * t)
        if (anim.pulse > 0f) s = s.copy(scale = s.scale * (1f + anim.pulse * sin(TAU * t)))
        if (anim.orbit > 0f) {
            s = s.copy(
                centerX = (s.centerX + anim.orbit * cos(TAU * t)).coerceIn(0f, 1f),
                centerY = (s.centerY + anim.orbit * sin(TAU * t)).coerceIn(0f, 1f),
            )
            if (s.type == GradientType.Gravity) {
                val n = s.gravity.points.size
                s = s.copy(
                    gravity = s.gravity.copy(
                        points = s.gravity.points.mapIndexed { i, p ->
                            val phase = i / n.toFloat()
                            p.copy(
                                x = (p.x + anim.orbit * cos(TAU * (t + phase))).coerceIn(0f, 1f),
                                y = (p.y + anim.orbit * sin(TAU * (t + phase))).coerceIn(0f, 1f),
                            )
                        },
                    ),
                )
            }
        }
        if (anim.flow != 0 && s.usesStops) s = s.copy(stops = flowStops(s.stops, anim.flow * t))
        if (anim.hueCycles != 0) s = rotateSpecHue(s, 360f * anim.hueCycles * t)
        return s
    }

    /**
     * The ramp made cyclic (forward then back, so there is no seam) and shifted by [offset] cycles. The result is
     * sampled into stops again, so the renderer needs no special case.
     */
    private fun flowStops(stops: List<ColorStop>, offset: Float): List<ColorStop> {
        val lut = GradientRenderer.buildLut(stops)
        val last = GradientRenderer.LUT_SIZE - 1
        val n = 33
        return List(n) { i ->
            val u = i / (n - 1f)
            var v = u + offset
            v -= Math.floor(v.toDouble()).toFloat()
            val pos = if (v < 0.5f) 2f * v else 2f - 2f * v
            ColorStop(u, lut[(pos * last + 0.5f).toInt().coerceIn(0, last)])
        }
    }

    private fun rotateSpecHue(s: GradientSpec, deg: Float): GradientSpec = s.copy(
        stops = s.stops.map { it.copy(color = ColorLab.rotateHue(it.color, deg)) },
        mesh = s.mesh.copy(colors = s.mesh.colors.map { ColorLab.rotateHue(it, deg) }),
        gravity = s.gravity.copy(points = s.gravity.points.map { it.copy(color = ColorLab.rotateHue(it.color, deg)) }),
    )

    // ------------------------------------------------------------------ morph

    /** Blends two sets of layers. Layers that cannot be interpolated parameter by parameter cross-fade. */
    fun morphLayers(from: List<GradientLayer>, to: List<GradientLayer>, m: Float): List<GradientLayer> {
        if (m <= 0f) return from
        if (m >= 1f) return to
        val out = ArrayList<GradientLayer>()
        val n = maxOf(from.size, to.size)
        var nextId = (from + to).maxOf { it.id } + 1
        for (i in 0 until n) {
            val a = from.getOrNull(i)
            val b = to.getOrNull(i)
            when {
                a != null && b != null && compatible(a.spec, b.spec) && a.blend == b.blend ->
                    out.add(
                        a.copy(
                            spec = lerpSpec(a.spec, b.spec, m),
                            opacity = lerp(a.opacity, b.opacity, m),
                            visible = a.visible || b.visible,
                        ),
                    )
                a != null && b != null -> {
                    out.add(a.copy(opacity = a.opacity * (1f - m)))
                    out.add(b.copy(id = nextId++, opacity = b.opacity * m))
                }
                a != null -> out.add(a.copy(opacity = a.opacity * (1f - m)))
                b != null -> out.add(b.copy(id = nextId++, opacity = b.opacity * m))
            }
        }
        return out
    }

    private fun compatible(a: GradientSpec, b: GradientSpec): Boolean {
        if (a.type != b.type) return false
        return when (a.type) {
            GradientType.Mesh -> a.mesh.cols == b.mesh.cols && a.mesh.rows == b.mesh.rows
            GradientType.Gravity -> a.gravity.points.size == b.gravity.points.size
            GradientType.Path -> a.path.points.size == b.path.points.size
            GradientType.Map -> a.map.image === b.map.image
            else -> true
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpSpec(a: GradientSpec, b: GradientSpec, m: Float): GradientSpec {
        val stops = if (a.stops.size == b.stops.size) {
            a.stops.indices.map { ColorStop(lerp(a.stops[it].position, b.stops[it].position, m), ColorLab.mix(a.stops[it].color, b.stops[it].color, m)) }
        } else {
            val ra = ColorLab.resample(a.stops, 12)
            val rb = ColorLab.resample(b.stops, 12)
            ra.indices.map { ColorStop(ra[it].position, ColorLab.mix(ra[it].color, rb[it].color, m)) }
        }
        return a.copy(
            stops = stops,
            centerX = lerp(a.centerX, b.centerX, m),
            centerY = lerp(a.centerY, b.centerY, m),
            angle = lerp(a.angle, b.angle, m),
            scale = lerp(a.scale, b.scale, m),
            turns = lerp(a.turns, b.turns, m),
            mesh = a.mesh.copy(colors = a.mesh.colors.indices.map { ColorLab.mix(a.mesh.colors[it], b.mesh.colors.getOrElse(it) { a.mesh.colors[it] }, m) }),
            gravity = a.gravity.copy(
                points = a.gravity.points.indices.map { i ->
                    val p = a.gravity.points[i]
                    val q = b.gravity.points.getOrElse(i) { p }
                    GravityPoint(lerp(p.x, q.x, m), lerp(p.y, q.y, m), ColorLab.mix(p.color, q.color, m), lerp(p.mass, q.mass, m))
                },
                power = lerp(a.gravity.power, b.gravity.power, m),
            ),
            path = a.path.copy(
                points = a.path.points.indices.map { i ->
                    val p = a.path.points[i]
                    val q = b.path.points.getOrElse(i) { p }
                    PathPoint(lerp(p.x, q.x, m), lerp(p.y, q.y, m))
                },
                width = lerp(a.path.width, b.path.width, m),
                softness = lerp(a.path.softness, b.path.softness, m),
            ),
            map = a.map.copy(gamma = lerp(a.map.gamma, b.map.gamma, m)),
        )
    }
}
