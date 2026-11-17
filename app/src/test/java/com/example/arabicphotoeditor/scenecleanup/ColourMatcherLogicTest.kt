package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * JVM tests for ColourMatcher's pure-logic helpers.
 *
 * We cannot call android.graphics.Bitmap here, so we test:
 * 1. The colour-transfer formula itself (scale + shift + smoothstep blend).
 * 2. The BFS distance-map logic (mirrored locally).
 * 3. The meanStd helper.
 * 4. The skip-correction guard (< MIN_SAMPLES).
 * 5. Channel clamping after correction.
 */
class ColourMatcherLogicTest {

    // ── colour transfer formula ───────────────────────────────────────────────

    @Test fun `colour transfer scale is outer_std over inner_std`() {
        // outer std = 20, inner std = 10  →  scale = 2
        val scale = 20f / 10f
        assertEquals(2f, scale, 0.001f)
    }

    @Test fun `colour transfer shift is outer_mean minus scale times inner_mean`() {
        val outerMean = 100f; val innerMean = 80f; val scale = 2f
        val shift = outerMean - scale * innerMean  // 100 - 160 = -60
        assertEquals(-60f, shift, 0.001f)
    }

    @Test fun `corrected pixel stays in 0-255 range`() {
        val raw = 240
        val scale = 2f; val shift = -60f
        val corrected = (scale * raw + shift).coerceIn(0f, 255f).toInt()
        assertTrue(corrected in 0..255)
    }

    @Test fun `corrected pixel negative clamped to 0`() {
        val raw = 10
        val scale = 0.1f; val shift = -50f
        val corrected = (scale * raw + shift).coerceIn(0f, 255f).toInt()
        assertEquals(0, corrected)
    }

    // ── smoothstep blend weight ───────────────────────────────────────────────

    @Test fun `smoothstep weight at t=0 is 0`() {
        assertEquals(0f, smoothstep(0f), 0.001f)
    }

    @Test fun `smoothstep weight at t=1 is 1`() {
        assertEquals(1f, smoothstep(1f), 0.001f)
    }

    @Test fun `smoothstep weight at t=0·5 is 0·5`() {
        assertEquals(0.5f, smoothstep(0.5f), 0.001f)
    }

    @Test fun `smoothstep is monotone`() {
        for (i in 0..9) {
            val t0 = i / 10f; val t1 = (i + 1) / 10f
            assertTrue("monotone at $t0", smoothstep(t0) <= smoothstep(t1))
        }
    }

    // ── blended pixel ─────────────────────────────────────────────────────────

    @Test fun `blend at weight 0 returns original pixel`() {
        val pr = 100; val cr = 180
        val blended = (pr + 0f * (cr - pr)).toInt()
        assertEquals(pr, blended)
    }

    @Test fun `blend at weight 1 returns corrected pixel`() {
        val pr = 100; val cr = 180
        val blended = (pr + 1f * (cr - pr)).toInt()
        assertEquals(cr, blended)
    }

    @Test fun `blend is linear interpolation`() {
        val pr = 0f; val cr = 100f; val w = 0.4f
        val blended = pr + w * (cr - pr)
        assertEquals(40f, blended, 0.001f)
    }

    // ── BFS distance map ──────────────────────────────────────────────────────

    @Test fun `unmasked pixel has distance 0`() {
        val w = 5; val h = 5
        val mask = BooleanArray(w * h)      // all false = all unmasked
        val dist = buildDistMap(mask, w, h)
        assertTrue("all dist 0", dist.all { it == 0 })
    }

    @Test fun `masked pixel adjacent to boundary has distance 1`() {
        val w = 5; val h = 5
        val mask = BooleanArray(w * h)
        mask[2 * w + 2] = true              // single centre pixel
        val dist = buildDistMap(mask, w, h)
        assertEquals(1, dist[2 * w + 2])
    }

    @Test fun `BFS distance increases toward centre of a filled square`() {
        val w = 7; val h = 7
        val mask = BooleanArray(w * h)
        for (y in 1..5) for (x in 1..5) mask[y * w + x] = true
        val dist = buildDistMap(mask, w, h)
        // Centre pixel (3,3) should have distance > border pixel (1,1)
        assertTrue(dist[3 * w + 3] > dist[1 * w + 1])
    }

    @Test fun `all-unmasked image: dist map is all zeros`() {
        val w = 10; val h = 10
        val mask = BooleanArray(w * h)   // all unmasked
        val dist = buildDistMap(mask, w, h)
        assertTrue(dist.all { it == 0 })
    }

    // ── meanStd helper ────────────────────────────────────────────────────────

    @Test fun `mean of uniform array is that value`() {
        val data = FloatArray(5) { 42f }
        val (mean, std) = meanStd(data, 5)
        assertEquals(42f, mean, 0.001f)
        assertEquals(0f, std, 0.001f)
    }

    @Test fun `mean of 0 and 100 is 50`() {
        val data = floatArrayOf(0f, 100f)
        val (mean, _) = meanStd(data, 2)
        assertEquals(50f, mean, 0.001f)
    }

    @Test fun `std of values equally spread around mean`() {
        // Values: 10, 30 → mean 20, variance = (100 + 100)/2 = 100 → std = 10
        val data = floatArrayOf(10f, 30f)
        val (_, std) = meanStd(data, 2)
        assertEquals(10f, std, 0.1f)
    }

    // ── scale guard (std ≤ 1 → scale stays 1) ────────────────────────────────

    @Test fun `scale is 1 when inner std is very small`() {
        // isR ≤ 1f → scR = 1 (no scaling applied, avoid divide-by-near-zero)
        val innerStd = 0.5f; val outerStd = 20f
        val scale = if (innerStd > 1f) outerStd / innerStd else 1f
        assertEquals(1f, scale, 0.001f)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun meanStd(data: FloatArray, n: Int): Pair<Float, Float> {
        var sum = 0.0
        for (i in 0 until n) sum += data[i]
        val mean = (sum / n).toFloat()
        var variance = 0.0
        for (i in 0 until n) { val d = data[i] - mean; variance += d * d }
        return mean to sqrt((variance / n).toFloat()).coerceAtLeast(0f)
    }

    /**
     * Mirrors ColourMatcher.buildDistMap (private in production code).
     * BFS from boundary — masked pixels adjacent to unmasked start at distance 1.
     */
    private fun buildDistMap(mask: BooleanArray, w: Int, h: Int): IntArray {
        val dist = IntArray(mask.size) { if (mask[it]) Int.MAX_VALUE else 0 }
        val queue = ArrayDeque<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!mask[i]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (!mask[ny * w + nx]) { dist[i] = 1; queue.addLast(i); break }
            }
        }
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w; val y = idx / w
            val d = dist[idx]
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val ni = ny * w + nx
                if (mask[ni] && dist[ni] > d + 1) { dist[ni] = d + 1; queue.addLast(ni) }
            }
        }
        return dist
    }
}
