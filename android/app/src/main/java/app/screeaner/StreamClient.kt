package app.screeaner

import android.graphics.RectF
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
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What this tablet asks of the Mac; fixed for one client. */
data class ClientOptions(
    val deviceName: String,
    val deviceId: String,
    val appVersion: String,
    val displayMode: String,
    val displayPosition: String,
    val audio: Boolean,
    val clipboard: Boolean,
)

/**
 * Owns the connection to the Mac: keeps trying USB (127.0.0.1 through
 * `adb reverse`), the manually typed host and everything found via Bonjour.
 *
 * Every connection starts with the pairing handshake (see SecureChannel);
 * after it, all control messages are encrypted. Video uses the same TCP
 * socket over USB, and UDP with forward error correction over Wi-Fi so that
 * a lost packet never stalls the stream waiting for a retransmission.
 */
class StreamClient(
    private val surface: Surface,
    width: Int,
    height: Int,
    private val densityDpi: Int,
    private val refreshRate: Float,
    private val options: ClientOptions,
    private val trust: TrustStore,
    private val discovery: Discovery,
    private val manualHost: () -> String?,
    private val listener: Listener,
) {
    enum class State { SEARCHING, CONNECTING, WAITING, PAIRING, STREAMING }

    /** All callbacks arrive on background threads. */
    interface Listener {
        fun onState(state: State, detail: String)
        fun onSample(sample: StreamSample)

        /**
         * A pairing code to show. When [reply] is not null the user must
         * confirm the code matches the Mac's; otherwise only the Mac has to
         * approve and the code is shown for reference.
         */
        fun onPairingCode(code: String, macName: String, reply: ((Boolean) -> Unit)?)
        fun onDenied(reason: String)
        fun onClipboard(text: String)
        fun onProbeInfo(area: RectF)
    }

    /** What the Mac said it is sending; null until a stream starts. */
    @Volatile var streamInfo: StreamInfo? = null
        private set

    @Volatile private var width = width
    @Volatile private var height = height
    @Volatile private var running = true
    @Volatile var isStreaming = false
        private set
    @Volatile private var socket: Socket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var audio: AudioPlayer? = null
    @Volatile private var assembler: FecAssembler? = null
    @Volatile private var secure: SecureChannel.Session? = null
    @Volatile private var videoOverUdp = false
    @Volatile private var overUsb = false
    @Volatile private var lastEncodeMicros = 0
    @Volatile private var rttNanos = 0L
    @Volatile private var lastKeyframeRequest = 0L
    @Volatile private var clockOffsetMicros = Long.MIN_VALUE
    private var bestRttNanos = Long.MAX_VALUE // reader thread only
    private val keyframeRequests = AtomicInteger()
    private val bytesReceived = AtomicLong()
    /** Plain messages ([type][len][payload]) the writer thread seals and sends. */
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

    // region Outgoing (called from the UI thread; never blocks)

    private fun queue(type: Int, payload: ByteArray) {
        outbox.offer(message(type, payload))
    }

    /** tool: Protocol.TOOL_*; tilt in -1..1 along each axis. */
    fun sendTouch(
        action: Int, x: Float, y: Float, pressure: Float, button: Int,
        tiltX: Float = 0f, tiltY: Float = 0f, tool: Int = Protocol.TOOL_FINGER,
    ) {
        if (!isStreaming) return
        val payload = ByteBuffer.allocate(23)
            .put(action.toByte()).putFloat(x).putFloat(y).putFloat(pressure).put(button.toByte())
            .putFloat(tiltX).putFloat(tiltY).put(tool.toByte())
        queue(Protocol.TOUCH, payload.array())
    }

    fun sendScroll(dx: Float, dy: Float, x: Float, y: Float) {
        if (!isStreaming) return
        queue(Protocol.SCROLL, ByteBuffer.allocate(16).putFloat(dx).putFloat(dy).putFloat(x).putFloat(y).array())
    }

    fun sendZoom(phase: Int, delta: Float, x: Float, y: Float) {
        if (!isStreaming) return
        queue(Protocol.ZOOM, ByteBuffer.allocate(13).put(phase.toByte()).putFloat(delta).putFloat(x).putFloat(y).array())
    }

    fun sendKey(action: Int, keyCode: Int, isRepeat: Boolean, scanCode: Int = 0) {
        if (!isStreaming) return
        val payload = ByteBuffer.allocate(6)
            .put(action.toByte()).putShort(keyCode.toShort()).put((if (isRepeat) 1 else 0).toByte())
            .putShort(scanCode.toShort())
        queue(Protocol.KEY, payload.array())
    }

    fun sendClipboard(text: String) {
        if (!isStreaming || !options.clipboard) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 1_000_000) return
        queue(Protocol.CLIPBOARD, bytes)
    }

    /** End-to-end latency probe; 0 asks the Mac to show the marker. */
    fun sendProbe(id: Int) {
        if (!isStreaming) return
        queue(Protocol.PROBE, ByteBuffer.allocate(4).putInt(id).array())
    }

    /** The tablet rotated: the Mac resizes the display in place. */
    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        if (secure == null) return // the next hello carries the new size
        queue(Protocol.RESIZE, JSONObject().put("width", newWidth).put("height", newHeight).toString().toByteArray())
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
            var outcome = Outcome.FAILED
            try {
                listener.onState(State.CONNECTING, endpoint.host)
                outcome = serve(connected)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Session ended", e)
            } finally {
                isStreaming = false
                secure = null
                closeQuietly(connected)
                socket = null
                udpSocket?.close()
                udpSocket = null
                assembler = null
                decoder?.release()
                decoder = null
                audio?.release()
                audio = null
            }
            when (outcome) {
                // An endpoint that accepts but never streams (e.g. a stale adb
                // reverse with the Mac app closed) is skipped for a while.
                Outcome.FAILED -> badUntil[endpoint] = System.nanoTime() + 3_000_000_000L
                // Refused by the user: don't nag again right away.
                Outcome.DENIED -> badUntil[endpoint] = System.nanoTime() + 15_000_000_000L
                Outcome.STREAMED -> {}
            }
            pause(400)
        }
    }

    private enum class Outcome { FAILED, DENIED, STREAMED }

    // region Pairing handshake (reader thread, clear text)

    private class Denied(reason: String) : Exception(reason)

    private fun readRaw(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.read()
        if (type < 0) throw IOException("Connection closed")
        val length = input.readInt()
        if (length < 0 || length > 64 * 1024) throw IOException("Bad handshake length $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        if (type == Protocol.SEC_DENIED) throw Denied(String(payload, Charsets.UTF_8))
        return type to payload
    }

    private fun expect(input: DataInputStream, type: Int): ByteArray {
        val (got, payload) = readRaw(input)
        if (got != type) throw IOException("Unexpected handshake message $got")
        return payload
    }

    /** Runs the handshake; returns the session, or throws (Denied / IOException / GeneralSecurityException). */
    private fun handshake(socket: Socket, input: DataInputStream, output: OutputStream): SecureChannel.Session {
        // The Mac may wait for its user; allow for that, then go back to blocking reads.
        socket.soTimeout = 150_000
        val identity = trust.identity
        val ephemeral = SecureChannel.generateKeyPair()
        val ephemeralPublic = SecureChannel.encodePublic(ephemeral.public)
        val nonce = SecureChannel.randomBytes(SecureChannel.NONCE_SIZE)
        val hello = SecureChannel.buildHello(
            SecureChannel.encodePublic(identity.public),
            SecureChannel.sha256(ephemeralPublic, nonce),
            options.deviceId, options.deviceName,
        )
        output.write(message(Protocol.SEC_HELLO, hello))
        val serverPayload = expect(input, Protocol.SEC_SERVER)
        val server = SecureChannel.Server(serverPayload)
        val reveal = SecureChannel.buildReveal(ephemeralPublic, nonce)
        output.write(message(Protocol.SEC_REVEAL, reveal))

        val transcript = SecureChannel.transcript(hello, serverPayload, reveal)
        val session = SecureChannel.Session(
            SecureChannel.ecdh(ephemeral.private, SecureChannel.decodePublic(server.ephemeralPublic, 0)),
            SecureChannel.ecdh(identity.private, SecureChannel.decodePublic(server.staticPublic, 0)),
            transcript, true,
        )

        val knowsMac = trust.isTrusted(server.staticPublic)
        if (!knowsMac) {
            // First time with this Mac: the user compares the code on both screens.
            listener.onState(State.PAIRING, server.name)
            val decided = CountDownLatch(1)
            val accepted = AtomicBoolean(false)
            listener.onPairingCode(session.sas, server.name) { ok ->
                accepted.set(ok)
                decided.countDown()
            }
            if (!decided.await(120, TimeUnit.SECONDS) || !accepted.get()) throw Denied("Pareamento cancelado no tablet")
        } else if (!server.knowsTablet) {
            listener.onState(State.PAIRING, server.name)
            listener.onPairingCode(session.sas, server.name, null)
        }
        output.write(message(Protocol.SEC_CONFIRM, session.tabletConfirmation()))

        val ok = expect(input, Protocol.SEC_OK)
        if (!SecureChannel.constantTimeEquals(ok, session.macConfirmation())) {
            throw GeneralSecurityException("Mac failed to prove its key")
        }
        trust.trust(server.staticPublic, server.name)
        socket.soTimeout = 0
        return session
    }

    // endregion

    private fun serve(socket: Socket): Outcome {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val output = socket.getOutputStream()
        overUsb = socket.inetAddress.isLoopbackAddress
        videoOverUdp = false
        bestRttNanos = Long.MAX_VALUE
        clockOffsetMicros = Long.MIN_VALUE
        outbox.clear()

        val session = try {
            handshake(socket, input, output)
        } catch (e: Denied) {
            listener.onDenied(e.message ?: "")
            return Outcome.DENIED
        }
        secure = session
        listener.onState(State.WAITING, "")
        outbox.offer(message(Protocol.HELLO, helloJson(wantsUdp = !overUsb).toByteArray(Charsets.UTF_8)))
        val writer = Thread({ writeLoop(socket, output, session) }, "screeaner-writer").apply { start() }
        var udpThread: Thread? = null

        var gotConfig = false
        var buffer = ByteArray(512 * 1024)
        try {
            while (running) {
                val wireType = input.read()
                if (wireType < 0) break
                val length = input.readInt()
                if (length < 0 || length > Protocol.MAX_PAYLOAD) throw IOException("Bad length $length")
                if (length > buffer.size) buffer = ByteArray(maxOf(length, buffer.size * 2))
                input.readFully(buffer, 0, length)
                if (wireType != Protocol.ENCRYPTED) throw IOException("Unencrypted message $wireType")
                // [type][payload]; any tampering throws and drops the connection.
                val plain = session.open(buffer, 0, length)
                val type = plain[0].toInt() and 0xFF
                val size = plain.size - 1

                when (type) {
                    Protocol.CONFIG -> {
                        val json = JSONObject(String(plain, 1, size, Charsets.UTF_8))
                        val info = StreamInfo(
                            codec = json.getString("codec"),
                            width = json.getInt("width"),
                            height = json.getInt("height"),
                            fps = json.optInt("fps", 60),
                            macName = json.optString("server", "Mac"),
                            macVersion = json.optString("version", "?"),
                            mode = json.optString("mode", "extend"),
                        )
                        streamInfo = info
                        // A second CONFIG means the size changed (rotation): new decoder, same connection.
                        decoder?.release()
                        decoder = VideoDecoder(info.codec, info.width, info.height, info.fps, surface).also {
                            it.clockOffsetMicros = clockOffsetMicros
                        }
                        if (!gotConfig) {
                            gotConfig = true
                            if (json.optBoolean("audio", false)) audio = AudioPlayer()
                            val token = json.optLong("token", 0L).toInt()
                            if (token != 0 && !overUsb) {
                                udpThread = startUdp(socket.inetAddress, json.optInt("udpPort", Protocol.PORT), token)
                            }
                        }
                        isStreaming = true
                        listener.onState(State.STREAMING, "")
                    }

                    Protocol.VIDEO -> if (size > Protocol.VIDEO_HEADER) {
                        val header = ByteBuffer.wrap(plain, 1, Protocol.VIDEO_HEADER)
                        val pts = header.long
                        val isKey = (header.get().toInt() and 1) != 0
                        val encodeMicros = header.int
                        // Once video flows over UDP, TCP video only means the Mac
                        // fell back, which always starts with a keyframe.
                        if (isKey) videoOverUdp = false
                        if (!videoOverUdp) {
                            deliver(
                                plain, 1 + Protocol.VIDEO_HEADER, size - Protocol.VIDEO_HEADER,
                                pts, isKey, encodeMicros, length,
                            )
                        }
                    }

                    Protocol.AUDIO -> audio?.write(plain, 1, size)

                    Protocol.CLIPBOARD -> listener.onClipboard(String(plain, 1, size, Charsets.UTF_8))

                    Protocol.PROBE_INFO -> if (size >= 16) {
                        val area = ByteBuffer.wrap(plain, 1, 16)
                        val x = area.float
                        val y = area.float
                        val w = area.float
                        val h = area.float
                        listener.onProbeInfo(RectF(x, y, x + w, y + h))
                    }

                    Protocol.PONG -> if (size >= 8) {
                        val pong = ByteBuffer.wrap(plain, 1, size)
                        val sentAt = pong.long
                        val rtt = System.nanoTime() - sentAt
                        rttNanos = rtt
                        if (size >= 16) syncClock(sentAt, rtt, pong.long)
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
        return if (gotConfig) Outcome.STREAMED else Outcome.FAILED
    }

    private fun deliver(
        data: ByteArray, offset: Int, length: Int, pts: Long, isKey: Boolean, encodeMicros: Int, wireBytes: Int,
    ) {
        lastEncodeMicros = encodeMicros
        bytesReceived.addAndGet(wireBytes.toLong())
        val accepted = decoder?.feed(data, offset, length, pts, isKey) ?: return
        if (!accepted) requestKeyframe()
    }

    /**
     * The Mac stamps its clock on every pong. Assuming a symmetric path, the
     * Mac read it half a round trip after we sent the ping; the quickest
     * round trips give the most trustworthy offset, so those win.
     */
    private fun syncClock(sentAtNanos: Long, rttNanos: Long, macMicros: Long) {
        if (bestRttNanos != Long.MAX_VALUE) bestRttNanos += bestRttNanos / 20 // let it age: clocks drift
        if (rttNanos > bestRttNanos) return
        bestRttNanos = rttNanos
        val offset = macMicros - (sentAtNanos + rttNanos / 2) / 1000
        clockOffsetMicros = offset
        decoder?.clockOffsetMicros = offset
    }

    private fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeRequest < 500_000_000L) return
        lastKeyframeRequest = now
        keyframeRequests.incrementAndGet()
        queue(Protocol.KEYFRAME_REQUEST, ByteArray(0))
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
            override fun onFrame(frameId: Int, blob: ByteArray, length: Int, key: Boolean) {
                val keys = secure ?: return
                val plain = try {
                    keys.openVideo(frameId.toLong() and 0xFFFFFFFFL, blob, length)
                } catch (e: GeneralSecurityException) {
                    requestKeyframe() // forged or corrupted: never decode it
                    return
                }
                if (plain.size <= UDP_BLOB_HEADER) return
                val header = ByteBuffer.wrap(plain, 0, UDP_BLOB_HEADER)
                val pts = header.long
                val encodeMicros = header.int
                videoOverUdp = true
                deliver(plain, UDP_BLOB_HEADER, plain.size - UDP_BLOB_HEADER, pts, key, encodeMicros, length)
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

    /** The only thread that seals and writes, so message counters follow the wire order. */
    private fun writeLoop(socket: Socket, output: OutputStream, session: SecureChannel.Session) {
        fun send(plain: ByteArray) {
            val sealed = session.seal(plain[0].toInt() and 0xFF, plain, 5, plain.size - 5)
            output.write(message(Protocol.ENCRYPTED, sealed))
        }
        var lastTick = 0L
        try {
            while (running && !socket.isClosed) {
                val next = outbox.poll(200, TimeUnit.MILLISECONDS)
                if (next != null) send(next)
                val now = System.nanoTime()
                if (now - lastTick >= 1_000_000_000L) {
                    lastTick = now
                    send(message(Protocol.PING, ByteBuffer.allocate(8).putLong(now).array()))
                    reportStats()?.let { send(it) }
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Encryption failed", e)
        } finally {
            closeQuietly(socket)
        }
    }

    /** Once per second: link stats for the Mac (returned) and the on-screen monitor. */
    private fun reportStats(): ByteArray? {
        val current = decoder ?: return null
        val decoded = current.takeStats()
        val bytes = bytesReceived.getAndSet(0)
        val link = assembler?.takeStats() ?: intArrayOf(0, 0, 0)
        val rttMicros = (rttNanos / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        val stats = ByteBuffer.allocate(16)
            .putInt(rttMicros)
            .putInt(link[0])
            .putInt(link[1])
            .putShort(link[2].coerceAtMost(0xFFFF).toShort())
            .putShort(decoded.frames.coerceAtMost(0xFFFF).toShort())

        listener.onSample(
            StreamSample(
                transport = if (overUsb) "USB" else if (videoOverUdp) "UDP+FEC" else "TCP",
                fps = decoded.frames,
                mbps = bytes * 8 / 1e6,
                latencyAvgMs = decoded.latencyAvgMs,
                latencyMaxMs = decoded.latencyMaxMs,
                encodeMs = lastEncodeMicros / 1000.0,
                networkMs = rttNanos / 2e6,
                decodeAvgMs = decoded.decodeAvgMs,
                decodeMaxMs = decoded.decodeMaxMs,
                packetLossPct = if (link[0] > 0) link[1] * 100.0 / link[0] else 0.0,
                framesLost = link[2],
                keyframeRequests = keyframeRequests.getAndSet(0),
            )
        )
        return message(Protocol.STATS, stats.array())
    }

    private fun helloJson(wantsUdp: Boolean): String = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("densityDpi", densityDpi)
        .put("refreshRate", refreshRate.toDouble())
        .put("codecs", JSONArray(VideoDecoder.supportedCodecs(width, height)))
        .put("name", options.deviceName)
        .put("udp", wantsUdp)
        .put("version", options.appVersion)
        .put("mode", options.displayMode)
        .put("position", options.displayPosition)
        .put("clipboard", options.clipboard)
        .put("audio", options.audio)
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
