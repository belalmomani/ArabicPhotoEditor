package com.example.arabicphotoeditor.gradient

import java.io.OutputStream

/**
 * Self-contained animated GIF89a writer: median-cut palette shared by all frames, ordered (Bayer) dithering so the
 * pattern does not shimmer between frames, LZW compression, optional transparency. No libraries.
 *
 * Usage: [buildPalette] from a few sample frames, then [begin], [addFrame] for every frame, [finish].
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    /** 256-entry palette (RGB triplets). If [hasTransparency], index 0 is reserved for transparent pixels. */
    class Palette(val rgb: IntArray, val hasTransparency: Boolean)

    private lateinit var palette: Palette
    private val cache = IntArray(1 shl 18) { -1 }

    companion object {
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        private const val ALPHA_CUTOFF = 128

        /**
         * Median-cut over the pixels of [frames] (non-premultiplied ARGB). Pixels that are mostly transparent
         * are ignored and reserve palette index 0.
         */
        fun buildPalette(frames: List<IntArray>): Palette {
            var transparent = false
            // Histogram on 5 bits per channel keeps the cut fast and memory small.
            val hist = IntArray(1 shl 15)
            for (f in frames) for (p in f) {
                if ((p ushr 24) < ALPHA_CUTOFF) { transparent = true; continue }
                hist[((p shr 19) and 31 shl 10) or ((p shr 11) and 31 shl 5) or ((p shr 3) and 31)]++
            }
            val maxColors = if (transparent) 255 else 256
            class Box(val keys: IntArray, var count: Long) {
                var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
                init {
                    for (k in keys) {
                        val r = k shr 10; val g = (k shr 5) and 31; val b = k and 31
                        if (r < rMin) rMin = r; if (r > rMax) rMax = r
                        if (g < gMin) gMin = g; if (g > gMax) gMax = g
                        if (b < bMin) bMin = b; if (b > bMax) bMax = b
                    }
                }
                fun spread() = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
            }
            val all = (0 until hist.size).filter { hist[it] > 0 }.toIntArray()
            val boxes = ArrayList<Box>()
            if (all.isNotEmpty()) boxes.add(Box(all, all.sumOf { hist[it].toLong() }))
            while (boxes.size < maxColors) {
                var pick = -1
                var best = 0L
                for (i in boxes.indices) {
                    val b = boxes[i]
                    if (b.keys.size < 2) continue
                    val score = b.count * (b.spread() + 1)
                    if (score > best) { best = score; pick = i }
                }
                if (pick < 0) break
                val box = boxes.removeAt(pick)
                val rangeR = box.rMax - box.rMin
                val rangeG = box.gMax - box.gMin
                val rangeB = box.bMax - box.bMin
                val channel = if (rangeG >= rangeR && rangeG >= rangeB) 1 else if (rangeR >= rangeB) 0 else 2
                fun comp(k: Int) = when (channel) { 0 -> k shr 10; 1 -> (k shr 5) and 31; else -> k and 31 }
                val sorted = box.keys.sortedBy { comp(it) }
                var acc = 0L
                var cut = 1
                for (i in sorted.indices) {
                    acc += hist[sorted[i]]
                    if (acc * 2 >= box.count) { cut = (i + 1).coerceIn(1, sorted.size - 1); break }
                }
                val left = sorted.subList(0, cut).toIntArray()
                val right = sorted.subList(cut, sorted.size).toIntArray()
                boxes.add(Box(left, left.sumOf { hist[it].toLong() }))
                boxes.add(Box(right, right.sumOf { hist[it].toLong() }))
            }
            val rgb = IntArray(256)
            var index = if (transparent) 1 else 0
            for (b in boxes) {
                var r = 0L; var g = 0L; var bl = 0L
                for (k in b.keys) {
                    val c = hist[k].toLong()
                    r += (k shr 10) * c; g += ((k shr 5) and 31) * c; bl += (k and 31) * c
                }
                val n = b.count.coerceAtLeast(1)
                fun expand(v: Long) = (((v.toDouble() / n) * 255.0 / 31.0) + 0.5).toInt().coerceIn(0, 255)
                rgb[index++] = (expand(r) shl 16) or (expand(g) shl 8) or expand(bl)
            }
            // Fill the unused tail so the table is always complete.
            val fill = if (index > 0) rgb[index - 1] else 0
            while (index < 256) rgb[index++] = fill
            return Palette(rgb, transparent)
        }
    }

    /** Writes the header, logical screen and the looping extension. [loops] 0 = forever. */
    fun begin(palette: Palette, loops: Int = 0) {
        this.palette = palette
        cache.fill(-1)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width); u16(height)
        out.write(0xF7) // global color table, 8 bits per channel, 256 entries
        out.write(0)    // background index
        out.write(0)    // pixel aspect ratio
        for (c in palette.rgb) { out.write(c shr 16); out.write(c shr 8); out.write(c) }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01))
        u16(loops)
        out.write(0)
    }

    /** [delayCs] is the frame time in hundredths of a second (GIF's resolution). */
    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size == width * height)
        val indices = quantize(argb)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
        // Disposal 2 (restore to background) so transparent areas do not accumulate old frames.
        out.write(((if (palette.hasTransparency) 2 else 1) shl 2) or (if (palette.hasTransparency) 1 else 0))
        u16(delayCs.coerceAtLeast(2))
        out.write(0) // transparent index
        out.write(0)
        out.write(0x2C)
        u16(0); u16(0); u16(width); u16(height)
        out.write(0)
        lzw(indices)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun quantize(argb: IntArray): ByteArray {
        val res = ByteArray(argb.size)
        val rgb = palette.rgb
        val first = if (palette.hasTransparency) 1 else 0
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[i]
                if (palette.hasTransparency && (p ushr 24) < ALPHA_CUTOFF) {
                    res[i] = 0
                } else {
                    // Bayer offset in -7..+7 levels, applied before the lookup.
                    val d = BAYER[(y and 3) * 4 + (x and 3)] - 8
                    val r = (((p shr 16) and 0xFF) + d).coerceIn(0, 255)
                    val g = (((p shr 8) and 0xFF) + d).coerceIn(0, 255)
                    val b = ((p and 0xFF) + d).coerceIn(0, 255)
                    val key = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
                    var idx = cache[key]
                    if (idx < 0) {
                        // Nearest palette entry to the cell's center.
                        val cr = ((r shr 2) shl 2) + 2
                        val cg = ((g shr 2) shl 2) + 2
                        val cb = ((b shr 2) shl 2) + 2
                        var bestD = Int.MAX_VALUE
                        idx = first
                        for (k in first until 256) {
                            val c = rgb[k]
                            val dr = ((c shr 16) and 0xFF) - cr
                            val dg = ((c shr 8) and 0xFF) - cg
                            val db = (c and 0xFF) - cb
                            val dist = dr * dr * 3 + dg * dg * 4 + db * db * 2
                            if (dist < bestD) { bestD = dist; idx = k }
                        }
                        cache[key] = idx
                    }
                    res[i] = idx.toByte()
                }
                i++
            }
        }
        return res
    }

    // ---- LZW (variable code size, 8-bit symbols) ----

    private fun lzw(pixels: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val hashSize = 5003
        val hashKeys = IntArray(hashSize)
        val hashCodes = IntArray(hashSize)

        val block = ByteArray(255)
        var blockLen = 0
        var cur = 0
        var curBits = 0
        fun flushBlock() {
            if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 }
        }
        fun emit(code: Int, size: Int) {
            cur = cur or (code shl curBits)
            curBits += size
            while (curBits >= 8) {
                block[blockLen++] = cur.toByte()
                if (blockLen == 255) flushBlock()
                cur = cur ushr 8
                curBits -= 8
            }
        }

        var codeSize = minCode + 1
        var nextCode = eoi + 1
        hashKeys.fill(-1)
        emit(clear, codeSize)
        var prefix = pixels[0].toInt() and 0xFF
        for (n in 1 until pixels.size) {
            val c = pixels[n].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = (key * 2654435761L ushr 7).toInt() and 0x7FFFFFFF
            h %= hashSize
            var found = -1
            while (hashKeys[h] != -1) {
                if (hashKeys[h] == key) { found = hashCodes[h]; break }
                h = (h + 1) % hashSize
            }
            if (found >= 0) {
                prefix = found
            } else {
                emit(prefix, codeSize)
                if (nextCode < 4096) {
                    hashKeys[h] = key
                    hashCodes[h] = nextCode
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                    nextCode++
                } else {
                    emit(clear, codeSize)
                    hashKeys.fill(-1)
                    codeSize = minCode + 1
                    nextCode = eoi + 1
                }
                prefix = c
            }
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        if (curBits > 0) { block[blockLen++] = cur.toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}
