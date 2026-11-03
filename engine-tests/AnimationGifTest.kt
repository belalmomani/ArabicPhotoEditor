import com.example.arabicphotoeditor.gradient.*
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

fun check(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + " " + extra); if (!ok) System.exit(1) }

fun main(args: Array<String>) {
    val dir = args[0]
    val rng = Random(3)
    val a = GradientSpec(type = GradientType.Linear, stops = GradientPresets.stopsOf(intArrayOf(0xFF6A5CFF.toInt(), 0xFFFF6FB1.toInt(), 0xFFFFE066.toInt())))
    val b = GradientSpec(type = GradientType.Radial, stops = GradientPresets.stopsOf(intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt())))
    val base = GradientProject(layers = listOf(GradientLayer(1, "a", a)), width = 240, height = 160)

    // --- seamless loop: frame(0) == frame(1) for every motion
    val anim = AnimationSpec(durationMs = 2000, fps = 12, rotate = 1, orbit = 0.1f, flow = 1, hueCycles = 1, pulse = 0.2f)
    val p = base.copy(animation = anim)
    val f0 = Animator.render(p, 0f, 120, 80); val f1 = Animator.render(p, 1f, 120, 80)
    var maxDiff = 0
    for (i in f0.indices) for (s in intArrayOf(0, 8, 16)) maxDiff = maxOf(maxDiff, Math.abs(((f0[i] shr s) and 0xFF) - ((f1[i] shr s) and 0xFF)))
    check("loop seamless (t=0 vs t=1)", maxDiff <= 3, "maxDiff=$maxDiff")
    val fh = Animator.render(p, 0.5f, 120, 80)
    check("mid frame differs", f0.indices.count { f0[it] != fh[it] } > f0.size / 2)

    // --- morph: t=0 equals A, mid is between, different types cross-fade
    val mp = base.copy(animation = AnimationSpec(morph = true), morphTarget = listOf(GradientLayer(1, "b", b.copy(angle = 200f))))
    val m0 = Animator.render(mp, 0f, 60, 40); val ref = GradientRenderer.render(base, 60, 40)
    check("morph t=0 == A", m0.contentEquals(ref))
    val mm = Animator.render(mp, 0.5f, 60, 40)
    val refB = GradientRenderer.render(base.copy(layers = mp.morphTarget!!), 60, 40)
    var between = 0
    for (i in mm.indices) { val r = (mm[i] shr 16) and 0xFF; val ra = (ref[i] shr 16) and 0xFF; val rb = (refB[i] shr 16) and 0xFF; if (r >= minOf(ra, rb) - 25 && r <= maxOf(ra, rb) + 25) between++ }
    check("morph mid roughly between", between > mm.size * 0.9, "$between/${mm.size}")
    val sameTypeP = mp.copy(morphTarget = listOf(GradientLayer(1, "b", a.copy(angle = 200f, stops = GradientPresets.stopsOf(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))))))
    check("same-type morph is one layer", Animator.frame(sameTypeP, 0.25f).layers.size == 1)
    check("cross-type morph adds a layer", Animator.frame(mp, 0.25f).layers.size == 2)

    // --- GIF round trip
    val frames = 10
    val w = 160; val h = 100
    val proj = GradientProject(layers = listOf(GradientLayer(1, "a", a)), width = w, height = h, animation = AnimationSpec(durationMs = 1000, fps = frames, rotate = 1, hueCycles = 1))
    val rendered = List(frames) { Animator.render(proj, it / frames.toFloat(), w, h) }
    val pal = GifEncoder.buildPalette(rendered)
    FileOutputStream("$dir/anim.gif").use { os ->
        val enc = GifEncoder(os, w, h); enc.begin(pal, 0)
        for (f in rendered) enc.addFrame(f, 4)
        enc.finish()
    }
    check("gif written", File("$dir/anim.gif").length() > 1000, File("$dir/anim.gif").length().toString())
    // raw dump of original frames for Python comparison
    File("$dir/frames.raw").outputStream().use { os -> for (f in rendered) for (px in f) { os.write(px shr 16); os.write(px shr 8); os.write(px) } }

    // transparency: glow on transparent canvas
    val tp = GradientProject(layers = emptyList<GradientLayer>().ifEmpty { listOf(GradientLayer(1, "t", a, visible = false)) }, width = 64, height = 64,
        lighting = LightingSpec(lights = listOf(Light(0.5f, 0.5f, 0.3f, 1f))))
    val tf = GradientRenderer.render(tp, 64, 64)
    val tpal = GifEncoder.buildPalette(listOf(tf))
    check("transparency detected", tpal.hasTransparency)
    FileOutputStream("$dir/trans.gif").use { os -> val e = GifEncoder(os, 64, 64); e.begin(tpal, 0); e.addFrame(tf, 5); e.finish() }
    File("$dir/trans.raw").outputStream().use { os -> for (px in tf) { os.write(px ushr 24); os.write(px shr 16); os.write(px shr 8); os.write(px) } }

    // stress LZW with noise (forces table resets) and odd sizes
    val nw = 97; val nh = 53
    val noise = IntArray(nw * nh) { 0xFF000000.toInt() or rng.nextInt(0xFFFFFF) }
    val npal = GifEncoder.buildPalette(listOf(noise))
    FileOutputStream("$dir/noise.gif").use { os -> val e = GifEncoder(os, nw, nh); e.begin(npal, 0); e.addFrame(noise, 5); e.finish() }
    File("$dir/noise.raw").outputStream().use { os -> for (px in noise) { os.write(px shr 16); os.write(px shr 8); os.write(px) } }
    println("ALL OK")
}
