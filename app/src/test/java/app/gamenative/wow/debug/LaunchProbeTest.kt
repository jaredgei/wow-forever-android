package app.gamenative.wow.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchProbeTest {
    private val ps = listOf(
        "USER           PID  PPID S WCHAN      TIME ARGS",
        "root             1     0 S 0      00:00:02 init second_stage",
        "u0_a240      12348   900 S 0      00:00:09 app.wowforeverywhere",
        "u0_a240      12400 12348 S 0      00:00:00 /system/bin/linker64 /data/user/0/app.wowforeverywhere/files/imagefs/usr/bin/wine explorer",
        "u0_a240      12401 12400 D 0      00:00:00 wineserver",
        "u0_a999      13000   900 S 0      00:00:01 com.other.app",
    )

    @Test
    fun listsOnlyTheAppsOtherProcesses() {
        assertEquals(listOf(ps[0], ps[3], ps[4]).joinToString("\n"), LaunchProbe.ownRows(ps, 12348))
    }

    @Test
    fun saysSoWhenNothingElseRuns() {
        assertEquals(ps[0] + "\n(none)", LaunchProbe.ownRows(ps.take(3), 12348))
        assertEquals("app process not found in ps", LaunchProbe.ownRows(ps.take(2), 12348))
    }
}
