package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UptimeWarningTest {
    private val day = 86_400_000L

    @Test
    fun noWarningBeforeTheClockLimit() {
        assertNull(longUptimeDays(0))
        assertNull(longUptimeDays(47 * day + day - 1))
    }

    @Test
    fun warnsAheadOfTheClockLimitWithWholeDays() {
        assertEquals(48L, longUptimeDays(48 * day))
        assertEquals(58L, longUptimeDays(58 * day + 19 * 3_600_000L))
    }
}
