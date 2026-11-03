package com.example.arabicphotoeditor.gradient

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/** Thrown when the user cancels or the device cannot encode; callers turn it into a message. */
class ExportFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Renders the project's animation frame by frame and writes it as GIF (own encoder) or MP4 (H.264 through the
 * device's MediaCodec). Both are fully offline. Call from a background thread.
 */
object AnimationExport {

    private fun dims(project: GradientProject, longSide: Int, multiple: Int): Pair<Int, Int> {
        val scale = longSide.toFloat() / maxOf(project.width, project.height)
        fun snap(v: Float) = ((v / multiple).roundToInt() * multiple).coerceAtLeast(multiple)
        return snap(project.width * scale) to snap(project.height * scale)
    }

    /** [progress] gets 0..1. Returns normally when finished; throws [ExportFailed] when cancelled. */
    fun gif(
        project: GradientProject,
        longSide: Int,
        out: OutputStream,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val (w, h) = dims(project, longSide, 1)
        val anim = project.animation
        val count = anim.frameCount
        val delay = (100f / anim.fps).roundToInt().coerceIn(2, 100)

        // Pass 1: a handful of frames decide the shared palette (they are kept, so they are rendered only once).
        val sampleCount = minOf(count, 12)
        val sampleIdx = (0 until sampleCount).map { it * count / sampleCount }
        val cache = HashMap<Int, IntArray>()
        for ((n, i) in sampleIdx.withIndex()) {
            if (cancelled()) throw ExportFailed("cancelled")
            cache[i] = Animator.render(project, i / count.toFloat(), w, h, cancelled)
            progress(0.15f * (n + 1) / sampleCount)
        }
        val palette = GifEncoder.buildPalette(sampleIdx.map { cache.getValue(it) })

        val encoder = GifEncoder(out, w, h)
        encoder.begin(palette, 0)
        for (i in 0 until count) {
            if (cancelled()) throw ExportFailed("cancelled")
            val pixels = cache.remove(i) ?: Animator.render(project, i / count.toFloat(), w, h, cancelled)
            encoder.addFrame(pixels, delay)
            progress(0.15f + 0.85f * (i + 1) / count)
        }
        encoder.finish()
    }

    /**
     * H.264 MP4 into [file]. MP4 has no transparency, so frames are composited over black. [loops] repeats the
     * animation that many times (players rarely loop MP4 on their own). Frame size is rounded to a multiple of 16,
     * which every hardware encoder accepts.
     */
    fun mp4(
        project: GradientProject,
        longSide: Int,
        loops: Int,
        file: File,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val (w, h) = dims(project, longSide, 16)
        val anim = project.animation
        val count = anim.frameCount
        val fps = anim.fps.coerceIn(10, 60)
        val totalFrames = count * loops.coerceAtLeast(1)
        val bitrate = (w.toLong() * h * fps * 0.12).toLong().coerceIn(1_000_000L, 20_000_000L).toInt()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var submitted = 0
            var inputDone = false
            var outputDone = false
            // Frames repeat when looping, so each distinct frame is rendered once and kept for the next loop only
            // if the whole animation is small; otherwise it is simply rendered again.
            val keep = if (loops > 1 && w.toLong() * h * count <= 40_000_000L) arrayOfNulls<IntArray>(count) else null

            while (!outputDone) {
                if (cancelled()) throw ExportFailed("cancelled")

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (submitted >= totalFrames) {
                            codec.queueInputBuffer(inIndex, 0, 0, submitted * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val frameIdx = submitted % count
                            val pixels = keep?.get(frameIdx)
                                ?: Animator.render(project, frameIdx / count.toFloat(), w, h, cancelled).also { keep?.set(frameIdx, it) }
                            val image = codec.getInputImage(inIndex) ?: throw ExportFailed("no input image")
                            fillImage(image, pixels, w, h)
                            codec.queueInputBuffer(inIndex, 0, w * h * 3 / 2, submitted * 1_000_000L / fps, 0)
                            submitted++
                            progress(submitted / totalFrames.toFloat() * 0.98f)
                        }
                    }
                }

                var drained = true
                while (drained) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> drained = false
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && info.size > 0 && !isConfig && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                drained = false
                            }
                        }
                    }
                }
            }
            if (!muxerStarted) throw ExportFailed("encoder produced no output")
            progress(1f)
        } catch (e: ExportFailed) {
            throw e
        } catch (e: Exception) {
            throw ExportFailed("encode failed", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** ARGB -> BT.709 limited-range YUV 4:2:0, written through the codec's own plane layout. */
    private fun fillImage(image: Image, argb: IntArray, w: Int, h: Int) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride

        fun over(p: Int, shift: Int): Int {
            // Composite over black: transparent areas become black.
            val a = p ushr 24
            return (((p shr shift) and 0xFF) * a + 127) / 255
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val r = over(p, 16)
                val g = over(p, 8)
                val b = over(p, 0)
                val yy = (16f + 0.1826f * r + 0.6142f * g + 0.0620f * b).roundToInt().coerceIn(16, 235)
                yBuf.put(y * yRow + x * yPix, yy.toByte())
                if ((y and 1) == 0 && (x and 1) == 0) {
                    // Average the 2x2 block for the chroma sample.
                    var rs = 0; var gs = 0; var bs = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val q = argb[(y + dy) * w + (x + dx)]
                        rs += over(q, 16); gs += over(q, 8); bs += over(q, 0)
                    }
                    val ra = rs / 4f; val ga = gs / 4f; val ba = bs / 4f
                    val u = (128f - 0.1006f * ra - 0.3386f * ga + 0.4392f * ba).roundToInt().coerceIn(16, 240)
                    val v = (128f + 0.4392f * ra - 0.3989f * ga - 0.0403f * ba).roundToInt().coerceIn(16, 240)
                    uBuf.put((y / 2) * uRow + (x / 2) * uPix, u.toByte())
                    vBuf.put((y / 2) * vRow + (x / 2) * vPix, v.toByte())
                }
            }
        }
    }
}
