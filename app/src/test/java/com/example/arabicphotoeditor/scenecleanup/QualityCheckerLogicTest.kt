package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for QualityChecker's scoring logic.
 * We can't use android.graphics.Bitmap here, so we test the pure-logic helpers via
 * a thin simulation that mirrors the same arithmetic.
 */
class QualityCheckerLogicTest {

    // ── overall score formula ─────────────────────────────────────────────────

    @Test fun `overall is weighted sum of four scores`() {
        val seam  = 0.4f
        val blotch = 0.2f
        val rep   = 0.1f
        val edge  = 0.5f
        val expected = (seam * 0.35f + blotch * 0.30f + rep * 0.20f + edge * 0.15f)
        val report = makeReport(seam, blotch, rep, edge)
        assertEquals(expected, report.overall, 0.001f)
    }

    @Test fun `overall is clamped to 0-1`() {
        val report = makeReport(2f, 2f, 2f, 2f)
        assertEquals(1f, report.overall, 0.001f)
        val report2 = makeReport(-1f, -1f, -1f, -1f)
        assertEquals(0f, report2.overall, 0.001f)
    }

    @Test fun `all zeroes → acceptable`() {
        val report = makeReport(0f, 0f, 0f, 0f)
        assertTrue(report.acceptable)
    }

    @Test fun `all ones → not acceptable`() {
        val report = makeReport(1f, 1f, 1f, 1f)
        assertFalse(report.acceptable)
    }

    @Test fun `borderline at threshold`() {
        // overall just below threshold = acceptable
        val belowReport = makeReport(0.3f, 0.3f, 0.3f, 0.3f)
        // overall = 0.3*0.35 + 0.3*0.30 + 0.3*0.20 + 0.3*0.15 = 0.3
        assertTrue("below threshold acceptable", belowReport.acceptable)

        // overall just above threshold = not acceptable
        val aboveReport = makeReport(0.4f, 0.4f, 0.4f, 0.4f)
        // overall = 0.4
        assertFalse("above threshold not acceptable", aboveReport.acceptable)
    }

    // ── ACCEPT_THRESHOLD ──────────────────────────────────────────────────────

    @Test fun `accept threshold is 0·35`() {
        assertEquals(0.35f, QualityChecker.QualityReport.ACCEPT_THRESHOLD, 0.001f)
    }

    // ── blotchScore sampling logic ────────────────────────────────────────────

    @Test fun `blotch sampler step is at least 1`() {
        // If indices.size <= MAX_SAMPLE, step = 1
        val size = 100
        val maxSample = 8_000
        val step = if (size <= maxSample) 1 else size / maxSample
        assertEquals(1, step)
    }

    @Test fun `blotch sampler step skips on large input`() {
        val size = 100_000
        val maxSample = 8_000
        val step = if (size <= maxSample) 1 else size / maxSample
        assertTrue("step > 1 for large input", step > 1)
        // Sample count ≈ size / step ≤ MAX_SAMPLE
        val sampleCount = size / step
        assertTrue("sample count ≤ MAX_SAMPLE", sampleCount <= maxSample)
    }

    // ── nearBoundary logic ────────────────────────────────────────────────────

    @Test fun `pixel surrounded entirely by same type is not on boundary`() {
        val w = 5; val h = 5
        val mask = BooleanArray(w * h) { true }
        // Centre pixel (2,2): all neighbours are also true → not near boundary
        assertFalse(nearBoundary(mask, 2, 2, w, h, 1))
    }

    @Test fun `pixel adjacent to different type is on boundary`() {
        val w = 5; val h = 5
        val mask = BooleanArray(w * h) { true }
        mask[0] = false   // top-left is known
        // (1,0) is adjacent to (0,0) which is false → on boundary
        assertTrue(nearBoundary(mask, 1, 0, w, h, 1))
    }

    // ── repetition score threshold ────────────────────────────────────────────

    @Test fun `repetition score with zero matches is zero`() {
        // 0 matches out of SAMPLE_N → score = 0 / SAMPLE_N / 0.3 = 0
        val score = repetitionScore(matches = 0, sampleN = 60)
        assertEquals(0f, score, 0.001f)
    }

    @Test fun `repetition score at 30% match rate is 1`() {
        // 30% × 60 = 18 matches → score clamped to 1
        val score = repetitionScore(matches = 18, sampleN = 60)
        assertEquals(1f, score, 0.001f)
    }

    @Test fun `repetition score is proportional`() {
        val half = repetitionScore(matches = 9, sampleN = 60)   // 15% → ~0.5
        assertEquals(0.5f, half, 0.01f)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeReport(seam: Float, blotch: Float, rep: Float, edge: Float) =
        QualityChecker.QualityReport(seam, blotch, rep, edge)

    /** Mirror of QualityChecker's nearBoundary. */
    private fun nearBoundary(mask: BooleanArray, x: Int, y: Int, w: Int, h: Int, r: Int): Boolean {
        val inside = mask[y * w + x]
        for (dy in -r..r) for (dx in -r..r) {
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
            if (mask[ny * w + nx] != inside) return true
        }
        return false
    }

    /** Mirror of QualityChecker's repetition score calculation. */
    private fun repetitionScore(matches: Int, sampleN: Int): Float =
        (matches.toFloat() / sampleN / 0.3f).coerceIn(0f, 1f)
}
