package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonDisplayTest {
    @Test
    fun displayVersion_prefixesOnlyBareNumbers() {
        assertEquals("v16.00", displayVersion("16.00"))
        assertEquals("v4.3.13-release", displayVersion("v4.3.13-release"))
        assertEquals("r123", displayVersion("r123"))
    }

    @Test
    fun interfaceSummary_countsTheOverflow() {
        assertEquals("120105", interfaceSummary("120105"))
        assertEquals("1, 2, 3", interfaceSummary("1, 2, 3"))
        assertEquals("11509, 16001 +5 more", interfaceSummary("11509, 16001, 20506, 38002, 50504, 120100, 120105"))
    }
}
