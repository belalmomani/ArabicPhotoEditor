import com.example.arabicphotoeditor.gradient.*
fun check(n: String, ok: Boolean, e: String = "") { println((if (ok) "PASS " else "FAIL ") + n + " " + e); if (!ok) System.exit(1) }
fun main() {
    val w = 40; val h = 30
    // photo: left half opaque grey ramp, right half transparent
    val photo = IntArray(w * h) { i -> val x = i % w; if (x < 20) (0xFF shl 24) or ((x * 12) * 0x010101) else 0 }
    val stops = GradientPresets.stopsOf(intArrayOf(0xFF0000FF.toInt(), 0xFFFFFF00.toInt()))
    val proj = GradientProject(layers = listOf(GradientLayer(1, "g", GradientSpec(stops = stops, angle = 0f))), width = w, height = h)
    // Background: transparent area shows gradient, opaque area shows photo
    val bg = PhotoCompose.apply(photo, w, h, proj, PhotoApply(PhotoMode.Background))
    check("bg: opaque px = photo", bg[5] == photo[5])
    check("bg: transparent px = gradient (opaque)", (bg[30] ushr 24) == 255 && bg[30] != photo[30])
    // Overlay keeps alpha
    val ov = PhotoCompose.apply(photo, w, h, proj, PhotoApply(PhotoMode.Overlay, BlendMode.Normal, 1f))
    check("overlay keeps transparency", (ov[30] ushr 24) == 0 && (ov[5] ushr 24) == 255)
    check("overlay opacity 1 normal = gradient colour on subject", ColorLab.deltaE(ov[5], GradientRenderer.render(proj, w, h)[5]) < 0.01f)
    val ov0 = PhotoCompose.apply(photo, w, h, proj, PhotoApply(PhotoMode.Overlay, BlendMode.Normal, 0f))
    check("overlay opacity 0 = photo", ov0.contentEquals(photo))
    // Map: dark pixel -> start (blue), bright -> end (yellow)
    val mp = PhotoCompose.apply(photo, w, h, proj, PhotoApply(PhotoMode.Map, opacity = 1f))
    check("map dark ~ blue", (mp[0] and 0xFF) > 200 && ((mp[0] shr 16) and 0xFF) < 40, "%08X".format(mp[0]))
    val bright = IntArray(w * h) { 0xFFFFFFFF.toInt() }
    val mb = PhotoCompose.apply(bright, w, h, proj, PhotoApply(PhotoMode.Map, opacity = 1f))
    check("map white = yellow", ColorLab.deltaE(mb[0], 0xFFFFFF00.toInt()) < 0.02f)
    check("map keeps transparency", (mp[30] ushr 24) == 0)
    println("ALL OK")
}
