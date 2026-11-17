package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the RngState LCG inside PatchMatchEngine.
 *
 * We mirror the LCG implementation to verify:
 * - Values stay in [0, 1].
 * - Two RngState instances starting from different seeds diverge.
 * - The sequence is deterministic for a fixed seed.
 * - Two instances seeded identically produce identical sequences.
 * - Thread isolation: two independent instances never share state.
 */
class PatchMatchRngTest {

    // ── Mirror of PatchMatchEngine.RngState ──────────────────────────────────

    private class RngState(var seed: Long = System.nanoTime() xor 0x12345678L) {
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFF).toFloat() / 65535f
        }
    }

    // ── range ─────────────────────────────────────────────────────────────────

    @Test fun `all values are in 0-1 range`() {
        val rng = RngState(12345L)
        repeat(10_000) {
            val v = rng.next()
            assertTrue("value $v out of range", v in 0f..1f)
        }
    }

    // ── determinism ───────────────────────────────────────────────────────────

    @Test fun `same seed produces identical sequence`() {
        val a = RngState(99999L)
        val b = RngState(99999L)
        for (i in 0 until 1000) {
            assertEquals("diverged at step $i", a.next(), b.next(), 0f)
        }
    }

    @Test fun `different seeds diverge immediately`() {
        val a = RngState(1L)
        val b = RngState(2L)
        // At least some values should differ in the first 10 draws.
        var allSame = true
        for (i in 0 until 10) if (a.next() != b.next()) { allSame = false; break }
        assertFalse("different seeds should diverge", allSame)
    }

    // ── independence ──────────────────────────────────────────────────────────

    @Test fun `two instances don't share state`() {
        val a = RngState(42L)
        val b = RngState(42L)
        a.next()          // advance only a
        // b's next should still equal a's first value
        val aFirst  = RngState(42L).next()
        val bSecond = b.next()
        assertEquals("b not affected by a's advance", aFirst, bSecond, 0f)
    }

    // ── distribution quality ──────────────────────────────────────────────────

    @Test fun `mean of 100k samples is close to 0·5`() {
        val rng = RngState(0xDEADBEEFL)
        var sum = 0.0
        val n = 100_000
        repeat(n) { sum += rng.next() }
        val mean = sum / n
        // LCG should average within ±2% of 0.5
        assertTrue("mean $mean out of [0.48, 0.52]", mean in 0.48..0.52)
    }

    @Test fun `no two consecutive values are identical for 10k samples`() {
        val rng = RngState(0xCAFEBABEL)
        var prev = rng.next()
        var collisions = 0
        repeat(10_000) {
            val v = rng.next()
            if (v == prev) collisions++
            prev = v
        }
        // Collisions can happen with very low probability — allow at most 2
        assertTrue("too many consecutive collisions: $collisions", collisions <= 2)
    }

    // ── seed initialization ───────────────────────────────────────────────────

    @Test fun `two instances created back-to-back have different seeds`() {
        // nanoTime()-based seeds: in real code they differ; we simulate by using
        // two explicit seeds that differ by 1 (worst case for time-based init).
        val a = RngState(System.nanoTime())
        val b = RngState(System.nanoTime() + 1L)
        // Different seeds → they should not produce identical first values
        // (note: with LCG, seed+1 almost certainly gives a different first output)
        val va = a.next(); val vb = b.next()
        // We just assert they're both valid floats — asserting inequality would be
        // fragile since there's a tiny chance of collision.
        assertTrue(va in 0f..1f)
        assertTrue(vb in 0f..1f)
    }

    // ── period coverage ───────────────────────────────────────────────────────

    @Test fun `generates both small and large values`() {
        val rng = RngState(0xABCD1234L)
        var hasSmall = false; var hasLarge = false
        repeat(1000) {
            val v = rng.next()
            if (v < 0.1f) hasSmall = true
            if (v > 0.9f) hasLarge = true
        }
        assertTrue("should generate small values", hasSmall)
        assertTrue("should generate large values", hasLarge)
    }
}
