package app.screeaner

import android.util.Log
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the connection to the Mac: keeps trying USB (127.0.0.1 through
 * `adb reverse`), the manually typed host and everything found via Bonjour,
 * then receives video into the decoder and sends input back.
 */
class StreamClient(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val refreshRate: Float,
    private val deviceName: String,
    private val discovery: Discovery,
    private val manualHost: () -> String?,
    private val listener: Listener,
) {
    enum class State { SEARCHING, CONNECTING, WAITING, STREAMING }

    /** All callbacks arrive on background threads. */
    interface Listener {
        fun onState(state: State, detail: String)
        fun onStats(text: String)
    }

    @Volatile private var running = true
    @Volatile private var streaming = false
    @Volatile private var socket: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var lastEncodeMicros = 0
    @Volatile private var rttNanos = 0L
    private val bytesReceived = AtomicLong()
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val badUntil = ConcurrentHashMap<Discovery.Endpoint, Long>()
    private var lastKeyframeRequest = 0L
    private val thread = Thread(::run, "screeaner-client")

    val sameSize: (Int, Int) -> Boolean = { w, h -> w == width && h == height }

    fun start() = thread.start()

    fun stop() {
        running = false
        closeQuietly(socket)
        thread.interrupt()
        try {
            thread.join(1500)
        } catch (_: InterruptedException) {
        }
    }

    // region Input (called from the UI thread; never blocks)

    fun sendTouch(action: Int, x: Float, y: Float, pressure: Float, button: Int) {
        if (!streaming) return
        val buffer = ByteBuffer.allocate(5 + 14)
        buffer.put(Protocol.TOUCH.toByte()).putInt(14)
        buffer.put(action.toByte()).putFloat(x).putFloat(y).putFloat(pressure).put(button.toByte())
        outbox.offer(buffer.array())
    }

    fun sendScroll(dx: Float, dy: Float, x: Float, y: Float) {
        if (!streaming) return
        val buffer = ByteBuffer.allocate(5 + 16)
        buffer.put(Protocol.SCROLL.toByte()).putInt(16)
        buffer.putFloat(dx).putFloat(dy).putFloat(x).putFloat(y)
        outbox.offer(buffer.array())
    }

    // endregion

    private fun message(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()

    private fun candidates(): List<Discovery.Endpoint> {
        val list = ArrayList<Discovery.Endpoint>()
        list += Discovery.Endpoint("127.0.0.1", Protocol.PORT) // USB first: lowest latency
        manualHost()?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val host = text.substringBefore(':')
            val port = text.substringAfter(':', "").toIntOrNull() ?: Protocol.PORT
            list += Discovery.Endpoint(host, port)
        }
        list += discovery.endpoints
        val now = System.nanoTime()
        return list.distinct().filter { (badUntil[it] ?: 0L) <= now }
    }

    private fun run() {
        while (running) {
            listener.onState(State.SEARCHING, "")
            var connected: Socket? = null
            var endpoint: Discovery.Endpoint? = null
            for (candidate in candidates()) {
                if (!running) return
                val attempt = Socket()
                try {
                    attempt.tcpNoDelay = true
                    attempt.connect(InetSocketAddress(candidate.host, candidate.port), 600)
                    connected = attempt
                    endpoint = candidate
                    break
                } catch (e: Exception) {
                    closeQuietly(attempt)
                }
            }
            if (connected == null || endpoint == null) {
                pause(700)
                continue
            }

            socket = connected
            var gotConfig = false
            try {
                listener.onState(State.WAITING, endpoint.host)
                gotConfig = serve(connected)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Session ended", e)
            } finally {
                streaming = false
                decoder?.release()
                decoder = null
                closeQuietly(connected)
                socket = null
            }
            // An endpoint that accepts but never streams (e.g. a stale adb
            // reverse with the Mac app closed) is skipped for a while.
            if (!gotConfig) badUntil[endpoint] = System.nanoTime() + 3_000_000_000L
            pause(400)
        }
    }

    /** Returns true if the Mac got as far as starting a stream. */
    private fun serve(socket: Socket): Boolean {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val output = socket.getOutputStream()
        outbox.clear()
        outbox.offer(message(Protocol.HELLO, helloJson().toByteArray(Charsets.UTF_8)))
        val writer = Thread({ writeLoop(socket, output) }, "screeaner-writer").apply { start() }

        var gotConfig = false
        var buffer = ByteArray(512 * 1024)
        try {
            while (running) {
                val type = input.read()
                if (type < 0) break
                val length = input.readInt()
                if (length < 0 || length > Protocol.MAX_PAYLOAD) throw IOException("Bad length $length")
                if (length > buffer.size) buffer = ByteArray(maxOf(length, buffer.size * 2))
                input.readFully(buffer, 0, length)

                when (type) {
                    Protocol.CONFIG -> {
                        val json = JSONObject(String(buffer, 0, length, Charsets.UTF_8))
                        decoder?.release()
                        decoder = VideoDecoder(
                            json.getString("codec"),
                            json.getInt("width"),
                            json.getInt("height"),
                            json.optInt("fps", 60),
                            surface,
                        )
                        gotConfig = true
                        streaming = true
                        listener.onState(State.STREAMING, "")
                    }

                    Protocol.VIDEO -> {
                        val current = decoder
                        if (current != null && length > Protocol.VIDEO_HEADER) {
                            val header = ByteBuffer.wrap(buffer, 0, Protocol.VIDEO_HEADER)
                            val pts = header.long
                            val isKey = (header.get().toInt() and 1) != 0
                            lastEncodeMicros = header.int
                            bytesReceived.addAndGet(length.toLong())
                            val accepted = current.feed(
                                buffer, Protocol.VIDEO_HEADER, length - Protocol.VIDEO_HEADER, pts, isKey,
                            )
                            if (!accepted) requestKeyframe()
                        }
                    }

                    Protocol.PONG -> if (length >= 8) {
                        rttNanos = System.nanoTime() - ByteBuffer.wrap(buffer, 0, 8).long
                    }
                }
            }
        } finally {
            streaming = false
            closeQuietly(socket)
            writer.interrupt()
        }
        return gotConfig
    }

    private fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeRequest < 500_000_000L) return
        lastKeyframeRequest = now
        outbox.offer(message(Protocol.KEYFRAME_REQUEST))
    }

    private fun writeLoop(socket: Socket, output: OutputStream) {
        var lastTick = 0L
        try {
            while (running && !socket.isClosed) {
                val next = outbox.poll(200, TimeUnit.MILLISECONDS)
                if (next != null) output.write(next)
                val now = System.nanoTime()
                if (now - lastTick >= 1_000_000_000L) {
                    lastTick = now
                    output.write(message(Protocol.PING, ByteBuffer.allocate(8).putLong(now).array()))
                    publishStats()
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            closeQuietly(socket)
        }
    }

    private fun publishStats() {
        val current = decoder ?: return
        val frames = current.renderedFrames.getAndSet(0)
        val decodeNanos = current.decodeNanos.getAndSet(0)
        val bytes = bytesReceived.getAndSet(0)
        val decodeMs = if (frames > 0) decodeNanos / frames / 1e6 else 0.0
        listener.onStats(
            String.format(
                Locale.US,
                "%d fps · %.1f Mbps · enc %.1f ms · rede %.1f ms · dec %.1f ms",
                frames, bytes * 8 / 1e6, lastEncodeMicros / 1000.0, rttNanos / 2e6, decodeMs,
            )
        )
    }

    private fun helloJson(): String = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("densityDpi", densityDpi)
        .put("refreshRate", refreshRate.toDouble())
        .put("codecs", JSONArray(VideoDecoder.supportedCodecs(width, height)))
        .put("name", deviceName)
        .toString()

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "ScreeanerClient"
    }
}
