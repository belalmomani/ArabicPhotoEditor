import com.example.arabicphotoeditor.gradient.*
import java.io.File
import kotlin.random.Random
fun main(args: Array<String>) {
    val rng = Random(11); val w = 120; val h = 80
    val tiles = ArrayList<IntArray>()
    fun lin(stops: List<ColorStop>, ang: Float = 0f) = GradientProject(layers = listOf(GradientLayer(1, "x", GradientSpec(stops = stops, angle = ang))), width = w, height = h)
    val a = GradientPresets.stopsOf(intArrayOf(0xFFFF512F.toInt(), 0xFFF09819.toInt()))
    val b = GradientPresets.stopsOf(intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt()))
    // row 1: collision with energy 0, .5, 1 and impact .3/.5/.7
    for ((imp, en) in listOf(0.3f to 0f, 0.5f to 0.5f, 0.5f to 1f, 0.7f to 0.7f, 0.5f to 0f, 0.4f to 0.3f)) tiles += GradientRenderer.render(lin(ColorLab.collide(a, b, imp, 0.25f, en)), w, h)
    // row 2: evolution children
    val parent = GradientPresets.stopsOf(intArrayOf(0xFF6A5CFF.toInt(), 0xFFFF6FB1.toInt(), 0xFFFFE066.toInt()))
    tiles += GradientRenderer.render(lin(parent), w, h)
    for (c in ColorLab.nextGeneration(listOf(parent), 5, 0.6f, rng)) tiles += GradientRenderer.render(lin(c), w, h)
    // row 3: flow + rotate animation frames of a radial
    val rp = GradientProject(layers = listOf(GradientLayer(1, "r", GradientSpec(type = GradientType.Conic, stops = parent))), width = w, height = h,
        animation = AnimationSpec(rotate = 1, flow = 1, orbit = 0.1f))
    for (k in 0 until 6) tiles += Animator.render(rp, k / 6f, w, h)
    // row 4: morph linear->mesh crossfade
    val mp = GradientProject(layers = listOf(GradientLayer(1, "m", GradientSpec(stops = parent, angle = 0f))), width = w, height = h,
        animation = AnimationSpec(morph = true), morphTarget = listOf(GradientLayer(1, "m2", GradientSpec(type = GradientType.Mesh))))
    for (k in 0 until 6) tiles += Animator.render(mp, k / 12f, w, h)
    // row 5: same-type morph with angle + palette
    val sp = GradientProject(layers = listOf(GradientLayer(1, "s", GradientSpec(stops = a, angle = 0f))), width = w, height = h,
        animation = AnimationSpec(morph = true), morphTarget = listOf(GradientLayer(1, "s2", GradientSpec(stops = b, angle = 120f, scale = 1.5f))))
    for (k in 0 until 6) tiles += Animator.render(sp, k / 12f, w, h)
    File(args[0]).outputStream().use { os -> for (t in tiles) for (p in t) { os.write(p shr 16); os.write(p shr 8); os.write(p) } }
    println(tiles.size)
}
