package com.example.arabicphotoeditor.gradient

/** How the studio's gradient is combined with the user's photo. */
enum class PhotoMode {
    /** The photo (usually a cut-out with a transparent background) is placed over the gradient. */
    Background,

    /** The gradient is blended over the photo; transparent parts of the photo stay transparent. */
    Overlay,

    /** The photo's brightness is replaced by the gradient's colors (dark = start color, light = end color). */
    Map,
}

data class PhotoApply(
    val mode: PhotoMode = PhotoMode.Overlay,
    val blend: BlendMode = BlendMode.SoftLight,
    /** Strength of the gradient, 0..1 (ignored for [PhotoMode.Background]). */
    val opacity: Float = 0.8f,
)

/** Pure Kotlin so it can be tested on a plain JVM. All arrays are non-premultiplied ARGB. */
object PhotoCompose {

    fun apply(
        photo: IntArray,
        w: Int,
        h: Int,
        project: GradientProject,
        options: PhotoApply,
        cancelled: () -> Boolean = { false },
    ): IntArray {
        require(photo.size == w * h)
        return when (options.mode) {
            PhotoMode.Background -> {
                val out = GradientRenderer.render(project, w, h, cancelled)
                GradientRenderer.composite(out, photo, 1f, BlendMode.Normal)
                out
            }

            PhotoMode.Overlay -> {
                val gradient = GradientRenderer.render(project, w, h, cancelled)
                val out = photo.copyOf()
                GradientRenderer.composite(out, gradient, options.opacity.coerceIn(0f, 1f), options.blend)
                keepAlpha(out, photo)
                out
            }

            PhotoMode.Map -> {
                val stops = rampOf(project.selected.spec)
                val lut = GradientRenderer.buildLut(stops)
                val last = GradientRenderer.LUT_SIZE - 1
                val mapped = IntArray(photo.size)
                for (i in photo.indices) {
                    val c = photo[i]
                    val luma = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                    val color = lut[(luma * last + 0.5f).toInt().coerceIn(0, last)]
                    mapped[i] = color
                }
                val out = photo.copyOf()
                GradientRenderer.composite(out, mapped, options.opacity.coerceIn(0f, 1f), BlendMode.Normal)
                keepAlpha(out, photo)
                out
            }
        }
    }

    /** The color ramp that represents any layer type. */
    fun rampOf(spec: GradientSpec): List<ColorStop> =
        if (spec.usesStops) spec.stops else ColorLab.stopsOf(ColorLab.colorsOf(spec))

    /** The photo decides where pixels exist: effects never fill in its transparent areas. */
    private fun keepAlpha(out: IntArray, photo: IntArray) {
        for (i in out.indices) out[i] = (photo[i] and 0xFF000000.toInt()) or (out[i] and 0x00FFFFFF)
    }
}
