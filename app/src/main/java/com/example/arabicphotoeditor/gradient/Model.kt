package com.example.arabicphotoeditor.gradient

import kotlin.random.Random

/** Basic ramps first, then the content-driven sources (each has its own parameters in [GradientSpec]). */
enum class GradientType { Linear, Radial, Conic, Spiral, Diamond, Mesh, Gravity, Path, Map }

/** What happens outside the 0..1 range of the color stops. */
enum class Spread { Clamp, Repeat, Mirror }

enum class BlendMode { Normal, Multiply, Screen, Overlay, SoftLight, Add, Difference }

/** [position] is 0..1 along the gradient; [color] is non-premultiplied ARGB. */
data class ColorStop(val position: Float, val color: Int)

/** A grid of colors (row-major, [cols] x [rows]) blended smoothly in OKLab. */
data class MeshSpec(
    val cols: Int = 3,
    val rows: Int = 3,
    val colors: List<Int> = listOf(
        0xFFFF6FB1.toInt(), 0xFFFFB86B.toInt(), 0xFFFFE066.toInt(),
        0xFF6A5CFF.toInt(), 0xFFB07CFF.toInt(), 0xFFFF7A9A.toInt(),
        0xFF2EC4B6.toInt(), 0xFF4D96FF.toInt(), 0xFF8E7DFF.toInt(),
    ),
) {
    /** A new grid size that keeps the look: each new node samples the old mesh at the same relative position. */
    fun resized(newCols: Int, newRows: Int): MeshSpec {
        val c = newCols.coerceIn(MIN_SIZE, MAX_SIZE)
        val r = newRows.coerceIn(MIN_SIZE, MAX_SIZE)
        if (c == cols && r == rows) return this
        val lab = SourceRenderers.meshLab(colors)
        val out = ArrayList<Int>(c * r)
        val sample = FloatArray(4)
        for (y in 0 until r) for (x in 0 until c) {
            SourceRenderers.sampleMesh(this, lab, x / (c - 1f), y / (r - 1f), sample)
            out.add(ColorMath.fromOklab(sample[0], sample[1], sample[2], (sample[3] + 0.5f).toInt()))
        }
        return MeshSpec(c, r, out)
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 5
    }
}

/** A "mass" of color at a canvas position (0..1). Heavier masses reach farther. */
data class GravityPoint(val x: Float, val y: Float, val color: Int, val mass: Float = 1f)

/** [power] controls falloff: low = soft wide blends, high = tight wells around each point. */
data class GravitySpec(
    val points: List<GravityPoint> = listOf(
        GravityPoint(0.25f, 0.3f, 0xFFFF6FB1.toInt()),
        GravityPoint(0.75f, 0.35f, 0xFF4D96FF.toInt()),
        GravityPoint(0.5f, 0.8f, 0xFFFFE066.toInt()),
    ),
    val power: Float = 2f,
)

data class PathPoint(val x: Float, val y: Float)

/**
 * A hand-drawn path that carries the color stops along its length. [width] is the half-thickness as a fraction of
 * the canvas' long side; [softness] 0..1 widens the fade at the edge. With [flow] the colors fill the whole canvas
 * following the path instead of fading out around it.
 */
data class PathSpec(
    val points: List<PathPoint> = listOf(
        PathPoint(0.12f, 0.7f), PathPoint(0.3f, 0.3f), PathPoint(0.5f, 0.6f),
        PathPoint(0.7f, 0.3f), PathPoint(0.88f, 0.65f),
    ),
    val width: Float = 0.1f,
    val softness: Float = 0.6f,
    val flow: Boolean = false,
)

/** A downscaled 8-bit luminance copy of a photo, used by the gradient map. Compared by identity. */
class MapImage(val width: Int, val height: Int, val luma: ByteArray)

/** Gradient map: the color stops are applied to the luminance of [image]. */
data class MapSpec(
    val image: MapImage? = null,
    val invert: Boolean = false,
    val gamma: Float = 1f,
)

/**
 * One layer's source. Coordinates are relative to the canvas (0..1), [angle] is in degrees
 * (0 = left to right, 90 = top to bottom), [scale] multiplies the gradient's reach.
 * Only the fields that belong to [type] are used.
 */
data class GradientSpec(
    val type: GradientType = GradientType.Linear,
    val stops: List<ColorStop> = listOf(
        ColorStop(0f, 0xFF6A5CFF.toInt()),
        ColorStop(1f, 0xFFFF6FB1.toInt()),
    ),
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val angle: Float = 45f,
    val scale: Float = 1f,
    /** Spiral only: how many times the colors wind around the center. */
    val turns: Float = 3f,
    val spread: Spread = Spread.Clamp,
    val mesh: MeshSpec = MeshSpec(),
    val gravity: GravitySpec = GravitySpec(),
    val path: PathSpec = PathSpec(),
    val map: MapSpec = MapSpec(),
) {
    /** Types whose look comes from the color stops (the Colors tab applies). */
    val usesStops: Boolean
        get() = type != GradientType.Mesh && type != GradientType.Gravity
}

data class GradientLayer(
    val id: Long,
    val name: String,
    val spec: GradientSpec = GradientSpec(),
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.Normal,
    val visible: Boolean = true,
)

/** A soft glow. [radius] is a fraction of the canvas' long side. */
data class Light(
    val x: Float = 0.5f,
    val y: Float = 0.35f,
    val radius: Float = 0.45f,
    val intensity: Float = 0.8f,
    val color: Int = 0xFFFFF1D6.toInt(),
)

