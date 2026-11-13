package app.screeaner

import android.util.Log
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the connection to the Mac: keeps trying USB (127.0.0.1 through
 * `adb reverse`), the manually typed host and everything found via Bonjour.
 *
 * Control messages and input always use TCP. Video uses the same TCP socket
 * over USB, and UDP with forward error correction over Wi-Fi so that a lost
 * packet never stalls the stream waiting for a retransmission.
 */
class StreamClient(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val refreshRate: Float,
    private val deviceName: String,
    private val deviceId: String,
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
    @Volatile var isStreaming = false
        private set
    @Volatile private var socket: Socket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var assembler: FecAssembler? = null
    @Volatile private var videoOverUdp = false
    @Volatile private var overUsb = false
    @Volatile private var lastEncodeMicros = 0
    @Volatile private var rttNanos = 0L
    @Volatile private var lastKeyframeRequest = 0L
    private val bytesReceived = AtomicLong()
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val badUntil = ConcurrentHashMap<Discovery.Endpoint, Long>()
    private val thread = Thread(::run, "screeaner-client")

    fun hasSize(w: Int, h: Int) = w == width && h == height

    fun start() = thread.start()

    fun stop() {
        running = false
        closeQuietly(socket)
        udpSocket?.close()
        thread.interrupt()
        try {
            thread.join(1500)
        } catch (_: InterruptedException) {
        }
    }

    // region Input (called from the UI thread; never blocks)

    fun sendTouch(action: Int, x: Float, y: Float, pressure: Float, button: Int) {
        if (!isStreaming) return
        val buffer = ByteBuffer.allocate(5 + 14)
        buffer.put(Protocol.TOUCH.toByte()).putInt(14)
        buffer.put(action.toByte()).putFloat(x).putFloat(y).putFloat(pressure).put(button.toByte())
        outbox.offer(buffer.array())
    }

    fun sendScroll(dx: Float, dy: Float, x: Float, y: Float) {
        if (!isStreaming) return
        val buffer = ByteBuffer.allocate(5 + 16)
        buffer.put(Protocol.SCROLL.toByte()).putInt(16)
        buffer.putFloat(dx).putFloat(dy).putFloat(x).putFloat(y)
        outbox.offer(buffer.array())
    }

    fun sendZoom(phase: Int, delta: Float, x: Float, y: Float) {
        if (!isStreaming) return
        val buffer = ByteBuffer.allocate(5 + 13)
        buffer.put(Protocol.ZOOM.toByte()).putInt(13)
        buffer.put(phase.toByte()).putFloat(delta).putFloat(x).putFloat(y)
        outbox.offer(buffer.array())
    }

    fun sendKey(action: Int, keyCode: Int, isRepeat: Boolean) {
        if (!isStreaming) return
        val buffer = ByteBuffer.allocate(5 + 4)
        buffer.put(Protocol.KEY.toByte()).putInt(4)
        buffer.put(action.toByte()).putShort(keyCode.toShort()).put((if (isRepeat) 1 else 0).toByte())
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
                isStreaming = false
                closeQuietly(connected)
                socket = null
                udpSocket?.close()
                udpSocket = null
                assembler = null
                decoder?.release()
                decoder = null
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
        overUsb = socket.inetAddress.isLoopbackAddress
        videoOverUdp = false
        outbox.clear()
        outbox.offer(message(Protocol.HELLO, helloJson(wantsUdp = !overUsb).toByteArray(Charsets.UTF_8)))
        val writer = Thread({ writeLoop(socket, output) }, "screeaner-writer").apply { start() }
        var udpThread: Thread? = null

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
                    Protocol.CONFIG -> if (!gotConfig) {
                        val json = JSONObject(String(buffer, 0, length, Charsets.UTF_8))
                        decoder = VideoDecoder(
                            json.getString("codec"),
                            json.getInt("width"),
                            json.getInt("height"),
                            json.optInt("fps", 60),
                            surface,
                        )
                        gotConfig = true
                        isStreaming = true
                        val token = json.optLong("token", 0L).toInt()
                        if (token != 0 && !overUsb) {
                            udpThread = startUdp(socket.inetAddress, json.optInt("udpPort", Protocol.PORT), token)
                        }
                        listener.onState(State.STREAMING, "")
                    }

                    Protocol.VIDEO -> if (length > Protocol.VIDEO_HEADER) {
                        val header = ByteBuffer.wrap(buffer, 0, Protocol.VIDEO_HEADER)
                        val pts = header.long
                        val isKey = (header.get().toInt() and 1) != 0
                        val encodeMicros = header.int
                        // Once video flows over UDP, TCP video only means the Mac
                        // fell back, which always starts with a keyframe.
                        if (isKey) videoOverUdp = false
                        if (!videoOverUdp) {
                            deliver(
                                buffer, Protocol.VIDEO_HEADER, length - Protocol.VIDEO_HEADER,
                                pts, isKey, encodeMicros, length,
                            )
                        }
                    }

                    Protocol.PONG -> if (length >= 8) {
                        rttNanos = System.nanoTime() - ByteBuffer.wrap(buffer, 0, 8).long
                    }
                }
            }
        } finally {
            isStreaming = false
            closeQuietly(socket)
            udpSocket?.close()
            writer.interrupt()
            try {
                udpThread?.join(500)
            } catch (_: InterruptedException) {
            }
        }
        return gotConfig
    }

    private fun deliver(
        data: ByteArray, offset: Int, length: Int, pts: Long, isKey: Boolean, encodeMicros: Int, wireBytes: Int,
    ) {
        lastEncodeMicros = encodeMicros
        bytesReceived.addAndGet(wireBytes.toLong())
        val accepted = decoder?.feed(data, offset, length, pts, isKey) ?: return
        if (!accepted) requestKeyframe()
    }

    private fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeRequest < 500_000_000L) return
        lastKeyframeRequest = now
        outbox.offer(message(Protocol.KEYFRAME_REQUEST))
    }

    // region UDP video

    private fun startUdp(address: InetAddress, port: Int, token: Int): Thread? {
        val udp = try {
            DatagramSocket().apply {
                receiveBufferSize = 2 * 1024 * 1024
                soTimeout = 20
            }
        } catch (e: IOException) {
            Log.w(TAG, "UDP unavailable, staying on TCP", e)
            return null
        }
        udpSocket = udp
        val frames = FecAssembler(object : FecAssembler.Sink {
            override fun onFrame(blob: ByteArray, length: Int, key: Boolean) {
                if (length <= UDP_BLOB_HEADER) return
                val header = ByteBuffer.wrap(blob, 0, UDP_BLOB_HEADER)
                val pts = header.long
                val encodeMicros = header.int
                videoOverUdp = true
                deliver(blob, UDP_BLOB_HEADER, length - UDP_BLOB_HEADER, pts, key, encodeMicros, length)
            }

            override fun onLoss() = requestKeyframe()
        })
        assembler = frames
        return Thread({ udpLoop(udp, address, port, token, frames) }, "screeaner-udp").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun udpLoop(udp: DatagramSocket, address: InetAddress, port: Int, token: Int, frames: FecAssembler) {
        // The Mac answers on the flow this hello opens, so it also works
        // through firewalls; repeating it keeps the flow alive.
        val hello = ByteBuffer.allocate(8).putInt(Protocol.UDP_MAGIC).putInt(token).array()
        val helloPacket = DatagramPacket(hello, hello.size, address, port)
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        var lastHello = 0L
        var receiving = false
        while (running && !udp.isClosed) {
            val now = System.nanoTime()
            if (now - lastHello > (if (receiving) 1_000_000_000L else 200_000_000L)) {
                lastHello = now
                try {
                    udp.send(helloPacket)
                } catch (_: IOException) {
                }
            }
            try {
                packet.setData(buffer, 0, buffer.size)
                udp.receive(packet)
                receiving = true
                frames.onPacket(buffer, packet.length, System.nanoTime())
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                break
            }
            frames.onIdle(System.nanoTime())
        }
    }

    // endregion

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
                    reportStats(output)
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            closeQuietly(socket)
        }
    }

    /** Once per second: tells the Mac how the link is doing and refreshes the HUD. */
    private fun reportStats(output: OutputStream) {
        val current = decoder ?: return
        val frames = current.renderedFrames.getAndSet(0)
        val decodeNanos = current.decodeNanos.getAndSet(0)
        val bytes = bytesReceived.getAndSet(0)
        val link = assembler?.takeStats() ?: intArrayOf(0, 0, 0)
        val rttMicros = (rttNanos / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        val stats = ByteBuffer.allocate(16)
            .putInt(rttMicros)
            .putInt(link[0])
            .putInt(link[1])
            .putShort(link[2].coerceAtMost(0xFFFF).toShort())
            .putShort(frames.coerceAtMost(0xFFFF).toShort())
        output.write(message(Protocol.STATS, stats.array()))

        val decodeMs = if (frames > 0) decodeNanos / frames / 1e6 else 0.0
        val transport = if (overUsb) "USB" else if (videoOverUdp) "UDP+FEC" else "TCP"
        var text = String.format(
            Locale.US,
            "%s · %d fps · %.1f Mbps · enc %.1f ms · rede %.1f ms · dec %.1f ms",
            transport, frames, bytes * 8 / 1e6, lastEncodeMicros / 1000.0, rttNanos / 2e6, decodeMs,
        )
        if (link[0] > 0 && link[1] > 0) {
            text += String.format(Locale.US, " · perda %.1f%%", link[1] * 100.0 / link[0])
        }
        listener.onStats(text)
    }

    private fun helloJson(wantsUdp: Boolean): String = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("densityDpi", densityDpi)
        .put("refreshRate", refreshRate.toDouble())
        .put("codecs", JSONArray(VideoDecoder.supportedCodecs(width, height)))
        .put("name", deviceName)
        .put("id", deviceId)
        .put("udp", wantsUdp)
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
        const val UDP_BLOB_HEADER = 12
    }
}
