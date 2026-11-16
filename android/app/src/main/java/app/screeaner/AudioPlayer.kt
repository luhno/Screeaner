package app.screeaner

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the Mac's audio (PCM s16le, stereo, 48 kHz) with as little delay as
 * possible: if more than [MAX_QUEUED_MS] is already waiting, new audio is
 * dropped instead of letting the delay grow.
 */
class AudioPlayer {
    private val track: AudioTrack?
    private var framesWritten = 0L

    init {
        track = try {
            val minimum = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE * BYTES_PER_FRAME / 10))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        } catch (e: Exception) {
            Log.w(TAG, "Audio output unavailable", e)
            null
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        val output = track ?: return
        val played = output.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queued = framesWritten - played
        if (queued > SAMPLE_RATE * MAX_QUEUED_MS / 1000) return
        val usable = length - length % BYTES_PER_FRAME
        val written = output.write(data, offset, usable, AudioTrack.WRITE_NON_BLOCKING)
        if (written > 0) framesWritten += written / BYTES_PER_FRAME
    }

    fun release() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
    }

    private companion object {
        const val TAG = "ScreeanerAudio"
        const val SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 4
        const val MAX_QUEUED_MS = 80
    }
}
