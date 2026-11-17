package com.example.arabicphotoeditor.scenecleanup

import org.junit.Assert.*
import org.junit.Test

class RegionLabelerTest {

    // ── basic labelling ───────────────────────────────────────────────────────

    @Test fun `empty mask returns no regions`() {
        val mask = BooleanArray(10 * 10)
        val regions = RegionLabeler.label(mask, 10, 10)
        assertTrue(regions.isEmpty())
    }

    @Test fun `single pixel returns one region`() {
        val mask = BooleanArray(5 * 5)
        mask[2 * 5 + 2] = true   // centre pixel
        val regions = RegionLabeler.label(mask, 5, 5, minArea = 1)
        assertEquals(1, regions.size)
        assertEquals(1, regions[0].area)
        assertEquals(2, regions[0].x0); assertEquals(2, regions[0].x1)
        assertEquals(2, regions[0].y0); assertEquals(2, regions[0].y1)
    }

    @Test fun `two disconnected blobs return two regions`() {
        val w = 10; val h = 5
        val mask = BooleanArray(w * h)
        // Blob A: 3×3 at top-left
        for (y in 0..2) for (x in 0..2) mask[y * w + x] = true
        // Blob B: 3×3 at bottom-right
        for (y in 2..4) for (x in 7..9) mask[y * w + x] = true

        val regions = RegionLabeler.label(mask, w, h)
        assertEquals(2, regions.size)
    }

    @Test fun `regions sorted by area descending`() {
        val w = 20; val h = 10
        val mask = BooleanArray(w * h)
        // Large blob: 5×5 = 25 px
        for (y in 0..4) for (x in 0..4) mask[y * w + x] = true
        // Small blob: 2×2 = 4 px
        for (y in 7..8) for (x in 15..16) mask[y * w + x] = true

        val regions = RegionLabeler.label(mask, w, h)
        assertEquals(2, regions.size)
        assertTrue("largest first", regions[0].area >= regions[1].area)
        assertEquals(25, regions[0].area)
        assertEquals(4, regions[1].area)
    }

    @Test fun `8-connected pixels form a single region`() {
        val w = 5; val h = 5
        val mask = BooleanArray(w * h)
        // Diagonal strip — 8-connected but not 4-connected
        mask[0 * w + 0] = true
        mask[1 * w + 1] = true
        mask[2 * w + 2] = true
        mask[3 * w + 3] = true
        mask[4 * w + 4] = true

        val regions = RegionLabeler.label(mask, w, h, minArea = 1)
        assertEquals("diagonal is one 8-connected region", 1, regions.size)
        assertEquals(5, regions[0].area)
    }

    @Test fun `minArea filter removes tiny regions`() {
        val w = 10; val h = 10
        val mask = BooleanArray(w * h)
        // Large region: 4×4
        for (y in 0..3) for (x in 0..3) mask[y * w + x] = true
        // Single-pixel noise
        mask[9 * w + 9] = true

        val regions = RegionLabeler.label(mask, w, h, minArea = 4)
        assertEquals("noise pixel filtered", 1, regions.size)
        assertEquals(16, regions[0].area)
    }

    @Test fun `bounding box is tight`() {
        val w = 10; val h = 10
        val mask = BooleanArray(w * h)
        for (y in 3..6) for (x in 2..7) mask[y * w + x] = true

        val regions = RegionLabeler.label(mask, w, h, minArea = 1)
        assertEquals(1, regions.size)
        val r = regions[0]
        assertEquals(2, r.x0); assertEquals(7, r.x1)
        assertEquals(3, r.y0); assertEquals(6, r.y1)
    }

    @Test fun `toBooleanArray matches original mask`() {
        val w = 8; val h = 8
        val mask = BooleanArray(w * h)
        for (y in 1..3) for (x in 1..3) mask[y * w + x] = true

        val regions = RegionLabeler.label(mask, w, h, minArea = 1)
        assertEquals(1, regions.size)
        val bool = regions[0].toBooleanArray()

        // Every pixel set in bool must have been set in original mask.
        for (i in bool.indices) {
            if (bool[i]) assertTrue("pixel $i should be in mask", mask[i])
        }
        // Every pixel set in original mask must be in bool.
        for (i in mask.indices) {
            if (mask[i]) assertTrue("pixel $i should be in bool", bool[i])
        }
    }

    // ── areaFrac ──────────────────────────────────────────────────────────────

    @Test fun `areaFrac is correct`() {
        val w = 10; val h = 10   // 100 total pixels
        val mask = BooleanArray(w * h)
        for (i in 0..24) mask[i] = true  // 25 pixels = 25%

        val regions = RegionLabeler.label(mask, w, h, minArea = 1)
        assertEquals(1, regions.size)
        assertEquals(0.25f, regions[0].areaFrac(), 0.001f)
    }
}
