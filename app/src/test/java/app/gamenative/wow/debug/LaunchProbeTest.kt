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
    fun listsEachMappedFileOnce() {
        val maps = File(proc, "maps").apply {
            writeText(
                """
                5c0000-5c1000 r--p 00000000 fd:01 100  /system/bin/linker64
                5c1000-5c2000 r-xp 00001000 fd:01 100  /system/bin/linker64
                7000-8000 rw-p 00000000 00:00 0  [anon:libc_malloc]
                9000-a000 r-xp 00000000 fd:01 200  /data/user/0/app/files/imagefs/usr/lib/libredirect-bionic-wx.so
                b000-c000 r--p 00000000 fd:01 300  /data/user/0/app/files/proton/bin/wine
                c000-d000 rw-p 00000000 00:00 0  [stack]
                """.trimIndent(),
            )
        }
        assertEquals(listOf("linker64", "libredirect-bionic-wx.so", "wine"), LaunchProbe.loadedFiles(maps.readLines()))
    }

    @Test
    fun placesAFutexLockAndTheCodeInTheirFiles() {
        // The line from the issue #9 report, with maps around its two addresses.
        val syscall = "98 0x7108328b80 0x89 0x1 0x0 0x0 0xffffffff 0x7ff54e0960 0x710b6f94a4"
        val maps = listOf(
            "7108320000-7108328000 r--p 00004000 fd:01 10 /data/app/lib/arm64/libgnoverlay.so",
            "7108328000-7108329000 rw-p 0000c000 fd:01 10 /data/app/lib/arm64/libgnoverlay.so",
            "710b600000-710b700000 r-xp 00040000 fd:01 20 /apex/com.android.runtime/lib64/bionic/libc.so",
        )
        assertEquals(
            "\n    code at: libc.so r-xp +0x1394a4\n    waiting on lock at: libgnoverlay.so rw-p +0xcb80",
            LaunchProbe.whereIs(syscall, maps),
        )
        assertEquals("\n    code at: 0x710b6f94a4 (not mapped)\n    waiting on lock at: 0x7108328b80 (not mapped)", LaunchProbe.whereIs(syscall, emptyList()))
        assertEquals("\n    code at: libc.so r-xp +0x1394a4", LaunchProbe.whereIs(syscall.replaceFirst("98", "73"), maps))
    }

    @Test
    fun placesALockInALibrarysBssInThatLibrary() {
        // libredirect-bionic-wx.so: .data at 0x10b00 shares a page with the start of .bss, the rest is anonymous.
        val maps = listOf(
            "7108300000-7108310000 r-xp 00000000 fd:01 10 /data/user/0/app/files/imagefs/usr/lib/libredirect-bionic-wx.so",
            "7108310000-7108311000 rw-p 0000d000 fd:01 10 /data/user/0/app/files/imagefs/usr/lib/libredirect-bionic-wx.so",
            "7108311000-710851f000 rw-p 00000000 00:00 0 [anon:.bss]",
        )
        val inPage = "98 0x7108310b80 0x89 0x1 0x0 0x0 0xffffffff 0x0 0x0"
        val inAnon = "98 0x7108400010 0x89 0x1 0x0 0x0 0xffffffff 0x0 0x0"
        assertTrue(LaunchProbe.whereIs(inPage, maps), LaunchProbe.whereIs(inPage, maps).endsWith("lock at: libredirect-bionic-wx.so rw-p +0x10b80"))
        assertTrue(
            LaunchProbe.whereIs(inAnon, maps),
            LaunchProbe.whereIs(inAnon, maps).endsWith("lock at: libredirect-bionic-wx.so ([anon:.bss] after it) rw-p +0x100010"),
        )
    }

    @Test
    fun saysSoWhenNothingElseRuns() {
        process(100, 10240, "app.wowforeverywhere", "app.wowforeverywhere", 'S')
        assertEquals("(none found in 1 /proc entries)", LaunchProbe.processes(proc, myUid = 10240, myPid = 100))
    }
}
