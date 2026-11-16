package app.screeaner

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardware decoder rendering straight to the Surface, configured so that every
 * frame is shown as soon as it is decoded (no reordering, no pacing queue).
 */
class VideoDecoder(
    codecName: String,
    private val width: Int,
    private val height: Int,
    fps: Int,
    surface: Surface,
) {
    private val codec: MediaCodec
    @Volatile private var running = true
    private var waitingForKeyframe = true
    private val queuedAt = ConcurrentHashMap<Long, Long>()
    private val outputThread: Thread

    /** What happened since the previous [takeStats] call. Latency is NaN until the clocks are synced. */
    class Stats(
        val frames: Int,
        val decodeAvgMs: Double,
        val decodeMaxMs: Double,
        val latencyAvgMs: Double,
        val latencyMaxMs: Double,
    )

    /**
     * Mac clock minus tablet clock, in microseconds (see StreamClient's ping).
     * With it, each frame's timestamp tells how long ago the Mac captured it.
     */
    @Volatile var clockOffsetMicros = Long.MIN_VALUE

    /** Called on the output thread with System.nanoTime() each time a frame goes to the display. */
    @Volatile var onRendered: ((Long) -> Unit)? = null

    private val statsLock = Any()
    private var frames = 0
    private var decodeNanosSum = 0L
    private var decodeNanosMax = 0L
    private var latencyMicrosSum = 0L
    private var latencyMicrosMax = 0L
    private var latencyCount = 0

    fun takeStats(): Stats = synchronized(statsLock) {
        val stats = Stats(
            frames = frames,
            decodeAvgMs = if (frames > 0) decodeNanosSum / frames / 1e6 else 0.0,
            decodeMaxMs = decodeNanosMax / 1e6,
            latencyAvgMs = if (latencyCount > 0) latencyMicrosSum / latencyCount / 1e3 else Double.NaN,
            latencyMaxMs = if (latencyCount > 0) latencyMicrosMax / 1e3 else Double.NaN,
        )
        frames = 0
        decodeNanosSum = 0
        decodeNanosMax = 0
        latencyMicrosSum = 0
        latencyMicrosMax = 0
        latencyCount = 0
        stats
    }

    init {
        val mime = mimeFor(codecName)
        codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(buildFormat(mime, fps, aggressive = true), surface, null, 0)
        } catch (e: Exception) {
            // Some vendors reject the operating-rate hint; retry with the basics.
            Log.w(TAG, "Aggressive low-latency config rejected, retrying", e)
            codec.reset()
            codec.configure(buildFormat(mime, fps, aggressive = false), surface, null, 0)
        }
        codec.start()
        outputThread = Thread(::drainOutput, "screeaner-decoder-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun buildFormat(mime: String, fps: Int, aggressive: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // Vendor-specific switches; decoders ignore keys they do not know.
        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
        format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
        format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
        format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        format.setInteger("vendor.low-latency.enable", 1)
        if (aggressive) {
            // Ask the decoder to run as fast as it can instead of pacing to the frame rate.
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        return format
    }

    /**
     * Feeds one Annex-B access unit. Returns false if the frame had to be
     * dropped, in which case the caller should request a keyframe.
     */
    @Synchronized
    fun feed(data: ByteArray, offset: Int, length: Int, ptsMicros: Long, isKey: Boolean): Boolean {
        if (!running) return false
        if (waitingForKeyframe && !isKey) return false
        try {
            var index = -1
            var attempts = 0
            while (index < 0 && attempts < 40 && running) {
                index = codec.dequeueInputBuffer(5_000)
                attempts++
            }
            if (index < 0) {
                waitingForKeyframe = true
                return false
            }
            val input = codec.getInputBuffer(index)
            if (input == null || length > input.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsMicros, 0)
                waitingForKeyframe = true
                return false
            }
            input.clear()
            input.put(data, offset, length)
            queuedAt[ptsMicros] = System.nanoTime()
            codec.queueInputBuffer(index, 0, length, ptsMicros, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            waitingForKeyframe = false
            return true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder input failed", e)
            waitingForKeyframe = true
            return false
        }
    }

    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index >= 0) {
                    // render = true: display immediately, do not wait for a timestamp.
                    codec.releaseOutputBuffer(index, true)
                    val now = System.nanoTime()
                    val start = queuedAt.remove(info.presentationTimeUs)
                    val offset = clockOffsetMicros
                    synchronized(statsLock) {
                        frames++
                        if (start != null) {
                            val decode = now - start
                            decodeNanosSum += decode
                            if (decode > decodeNanosMax) decodeNanosMax = decode
                        }
                        if (offset != Long.MIN_VALUE) {
                            // Mac encode start -> handed to the display, on the Mac's clock.
                            val latency = now / 1000 + offset - info.presentationTimeUs
                            if (latency in 0L..2_000_000L) {
                                latencyMicrosSum += latency
                                if (latency > latencyMicrosMax) latencyMicrosMax = latency
                                latencyCount++
                            }
                        }
                    }
                    if (queuedAt.size > 64) queuedAt.clear()
                    onRendered?.invoke(now)
                }
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "Decoder output failed", e)
                break
            }
        }
    }

    fun release() {
        running = false
        try {
            outputThread.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
    }

    companion object {
        private const val TAG = "ScreeanerDecoder"

        fun mimeFor(codecName: String): String =
            if (codecName == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        private val codecCache = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()

        /** Codecs this device can decode at the given size; cached, since listing codecs is slow. */
        fun supportedCodecs(width: Int, height: Int): List<String> =
            codecCache.getOrPut((width.toLong() shl 32) or height.toLong()) { querySupportedCodecs(width, height) }

        private fun querySupportedCodecs(width: Int, height: Int): List<String> {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return listOf("h264", "hevc").filter { name ->
                val format = MediaFormat.createVideoFormat(mimeFor(name), width, height)
                name == "h264" || list.findDecoderForFormat(format) != null
            }
        }
    }
}
