package app.screeaner

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    /** Frames rendered and total decode time since the last stats read. */
    val renderedFrames = AtomicInteger()
    val decodeNanos = AtomicLong()

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
                    val start = queuedAt.remove(info.presentationTimeUs)
                    if (start != null) decodeNanos.addAndGet(System.nanoTime() - start)
                    renderedFrames.incrementAndGet()
                    if (queuedAt.size > 64) queuedAt.clear()
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

        /** Codecs this device can decode at the given size, in protocol naming. */
        fun supportedCodecs(width: Int, height: Int): List<String> {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return listOf("h264", "hevc").filter { name ->
                val format = MediaFormat.createVideoFormat(mimeFor(name), width, height)
                name == "h264" || list.findDecoderForFormat(format) != null
            }
        }
    }
}
