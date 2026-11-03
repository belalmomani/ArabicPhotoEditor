package com.example.arabicphotoeditor.gradient

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Color theory helpers (no AI, only math): OKLCH conversion, WCAG contrast, harmony detection and generation,
 * plus the genetic operators used by Evolution, Color Roulette, Collision and DNA. Pure Kotlin.
 */
object ColorLab {

    // ------------------------------------------------------------------ OKLCH

    /** Returns [L 0..1, C >= 0, hue 0..360). */
    fun toLch(argb: Int): FloatArray {
        val lab = FloatArray(3)
        ColorMath.toOklab(argb, lab, 0)
        val c = hypot(lab[1], lab[2])
        var h = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
        if (h < 0f) h += 360f
        return floatArrayOf(lab[0], c, h)
    }

    private fun inGamut(L: Float, a: Float, b: Float): Boolean {
        val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        val e = 0.0005f
        return r in -e..1f + e && g in -e..1f + e && bl in -e..1f + e
    }

    /** OKLCH to ARGB. Colors outside the sRGB gamut lose chroma (not lightness or hue) until they fit. */
    fun fromLch(L: Float, C: Float, h: Float, alpha: Int = 255): Int {
        val ll = L.coerceIn(0f, 1f)
        val rad = Math.toRadians(h.toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()
        var c = max(C, 0f)
        if (!inGamut(ll, c * ca, c * sa)) {
            var lo = 0f
            var hi = c
            repeat(18) {
                val mid = (lo + hi) / 2f
                if (inGamut(ll, mid * ca, mid * sa)) lo = mid else hi = mid
            }
            c = lo
        }
        return ColorMath.fromOklab(ll, c * ca, c * sa, alpha)
    }

    fun angleDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Perceptual distance (Euclidean in OKLab; about 0.02 is a just-noticeable difference). */
    fun deltaE(a: Int, b: Int): Float {
        val x = FloatArray(3)
        val y = FloatArray(3)
        ColorMath.toOklab(a, x, 0)
        ColorMath.toOklab(b, y, 0)
        return Math.sqrt(((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2)).toDouble()).toFloat()
    }

    /** Mix in OKLab; alpha is mixed linearly. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val x = FloatArray(3)
        val y = FloatArray(3)
        ColorMath.toOklab(a, x, 0)
        ColorMath.toOklab(b, y, 0)
        val aa = (a ushr 24).toFloat()
        val ab = (b ushr 24).toFloat()
        return ColorMath.fromOklab(
            x[0] + (y[0] - x[0]) * t,
            x[1] + (y[1] - x[1]) * t,
            x[2] + (y[2] - x[2]) * t,
            (aa + (ab - aa) * t + 0.5f).toInt(),
        )
    }

    /** Rotates the hue (degrees) in OKLCH, keeping lightness, chroma and alpha. */
    fun rotateHue(argb: Int, degrees: Float): Int {
        val lch = toLch(argb)
        if (lch[1] < 1e-4f) return argb
        return fromLch(lch[0], lch[1], lch[2] + degrees, argb ushr 24)
    }

    // ------------------------------------------------------------------ contrast

    /** WCAG relative luminance of the sRGB color. */
    fun luminance(argb: Int): Float {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    // ------------------------------------------------------------------ harmony

    enum class Harmony(val offsets: FloatArray) {
        Monochrome(floatArrayOf(0f)),
        Analogous(floatArrayOf(-30f, 0f, 30f)),
        Complementary(floatArrayOf(0f, 180f)),
        SplitComplementary(floatArrayOf(0f, 150f, 210f)),
        Triadic(floatArrayOf(0f, 120f, 240f)),
        Tetradic(floatArrayOf(0f, 90f, 180f, 270f)),
    }

    class Analysis(
        /** Best matching harmony rule, or null when the hues fit none of them well. */
        val harmony: Harmony?,
        /** Average angular error (degrees) between the colors and the matched rule. */
        val harmonyError: Float,
        val minLightness: Float,
        val maxLightness: Float,
        val averageChroma: Float,
        /** WCAG contrast between the lightest and the darkest color. */
        val contrastRatio: Float,
        /** Lightness changes in one direction only (a clean ramp). */
        val monotonic: Boolean,
        /** Smallest perceptual distance between neighbours; tiny values waste a stop, huge ones can band. */
        val minStep: Float,
        val maxStep: Float,
    ) {
        val lowContrast: Boolean get() = contrastRatio < 1.5f
        val bandingRisk: Boolean get() = maxStep > 0.18f
    }

    /** Hues of colors that actually have a hue (neutrals are ignored). */
    private fun chromaticHues(colors: IntArray): List<Float> =
        colors.map { toLch(it) }.filter { it[1] > 0.03f }.map { it[2] }

    fun analyze(colors: IntArray): Analysis {
        require(colors.isNotEmpty())
        val lch = colors.map { toLch(it) }
        val hues = chromaticHues(colors)

        var best: Harmony? = null
        var bestScore = Float.MAX_VALUE
        var bestError = 0f
        if (hues.isEmpty()) {
            best = Harmony.Monochrome
            bestError = 0f
        } else {
            for (rule in Harmony.entries) {
                var ruleBest = Float.MAX_VALUE
                var ruleError = 0f
                var base = 0f
                while (base < 360f) {
                    val points = FloatArray(rule.offsets.size) { (base + rule.offsets[it]) % 360f }
                    val toRule = hues.map { h -> points.minOf { angleDistance(h, it) } }.average().toFloat()
                    val fromRule = points.map { p -> hues.minOf { angleDistance(p, it) } }.average().toFloat()
                    val score = toRule + fromRule
                    if (score < ruleBest) {
                        ruleBest = score
                        ruleError = toRule
                    }
                    base += 3f
                }
                // A tiny penalty per rule point prefers the simplest explanation.
                val total = ruleBest + rule.offsets.size * 1.5f
                if (total < bestScore) {
                    bestScore = total
                    best = rule
                    bestError = ruleError
                }
            }
            if (bestError > 22f) best = null
        }

        val ls = lch.map { it[0] }
        var increasing = true
        var decreasing = true
        for (i in 1 until ls.size) {
            if (ls[i] < ls[i - 1] - 0.01f) increasing = false
            if (ls[i] > ls[i - 1] + 0.01f) decreasing = false
        }
        var minStep = Float.MAX_VALUE
        var maxStep = 0f
        for (i in 1 until colors.size) {
            val d = deltaE(colors[i - 1], colors[i])
            minStep = min(minStep, d)
            maxStep = max(maxStep, d)
        }
        if (colors.size == 1) minStep = 0f
        val lightest = colors[ls.indices.maxByOrNull { ls[it] }!!]
        val darkest = colors[ls.indices.minByOrNull { ls[it] }!!]
        return Analysis(
            harmony = best,
            harmonyError = bestError,
            minLightness = ls.min(),
            maxLightness = ls.max(),
            averageChroma = lch.map { it[1] }.average().toFloat(),
            contrastRatio = contrast(lightest, darkest),
            monotonic = increasing || decreasing,
            minStep = minStep,
            maxStep = maxStep,
        )
    }

    /** [count] colors following [rule], starting at [baseHue]; lightness and chroma vary a little so it feels natural. */
    fun harmonize(rule: Harmony, baseHue: Float, count: Int, rng: Random, chroma: Float = 0.15f, lightness: Float = 0.72f): IntArray =
        IntArray(count) { i ->
            val hue = baseHue + rule.offsets[i % rule.offsets.size] + (if (rule == Harmony.Monochrome) 0f else (i / rule.offsets.size) * 8f)
            val l = (lightness + (rng.nextFloat() - 0.5f) * 0.3f + (if (rule == Harmony.Monochrome) (i - (count - 1) / 2f) * 0.1f else 0f)).coerceIn(0.2f, 0.95f)
            val c = (chroma * (0.75f + rng.nextFloat() * 0.5f)).coerceAtLeast(0.02f)
            fromLch(l, c, hue)
        }

    // ------------------------------------------------------------------ suggestions

    enum class Fix { MoreContrast, EvenLightness, EvenSteps, Harmonize, Vibrance, Soften }

    class Suggestion(val fix: Fix, val colors: IntArray, val rule: Harmony? = null)

    /** Ready-to-apply improvements. Only the ones that would change something are returned. */
    fun suggest(colors: IntArray): List<Suggestion> {
        if (colors.size < 2) return emptyList()
        val out = ArrayList<Suggestion>()
        val a = analyze(colors)
        val lch = colors.map { toLch(it) }

        if (a.contrastRatio < 3f) {
            // Push the two ends apart in lightness, then re-spread the middle between them.
            val first = lch.first()
            val last = lch.last()
            val darkFirst = first[0] <= last[0]
            val lo = 0.28f
            val hi = 0.92f
            val res = IntArray(colors.size) { i ->
                val t = i / (colors.size - 1f)
                val srcL = lch[i][0]
                val targetL = if (darkFirst) lo + (hi - lo) * t else hi - (hi - lo) * t
                fromLch(srcL * 0.3f + targetL * 0.7f, lch[i][1], lch[i][2], colors[i] ushr 24)
            }
            out.add(Suggestion(Fix.MoreContrast, res))
        }
        if (!a.monotonic) {
            val lo = a.minLightness
            val hi = a.maxLightness
            val ascending = lch.first()[0] <= lch.last()[0]
            val res = IntArray(colors.size) { i ->
                val t = i / (colors.size - 1f)
                val l = if (ascending) lo + (hi - lo) * t else hi - (hi - lo) * t
                fromLch(l, lch[i][1], lch[i][2], colors[i] ushr 24)
            }
            out.add(Suggestion(Fix.EvenLightness, res))
        }
        if (a.maxStep > 1.8f * a.minStep.coerceAtLeast(0.01f) && colors.size >= 3) {
            // Equalize the perceptual distance between neighbours by walking the OKLab path at equal arc lengths.
            out.add(Suggestion(Fix.EvenSteps, equalSteps(colors)))
        }
        val hues = chromaticHues(colors)
        if (hues.isNotEmpty()) {
            val baseHue = lch.first { it[1] > 0.03f }[2]
            val suggested = listOf(Harmony.Analogous, Harmony.Complementary, Harmony.Triadic, Harmony.SplitComplementary)
                .firstOrNull { it != a.harmony } ?: Harmony.Complementary
            // Keep lightness order and chroma of the original; only the hues are snapped onto the rule.
            val res = IntArray(colors.size) { i ->
                val hue = baseHue + suggested.offsets[i * suggested.offsets.size / colors.size.coerceAtLeast(1) % suggested.offsets.size]
                fromLch(lch[i][0], max(lch[i][1], 0.05f), hue, colors[i] ushr 24)
            }
            out.add(Suggestion(Fix.Harmonize, res, suggested))
        }
        if (a.averageChroma < 0.08f) {
            out.add(Suggestion(Fix.Vibrance, IntArray(colors.size) { fromLch(lch[it][0], lch[it][1] * 1.7f + 0.03f, lch[it][2], colors[it] ushr 24) }))
        } else if (a.averageChroma > 0.2f) {
            out.add(Suggestion(Fix.Soften, IntArray(colors.size) { fromLch(lch[it][0], lch[it][1] * 0.7f, lch[it][2], colors[it] ushr 24) }))
        }
        return out
    }

    /** Re-samples the polyline through the colors (in OKLab) at equal perceptual distances. */
    fun equalSteps(colors: IntArray): IntArray {
        val n = colors.size
        if (n < 3) return colors.copyOf()
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + deltaE(colors[i - 1], colors[i])
        val total = cum[n - 1]
        if (total < 1e-6f) return colors.copyOf()
        return IntArray(n) { k ->
            val target = total * k / (n - 1f)
            var seg = 0
            while (seg < n - 2 && cum[seg + 1] < target) seg++
            val span = cum[seg + 1] - cum[seg]
            val t = if (span < 1e-6f) 0f else ((target - cum[seg]) / span).coerceIn(0f, 1f)
            mix(colors[seg], colors[seg + 1], t)
        }
    }

    // ------------------------------------------------------------------ stops helpers

    /** Color of a ramp at [t] (OKLab interpolation, same as the renderer). */
    fun sample(stops: List<ColorStop>, t: Float): Int {
        val lut = GradientRenderer.buildLut(stops)
        return lut[(t.coerceIn(0f, 1f) * (GradientRenderer.LUT_SIZE - 1) + 0.5f).toInt()]
    }

    /** [count] evenly spaced samples of the ramp as new stops. */
    fun resample(stops: List<ColorStop>, count: Int): List<ColorStop> {
        val lut = GradientRenderer.buildLut(stops)
        val last = GradientRenderer.LUT_SIZE - 1
        return List(count) { i ->
            val t = if (count == 1) 0f else i / (count - 1f)
            ColorStop(t, lut[(t * last + 0.5f).toInt().coerceIn(0, last)])
        }
    }

    fun stopsOf(colors: IntArray): List<ColorStop> = GradientPresets.stopsOf(colors)

    /** The colors that define a spec, whatever its type (for palette-level tools). */
    fun colorsOf(spec: GradientSpec): IntArray = when (spec.type) {
        GradientType.Mesh -> spec.mesh.colors.toIntArray()
        GradientType.Gravity -> IntArray(spec.gravity.points.size) { spec.gravity.points[it].color }
        else -> IntArray(spec.stops.size) { spec.stops[it].color }
    }

    /**
     * Writes [colors] back into the spec. For stop-based types the existing stop positions are kept when the count
     * matches, otherwise the stops are spread evenly.
     */
    fun withColors(spec: GradientSpec, colors: IntArray): GradientSpec = when (spec.type) {
        GradientType.Mesh -> spec.copy(mesh = spec.mesh.copy(colors = List(spec.mesh.colors.size) { colors[it % colors.size] }))
        GradientType.Gravity -> spec.copy(
            gravity = spec.gravity.copy(points = spec.gravity.points.mapIndexed { i, p -> p.copy(color = colors[i % colors.size]) }),
        )
        else ->
            if (colors.size == spec.stops.size) {
                spec.copy(stops = spec.stops.mapIndexed { i, s -> s.copy(color = colors[i]) })
            } else {
                spec.copy(stops = stopsOf(colors))
            }
    }

    // ------------------------------------------------------------------ Evolution

    /**
     * A gradient's "genes" are its stops. [strength] 0..1 controls how far a child may drift from the parent
     * in lightness, chroma, hue and stop position.
     */
    fun mutate(stops: List<ColorStop>, rng: Random, strength: Float): List<ColorStop> {
        val s = stops.sortedBy { it.position }
        val mutated = s.mapIndexed { i, stop ->
            val lch = toLch(stop.color)
            val l = (lch[0] + (rng.nextFloat() - 0.5f) * 0.35f * strength).coerceIn(0.05f, 0.98f)
            val c = (lch[1] + (rng.nextFloat() - 0.5f) * 0.12f * strength).coerceIn(0f, 0.33f)
            val h = lch[2] + (rng.nextFloat() - 0.5f) * 140f * strength
            val color = if (lch[1] < 0.01f && rng.nextFloat() > strength) stop.color else fromLch(l, c, h, stop.color ushr 24)
            val edge = i == 0 || i == s.lastIndex
            val p = if (edge) stop.position else stop.position + (rng.nextFloat() - 0.5f) * 0.2f * strength
            ColorStop(p.coerceIn(0f, 1f), color)
        }
        return mutated.sortedBy { it.position }
    }

    /** Each child stop comes from one parent or the other (or a mix of the two), sampled at the same position. */
    fun crossover(a: List<ColorStop>, b: List<ColorStop>, rng: Random): List<ColorStop> {
        val n = max(a.size, b.size).coerceAtLeast(2)
        val ra = resample(a, n)
        val rb = resample(b, n)
        return List(n) { i ->
            val color = when (rng.nextInt(3)) {
                0 -> ra[i].color
                1 -> rb[i].color
                else -> mix(ra[i].color, rb[i].color, 0.5f)
            }
            ColorStop(if (rng.nextBoolean()) ra[i].position else rb[i].position, color)
        }.sortedBy { it.position }.let { fixEnds(it) }
    }

    private fun fixEnds(stops: List<ColorStop>): List<ColorStop> {
        if (stops.size < 2) return stops
        return stops.mapIndexed { i, s ->
            when (i) {
                0 -> s.copy(position = 0f)
                stops.lastIndex -> s.copy(position = 1f)
                else -> s
            }
        }
    }

    /**
     * The next generation of [count] children. With two or more [parents] some children are crossovers, the rest
     * are mutations of a random parent. The first parent is never reproduced exactly.
     */
    fun nextGeneration(parents: List<List<ColorStop>>, count: Int, strength: Float, rng: Random): List<List<ColorStop>> =
        List(count) { i ->
            if (parents.size >= 2 && i % 2 == 1) {
                val a = parents[rng.nextInt(parents.size)]
                var b = parents[rng.nextInt(parents.size)]
                if (b === a) b = parents[(parents.indexOf(a) + 1) % parents.size]
                mutate(crossover(a, b, rng), rng, strength * 0.5f)
            } else {
                mutate(parents[rng.nextInt(parents.size)], rng, strength)
            }
        }

    // ------------------------------------------------------------------ Color Roulette

    /**
     * Spins the wheel: a random harmony rule is chosen and every unlocked color is replaced. If some colors are
     * locked, the rule is built around the first locked color's hue so the new colors work with it.
     */
    fun spin(current: IntArray, locked: BooleanArray, rng: Random): Pair<Harmony, IntArray> {
        val rules = Harmony.entries
        val rule = rules[rng.nextInt(rules.size)]
        val anchor = current.indices.firstOrNull { locked.getOrElse(it) { false } && toLch(current[it])[1] > 0.03f }
        val baseHue = if (anchor != null) toLch(current[anchor])[2] else rng.nextFloat() * 360f
        val fresh = harmonize(rule, baseHue, current.size, rng, chroma = 0.1f + rng.nextFloat() * 0.1f, lightness = 0.55f + rng.nextFloat() * 0.25f)
        return rule to IntArray(current.size) { if (locked.getOrElse(it) { false }) current[it] else fresh[it] }
    }

    // ------------------------------------------------------------------ Collision

    /**
     * Two gradients meet at [impact] (0..1). Each is squeezed into its side, and where they touch the colors mix
     * and flash: [energy] 0..1 adds a bright, saturated spark across the contact zone of width [zone].
     */
    fun collide(a: List<ColorStop>, b: List<ColorStop>, impact: Float, zone: Float, energy: Float): List<ColorStop> {
        val imp = impact.coerceIn(0.15f, 0.85f)
        val half = zone.coerceIn(0.02f, 0.4f) / 2f
        val left = (imp - half).coerceAtLeast(0.05f)
        val right = (imp + half).coerceAtMost(0.95f)
        val aEnd = sample(a, 1f)
        val bStart = sample(b, 0f)
        val out = ArrayList<ColorStop>()
        val sideCount = 6
        for (i in 0 until sideCount) {
            val t = i / (sideCount - 1f)
            out.add(ColorStop(left * t, sample(a, t)))
        }
        val zoneSteps = 5
        for (i in 1 until zoneSteps - 1) {
            val t = i / (zoneSteps - 1f)
            val pos = left + (right - left) * t
            var color = mix(aEnd, bStart, t)
            val bump = (1f - abs(2f * t - 1f)) * energy
            if (bump > 0f) {
                val lch = toLch(color)
                color = fromLch((lch[0] + 0.28f * bump).coerceAtMost(0.98f), lch[1] * (1f + 0.6f * bump), lch[2], color ushr 24)
            }
            out.add(ColorStop(pos, color))
        }
        for (i in 0 until sideCount) {
            val t = i / (sideCount - 1f)
            out.add(ColorStop(right + (1f - right) * t, sample(b, t)))
        }
        return out.sortedBy { it.position }
    }
}
