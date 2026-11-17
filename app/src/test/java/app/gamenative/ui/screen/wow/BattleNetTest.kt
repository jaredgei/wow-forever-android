package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleNetTest {

    private fun tempDir(): File = Files.createTempDirectory("bnet").toFile().apply { deleteOnExit() }

    @Test
    fun nothingToStartWithoutInstallOrInstaller() {
        val container = tempDir()
        val downloads = tempDir()
        assertFalse(BattleNet.isInstalled(container))
        assertNull(BattleNet.executable(container, downloads))
        assertNull(BattleNet.executable(null, downloads))
    }

    @Test
    fun installerFromDownloadRunsUntilBattleNetIsInstalled() {
        val container = tempDir()
        val downloads = tempDir()
        File(downloads, BattleNet.INSTALLER_NAME).writeText("installer")
        assertEquals(BattleNet.INSTALLER_WINDOWS_PATH, BattleNet.executable(container, downloads))

        val launcher = File(container, ".wine/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe")
        launcher.parentFile!!.mkdirs()
        launcher.writeText("launcher")
        assertTrue(BattleNet.isInstalled(container))
        assertEquals(BattleNet.LAUNCHER_WINDOWS_PATH, BattleNet.executable(container, downloads))
    }

    @Test
    fun windowsPathsPointAtTheMountedDrives() {
        // D: is Android's Download folder in the container's drive list; C: is the prefix.
        assertEquals("D:\\Battle.net-Setup.exe", BattleNet.INSTALLER_WINDOWS_PATH)
        assertTrue(BattleNet.LAUNCHER_WINDOWS_PATH.startsWith("C:\\Program Files (x86)\\Battle.net\\"))
        // A path with "ARM64" in it would skip winhandler.exe; the Battle.net paths must not.
        assertFalse(BattleNet.LAUNCHER_WINDOWS_PATH.contains("ARM64", ignoreCase = true))
    }
}
