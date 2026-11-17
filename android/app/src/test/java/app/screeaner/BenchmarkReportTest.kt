package app.screeaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {
    private fun sample(latency: Double, encode: Double, transfer: Double) = StreamSample(
        transport = "UDP+FEC", fps = 60, mbps = 20.0, latencyAvgMs = latency, latencyMaxMs = latency + 10,
        encodeMs = encode, networkMs = 2.5, transferAvgMs = transfer, transferMaxMs = transfer * 2,
        decodeAvgMs = 7.0, decodeMaxMs = 9.0, packetLossPct = 0.0, framesLost = 0, keyframeRequests = 0,
    )

    private fun info(codec: String, fps: Int, lowLatency: Boolean) =
        StreamInfo(codec, 2800, 1752, fps, "Mac", "0.5.0", lowLatency, frameCap = true)

    private fun touch(vararg ms: Double) =
        LatencyTest.Result(LatencyTest.Outcome.DONE, ms.toList(), listOf(4.0), listOf(30.0), timeouts = 0)

    @Test
    fun matrixFollowsCodecsAndPanel() {
        assertEquals(
            listOf("HEVC 60", "HEVC 60 sem limite", "H.264 60", "H.264 60 sem limite", "H.264 120", "HEVC 120"),
            Benchmark.configsFor(listOf("h264", "hevc"), 120).map { it.label },
        )
        assertEquals(
            listOf("H.264 60", "H.264 60 sem limite"),
            Benchmark.configsFor(listOf("h264"), 60).map { it.label },
        )
        assertEquals(90, Benchmark.configsFor(listOf("h264"), 90).last().fps)
    }

    @Test
    fun picksLowestTouchLatencyAndWritesCsv() {
        val hevc = StreamOverride("HEVC 60", "hevc", 60, true)
        val h264 = StreamOverride("H.264 120", "h264", 120, true)
        val report = Benchmark.Report(
            listOf(
                Benchmark.ConfigResult(hevc, info("hevc", 60, false), listOf(sample(31.0, 17.5, 4.0)), touch(50.0, 52.0, 70.0)),
                Benchmark.ConfigResult(h264, info("h264", 120, true), listOf(sample(18.0, 4.0, 3.0)), touch(30.0, 33.0, 41.0)),
                Benchmark.ConfigResult.failed(StreamOverride("H.264 60", "h264", 60, false)),
            ),
            cancelled = false,
        )
        assertEquals("H.264 120", report.best?.config?.label)

        val lines = report.lines()
        assertEquals(
            "HEVC 60 [normal]: toque p50 52.0 / p95 52.0 · mov 31.0 ms (enc 17.5 · transf 4.0 · dec 7.0) · 20.0 Mbps",
            lines[0],
        )
        assertEquals("H.264 60: não conectou", lines[2])
        assertEquals("melhor: H.264 120 (toque p50 33.0 ms)", lines.last())

        val csv = report.csv()
        assertEquals(Benchmark.Report.CSV_HEADER, csv[0])
        assertEquals(4, csv.size)
        val columns = Benchmark.Report.CSV_HEADER.split(",").size
        csv.forEach { assertEquals(it, columns, it.split(",").size) }
        assertTrue(csv[2], csv[2].startsWith("H.264 120,h264,120,true,true,true,18.00,28.00,4.00,2.50,3.00,7.00,60.00,20.00,33.00,33.00"))
        assertTrue(csv[3], csv[3].startsWith("H.264 60,h264,60,,false,false,"))
    }
}
