package android.media
import java.nio.ByteBuffer
abstract class Image { abstract class Plane { abstract val buffer: ByteBuffer; abstract val rowStride: Int; abstract val pixelStride: Int }
  abstract val planes: Array<Plane> }
class MediaCodecInfo { object CodecCapabilities { const val COLOR_FormatYUV420Flexible = 0x7F420888 } }
class MediaFormat { 
  fun setInteger(k: String, v: Int) {}
  val x = 1
  companion object { const val MIMETYPE_VIDEO_AVC = "v"; const val KEY_COLOR_FORMAT = "a"; const val KEY_BIT_RATE = "b"; const val KEY_FRAME_RATE = "c"; const val KEY_I_FRAME_INTERVAL = "d"; const val KEY_COLOR_STANDARD = "e"; const val KEY_COLOR_RANGE = "f"; const val COLOR_STANDARD_BT709 = 1; const val COLOR_RANGE_LIMITED = 2
    @JvmStatic fun createVideoFormat(m: String, w: Int, h: Int): MediaFormat = MediaFormat() } }
class MediaCodec {
  class BufferInfo { @JvmField var offset = 0; @JvmField var size = 0; @JvmField var flags = 0 }
  fun configure(f: MediaFormat, s: Any?, c: Any?, flags: Int) {}
  fun start() {}; fun stop() {}; fun release() {}
  fun dequeueInputBuffer(t: Long): Int = 0
  fun getInputImage(i: Int): Image? = null
  fun queueInputBuffer(i: Int, o: Int, s: Int, t: Long, f: Int) {}
  fun dequeueOutputBuffer(info: BufferInfo, t: Long): Int = 0
  fun getOutputBuffer(i: Int): ByteBuffer? = null
  fun releaseOutputBuffer(i: Int, r: Boolean) {}
  val outputFormat: MediaFormat get() = MediaFormat()
  companion object { const val CONFIGURE_FLAG_ENCODE = 1; const val BUFFER_FLAG_END_OF_STREAM = 4; const val BUFFER_FLAG_CODEC_CONFIG = 2; const val INFO_TRY_AGAIN_LATER = -1; const val INFO_OUTPUT_FORMAT_CHANGED = -2
    @JvmStatic fun createEncoderByType(t: String): MediaCodec = MediaCodec() } }
class MediaMuxer(path: String, fmt: Int) { object OutputFormat { const val MUXER_OUTPUT_MPEG_4 = 0 }
  fun addTrack(f: MediaFormat): Int = 0; fun start() {}; fun stop() {}; fun release() {}
  fun writeSampleData(t: Int, b: ByteBuffer, i: MediaCodec.BufferInfo) {} }
