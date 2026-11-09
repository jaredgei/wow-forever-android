package app.gamenative.wow.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClockTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun aRestartIsAskedForFromDay49() {
        assertFalse(DeviceClock.needsRestart(0))
        assertFalse(DeviceClock.needsRestart(49 * day - 1))
        assertTrue(DeviceClock.needsRestart(49 * day))
    }

    @Test
    fun aDevicePastTheLimitStillNeedsARestart() {
        assertTrue(DeviceClock.needsRestart(1L shl 32))
        assertTrue(DeviceClock.needsRestart(400 * day))
    }

    @Test
    fun daysAreWholeDays() {
        assertEquals(49, DeviceClock.days(1L shl 32))
        assertEquals(0, DeviceClock.days(day - 1))
    }
}
