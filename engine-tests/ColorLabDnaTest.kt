import com.example.arabicphotoeditor.gradient.*
import kotlin.random.Random

fun check(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + " " + extra); if (!ok) System.exit(1) }

fun main() {
    val rng = Random(7)
    // LCH round trip
    for (c in listOf(0xFFFF6FB1.toInt(), 0xFF2EC4B6.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
        val l = ColorLab.toLch(c); val back = ColorLab.fromLch(l[0], l[1], l[2])
        check("lch roundtrip %08X".format(c), ColorLab.deltaE(c, back) < 0.01f, "%08X".format(back))
    }
    // gamut clip: absurd chroma keeps hue, no exception
    val clip = ColorLab.fromLch(0.6f, 0.6f, 140f)
    check("gamut clip", ColorLab.deltaE(clip, ColorLab.fromLch(0.6f, 0.3f, 140f)) < 0.35f)
    // contrast black/white = 21
    check("contrast 21", Math.abs(ColorLab.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) - 21f) < 0.01f)
    // harmony detection for generated palettes
    var ok = 0; var total = 0
    for (rule in listOf(ColorLab.Harmony.Analogous, ColorLab.Harmony.Complementary, ColorLab.Harmony.Triadic, ColorLab.Harmony.SplitComplementary, ColorLab.Harmony.Tetradic)) {
        var hit = 0
        repeat(40) {
            val cols = ColorLab.harmonize(rule, rng.nextFloat() * 360f, rule.offsets.size, rng, chroma = 0.15f)
            if (ColorLab.analyze(cols).harmony == rule) hit++
        }
        println("  detect $rule: $hit/40"); ok += hit; total += 40
    }
    check("harmony detect >= 85%", ok >= 0.85 * total, "$ok/$total")
    val mono = ColorLab.analyze(intArrayOf(0xFF202020.toInt(), 0xFF808080.toInt(), 0xFFE0E0E0.toInt()))
    check("neutral = monochrome", mono.harmony == ColorLab.Harmony.Monochrome)
    // suggestions
    val flat = intArrayOf(0xFF777777.toInt(), 0xFF7A7A7A.toInt())
    val s = ColorLab.suggest(flat)
    check("flat ramp gets MoreContrast", s.any { it.fix == ColorLab.Fix.MoreContrast })
    val fixed = s.first { it.fix == ColorLab.Fix.MoreContrast }.colors
    check("contrast improved", ColorLab.analyze(fixed).contrastRatio > 3f, "%.2f".format(ColorLab.analyze(fixed).contrastRatio))
    val bumpy = intArrayOf(0xFF202020.toInt(), 0xFFF0F0F0.toInt(), 0xFF303030.toInt(), 0xFFE0E0E0.toInt())
    val sb = ColorLab.suggest(bumpy)
    check("non-monotonic gets EvenLightness", sb.any { it.fix == ColorLab.Fix.EvenLightness })
    check("EvenLightness monotonic", ColorLab.analyze(sb.first { it.fix == ColorLab.Fix.EvenLightness }.colors).monotonic)
    val uneven = intArrayOf(0xFF000000.toInt(), 0xFF101010.toInt(), 0xFF202020.toInt(), 0xFFFFFFFF.toInt())
    val eq = ColorLab.equalSteps(uneven)
    val steps = (1 until 4).map { ColorLab.deltaE(eq[it - 1], eq[it]) }
    check("equal steps", (steps.max() - steps.min()) < 0.03f, steps.toString())
    // evolution
    val parent = GradientPresets.stopsOf(intArrayOf(0xFF6A5CFF.toInt(), 0xFFFF6FB1.toInt(), 0xFFFFE066.toInt()))
    val gen = ColorLab.nextGeneration(listOf(parent, GradientPresets.stopsOf(intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()))), 8, 0.5f, rng)
    check("generation size", gen.size == 8)
    check("children valid", gen.all { it.size >= 2 && it.first().position == 0f || it.first().position < 0.25f })
    check("children sorted", gen.all { c -> c.zipWithNext().all { it.first.position <= it.second.position } })
    check("children differ from parent", gen.all { it != parent })
    // weak strength stays close
    val near = ColorLab.mutate(parent, rng, 0.05f)
    check("low strength near parent", near.indices.all { ColorLab.deltaE(near[it].color, parent[it].color) < 0.12f })
    // roulette
    val cur = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    val locked = booleanArrayOf(true, false, false)
    val (rule, spun) = ColorLab.spin(cur, locked, rng)
    check("roulette keeps locked", spun[0] == cur[0] && spun[1] != cur[1], rule.toString())
    // collision
    val a = GradientPresets.stopsOf(intArrayOf(0xFFFF512F.toInt(), 0xFFF09819.toInt()))
    val b = GradientPresets.stopsOf(intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt()))
    val col = ColorLab.collide(a, b, 0.5f, 0.2f, 0.6f)
    check("collision stops", col.first().position == 0f && col.last().position == 1f && col.zipWithNext().all { it.first.position <= it.second.position }, col.size.toString())
    check("collision start/end colors", ColorLab.deltaE(col.first().color, a.first().color) < 0.01f && ColorLab.deltaE(col.last().color, b.last().color) < 0.01f)
    // DNA
    val spec = GradientSpec(type = GradientType.Spiral, stops = parent, angle = 123.4f, scale = 1.75f, turns = 4.5f, spread = Spread.Mirror, centerX = 0.3f, centerY = 0.8f)
    val code = Dna.encode(spec)
    println("  DNA: $code")
    val dec = Dna.decode(code)!!
    check("dna roundtrip type/spread", dec.type == spec.type && dec.spread == spec.spread)
    check("dna roundtrip numbers", Math.abs(dec.angle - 123.4f) < 0.11f && Math.abs(dec.scale - 1.75f) < 0.002f && Math.abs(dec.turns - 4.5f) < 0.11f && Math.abs(dec.centerX - 0.3f) < 0.001f)
    check("dna roundtrip stops", dec.stops.size == 3 && dec.stops.indices.all { dec.stops[it].color == spec.stops[it].color && Math.abs(dec.stops[it].position - spec.stops[it].position) < 0.0001f })
    check("dna lowercase+spaces ok", Dna.decode(code.lowercase().replace("-", " ")) != null)
    val broken = code.substring(0, 10) + (if (code[10] == 'A') 'B' else 'A') + code.substring(11)
    check("dna checksum rejects corruption", Dna.decode(broken) == null)
    check("dna rejects junk", Dna.decode("hello") == null && Dna.decode("GD1-") == null && Dna.decode("") == null)
    val meshSpec = GradientSpec(type = GradientType.Mesh)
    check("dna of mesh decodes as ramp", Dna.decode(Dna.encode(meshSpec))!!.stops.size == 9)
    val child = Dna.breed(spec, dec.copy(angle = 10f, type = GradientType.Linear), rng)
    check("breed valid", child.stops.size >= 2 && Dna.decode(Dna.encode(child)) != null)
    // fuzz decode doesn't throw
    repeat(2000) { val s = (1..rng.nextInt(1, 40)).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ-"[rng.nextInt(32)] }.joinToString(""); Dna.decode("GD1-$s") }
    check("fuzz ok", true)
    println("ALL OK")
}
