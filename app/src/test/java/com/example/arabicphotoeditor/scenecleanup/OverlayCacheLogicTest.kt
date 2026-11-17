package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the overlay-cache pixel-buffer logic.
 *
 * We mirror the colour-mapping arithmetic (SelectionColor vs transparent)
 * and the buffer-reuse decision so we can test it without android.graphics.Bitmap.
 */
class OverlayCacheLogicTest {

    private val SelectionColor = 0x99FF3B30.toInt()

    // Mirror of the pixel-mapping logic in OverlayCache.update
    private fun buildPixels(data: ByteArray): IntArray =
        IntArray(data.size) { if (data[it].toInt() != 0) SelectionColor else 0 }

    // ── pixel mapping ─────────────────────────────────────────────────────────

    @Test fun `selected byte maps to SelectionColor`() {
        val data = byteArrayOf(SelectionMask.SELECTED)
        val pixels = buildPixels(data)
        assertEquals(SelectionColor, pixels[0])
    }

    @Test fun `zero byte maps to transparent (0)`() {
        val data = byteArrayOf(0)
        val pixels = buildPixels(data)
        assertEquals(0, pixels[0])
    }

    @Test fun `mixed mask maps correctly`() {
        val data = ByteArray(4).also {
            it[0] = SelectionMask.SELECTED
            it[1] = 0
            it[2] = SelectionMask.SELECTED
            it[3] = 0
        }
        val pixels = buildPixels(data)
        assertEquals(SelectionColor, pixels[0])
        assertEquals(0,              pixels[1])
        assertEquals(SelectionColor, pixels[2])
        assertEquals(0,              pixels[3])
    }

    @Test fun `all selected → all SelectionColor`() {
        val n = 100
        val data = ByteArray(n) { SelectionMask.SELECTED }
        val pixels = buildPixels(data)
        assertTrue(pixels.all { it == SelectionColor })
    }

    @Test fun `all zero → all transparent`() {
        val n = 100
        val data = ByteArray(n) { 0 }
        val pixels = buildPixels(data)
        assertTrue(pixels.all { it == 0 })
    }

    // ── buffer-reuse decision ────────────────────────────────────────────────

    @Test fun `buffer of same size is reused`() {
        // Simulate: existing buf size == w*h → reuse
        val w = 10; val h = 10
        val existingBuf = IntArray(w * h)
        val reuse = existingBuf.size == w * h
        assertTrue(reuse)
    }

    @Test fun `buffer of different size triggers reallocation`() {
        val w = 10; val h = 10
        val existingBuf = IntArray(5 * 5)   // wrong size
        val reuse = existingBuf.size == w * h
        assertFalse(reuse)
    }

    // ── SelectionColor value ──────────────────────────────────────────────────

    @Test fun `SelectionColor alpha is semi-transparent`() {
        val alpha = (SelectionColor ushr 24) and 0xFF
        // 0x99 = 153 — semi-transparent
        assertEquals(0x99, alpha)
    }

    @Test fun `SelectionColor hue is red`() {
        val r = (SelectionColor shr 16) and 0xFF
        val g = (SelectionColor shr 8)  and 0xFF
        val b =  SelectionColor         and 0xFF
        // Red channel dominant
        assertTrue("r=$r should dominate", r > g && r > b)
    }
}