/** Glows (screen-blended over the result) and an edge-darkening vignette. */
data class LightingSpec(
    val lights: List<Light> = emptyList(),
    val vignette: Float = 0f,
)

/**
 * Grain and noise texture, applied on top of the finished image (alpha is never changed).
 * [grain] is per-pixel film grain, [noise] a smooth cloudy variation, [dither] breaks up banding.
 */
data class TextureSpec(
    val grain: Float = 0f,
    /** Grain cell size in pixels at 1080 px; scales with export size so preview matches export. */
    val grainSize: Float = 1f,
    val noise: Float = 0f,
    /** Cloud size as a fraction of the canvas' long side. */
    val noiseScale: Float = 0.25f,
    val monochrome: Boolean = true,
    val dither: Boolean = false,
    val seed: Int = 1,
) {
    val isActive: Boolean get() = grain > 0f || noise > 0f || dither
}

/**
 * Looping animation of the whole project. Every motion is periodic over one loop, so the last frame flows
 * seamlessly into the first. Integer "cycles" keep it seamless.
 */
data class AnimationSpec(
    val durationMs: Int = 3000,
    val fps: Int = 24,
    /** Full rotations of every angle per loop (negative = other direction). */
    val rotate: Int = 0,
    /** Radius (fraction of the canvas) of the circle the center, gravity points and lights travel on. */
    val orbit: Float = 0f,
    /** How many times the color ramp scrolls through itself per loop. */
    val flow: Int = 0,
    /** Full trips around the hue wheel per loop. */
    val hueCycles: Int = 0,
    /** Breathing of the scale, 0..0.5. */
    val pulse: Float = 0f,
    /** Morph to [GradientProject.morphTarget] and back. */
    val morph: Boolean = false,
) {
    val isActive: Boolean
        get() = rotate != 0 || orbit > 0f || flow != 0 || hueCycles != 0 || pulse > 0f || morph

    val frameCount: Int get() = (durationMs * fps / 1000).coerceAtLeast(2)
}

/** Immutable, so every edit is a cheap snapshot for undo and the time machine. Layers are bottom to top. */
data class GradientProject(
    val layers: List<GradientLayer>,
    val selectedIndex: Int = 0,
    val width: Int = 1080,
    val height: Int = 1080,
    /** ARGB; 0 keeps the canvas transparent. */
    val background: Int = 0,
    val lighting: LightingSpec = LightingSpec(),
    val texture: TextureSpec = TextureSpec(),
    val animation: AnimationSpec = AnimationSpec(),
    /** The look the animation morphs towards (layers only). Null until the user saves one. */
    val morphTarget: List<GradientLayer>? = null,
) {
    val selected: GradientLayer
        get() = layers[selectedIndex.coerceIn(0, layers.lastIndex)]

    fun updateSelected(transform: (GradientLayer) -> GradientLayer): GradientProject {
        val index = selectedIndex.coerceIn(0, layers.lastIndex)
        return copy(layers = layers.mapIndexed { i, layer -> if (i == index) transform(layer) else layer })
    }

    fun updateSpec(transform: (GradientSpec) -> GradientSpec): GradientProject =
        updateSelected { it.copy(spec = transform(it.spec)) }

    fun nextLayerId(): Long = (layers.maxOfOrNull { it.id } ?: 0L) + 1L

    companion object {
        fun new(name: String): GradientProject =
            GradientProject(layers = listOf(GradientLayer(id = 1L, name = name)))
    }
}

/** Ready-made color sets (evenly spaced stops) and a simple random generator. */
object GradientPresets {

    val palettes: List<IntArray> = listOf(
        intArrayOf(0xFF6A5CFF.toInt(), 0xFFFF6FB1.toInt()),
        intArrayOf(0xFFFF512F.toInt(), 0xFFF09819.toInt()),
        intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt()),
        intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()),
        intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt()),
        intArrayOf(0xFFFFD200.toInt(), 0xFFF7971E.toInt()),
        intArrayOf(0xFFFC466B.toInt(), 0xFF3F5EFB.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt(), 0xFF2C5364.toInt()),
        intArrayOf(0xFFF857A6.toInt(), 0xFFFF5858.toInt()),
        intArrayOf(0xFF43CEA2.toInt(), 0xFF185A9D.toInt()),
        intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
            0xFF32ADE6.toInt(), 0xFF5856D6.toInt(), 0xFFFF2D55.toInt(),
        ),
    )

    fun stopsOf(colors: IntArray): List<ColorStop> =
        colors.mapIndexed { i, c -> ColorStop(if (colors.size == 1) 0f else i / (colors.size - 1f), c) }

    /** An analogous 3-color palette around a random hue. */
    fun random(rng: Random): List<ColorStop> = stopsOf(randomColors(rng, 3))

    /** [count] harmonious colors: a random base hue, then steady hue steps with varied saturation and value. */
    fun randomColors(rng: Random, count: Int): IntArray {
        val hue = rng.nextFloat() * 360f
        val shift = 25f + rng.nextFloat() * 60f
        return IntArray(count) { i ->
            ColorMath.hsvToArgb(
                (hue + shift * 1.1f * i) % 360f,
                (0.55f + rng.nextFloat() * 0.4f).coerceAtMost(1f),
                (0.8f + rng.nextFloat() * 0.2f).coerceAtMost(1f),
            )
        }
    }
}
