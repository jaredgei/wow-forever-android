package app.gamenative.wow.debug

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchProbeTest {
    private val proc: File = Files.createTempDirectory("proc").toFile()

    private fun process(pid: Int, uid: Int, name: String, cmdline: String, vararg threadStates: Char) {
        val dir = File(proc, "$pid").apply { mkdirs() }
        File(dir, "status").writeText("Name:\t$name\nState:\tS (sleeping)\nPPid:\t1\nUid:\t$uid\t$uid\t$uid\t$uid\nThreads:\t${threadStates.size}\n")
        File(dir, "cmdline").writeText(cmdline.replace(' ', '\u0000') + "\u0000")
        File(dir, "wchan").writeText("0")
        threadStates.forEachIndexed { i, s -> File(dir, "task/${pid + i}").mkdirs(); File(dir, "task/${pid + i}/stat").writeText("${pid + i} ($name) $s 1 2 3") }
    }

    @Test
    fun listsTheAppsOtherProcessesWithTheirThreadStates() {
        process(100, 10240, "app.wowforeverywhere", "app.wowforeverywhere", 'S')
        process(101, 10240, "wine", "/system/bin/linker64 /data/wine explorer", 'S', 'S', 'D')
        process(102, 10999, "other", "com.other.app", 'R')
        File(proc, "self").mkdirs()

        val rows = LaunchProbe.processes(proc, myUid = 10240, myPid = 100).lines()

        assertEquals(1, rows.size)
        assertTrue(rows[0], rows[0].startsWith("pid 101 ppid 1 wine state S (sleeping) threads 3 (S 2, D 1)"))
        assertTrue(rows[0], rows[0].endsWith("| /system/bin/linker64 /data/wine explorer"))
    }

    @Test
    fun saysSoWhenNothingElseRuns() {
        process(100, 10240, "app.wowforeverywhere", "app.wowforeverywhere", 'S')
        assertEquals("(none found in 1 /proc entries)", LaunchProbe.processes(proc, myUid = 10240, myPid = 100))
    }
}
