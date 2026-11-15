package app.screeaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTestResultTest {
    @Test
    fun describesPercentiles() {
        val values = (1..20).map { it.toDouble() } // 1..20 ms
        assertEquals(
            "média 10.5 ms · p50 10.0 · p95 19.0 · mín 1.0 · máx 20.0",
            LatencyTest.describe(values),
        )
    }

    @Test
    fun emptyListHasNoNumbers() {
        assertEquals("–", LatencyTest.describe(emptyList()))
        val result = LatencyTest.Result(LatencyTest.Outcome.NO_MAC, emptyList(), emptyList(), emptyList(), 0)
        assertTrue(result.lines().isEmpty())
    }

    @Test
    fun linesIncludeBreakdownAndTimeouts() {
        val result = LatencyTest.Result(
            LatencyTest.Outcome.DONE,
            roundTripMs = listOf(30.0, 40.0),
            toMacMs = listOf(4.0, 6.0),
            toScreenMs = listOf(26.0, 34.0),
            timeouts = 1,
        )
        val lines = result.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("toque→tela (ida e volta): média 35.0 ms"))
        assertTrue(lines[0], lines[0].endsWith("2 amostras · 1 sem resposta"))
        assertEquals("ida tablet→Mac 5.0 ms · volta Mac→tela 30.0 ms (médias)", lines[1])
    }
}
