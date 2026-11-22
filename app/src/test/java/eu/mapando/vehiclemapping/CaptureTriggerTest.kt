package eu.mapando.vehiclemapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTriggerTest {

    // 0.0001° of latitude is about 11.1 m.
    private fun fix(latSteps: Int, timeMs: Long, speed: Float? = 10f) =
        Fix(lat = 45.0 + latSteps * 0.0001, lon = 9.0, timeMs = timeMs, speedMps = speed)

    private fun distance(meters: Int) =
        CaptureTrigger(TriggerMode.DISTANCE, intervalSeconds = 1, distanceMeters = meters, skipWhenStationary = true)

    private fun time(seconds: Int, skip: Boolean = true) =
        CaptureTrigger(TriggerMode.TIME, intervalSeconds = seconds, distanceMeters = 10, skipWhenStationary = skip)

    @Test
    fun haversineMatchesKnownDistance() {
        assertEquals(11.1, CaptureTrigger.distanceMeters(fix(0, 0), fix(1, 0)), 0.1)
    }

    @Test
    fun noFixMeansNoCapture() {
        assertFalse(distance(10).shouldCapture(1000, null))
        assertFalse(time(1).shouldCapture(1000, null))
    }

    @Test
    fun staleFixMeansNoCapture() {
        assertFalse(distance(10).shouldCapture(10_000, fix(0, 1_000)))
        assertFalse(time(1).shouldCapture(10_000, fix(0, 1_000)))
    }

    @Test
    fun distanceModeCapturesFirstFixThenWaitsForDistance() {
        val t = distance(20)
        val first = fix(0, 0)
        assertTrue(t.shouldCapture(0, first))
        t.markCaptured(0, first)

        assertFalse(t.shouldCapture(1000, fix(1, 1000))) // ~11 m
        assertTrue(t.shouldCapture(2000, fix(2, 2000))) // ~22 m
    }

    @Test
    fun distanceModeDoesNotCaptureWhileStandingStill() {
        val t = distance(10)
        val first = fix(0, 0)
        t.markCaptured(0, first)
        assertFalse(t.shouldCapture(60_000, fix(0, 60_000, speed = 0f)))
    }

    @Test
    fun timeModeRespectsInterval() {
        val t = time(2)
        val first = fix(0, 0)
        assertTrue(t.shouldCapture(0, first))
        t.markCaptured(0, first)

        assertFalse(t.shouldCapture(1_999, fix(1, 1_999)))
        assertTrue(t.shouldCapture(2_000, fix(2, 2_000)))
    }

    @Test
    fun timeModeSkipsWhenStationaryOnlyIfEnabled() {
        val still = fix(0, 0, speed = 0.2f)
        assertFalse(time(1, skip = true).shouldCapture(0, still))
        assertTrue(time(1, skip = false).shouldCapture(0, still))
    }

    @Test
    fun timeModeCapturesWhenSpeedIsUnknown() {
        assertTrue(time(1, skip = true).shouldCapture(0, fix(0, 0, speed = null)))
    }
}
