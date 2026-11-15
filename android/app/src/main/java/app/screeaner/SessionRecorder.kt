package app.screeaner

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a test session: one CSV row per second with every stream and device
 * measurement, plus a header describing the setup and a summary at the end,
 * so runs can be compared (USB vs Wi-Fi, codecs, bitrates, ...).
 *
 * Files live in the app's external "sessions" folder and are also copied to
 * Downloads/Screeaner on Android 10+. Use from a single thread.
 */
class SessionRecorder(private val context: Context) {
    class Summary(val file: File, val seconds: Int, val text: String, val publicLocation: String?)

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var startedAt = 0L
    private var rows = 0
    private val latencies = ArrayList<Double>()
    private var fpsSum = 0L
    private var mbpsSum = 0.0
    private var lossSum = 0.0
    private var framesLost = 0
    private var keyframes = 0
    private var cpuSum = 0.0
    private var maxTemp = 0.0
    private val notes = ArrayList<String>()

    val isRecording: Boolean get() = writer != null
    val elapsedSeconds: Int get() = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    /** Most recent finished recording, if any. */
    fun lastFile(): File? = directory().listFiles { f -> f.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }

    private fun directory(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }

    /** [setup] lines describe the test conditions and go to the top of the file as comments. */
    fun start(setup: List<String>): Boolean {
        if (isRecording) return true
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(directory(), "screeaner-$stamp.csv")
            val out = BufferedWriter(FileWriter(target))
            out.write("# Screeaner test session\n")
            out.write("# started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            setup.forEach { out.write("# $it\n") }
            out.write(HEADER + "\n")
            out.flush()
            writer = out
            file = target
            startedAt = SystemClock.elapsedRealtime()
            rows = 0
            latencies.clear()
            fpsSum = 0
            mbpsSum = 0.0
            lossSum = 0.0
            framesLost = 0
            keyframes = 0
            cpuSum = 0.0
            maxTemp = 0.0
            notes.clear()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start recording", e)
            false
        }
    }

    fun append(stream: StreamSample, device: DeviceSample) {
        val out = writer ?: return
        try {
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            out.write(
                listOf(
                    num(seconds, 1), stream.transport, stream.fps.toString(), num(stream.mbps, 2),
                    num(stream.latencyAvgMs, 2), num(stream.latencyMaxMs, 2), num(stream.encodeMs, 2),
                    num(stream.networkMs, 2), num(stream.decodeAvgMs, 2), num(stream.decodeMaxMs, 2),
                    num(stream.packetLossPct, 2), stream.framesLost.toString(), stream.keyframeRequests.toString(),
                    num(device.cpuPct, 1), num(device.memoryMb, 1), device.batteryPct.toString(),
                    num(device.batteryTempC, 1), device.thermal,
                ).joinToString(",")
            )
            out.write("\n")
            rows++
            if (rows % 5 == 0) out.flush()

            if (!stream.latencyAvgMs.isNaN()) latencies += stream.latencyAvgMs
            fpsSum += stream.fps
            mbpsSum += stream.mbps
            lossSum += stream.packetLossPct
            framesLost += stream.framesLost
            keyframes += stream.keyframeRequests
            cpuSum += device.cpuPct
            if (device.batteryTempC > maxTemp) maxTemp = device.batteryTempC
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed", e)
        }
    }

    /** Writes [lines] (e.g. a latency test result) as comments, and repeats them in the summary. */
    fun note(lines: List<String>) {
        val out = writer ?: return
        val seconds = num((SystemClock.elapsedRealtime() - startedAt) / 1000.0, 1)
        try {
            lines.forEach { out.write("# [$seconds s] $it\n") }
            out.flush()
            notes += lines
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed", e)
        }
    }

    /** Closes the file, appends the summary and publishes a copy to Downloads when possible. */
    fun stop(): Summary? {
        val out = writer ?: return null
        val target = file ?: return null
        writer = null
        file = null

        val n = maxOf(rows, 1)
        val sorted = latencies.sorted()
        fun percentile(p: Double) = if (sorted.isEmpty()) Double.NaN else sorted[((sorted.size - 1) * p).toInt()]
        val lines = listOf(
            "amostras: $rows s",
            "latência Mac→tela: média ${num(sorted.average(), 1)} ms · p50 ${num(percentile(0.5), 1)} ms" +
                " · p95 ${num(percentile(0.95), 1)} ms · máx ${num(sorted.lastOrNull() ?: Double.NaN, 1)} ms",
            "fps médio: ${num(fpsSum.toDouble() / n, 1)} · bitrate médio: ${num(mbpsSum / n, 1)} Mbps",
            "perda média: ${num(lossSum / n, 2)} % · quadros perdidos: $framesLost · keyframes pedidos: $keyframes",
            "CPU média do app: ${num(cpuSum / n, 0)} % de um núcleo · temp. máx. bateria: ${num(maxTemp, 1)} °C",
        ) + notes
        try {
            out.write("# --- resumo ---\n")
            lines.forEach { out.write("# $it\n") }
            out.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not finish recording", e)
        }
        return Summary(target, rows, lines.joinToString("\n"), publish(target))
    }

    /** Copies the file to the shared Downloads folder so it is easy to find. Android 10+ only. */
    private fun publish(source: File): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Screeaner")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            "Downloads/Screeaner/${source.name}"
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy to Downloads", e)
            null
        }
    }

    private fun num(value: Double, decimals: Int): String =
        if (value.isNaN()) "" else String.format(Locale.US, "%.${decimals}f", value)

    private companion object {
        const val TAG = "ScreeanerRecorder"
        const val HEADER = "t_s,transport,fps,mbps,latency_avg_ms,latency_max_ms,encode_ms,network_ms," +
            "decode_avg_ms,decode_max_ms,packet_loss_pct,frames_lost,keyframe_requests," +
            "cpu_pct,memory_mb,battery_pct,battery_temp_c,thermal"
    }
}
