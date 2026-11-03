package com.example.arabicphotoeditor.gradient

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Gradient DNA: a short text code that captures a gradient's genes (type, shape and color stops) so it can be
 * shared, pasted into another device and bred with another code. Pure Kotlin, no network.
 *
 * Format: "GD1-" + Base32 groups of four, over the bytes
 * `version, type, spread, angle*10 (u16), scale*1000 (u16), turns*10 (u8), centerX (u16), centerY (u16),
 * stopCount (u8), stops (position u16, ARGB 4 bytes)..., checksum (CRC-16)`.
 */
object Dna {

    private const val PREFIX = "GD1"
    private const val VERSION = 1
    const val MAX_STOPS = 16

    // Crockford-style alphabet without I, L, O, U so codes survive being read aloud or retyped.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Types that DNA can express. Content-driven types are stored as plain color ramps. */
    private fun encodableType(type: GradientType): GradientType =
        if (type.ordinal <= GradientType.Diamond.ordinal) type else GradientType.Linear

    fun encode(spec: GradientSpec): String {
        val colors = ColorLab.colorsOf(spec)
        val stops = if (spec.usesStops) spec.stops else ColorLab.stopsOf(colors)
        val limited = if (stops.size > MAX_STOPS) ColorLab.resample(stops, MAX_STOPS) else stops.sortedBy { it.position }
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        u8(VERSION)
        u8(encodableType(spec.type).ordinal)
        u8(spec.spread.ordinal)
        u16((spec.angle * 10f).toInt().coerceIn(0, 3600))
        u16((spec.scale * 1000f).toInt().coerceIn(0, 65535))
        u8((spec.turns * 10f).toInt().coerceIn(0, 255))
        u16((spec.centerX * 65535f).toInt().coerceIn(0, 65535))
        u16((spec.centerY * 65535f).toInt().coerceIn(0, 65535))
        u8(limited.size)
        for (s in limited) {
            u16((s.position * 65535f + 0.5f).toInt().coerceIn(0, 65535))
            u8(s.color ushr 24); u8(s.color shr 16); u8(s.color shr 8); u8(s.color)
        }
        val body = out.toByteArray()
        val crc = crc16(body)
        val all = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        val text = base32(all)
        return PREFIX + "-" + text.chunked(4).joinToString("-")
    }

    /** Returns null when the code is malformed, truncated or fails its checksum. */
    fun decode(code: String): GradientSpec? {
        val cleaned = code.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (!cleaned.startsWith(PREFIX)) return null
        val bytes = unbase32(cleaned.substring(PREFIX.length)) ?: return null
        if (bytes.size < 17) return null
        val body = bytes.copyOfRange(0, bytes.size - 2)
        val crc = ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 1].toInt() and 0xFF)
        if (crc != crc16(body)) return null
        var pos = 0
        fun u8(): Int = body[pos++].toInt() and 0xFF
        fun u16(): Int { val h = u8(); return (h shl 8) or u8() }
        try {
            if (u8() != VERSION) return null
            val type = GradientType.entries.getOrNull(u8())?.takeIf { it.ordinal <= GradientType.Diamond.ordinal } ?: return null
            val spread = Spread.entries.getOrNull(u8()) ?: return null
            val angle = u16() / 10f
            val scale = u16() / 1000f
            val turns = u8() / 10f
            val cx = u16() / 65535f
            val cy = u16() / 65535f
            val count = u8()
            if (count < 2 || count > MAX_STOPS || body.size != pos + count * 6) return null
            val stops = List(count) {
                val p = u16() / 65535f
                val a = u8(); val r = u8(); val g = u8(); val b = u8()
                ColorStop(p, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            return GradientSpec(
                type = type, stops = stops, centerX = cx, centerY = cy, angle = angle.coerceIn(0f, 360f),
                scale = scale.coerceIn(0.1f, 4f), turns = turns.coerceIn(1f, 10f), spread = spread,
            )
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
    }

    /**
     * A child of two genomes: each shape gene comes from one parent, and the color stops are crossed over and
     * lightly mutated, so the child is related to both but identical to neither.
     */
    fun breed(a: GradientSpec, b: GradientSpec, rng: Random): GradientSpec {
        val sa = if (a.usesStops) a.stops else ColorLab.stopsOf(ColorLab.colorsOf(a))
        val sb = if (b.usesStops) b.stops else ColorLab.stopsOf(ColorLab.colorsOf(b))
        val stops = ColorLab.mutate(ColorLab.crossover(sa, sb, rng), rng, 0.15f)
        fun <T> pick(x: T, y: T): T = if (rng.nextBoolean()) x else y
        return GradientSpec(
            type = encodableType(pick(a.type, b.type)),
            stops = stops,
            centerX = pick(a.centerX, b.centerX),
            centerY = pick(a.centerY, b.centerY),
            angle = pick(a.angle, b.angle),
            scale = pick(a.scale, b.scale),
            turns = pick(a.turns, b.turns),
            spread = pick(a.spread, b.spread),
        )
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun unbase32(text: String): ByteArray? {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            // Forgive the usual look-alikes.
            val c = when (ch) { 'O' -> '0'; 'I', 'L' -> '1'; else -> ch }
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }
}
