package app.screeaner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/** What the Mac announced for the current stream. */
data class StreamInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val macName: String,
    val macVersion: String,
    /** True when the Mac's encoder runs in low-latency rate control; null if the Mac app is too old to say. */
    val lowLatencyEncoder: Boolean? = null,
    /** True when each encoded frame is capped in size; null if unknown. */
    val frameCap: Boolean? = null,
) {
    /** "H.264 · 60 fps · encoder baixa latência · limite por quadro" */
    fun describe(): String {
        val parts = mutableListOf(if (codec == "hevc") "HEVC" else "H.264", "$fps fps")
        lowLatencyEncoder?.let { parts += if (it) "encoder baixa latência" else "encoder normal" }
        frameCap?.let { parts += if (it) "limite por quadro" else "sem limite por quadro" }
        return parts.joinToString(" · ")
    }
}

/** Settings the tablet asks the Mac to use for one stream (the benchmark's configurations). */
data class StreamOverride(val label: String, val codec: String, val fps: Int, val frameCap: Boolean)

/** One second of stream measurements. Latencies are NaN while the clocks are not synced yet. */
data class StreamSample(
    val transport: String,
    val fps: Int,
    val mbps: Double,
    /** Mac starts encoding the frame -> tablet hands it to the display. */
    val latencyAvgMs: Double,
    val latencyMaxMs: Double,
    val encodeMs: Double,
    /** Half of the ping round trip. */
    val networkMs: Double,
    /** First byte (TCP) or datagram (UDP) of a frame -> whole frame received. */
    val transferAvgMs: Double,
    val transferMaxMs: Double,
    /** Frame enters the decoder -> handed to the display. */
    val decodeAvgMs: Double,
    val decodeMaxMs: Double,
    val packetLossPct: Double,
    val framesLost: Int,
    val keyframeRequests: Int,
)

/** One reading of how the tablet itself is coping. */
data class DeviceSample(
    /** CPU used by this app, as a percentage of one core. */
    val cpuPct: Double,
    val memoryMb: Double,
    val batteryPct: Int,
    val batteryTempC: Double,
    val thermal: String,
)

/** Cheap device health readings, meant to be polled about once per second. */
class DeviceMonitor(private val context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

    fun sample(): DeviceSample {
        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val cpuPct = if (wallMs > lastWallMs) (cpuMs - lastCpuMs) * 100.0 / (wallMs - lastWallMs) else 0.0
        lastCpuMs = cpuMs
        lastWallMs = wallMs

        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory() + Debug.getNativeHeapAllocatedSize()

        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        return DeviceSample(
            cpuPct = cpuPct,
            memoryMb = usedBytes / 1048576.0,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            batteryTempC = tenths / 10.0,
            thermal = thermalStatus(),
        )
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "n/d"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "leve"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderado"
            PowerManager.THERMAL_STATUS_SEVERE -> "severo"
            PowerManager.THERMAL_STATUS_CRITICAL -> "crítico"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergência"
            else -> "desligando"
        }
    }
}
