import com.example.arabicphotoeditor.scenecleanup.SelectionMask
fun check(n: String, ok: Boolean, e: String = "") { println((if (ok) "PASS " else "FAIL ") + n + " " + e); if (!ok) System.exit(1) }
fun main() {
    val big = SelectionMask.forPhoto(4000, 3000)
    check("working size capped", big.width == 1024 && big.height == 768, "${big.width}x${big.height}")
    val small = SelectionMask.forPhoto(300, 200)
    check("small photo not upscaled", small.width == 300 && small.height == 200)
    val m = SelectionMask(100, 100)
    check("empty", m.isEmpty() && m.coverage() == 0f)
    m.stamp(50f, 50f, 10f, true)
    val cov = m.coverage()
    check("dab area ~ pi r^2", Math.abs(cov * 10000 - Math.PI * 100) < 25, (cov * 10000).toString())
    m.stamp(50f, 50f, 4f, false)
    check("erase hole", m.data[50 * 100 + 50].toInt() == 0 && m.data[50 * 100 + 58].toInt() != 0)
    val m2 = SelectionMask(200, 50)
    m2.stroke(listOf(10f to 25f, 190f to 25f), 4f, true)
    check("stroke continuous", (10..190).all { x -> m2.data[25 * 200 + x].toInt() != 0 })
    check("stroke width", m2.data[22 * 200 + 100].toInt() != 0 && m2.data[10 * 200 + 100].toInt() == 0)
    val snap = m2.copy(); m2.clear()
    check("copy independent", m2.isEmpty() && !snap.isEmpty())
    // full resolution
    val s = SelectionMask(50, 50); s.stamp(25f, 25f, 10f, true)
    val full = s.toFullResolution(200, 200)
    val cnt = full.count { it }
    check("upscale keeps area", Math.abs(cnt / 16.0 - s.coverage() * 2500) < 60, "${cnt / 16.0} vs ${s.coverage() * 2500}")
    check("upscale centre in / corner out", full[100 * 200 + 100] && !full[5 * 200 + 5])
    val grown = s.toFullResolution(200, 200, 3)
    check("grow adds pixels", grown.count { it } > cnt + 200)
    check("grown covers original", full.indices.all { !full[it] || grown[it] })
    // grow does not leak far
    val far = (0 until 200 * 200).filter { grown[it] && !full[it] }.maxOf { i -> val x = i % 200; val y = i / 200; Math.hypot((x - 100.0), (y - 100.0)) }
    check("grow bounded", far < 40 + 3 * 1.5 + 2, far.toString())
    // protect against empty mask scaling
    check("empty scales to empty", SelectionMask(10, 10).toFullResolution(40, 40).none { it })
    println("ALL OK")
}
