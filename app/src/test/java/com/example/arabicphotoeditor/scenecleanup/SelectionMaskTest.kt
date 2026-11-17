package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max

class SelectionMaskTest {

    // ── isEmpty / coverage ────────────────────────────────────────────────────

    @Test fun `new mask is empty`() {
        val m = SelectionMask(10, 10)
        assertTrue(m.isEmpty())
        assertEquals(0f, m.coverage(), 0.001f)
    }

    @Test fun `stamp marks pixels inside circle`() {
        val m = SelectionMask(20, 20)
        m.stamp(10f, 10f, 3f, true)
        assertFalse(m.isEmpty())
        // Centre pixel must be selected
        assertNotEquals(0.toByte(), m.data[10 * 20 + 10])
    }

    @Test fun `eraser stamp clears pixels`() {
        val m = SelectionMask(20, 20)
        m.stamp(10f, 10f, 5f, true)
        assertFalse(m.isEmpty())
        m.stamp(10f, 10f, 10f, false)
        assertTrue("all cleared", m.isEmpty())
    }

    @Test fun `coverage matches selected fraction`() {
        val w = 10; val h = 10
        val m = SelectionMask(w, h)
        // Manually set half the pixels
        for (i in 0 until 50) m.data[i] = 255.toByte()
        assertEquals(0.5f, m.coverage(), 0.001f)
    }

    // ── copy ──────────────────────────────────────────────────────────────────

    @Test fun `copy is independent`() {
        val m = SelectionMask(5, 5)
        m.stamp(2f, 2f, 1f, true)
        val copy = m.copy()
        copy.clear()
        assertFalse("original unchanged", m.isEmpty())
        assertTrue("copy cleared", copy.isEmpty())
    }

    // ── forPhoto ──────────────────────────────────────────────────────────────

    @Test fun `forPhoto respects MAX_SIDE`() {
        val m = SelectionMask.forPhoto(4000, 3000)
        assertTrue("width ≤ MAX_SIDE", m.width  <= SelectionMask.MAX_SIDE)
        assertTrue("height ≤ MAX_SIDE", m.height <= SelectionMask.MAX_SIDE)
        // Aspect ratio preserved within 1 pixel.
        val ratio = m.width.toFloat() / m.height
        val expected = 4000f / 3000f
        assertEquals(expected, ratio, 0.02f)
    }

    @Test fun `forPhoto small image is not upscaled`() {
        val m = SelectionMask.forPhoto(100, 80)
        assertEquals(100, m.width)
        assertEquals(80, m.height)
    }

    // ── toFullResolution ──────────────────────────────────────────────────────

    @Test fun `toFullResolution round-trips a filled mask`() {
        val m = SelectionMask(10, 10)
        // Fill completely
        m.data.fill(255.toByte())
        val full = m.toFullResolution(100, 100, 0)
        assertTrue("all pixels selected", full.all { it })
    }

    @Test fun `toFullResolution round-trips an empty mask`() {
        val m = SelectionMask(10, 10)
        val full = m.toFullResolution(100, 100, 0)
        assertFalse("no pixels selected", full.any { it })
    }

    @Test fun `grow expands mask outward`() {
        val w = 10; val h = 10
        val mask = BooleanArray(w * h)
        mask[5 * w + 5] = true   // single centre pixel

        val grown = SelectionMask.grow(mask, w, h, 2)
        // The 5×5 square around (5,5) should all be true
        for (dy in -2..2) for (dx in -2..2) {
            val y = 5 + dy; val x = 5 + dx
            assertTrue("(${x},${y}) should be grown", grown[y * w + x])
        }
    }

    @Test fun `grow does not shrink non-grown pixels`() {
        val w = 8; val h = 8
        val mask = BooleanArray(w * h)
        for (y in 2..5) for (x in 2..5) mask[y * w + x] = true

        val grown = SelectionMask.grow(mask, w, h, 1)
        // Original pixels still set
        for (y in 2..5) for (x in 2..5) {
            assertTrue("original pixel (${x},${y}) still set", grown[y * w + x])
        }
        // Border expanded by 1
        assertTrue("expanded pixel", grown[1 * w + 2])
    }

    // ── stroke ────────────────────────────────────────────────────────────────

    @Test fun `stroke connects two points`() {
        val m = SelectionMask(100, 100)
        // Draw a horizontal line
        m.stroke(listOf(10f to 50f, 90f to 50f), 2f, true)
        // Sample some intermediate pixels
        assertNotEquals(0.toByte(), m.data[50 * 100 + 50])
        assertNotEquals(0.toByte(), m.data[50 * 100 + 30])
    }

    @Test fun `single-point stroke is equivalent to stamp`() {
        val m1 = SelectionMask(20, 20)
        val m2 = SelectionMask(20, 20)
        m1.stroke(listOf(10f to 10f), 3f, true)
        m2.stamp(10f, 10f, 3f, true)
        assertArrayEquals(m1.data, m2.data)
    }

    // ── SELECTED constant ─────────────────────────────────────────────────────

    @Test fun `SELECTED constant equals 255 as byte`() {
        // -1 as signed byte = 0xFF = 255 unsigned
        assertEquals((-1).toByte(), SelectionMask.SELECTED)
        assertEquals(255, SelectionMask.SELECTED.toInt() and 0xFF)
    }
}
